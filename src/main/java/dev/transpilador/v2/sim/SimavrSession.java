package dev.transpilador.v2.sim;

import dev.transpilador.v2.model.Hardware;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Sesión con el motor nativo simavr (protocolo v1) sobre ATmega328P @ 1 MHz.
 * Resuelve rutas Windows -> WSL en Java puro (sin lanzar subprocesos wslpath)
 * y tolera arranque en frío de WSL con presupuesto de inicio y reintento único.
 */
public final class SimavrSession implements AutoCloseable {
    public record PortState(int port, int ddr, int pin) {}

    /** Instantánea completa de registros internos de CPU ATmega328P (r0..r31, SREG y SP). */
    public record CpuRegisters(int[] r, int sreg, int sp) {
        public CpuRegisters {
            if (r == null || r.length != 32) {
                throw new IllegalArgumentException("Se esperaban 32 registros r0..r31");
            }
            r = Arrays.copyOf(r, 32);
        }

        public int reg(int index) {
            return r[index] & 0xFF;
        }

        public boolean flag(int bit) {
            return ((sreg >> bit) & 1) != 0;
        }
    }

    public record State(
            long cycles,
            int pc,
            int cpuState,
            PortState portB,
            PortState portC,
            PortState portD,
            long txTotal,
            List<Integer> txBytes,
            boolean uartRxReady) {

        public State {
            txBytes = List.copyOf(txBytes);
        }

        public PortState port(Hardware.Port p) {
            return switch (p) {
                case B -> portB;
                case C -> portC;
                case D -> portD;
            };
        }

        /** Nivel eléctrico actual (0 o 1) observado en el pin físico. */
        public boolean pinHigh(Hardware.Pin pin) {
            return ((port(pin.port()).pin() >> pin.bit()) & 1) != 0;
        }

        /** Estado lógico de encendido del LED respetando polaridad VCC/GND. */
        public boolean isLedOn(Hardware.Led led) {
            boolean high = pinHigh(led.pin());
            return led.activeHigh() ? high : !high;
        }

        /** Nibble BCD actual (0..15) en los 4 pines del display. */
        public int readBcd(Hardware.BcdDisplay display) {
            return (port(display.firstPin().port()).pin() >> display.firstPin().bit()) & 0x0F;
        }

        /** Bytes transmitidos por UART0 interpretados como texto ASCII. */
        public String txAscii() {
            StringBuilder sb = new StringBuilder(txBytes.size());
            for (int b : txBytes) sb.append((char) (b & 0xFF));
            return sb.toString();
        }
    }

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final ExecutorService readPool;
    private final Path tempDir;
    private final Path logFile;
    private final long fCpuHz;
    private volatile long lastCycles;
    private volatile boolean closed;

    private SimavrSession(Process process, Path tempDir, Path logFile, long fCpuHz) {
        this.process = process;
        this.tempDir = tempDir;
        this.logFile = logFile;
        this.fCpuHz = fCpuHz;
        this.writer = process.outputWriter(StandardCharsets.US_ASCII);
        this.reader = process.inputReader(StandardCharsets.US_ASCII);
        this.readPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "simavr-v2-reader");
            t.setDaemon(true);
            return t;
        });
    }

    public static SimavrSession open(Path hexOrElf, Toolchain toolchain) throws IOException, InterruptedException {
        return open(hexOrElf, 1_000_000L, toolchain);
    }

    public static SimavrSession open(Path hexOrElf, long fCpuHz, Toolchain toolchain) throws IOException, InterruptedException {
        Hardware.ClockFreq.fromHz(fCpuHz);
        if (!toolchain.hasSimavr()) {
            throw new IOException("No se encontró el binario firmware-runner de simavr.");
        }
        IOException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return openOnce(hexOrElf, fCpuHz, toolchain);
            } catch (IOException ex) {
                lastError = ex;
                if (attempt == 1 && isTransientStartupError(ex)) {
                    Thread.sleep(500);
                    continue;
                }
                throw ex;
            }
        }
        throw lastError;
    }

    private static boolean isTransientStartupError(IOException ex) {
        String msg = ex.getMessage();
        return msg != null && (msg.contains("no respondió") || msg.contains("terminó"));
    }

    private static SimavrSession openOnce(Path hexOrElf, long fCpuHz, Toolchain toolchain) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("transpilador-v2-simavr-");
        Path fwCopy = dir.resolve("program.hex");
        Path vcd = dir.resolve("signals.vcd");
        Path log = dir.resolve("simavr.log");
        String hexContent = Files.readString(hexOrElf, StandardCharsets.US_ASCII);
        Files.writeString(fwCopy, normalizeContiguousIhex(hexContent), StandardCharsets.US_ASCII);

        List<String> cmd = buildCommand(toolchain.simavrRunner(), toolchain.wslDistro(), fwCopy, vcd, fCpuHz);
        Process proc = new ProcessBuilder(cmd).redirectError(log.toFile()).start();
        SimavrSession session = new SimavrSession(proc, dir, log, fCpuHz);
        try {
            String ready = session.receive(25); // Margen generoso para arranque en frío de WSL
            String expected = "@READY 1 atmega328p " + fCpuHz;
            if (!ready.equals(expected)) {
                throw new IOException("Protocolo simavr inesperado: '" + ready + "' (esperado '" + expected + "')");
            }
            String caps = session.request("CAPS", 10);
            if (!caps.startsWith("@CAPS ")) {
                throw new IOException("Capacidades simavr inesperadas: " + caps);
            }
            return session;
        } catch (IOException | InterruptedException ex) {
            int exitCode = proc.isAlive() ? -999 : proc.exitValue();
            String extra = " [exit=" + exitCode + ", cmd=" + cmd + ", log=" + session.readLog() + "]";
            session.close();
            if (ex instanceof IOException io) throw new IOException(io.getMessage() + extra, io.getCause());
            throw ex;
        }
    }

    /**
     * Convierte un Intel HEX posiblemente disperso (ej. AVRASM2 con .org INT_VECTORS_SIZE)
     * en un bloque contiguo desde 0x0000 rellenando huecos con 0xFF (Flash borrada AVR),
     * evitando que simavr ignore bloques adicionales.
     */
    public static String normalizeContiguousIhex(String ihex) {
        byte[] flash = new byte[32768];
        Arrays.fill(flash, (byte) 0xFF);
        int baseAddr = 0;
        int maxEnd = 0;
        for (String rawLine : ihex.split("\\R")) {
            String line = rawLine.trim();
            if (!line.startsWith(":") || line.length() < 11) continue;
            int count = Integer.parseInt(line.substring(1, 3), 16);
            int addr = Integer.parseInt(line.substring(3, 7), 16);
            int type = Integer.parseInt(line.substring(7, 9), 16);
            if (type == 0x00) {
                int fullAddr = baseAddr + addr;
                for (int i = 0; i < count; i++) {
                    int b = Integer.parseInt(line.substring(9 + i * 2, 11 + i * 2), 16);
                    if (fullAddr + i < flash.length) {
                        flash[fullAddr + i] = (byte) b;
                        if (fullAddr + i + 1 > maxEnd) maxEnd = fullAddr + i + 1;
                    }
                }
            } else if (type == 0x02 && count == 2) {
                baseAddr = Integer.parseInt(line.substring(9, 13), 16) << 4;
            } else if (type == 0x04 && count == 2) {
                baseAddr = Integer.parseInt(line.substring(9, 13), 16) << 16;
            } else if (type == 0x01) {
                break;
            }
        }
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < maxEnd; offset += 16) {
            int len = Math.min(16, maxEnd - offset);
            int sum = len + ((offset >> 8) & 0xFF) + (offset & 0xFF);
            out.append(String.format(":%02X%04X00", len, offset));
            for (int i = 0; i < len; i++) {
                int b = flash[offset + i] & 0xFF;
                sum += b;
                out.append(String.format("%02X", b));
            }
            int checksum = (-sum) & 0xFF;
            out.append(String.format("%02X\n", checksum));
        }
        out.append(":00000001FF\n");
        return out.toString();
    }

    private static List<String> buildCommand(Path runner, String distro, Path fw, Path vcd, long fCpuHz) {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        boolean isExe = runner.toString().toLowerCase(Locale.ROOT).endsWith(".exe");
        if (windows && !isExe) {
            List<String> cmd = new ArrayList<>();
            cmd.add("wsl.exe");
            if (distro != null && !distro.isBlank()) {
                cmd.add("-d");
                cmd.add(distro);
            }
            cmd.add("--exec");
            cmd.add(toWslPath(runner));
            cmd.add(toWslPath(fw));
            cmd.add(toWslPath(vcd));
            cmd.add(String.valueOf(fCpuHz));
            return cmd;
        }
        return List.of(runner.toAbsolutePath().toString(), fw.toAbsolutePath().toString(),
                vcd.toAbsolutePath().toString(), String.valueOf(fCpuHz));
    }

    /** Conversión pura Windows -> /mnt/<letra>/... resolviendo nombres cortos 8.3 (ej. X1CARB~1). */
    public static String toWslPath(Path path) {
        Path resolved = resolveReal(path);
        String abs = resolved.toString();
        if (abs.startsWith("/")) return abs;
        if (abs.length() >= 3 && abs.charAt(1) == ':' && (abs.charAt(2) == '\\' || abs.charAt(2) == '/')) {
            char drive = Character.toLowerCase(abs.charAt(0));
            return "/mnt/" + drive + abs.substring(2).replace('\\', '/');
        }
        return abs.replace('\\', '/');
    }

    private static Path resolveReal(Path path) {
        Path norm = path.toAbsolutePath().normalize();
        try {
            if (Files.exists(norm)) return norm.toRealPath();
            Path parent = norm.getParent();
            if (parent != null && Files.exists(parent)) {
                return parent.toRealPath().resolve(norm.getFileName());
            }
        } catch (IOException ignored) {}
        return norm;
    }

    /** Fija nivel eléctrico de un pin externo (para botones: respetar polaridad con pressButton). */
    public void setPin(Hardware.Pin pin, boolean high) throws IOException, InterruptedException {
        expectOk("PIN " + pin.port().letter() + " " + pin.bit() + " " + (high ? 1 : 0));
    }

    /** Presiona o suelta un botón traduciendo automáticamente su polaridad VCC/GND. */
    public void setButton(Hardware.Button button, boolean pressed) throws IOException, InterruptedException {
        boolean high = button.activeHigh() ? pressed : !pressed;
        setPin(button.pin(), high);
    }

    /** Fija tensión analógica en mV (0..5000) en un canal ADC0..ADC5. */
    public void setAnalogMv(int channel, int millivolts) throws IOException, InterruptedException {
        if (channel < 0 || channel > 5 || millivolts < 0 || millivolts > 5000) {
            throw new IllegalArgumentException("ADC fuera de rango: canal " + channel + ", " + millivolts + " mV");
        }
        expectOk("ADC " + channel + " " + millivolts);
    }

    /** Inyecta un byte en UART0 RX. Devuelve false si RX no está listo/ocupado (sin romper la sesión). */
    public boolean injectUartRx(int byteValue) throws IOException, InterruptedException {
        String reply = request("RX " + (byteValue & 0xFF), 10);
        if (reply.equals("@OK")) return true;
        if (reply.equals("@RX BUSY")) return false;
        throw new IOException("Respuesta inesperada a RX: " + reply);
    }

    /** Avanza N ciclos de CPU (dividido automáticamente en bloques <= 4_000_000). */
    public State advanceCycles(long totalCycles) throws IOException, InterruptedException {
        if (totalCycles < 1) throw new IllegalArgumentException("totalCycles debe ser >= 1");
        long remaining = totalCycles;
        State last = null;
        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, 4_000_000L);
            last = parseState(request("RUN " + chunk, 20));
            remaining -= chunk;
        }
        return last;
    }

    /** Avanza aproximadamente N milisegundos según el reloj configurado (fCpuHz / 1000 ciclos/ms). */
    public State advanceMs(int ms) throws IOException, InterruptedException {
        return advanceCycles(ms * (fCpuHz / 1000L));
    }

    public long fCpuHz() { return fCpuHz; }

    public State state() throws IOException, InterruptedException {
        return parseState(request("STATE", 10));
    }

    /** Ejecuta exactamente una instrucción AVR en simavr y devuelve el nuevo estado. */
    public State stepInstruction() throws IOException, InterruptedException {
        return parseState(request("STEP", 10));
    }

    /** Lee los 32 registros de propósito general r0..r31, SREG y SP (SPH:SPL) directamente desde simavr. */
    public CpuRegisters readRegisters() throws IOException, InterruptedException {
        String resp = request("REGS", 10);
        String[] p = resp.split(" ");
        if (p.length < 35 || !p[0].equals("@REGS")) {
            throw new IOException("Respuesta @REGS inválida: " + resp);
        }
        int[] regs = new int[32];
        for (int i = 0; i < 32; i++) {
            regs[i] = Integer.parseInt(p[1 + i]) & 0xFF;
        }
        int sreg = Integer.parseInt(p[33]) & 0xFF;
        int sp = Integer.parseInt(p[34]) & 0xFFFF;
        return new CpuRegisters(regs, sreg, sp);
    }

    /** Lee un bloque contiguo de SRAM interna del ATmega328P (0x0100..0x08FF) mediante comandos MEM. */
    public int[] readMemory(int startAddr, int length) throws IOException, InterruptedException {
        if (startAddr < 0x0100 || length < 1 || startAddr + length > 0x0900) {
            throw new IllegalArgumentException("Rango SRAM inválido: 0x" + Integer.toHexString(startAddr) + " len=" + length);
        }
        int[] out = new int[length];
        int offset = 0;
        while (offset < length) {
            int chunk = Math.min(16, length - offset);
            String resp = request("MEM " + (startAddr + offset) + " " + chunk, 10);
            String[] p = resp.split(" ");
            if (p.length < 1 + chunk || !p[0].equals("@MEM")) {
                throw new IOException("Respuesta @MEM inválida: " + resp);
            }
            for (int i = 0; i < chunk; i++) {
                out[offset + i] = Integer.parseInt(p[1 + i]) & 0xFF;
            }
            offset += chunk;
        }
        return out;
    }

    /** Lee el ciclo de trabajo hardware real (0..255) de los 6 canales Fast PWM desde los registros OCR/TCCR de simavr. */
    public Map<Hardware.PwmChannel, Integer> readPwmDuties() throws IOException, InterruptedException {
        String resp = request("PWM", 10);
        String[] p = resp.split(" ");
        if (p.length < 7 || !p[0].equals("@PWM")) {
            throw new IOException("Respuesta @PWM inválida: " + resp);
        }
        EnumMap<Hardware.PwmChannel, Integer> map = new EnumMap<>(Hardware.PwmChannel.class);
        map.put(Hardware.PwmChannel.OC0A, Integer.parseInt(p[1]));
        map.put(Hardware.PwmChannel.OC0B, Integer.parseInt(p[2]));
        map.put(Hardware.PwmChannel.OC1A, Integer.parseInt(p[3]));
        map.put(Hardware.PwmChannel.OC1B, Integer.parseInt(p[4]));
        map.put(Hardware.PwmChannel.OC2A, Integer.parseInt(p[5]));
        map.put(Hardware.PwmChannel.OC2B, Integer.parseInt(p[6]));
        return map;
    }

    public String readVcd() throws IOException {
        Path vcd = tempDir.resolve("signals.vcd");
        return Files.isRegularFile(vcd) ? Files.readString(vcd, StandardCharsets.US_ASCII) : "";
    }

    /**
     * Mide la frecuencia real (Hz) de cada canal PWM directamente desde los flancos ascendentes
     * registrados por simavr en {@code signals.vcd} (leyendo solo la cola reciente para ser O(1)),
     * contrastando contra el tiempo simulado actual para expirar canales detenidos.
     */
    public Map<Hardware.PwmChannel, Double> measurePwmFrequenciesHz(List<Hardware.PwmOutput> pwms, long fCpuHz) throws IOException {
        return measurePwmFrequenciesHz(pwms, fCpuHz, lastCycles, null);
    }

    /**
     * Mide la frecuencia real (Hz) de cada canal PWM desde {@code signals.vcd} usando tanto
     * el reloj simulado actual ({@code currentCycles}) como el duty hardware activo ({@code liveDuties})
     * para devolver 0 Hz de inmediato cuando el canal vuelve a modo DC (0 o 255) o deja de conmutar.
     */
    public Map<Hardware.PwmChannel, Double> measurePwmFrequenciesHz(
            List<Hardware.PwmOutput> pwms,
            long fCpuHz,
            long currentCycles,
            Map<Hardware.PwmChannel, Integer> liveDuties) throws IOException {
        EnumMap<Hardware.PwmChannel, Double> freqs = new EnumMap<>(Hardware.PwmChannel.class);
        if (pwms.isEmpty()) return freqs;
        Path vcd = tempDir.resolve("signals.vcd");
        if (!Files.isRegularFile(vcd)) return freqs;

        Map<Character, Hardware.PwmChannel> idToChannel = new HashMap<>();
        for (Hardware.PwmOutput p : pwms) {
            int portIdx = p.pin().port().letter() - 'B';
            char id = (char) ('!' + portIdx * 8 + p.pin().bit());
            idToChannel.put(id, p.channel());
            freqs.put(p.channel(), 0.0);
        }

        // Leer solo los últimos 64 KB del VCD para mantener coste constante aunque la simulación lleve minutos
        String tail;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(vcd.toFile(), "r")) {
            long len = raf.length();
            long start = Math.max(0L, len - 65_536L);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            tail = new String(buf, StandardCharsets.US_ASCII);
            if (start > 0) {
                int nl = tail.indexOf('\n');
                if (nl >= 0) tail = tail.substring(nl + 1);
            }
        }

        Map<Hardware.PwmChannel, Long> prevRiseNs = new EnumMap<>(Hardware.PwmChannel.class);
        Map<Hardware.PwmChannel, Long> lastRiseNs = new EnumMap<>(Hardware.PwmChannel.class);
        Map<Hardware.PwmChannel, Character> lastVal = new EnumMap<>(Hardware.PwmChannel.class);
        long currentNs = -1L;
        long maxNs = 0L;

        for (String line : tail.split("\\R")) {
            if (line.isEmpty()) continue;
            char c0 = line.charAt(0);
            if (c0 == '#') {
                try {
                    currentNs = Long.parseLong(line.substring(1).trim());
                    if (currentNs > maxNs) maxNs = currentNs;
                } catch (NumberFormatException ignored) {}
            } else if ((c0 == '0' || c0 == '1') && line.length() == 2 && currentNs >= 0) {
                Hardware.PwmChannel ch = idToChannel.get(line.charAt(1));
                if (ch != null) {
                    Character prev = lastVal.put(ch, c0);
                    if (c0 == '1' && (prev == null || prev == '0')) {
                        Long prevR = lastRiseNs.put(ch, currentNs);
                        if (prevR != null && currentNs > prevR) {
                            prevRiseNs.put(ch, prevR);
                        }
                    }
                }
            }
        }

        long simNowNs = 0L;
        if (currentCycles > 0 && fCpuHz > 0) {
            simNowNs = (currentCycles / fCpuHz) * 1_000_000_000L
                    + ((currentCycles % fCpuHz) * 1_000_000_000L) / fCpuHz;
        }
        long refNs = Math.max(maxNs, simNowNs);

        // Expirar canales detenidos (DC 0/255 o sin flancos recientes respecto al tiempo simulado actual)
        for (Hardware.PwmOutput p : pwms) {
            Hardware.PwmChannel ch = p.channel();
            if (liveDuties != null) {
                Integer duty = liveDuties.get(ch);
                if (duty != null && (duty <= 0 || duty >= 255)) {
                    freqs.put(ch, 0.0);
                    continue;
                }
            }
            Long r2 = lastRiseNs.get(ch);
            Long r1 = prevRiseNs.get(ch);
            if (r1 != null && r2 != null && r2 > r1) {
                long periodNs = r2 - r1;
                long maxStaleNs = Math.min(30_000_000L, Math.max(5_000_000L, 4L * periodNs));
                if ((refNs - r2) <= maxStaleNs) {
                    freqs.put(ch, 1_000_000_000.0 / (double) periodNs);
                }
            }
        }
        return freqs;
    }

    private void expectOk(String cmd) throws IOException, InterruptedException {
        String r = request(cmd, 10);
        if (!r.equals("@OK")) throw new IOException("Se esperaba @OK para '" + cmd + "', recibido: " + r);
    }

    private String request(String command, int timeoutSec) throws IOException, InterruptedException {
        if (closed) throw new IOException("Sesión simavr cerrada.");
        writer.write(command);
        writer.write('\n');
        writer.flush();
        return receive(timeoutSec);
    }

    private String receive(int timeoutSec) throws IOException, InterruptedException {
        Future<String> future = readPool.submit(reader::readLine);
        try {
            String line = future.get(timeoutSec, TimeUnit.SECONDS);
            if (line == null) throw new IOException("El motor simavr terminó inesperadamente. " + readLog());
            if (line.startsWith("@ERROR")) throw new IOException("simavr error: " + line.substring(6).strip());
            return line;
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new IOException("simavr no respondió en " + timeoutSec + " s. " + readLog(), ex);
        } catch (ExecutionException ex) {
            throw new IOException("Error leyendo simavr: " + ex.getCause().getMessage(), ex.getCause());
        }
    }

    private State parseState(String line) throws IOException, InterruptedException {
        String[] parts = line.split(" ");
        if (parts.length < 15 || !parts[0].equals("@STATE")) {
            throw new IOException("Respuesta @STATE inválida: " + line);
        }
        long cycles = Long.parseLong(parts[1]);
        this.lastCycles = cycles;
        int pc = Integer.parseInt(parts[2]);
        int cpu = Integer.parseInt(parts[3]);
        PortState pb = new PortState(Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), Integer.parseInt(parts[6]));
        PortState pcPort = new PortState(Integer.parseInt(parts[7]), Integer.parseInt(parts[8]), Integer.parseInt(parts[9]));
        PortState pd = new PortState(Integer.parseInt(parts[10]), Integer.parseInt(parts[11]), Integer.parseInt(parts[12]));
        long txTotal = Long.parseLong(parts[13]);
        List<Integer> tx = new ArrayList<>();
        if (!parts[14].equals("-")) {
            for (byte b : HexFormat.of().parseHex(parts[14])) tx.add(b & 0xFF);
        }
        String rxReady = request("RXREADY", 10);
        return new State(cycles, pc, cpu, pb, pcPort, pd, txTotal, tx, rxReady.equals("@RXREADY 1"));
    }

    private String readLog() {
        try {
            return Files.isRegularFile(logFile) ? Files.readString(logFile).strip() : "";
        } catch (IOException ex) {
            return "";
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        process.destroy();
        try {
            process.descendants().forEach(ProcessHandle::destroy);
        } catch (Throwable ignored) {
            // En Windows, CreateToolhelp32Snapshot puede fallar transitoriamente bajo alta carga de procesos
        }
        if (process.isAlive()) process.destroyForcibly();
        readPool.shutdownNow();
        try { writer.close(); } catch (IOException ignored) {}
        try { reader.close(); } catch (IOException ignored) {}
        try {
            Files.deleteIfExists(tempDir.resolve("signals.vcd"));
            Files.deleteIfExists(logFile);
            try (var s = Files.list(tempDir)) {
                for (Path p : s.toList()) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(tempDir);
        } catch (IOException ignored) {}
    }
}
