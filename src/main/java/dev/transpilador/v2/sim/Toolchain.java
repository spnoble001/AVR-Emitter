package dev.transpilador.v2.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Localiza e invoca las herramientas reales de compilación y ensamblado AVR:
 * avr-gcc + avr-objcopy (C99 -> ELF/HEX) y AVRASM2 (ASM -> HEX), más el runner de simavr.
 */
public final class Toolchain {
    public record BuildArtifact(Path sourceFile, Path elfFile, Path hexFile, String toolOutput) {}
    public enum DiagnosticSeverity { ERROR, WARNING, NOTE }
    public record BuildDiagnostic(int line, int column, DiagnosticSeverity severity, String message) {}
    public record RawBuildResult(
            boolean success,
            BuildArtifact artifact,
            List<BuildDiagnostic> diagnostics,
            int flashBytes,
            String rawOutput) {
        public long errorCount() {
            return diagnostics.stream().filter(d -> d.severity() == DiagnosticSeverity.ERROR).count();
        }
        public long warningCount() {
            return diagnostics.stream().filter(d -> d.severity() == DiagnosticSeverity.WARNING).count();
        }
    }

    private record ExecResult(int exitCode, String output) {}

    private static final Pattern GCC_DIAG_PATTERN = Pattern.compile(
            "^.*?([^:/\\\\]+\\.[ch]):(\\d+)(?::(\\d+))?:\\s*(fatal error|error|warning|note):\\s*(.+)$");
    private static final Pattern AVRASM_DIAG_PATTERN = Pattern.compile(
            "^.*?\\.asm\\((\\d+)\\):\\s*(error|warning|info):\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AVRASM_FALLBACK_PATTERN = Pattern.compile(
            "^\\s*(error|warning):\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private final Path avrGcc;
    private final Path avrObjcopy;
    private final Path avrasm2;
    private final Path avrIncludes;
    private final Path simavrRunner;
    private final String wslDistro;

    public Toolchain(Path avrGcc, Path avrObjcopy, Path avrasm2, Path avrIncludes, Path simavrRunner, String wslDistro) {
        this.avrGcc = avrGcc;
        this.avrObjcopy = avrObjcopy;
        this.avrasm2 = avrasm2;
        this.avrIncludes = avrIncludes;
        this.simavrRunner = simavrRunner;
        this.wslDistro = wslDistro;
    }

    public static Toolchain detect() {
        Path avrGcc = firstExisting(
                System.getProperty("avr.gcc"),
                "C:\\Program Files (x86)\\Atmel\\Studio\\7.0\\toolchain\\avr8\\avr8-gnu-toolchain\\bin\\avr-gcc.exe",
                "C:\\Users\\X1 Carbon\\Desktop\\transpilador-main\\local-resources\\minicore\\avr\\bin\\avr-gcc.exe"
        );
        String explicitObjcopy = System.getProperty("avr.objcopy");
        Path avrObjcopy;
        if (explicitObjcopy != null && !explicitObjcopy.isBlank()) {
            Path p = Path.of(explicitObjcopy).toAbsolutePath().normalize();
            avrObjcopy = Files.exists(p) ? p : null;
        } else {
            avrObjcopy = firstExisting(
                    avrGcc != null ? avrGcc.getParent().resolve("avr-objcopy.exe").toString() : null,
                    avrGcc != null ? avrGcc.getParent().resolve("avr-objcopy").toString() : null,
                    "C:\\Program Files (x86)\\Atmel\\Studio\\7.0\\toolchain\\avr8\\avr8-gnu-toolchain\\bin\\avr-objcopy.exe",
                    "C:\\Users\\X1 Carbon\\Desktop\\transpilador-main\\local-resources\\minicore\\avr\\bin\\avr-objcopy.exe");
        }
        Path avrasm2 = firstExisting(
                System.getProperty("avr.avrasm2"),
                "C:\\Program Files (x86)\\Atmel\\Studio\\7.0\\toolchain\\avr8\\avrassembler\\avrasm2.exe",
                "C:\\Program Files (x86)\\Labcenter Electronics\\Proteus 8 Professional\\Tools\\AVRASM\\avrasm2.exe"
        );
        Path avrIncludes = firstExisting(
                System.getProperty("avr.includes"),
                "C:\\Program Files (x86)\\Atmel\\Studio\\7.0\\packs\\atmel\\ATmega_DFP\\1.7.374\\avrasm\\inc",
                "C:\\Program Files (x86)\\Labcenter Electronics\\Proteus 8 Professional\\Tools\\AVRASM\\APPNOTES"
        );
        Path runner = firstExisting(
                System.getProperty("simavr.runner"),
                System.getenv("SIMAVR_RUNNER"),
                "local-resources/simavr/firmware-runner",
                "C:\\Users\\X1 Carbon\\.gemini\\antigravity\\scratch\\transpilador-avr-v2\\local-resources\\simavr\\firmware-runner",
                "C:\\Users\\X1 Carbon\\Desktop\\transpilador-main\\local-resources\\simavr\\firmware-runner"
        );
        String distro = System.getProperty("simavr.wsl.distribution",
                System.getenv("SIMAVR_WSL_DISTRO") != null ? System.getenv("SIMAVR_WSL_DISTRO") : "Ubuntu-24.04");
        return new Toolchain(avrGcc, avrObjcopy, avrasm2, avrIncludes, runner, distro);
    }

    public boolean hasAvrGcc() { return avrGcc != null && Files.isRegularFile(avrGcc) && avrObjcopy != null && Files.isRegularFile(avrObjcopy); }
    public boolean hasAvrasm2() { return avrasm2 != null && Files.isRegularFile(avrasm2) && avrIncludes != null && Files.isDirectory(avrIncludes); }
    public boolean hasSimavr() { return simavrRunner != null && Files.isRegularFile(simavrRunner); }
    public Path avrGcc() { return avrGcc; }
    public Path avrObjcopy() { return avrObjcopy; }
    public Path simavrRunner() { return simavrRunner; }
    public String wslDistro() { return wslDistro; }

    /** Convierte cualquier título de proyecto en un nombre base seguro para archivos temporales en Windows/Linux. */
    public static String safeFileStem(String rawName) {
        if (rawName == null || rawName.isBlank()) return "firmware";
        String noAccents = Normalizer.normalize(rawName, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String cleaned = noAccents.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (cleaned.isEmpty()) return "firmware";
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    /** Compila C99 estricto (-Wall -Wextra -Werror) a ELF y HEX para ATmega328P. */
    public BuildArtifact compileC(String name, String cSource, Path workDir) throws IOException, InterruptedException {
        if (!hasAvrGcc()) throw new IOException("No se encontró avr-gcc/avr-objcopy en el equipo.");
        Files.createDirectories(workDir);
        String stem = safeFileStem(name);
        Path cFile = workDir.resolve(stem + ".c");
        Path elfFile = workDir.resolve(stem + ".elf");
        Path hexFile = workDir.resolve(stem + ".hex");
        Files.writeString(cFile, cSource, StandardCharsets.UTF_8);

        String gccOut = run(List.of(
                avrGcc.toString(),
                "-mmcu=atmega328p",
                "-Os",
                "-std=c99",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-g",
                "-o", elfFile.toString(),
                cFile.toString()
        ), workDir);

        String objOut = run(List.of(
                avrObjcopy.toString(),
                "-O", "ihex",
                "-R", ".eeprom",
                elfFile.toString(),
                hexFile.toString()
        ), workDir);

        return new BuildArtifact(cFile, elfFile, hexFile, (gccOut + "\n" + objOut).strip());
    }

    /** Ensambla código AVRASM2 a Intel HEX sin errores ni advertencias. */
    public BuildArtifact assembleAsm(String name, String asmSource, Path workDir) throws IOException, InterruptedException {
        if (!hasAvrasm2()) throw new IOException("No se encontró avrasm2.exe o m328Pdef.inc en el equipo.");
        Files.createDirectories(workDir);
        String stem = safeFileStem(name);
        Path asmFile = workDir.resolve(stem + ".asm");
        Path hexFile = workDir.resolve(stem + ".hex");
        Files.writeString(asmFile, asmSource, StandardCharsets.UTF_8);

        String out = run(List.of(
                avrasm2.toString(),
                "-fI",
                "-I", avrIncludes.toString(),
                "-o", hexFile.toString(),
                asmFile.toString()
        ), workDir);

        if (!out.contains("0 errors. 0 warnings")) {
            throw new IOException("AVRASM2 emitió errores o advertencias en " + name + ":\n" + out);
        }
        return new BuildArtifact(asmFile, null, hexFile, out.strip());
    }

    /**
     * Compila código C99 editado manualmente para el IDE integrado:
     * conserva -Wall -Wextra pero permite advertencias sin abortar la generación de .elf/.hex,
     * devuelve diagnósticos estructurados con número de línea y tamaño exacto de Flash.
     */
    public RawBuildResult compileRawC(String name, String cSource, Path workDir) throws IOException, InterruptedException {
        if (!hasAvrGcc()) {
            return new RawBuildResult(false, null,
                    List.of(new BuildDiagnostic(0, 0, DiagnosticSeverity.ERROR, "No se encontró avr-gcc/avr-objcopy en el equipo.")),
                    0, "avr-gcc no disponible");
        }
        Files.createDirectories(workDir);
        String stem = safeFileStem(name);
        Path cFile = workDir.resolve(stem + ".c");
        Path elfFile = workDir.resolve(stem + ".elf");
        Path hexFile = workDir.resolve(stem + ".hex");
        Files.writeString(cFile, cSource == null ? "" : cSource, StandardCharsets.UTF_8);

        ExecResult gcc = runCapture(List.of(
                avrGcc.toString(),
                "-mmcu=atmega328p",
                "-Os",
                "-std=c99",
                "-Wall",
                "-Wextra",
                "-g",
                "-o", elfFile.toString(),
                cFile.toString()
        ), workDir);

        List<BuildDiagnostic> diags = parseGccDiagnostics(gcc.output());
        if (gcc.exitCode() != 0) {
            if (diags.stream().noneMatch(d -> d.severity() == DiagnosticSeverity.ERROR)) {
                String fallback = gcc.output().isBlank() ? "avr-gcc terminó con código " + gcc.exitCode() : firstNonBlankLine(gcc.output());
                diags = new ArrayList<>(diags);
                diags.add(new BuildDiagnostic(0, 0, DiagnosticSeverity.ERROR, fallback));
            }
            return new RawBuildResult(false, null, List.copyOf(diags), 0, gcc.output().strip());
        }

        ExecResult obj = runCapture(List.of(
                avrObjcopy.toString(),
                "-O", "ihex",
                "-R", ".eeprom",
                elfFile.toString(),
                hexFile.toString()
        ), workDir);
        if (obj.exitCode() != 0 || !Files.isRegularFile(hexFile)) {
            List<BuildDiagnostic> all = new ArrayList<>(diags);
            all.add(new BuildDiagnostic(0, 0, DiagnosticSeverity.ERROR, "avr-objcopy falló: " + obj.output().strip()));
            return new RawBuildResult(false, null, List.copyOf(all), 0, (gcc.output() + "\n" + obj.output()).strip());
        }

        int flashBytes = measureIntelHexBytes(hexFile);
        String combined = (gcc.output() + "\n" + obj.output()).strip();
        return new RawBuildResult(true, new BuildArtifact(cFile, elfFile, hexFile, combined), List.copyOf(diags), flashBytes, combined);
    }

    /**
     * Ensambla código AVRASM2 editado manualmente para el IDE integrado:
     * devuelve diagnósticos con línea exacta, permite advertencias no fatales y mide Flash real.
     */
    public RawBuildResult assembleRawAsm(String name, String asmSource, Path workDir) throws IOException, InterruptedException {
        if (!hasAvrasm2()) {
            return new RawBuildResult(false, null,
                    List.of(new BuildDiagnostic(0, 0, DiagnosticSeverity.ERROR, "No se encontró avrasm2.exe o m328Pdef.inc en el equipo.")),
                    0, "avrasm2 no disponible");
        }
        Files.createDirectories(workDir);
        String stem = safeFileStem(name);
        Path asmFile = workDir.resolve(stem + ".asm");
        Path hexFile = workDir.resolve(stem + ".hex");
        Files.writeString(asmFile, asmSource == null ? "" : asmSource, StandardCharsets.UTF_8);

        ExecResult asm = runCapture(List.of(
                avrasm2.toString(),
                "-fI",
                "-I", avrIncludes.toString(),
                "-o", hexFile.toString(),
                asmFile.toString()
        ), workDir);

        List<BuildDiagnostic> diags = parseAvrasmDiagnostics(asm.output());
        boolean hasErrors = asm.exitCode() != 0 || diags.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR)
                || !Files.isRegularFile(hexFile);
        if (hasErrors) {
            if (diags.stream().noneMatch(d -> d.severity() == DiagnosticSeverity.ERROR)) {
                String fallback = asm.output().isBlank() ? "avrasm2 terminó con código " + asm.exitCode() : firstNonBlankLine(asm.output());
                diags = new ArrayList<>(diags);
                diags.add(new BuildDiagnostic(0, 0, DiagnosticSeverity.ERROR, fallback));
            }
            return new RawBuildResult(false, null, List.copyOf(diags), 0, asm.output().strip());
        }

        int flashBytes = measureIntelHexBytes(hexFile);
        return new RawBuildResult(true, new BuildArtifact(asmFile, null, hexFile, asm.output().strip()),
                List.copyOf(diags), flashBytes, asm.output().strip());
    }

    /** Valida un archivo Intel HEX externo y devuelve su tamaño en bytes de Flash. */
    public static int validateExternalHex(Path hexFile) throws IOException {
        if (hexFile == null || !Files.isRegularFile(hexFile)) {
            throw new IOException("El archivo .hex no existe.");
        }
        int totalBytes = 0;
        boolean sawEof = false;
        List<String> lines = Files.readAllLines(hexFile, StandardCharsets.UTF_8);
        for (int idx = 0; idx < lines.size(); idx++) {
            String line = lines.get(idx).trim();
            if (line.isEmpty()) continue;
            if (!line.startsWith(":") || line.length() < 11) {
                throw new IOException("Línea " + (idx + 1) + " no es un registro Intel HEX válido.");
            }
            int len;
            int recType;
            try {
                len = Integer.parseInt(line.substring(1, 3), 16);
                recType = Integer.parseInt(line.substring(7, 9), 16);
            } catch (NumberFormatException ex) {
                throw new IOException("Hexadecimal inválido en línea " + (idx + 1) + ".");
            }
            if (line.length() < 11 + len * 2) {
                throw new IOException("Registro Intel HEX truncado en línea " + (idx + 1) + ".");
            }
            if (recType == 0) totalBytes += len;
            else if (recType == 1) sawEof = true;
        }
        if (!sawEof) {
            throw new IOException("El archivo .hex no contiene el registro de fin de archivo (:00000001FF).");
        }
        if (totalBytes <= 0) {
            throw new IOException("El archivo .hex no contiene bytes de programa.");
        }
        return totalBytes;
    }

    /** Mide el total de bytes de Flash (registros tipo 00) en un archivo Intel HEX. */
    public static int measureIntelHexBytes(Path hexFile) {
        try {
            int total = 0;
            for (String raw : Files.readAllLines(hexFile, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.startsWith(":") && line.length() >= 11) {
                    int len = Integer.parseInt(line.substring(1, 3), 16);
                    int recType = Integer.parseInt(line.substring(7, 9), 16);
                    if (recType == 0) total += len;
                }
            }
            return total;
        } catch (Exception ex) {
            return 0;
        }
    }

    public static List<BuildDiagnostic> parseGccDiagnostics(String output) {
        List<BuildDiagnostic> list = new ArrayList<>();
        if (output == null || output.isBlank()) return list;
        for (String line : output.lines().toList()) {
            Matcher m = GCC_DIAG_PATTERN.matcher(line);
            if (m.matches()) {
                int ln = parseIntSafe(m.group(2));
                int col = m.group(3) != null ? parseIntSafe(m.group(3)) : 0;
                String sevStr = m.group(4).toLowerCase(Locale.ROOT);
                DiagnosticSeverity sev = sevStr.contains("error") ? DiagnosticSeverity.ERROR
                        : sevStr.equals("warning") ? DiagnosticSeverity.WARNING
                        : DiagnosticSeverity.NOTE;
                list.add(new BuildDiagnostic(ln, col, sev, m.group(5).trim()));
            } else if (line.contains("undefined reference to") || line.contains("ld.exe:")) {
                list.add(new BuildDiagnostic(0, 0, DiagnosticSeverity.ERROR, line.trim()));
            }
        }
        return list;
    }

    public static List<BuildDiagnostic> parseAvrasmDiagnostics(String output) {
        List<BuildDiagnostic> list = new ArrayList<>();
        if (output == null || output.isBlank()) return list;
        for (String line : output.lines().toList()) {
            Matcher m = AVRASM_DIAG_PATTERN.matcher(line);
            if (m.matches()) {
                int ln = parseIntSafe(m.group(1));
                String sevStr = m.group(2).toLowerCase(Locale.ROOT);
                DiagnosticSeverity sev = sevStr.equals("error") ? DiagnosticSeverity.ERROR
                        : sevStr.equals("warning") ? DiagnosticSeverity.WARNING
                        : DiagnosticSeverity.NOTE;
                list.add(new BuildDiagnostic(ln, 0, sev, m.group(3).trim()));
                continue;
            }
            Matcher mf = AVRASM_FALLBACK_PATTERN.matcher(line);
            if (mf.matches()) {
                String sevStr = mf.group(1).toLowerCase(Locale.ROOT);
                DiagnosticSeverity sev = sevStr.equals("error") ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING;
                list.add(new BuildDiagnostic(0, 0, sev, mf.group(2).trim()));
            }
        }
        return list;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception ex) { return 0; }
    }

    private static String firstNonBlankLine(String s) {
        return s.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst().orElse("Error desconocido");
    }

    private static String run(List<String> cmd, Path cwd) throws IOException, InterruptedException {
        ExecResult r = runCapture(cmd, cwd);
        if (r.exitCode() == 0) return r.output();
        throw new IOException("Falló " + cmd.getFirst() + " (código " + r.exitCode() + "):\n" + r.output());
    }

    private static ExecResult runCapture(List<String> cmd, Path cwd) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            Process p = new ProcessBuilder(cmd)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .start();
            p.getOutputStream().close();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Tiempo agotado ejecutando: " + cmd.getFirst());
            }
            if (p.exitValue() == 0 || !(attempt < 2 && output.contains("CreateProcess"))) {
                return new ExecResult(p.exitValue(), output);
            }
            Thread.sleep(150);
        }
    }

    private static Path firstExisting(String... candidates) {
        for (String c : candidates) {
            if (c == null || c.isBlank()) continue;
            Path p = Path.of(c).toAbsolutePath().normalize();
            if (Files.exists(p)) return p;
        }
        return null;
    }
}
