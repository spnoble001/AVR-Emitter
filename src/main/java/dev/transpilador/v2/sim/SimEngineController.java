package dev.transpilador.v2.sim;

import dev.transpilador.v2.emit.AsmEmitter;
import dev.transpilador.v2.emit.CEmitter;
import dev.transpilador.v2.emit.TraceMap;
import dev.transpilador.v2.model.Hardware;
import dev.transpilador.v2.model.ProgramSpec;
import dev.transpilador.v2.model.State;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Controlador de simulación desacoplado de la interfaz gráfica.
 * Administra la compilación (C99 / AVR ASM), la sesión activa de {@link SimavrSession},
 * el reloj de avance periódico, las entradas del usuario (botones, ADC, UART RX) y la
 * difusión de instantáneas inmutables {@link Snapshot} hacia cualquier vista (JCEF/HTTP o Swing).
 */
public final class SimEngineController implements AutoCloseable {
    public enum Backend {
        C99("C99 (avr-gcc)"),
        ASM("AVR ASM (avrasm2)");

        private final String label;
        Backend(String label) { this.label = label; }
        public String label() { return label; }
        @Override public String toString() { return label; }
    }

    public record PortRegs(int port, int ddr, int pin) {}

    public record CpuSnapshot(
            int[] regs,
            long changedRegsMask,
            int sreg,
            int prevSreg,
            int pc,
            int sp,
            Map<String, PortRegs> ports,
            Map<String, String> registerAliases,
            Map<String, Integer> liveVars,
            Map<String, String> livePhases,
            Map<String, Integer> liveTimersMs
    ) {}

    public record FsmTransitionEvent(
            long seq,
            String group,
            String from,
            String to,
            double elapsedMs
    ) {}

    public record HighlightState(
            int ruleIndex,
            String title,
            Set<String> components,
            Set<String> pins
    ) {
        public HighlightState {
            components = Collections.unmodifiableSet(new LinkedHashSet<>(components));
            pins = Collections.unmodifiableSet(new LinkedHashSet<>(pins));
        }
    }

    public record CustomFirmware(
            String label,
            Backend backend,
            Path hexFile,
            Path elfFile,
            int flashBytes
    ) {}

    public record Snapshot(
            long specVersion,
            long stateSeq,
            boolean running,
            boolean desiredRunning,
            boolean busy,
            boolean sessionActive,
            String backend,
            String customFirmwareLabel,
            String clockLabel,
            long fCpuHz,
            long cycles,
            double elapsedMs,
            String status,
            String error,
            Map<String, Boolean> pinHigh,
            Map<String, Boolean> buttonPressed,
            Map<String, Boolean> buttonPinned,
            Map<String, Boolean> ledOn,
            Map<String, Integer> bcdDigits,
            Map<String, Integer> analogMv,
            Map<String, Integer> pwmDuties,
            Map<String, Double> pwmMeasuredHz,
            String uartTx,
            boolean uartRxReady,
            CpuSnapshot cpu,
            List<FsmTransitionEvent> recentFsmTransitions,
            HighlightState highlight
    ) {}

    private record TelemetryMap(
            Map<String, String> registerAliases,
            Map<String, TraceMap.StorageLoc> varLocs,
            Map<String, TraceMap.StorageLoc> phaseLocs,
            Map<String, TraceMap.PairLoc> timerLocs) {
        static TelemetryMap empty() {
            return new TelemetryMap(Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    private record TelemetrySample(
            SimavrSession.CpuRegisters regs,
            Map<String, Integer> vars,
            Map<String, String> phases,
            Map<String, Integer> timersMs) {}

    private static final List<Hardware.Pin> ALL_PINS = buildAllPins();

    private final Toolchain toolchain;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sim-engine-worker");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sim-engine-ticker");
        t.setDaemon(true);
        return t;
    });

    private final List<Consumer<Snapshot>> stateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ProgramSpec>> specListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<HighlightState>> highlightListeners = new CopyOnWriteArrayList<>();
    private final List<IntConsumer> ruleSelectListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong specVersion = new AtomicLong(1);
    private final AtomicLong stateSeq = new AtomicLong(0);

    private final Object lock = new Object();
    private ProgramSpec spec;
    private Backend backend = Backend.C99;
    private CustomFirmware customFirmware;
    private AsmEmitter.InstructionProfile asmProfile = AsmEmitter.InstructionProfile.ELEMENTARY;
    private AsmEmitter.StorageMode asmStorageMode = AsmEmitter.StorageMode.REGISTERS_FIRST;

    private final Set<String> pressedButtons = new HashSet<>();
    private final Set<String> pinnedButtons = new HashSet<>();
    private final Map<String, Integer> analogMv = new LinkedHashMap<>();

    private SimavrSession session;
    private SimavrSession.State lastState;
    private TelemetryMap telemetryMap = TelemetryMap.empty();
    private SimavrSession.CpuRegisters lastCpuRegs;
    private SimavrSession.CpuRegisters prevCpuRegs;
    private Map<String, Integer> liveVars = Map.of();
    private Map<String, String> livePhases = Map.of();
    private Map<String, Integer> liveTimersMs = Map.of();
    private final Deque<FsmTransitionEvent> fsmTransitions = new ArrayDeque<>();
    private long fsmEventSeq;
    private HighlightState highlight;
    private Map<Hardware.PwmChannel, Integer> livePwmDuties = Map.of();
    private Map<Hardware.PwmChannel, Double> livePwmFreqHz = Map.of();
    private boolean running;
    private boolean desiredRunning;
    private boolean busy;
    private long generation;
    private String statusText = "Listo para simular";
    private String errorText = "";
    private volatile boolean closed;

    public SimEngineController(ProgramSpec initialSpec) {
        this(initialSpec, Toolchain.detect());
    }

    public SimEngineController(ProgramSpec initialSpec, Toolchain toolchain) {
        this.toolchain = Objects.requireNonNull(toolchain, "toolchain");
        this.spec = Objects.requireNonNull(initialSpec, "initialSpec");
        resetInputsForSpec(initialSpec);
        this.statusText = "Listo para simular (" + backend + ")";
        ticker.scheduleAtFixedRate(this::onTick, 40, 40, TimeUnit.MILLISECONDS);
    }

    public void addStateListener(Consumer<Snapshot> listener) {
        stateListeners.add(Objects.requireNonNull(listener));
    }

    public void removeStateListener(Consumer<Snapshot> listener) {
        stateListeners.remove(listener);
    }

    public void addSpecListener(Consumer<ProgramSpec> listener) {
        specListeners.add(Objects.requireNonNull(listener));
    }

    public void removeSpecListener(Consumer<ProgramSpec> listener) {
        specListeners.remove(listener);
    }

    public void addHighlightListener(Consumer<HighlightState> listener) {
        highlightListeners.add(Objects.requireNonNull(listener));
    }

    public void removeHighlightListener(Consumer<HighlightState> listener) {
        highlightListeners.remove(listener);
    }

    public void addRuleSelectListener(IntConsumer listener) {
        ruleSelectListeners.add(Objects.requireNonNull(listener));
    }

    public void removeRuleSelectListener(IntConsumer listener) {
        ruleSelectListeners.remove(listener);
    }

    public HighlightState highlight() {
        synchronized (lock) {
            return highlight;
        }
    }

    public void setHighlight(HighlightState next) {
        synchronized (lock) {
            if (Objects.equals(this.highlight, next)) return;
            this.highlight = next;
        }
        notifyHighlight(next);
    }

    /** Notifica al Estudio (Swing) que el usuario seleccionó una tarjeta/transición desde la vista web. */
    public void requestSelectRuleFromWeb(int ruleIndex) {
        for (IntConsumer l : ruleSelectListeners) {
            try {
                l.accept(ruleIndex);
            } catch (Exception ignored) {
            }
        }
    }

    public ProgramSpec spec() {
        synchronized (lock) {
            return spec;
        }
    }

    public Backend backend() {
        synchronized (lock) {
            return backend;
        }
    }

    public CustomFirmware customFirmware() {
        synchronized (lock) {
            return customFirmware;
        }
    }

    /**
     * Carga un firmware compilado manualmente (desde el editor C99/ASM o un .hex externo)
     * para ejecutarlo en simavr contra el circuito físico actual.
     */
    public void setCustomFirmware(CustomFirmware firmware) {
        SimavrSession old;
        synchronized (lock) {
            old = clearSessionLocked();
            this.customFirmware = firmware;
            if (firmware != null && firmware.backend() != null) {
                this.backend = firmware.backend();
            }
            this.errorText = "";
            this.statusText = firmware != null
                    ? "Listo (" + firmware.label() + " · " + firmware.flashBytes() + " B Flash)"
                    : "Listo para simular (" + backend + ")";
        }
        closeAsync(old);
        notifyState();
    }

    public void clearCustomFirmware() {
        setCustomFirmware(null);
    }

    private String activeSourceLabelLocked() {
        return customFirmware != null ? customFirmware.label() : backend.label();
    }

    public void setProgram(ProgramSpec next) {
        Objects.requireNonNull(next, "next");
        SimavrSession old;
        synchronized (lock) {
            old = clearSessionLocked();
            this.spec = next;
            this.highlight = null;
            specVersion.incrementAndGet();
            resetInputsForSpec(next);
            this.statusText = "Listo para simular (" + activeSourceLabelLocked() + ")";
            this.errorText = "";
        }
        closeAsync(old);
        notifySpec(next);
        notifyHighlight(null);
        notifyState();
    }

    public void setBackend(Backend next) {
        Objects.requireNonNull(next, "next");
        SimavrSession old = null;
        boolean changed;
        synchronized (lock) {
            changed = (this.backend != next) || (this.customFirmware != null);
            if (changed) {
                this.backend = next;
                this.customFirmware = null;
                old = clearSessionLocked();
                this.statusText = "Listo para simular (" + backend + ")";
                this.errorText = "";
            }
        }
        if (changed) {
            closeAsync(old);
            notifyState();
        }
    }

    public void setAsmProfile(AsmEmitter.InstructionProfile profile) {
        if (profile == null) return;
        SimavrSession old = null;
        boolean changed;
        synchronized (lock) {
            changed = (this.asmProfile != profile);
            if (changed) {
                this.asmProfile = profile;
                if (this.backend == Backend.ASM) {
                    old = clearSessionLocked();
                    this.statusText = "Listo para simular (" + backend + ")";
                    this.errorText = "";
                }
            }
        }
        if (changed) {
            closeAsync(old);
            notifyState();
        }
    }

    public void setAsmStorageMode(AsmEmitter.StorageMode mode) {
        if (mode == null) return;
        SimavrSession old = null;
        boolean changed;
        synchronized (lock) {
            changed = (this.asmStorageMode != mode);
            if (changed) {
                this.asmStorageMode = mode;
                if (this.backend == Backend.ASM) {
                    old = clearSessionLocked();
                    this.statusText = "Listo para simular (" + backend + ")";
                    this.errorText = "";
                }
            }
        }
        if (changed) {
            closeAsync(old);
            notifyState();
        }
    }

    public void start() {
        if (closed) return;
        synchronized (lock) {
            if (busy) {
                desiredRunning = true;
                return;
            }
            if (session != null) {
                desiredRunning = true;
                running = true;
                errorText = "";
                if (lastState != null) {
                    statusText = "En marcha (" + backend + " @ " + spec.clockFreq().label() + ") · "
                            + formatMs(lastState.cycles(), spec.fCpuHz());
                }
                notifyState();
                return;
            }
        }
        launchSession(2, true, null);
    }

    private void launchSession(int initialMs, boolean runAfterLaunch, String customStatus) {
        final ProgramSpec snap;
        final Backend targetBackend;
        final CustomFirmware targetCustom;
        final String targetLabel;
        final AsmEmitter.InstructionProfile targetProfile;
        final AsmEmitter.StorageMode targetMode;
        final long gen;
        synchronized (lock) {
            if (closed || busy || session != null) return;
            snap = spec;
            targetBackend = backend;
            targetCustom = customFirmware;
            targetLabel = activeSourceLabelLocked();
            targetProfile = asmProfile;
            targetMode = asmStorageMode;
            gen = ++generation;
            busy = true;
            running = false;
            desiredRunning = runAfterLaunch;
            errorText = "";
            statusText = customStatus != null
                    ? customStatus
                    : (targetCustom != null ? "Cargando " + targetLabel + " en simavr…" : "Compilando " + targetBackend + " y arrancando simavr…");
        }
        notifyState();

        worker.submit(() -> {
            try {
                Path hexToLoad;
                TelemetryMap tMap;
                if (targetCustom != null) {
                    hexToLoad = targetCustom.hexFile();
                    tMap = targetCustom.elfFile() != null
                            ? buildCTelemetryMap(snap, targetCustom.elfFile())
                            : TelemetryMap.empty();
                } else {
                    Path dir = Files.createTempDirectory("studio-v2-sim-");
                    Toolchain.BuildArtifact art;
                    if (targetBackend == Backend.C99) {
                        boolean needsSimSymbols = !snap.vars().isEmpty() || !snap.phaseGroups().isEmpty() || !snap.timers().isEmpty();
                        String cSrc = needsSimSymbols
                                ? new CEmitter().emitForSimulation(snap)
                                : new CEmitter().emit(snap);
                        art = toolchain.compileC(snap.name(), cSrc, dir);
                        tMap = buildCTelemetryMap(snap, art.elfFile());
                    } else {
                        AsmEmitter.EmitOutput asmOut = new AsmEmitter().emitWithTrace(snap, targetProfile, targetMode);
                        art = toolchain.assembleAsm(snap.name(), asmOut.code(), dir);
                        tMap = new TelemetryMap(
                                asmOut.registerAliases(),
                                asmOut.varLocations(),
                                asmOut.phaseLocations(),
                                asmOut.timerRemLocations());
                    }
                    hexToLoad = art.hexFile();
                }
                SimavrSession opened = SimavrSession.open(hexToLoad, snap.fCpuHz(), toolchain);
                applyAllInputsTo(opened, snap);
                SimavrSession.State init = initialMs > 0
                        ? opened.advanceMs(initialMs)
                        : opened.stepInstruction();
                Map<Hardware.PwmChannel, Integer> pwms = readPwmsSafe(opened, snap);
                Map<Hardware.PwmChannel, Double> freqs = measurePwmFreqsSafe(opened, snap, init.cycles(), pwms);
                TelemetrySample sample = readTelemetrySafe(opened, snap, tMap);
                boolean stale;
                synchronized (lock) {
                    stale = closed || (gen != generation);
                    if (!stale) {
                        session = opened;
                        telemetryMap = tMap;
                        lastState = init;
                        livePwmDuties = pwms;
                        livePwmFreqHz = freqs;
                        applyTelemetryLocked(snap, init, sample, true);
                        busy = false;
                        running = desiredRunning;
                        errorText = "";
                        String prefix = running ? "En marcha" : "En pausa";
                        statusText = prefix + " (" + targetLabel + " @ " + snap.clockFreq().label() + ") · "
                                + formatMs(init.cycles(), snap.fCpuHz());
                    }
                }
                if (stale) {
                    opened.close();
                } else {
                    notifyState();
                }
            } catch (Throwable ex) {
                synchronized (lock) {
                    if (gen != generation) return;
                    busy = false;
                    running = false;
                    desiredRunning = false;
                    errorText = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    statusText = "Error: " + errorText;
                }
                notifyState();
            }
        });
    }

    public void pause() {
        synchronized (lock) {
            desiredRunning = false;
            running = false;
            if (session != null && lastState != null) {
                statusText = "En pausa (" + backend + " @ " + spec.clockFreq().label() + ") · "
                        + formatMs(lastState.cycles(), spec.fCpuHz());
            } else if (busy) {
                statusText = "Pausa solicitada (quedará en pausa al terminar de compilar)…";
            }
        }
        notifyState();
    }

    public void stepMs(int ms) {
        if (closed) return;
        int clampedMs = Math.clamp(ms, 1, 500);
        final SimavrSession s;
        final ProgramSpec snap;
        final TelemetryMap tMap;
        final long gen;
        final boolean needLaunch;
        final Backend bSnap;
        synchronized (lock) {
            if (busy) return;
            if (session == null) {
                needLaunch = true;
                bSnap = backend;
                s = null;
                snap = null;
                tMap = TelemetryMap.empty();
                gen = 0;
            } else {
                needLaunch = false;
                bSnap = backend;
                running = false;
                desiredRunning = false;
                busy = true;
                s = session;
                snap = spec;
                tMap = telemetryMap;
                gen = generation;
            }
        }
        if (needLaunch) {
            // Compilar y avanzar exactamente clampedMs quedando EN PAUSA (nunca iniciar reproducción continua)
            launchSession(clampedMs, false, "Compilando " + bSnap + " para avanzar +" + clampedMs + " ms…");
            return;
        }
        notifyState();

        worker.submit(() -> {
            try {
                applyAllInputsTo(s, snap);
                SimavrSession.State next = s.advanceMs(clampedMs);
                Map<Hardware.PwmChannel, Integer> pwms = readPwmsSafe(s, snap);
                Map<Hardware.PwmChannel, Double> freqs = measurePwmFreqsSafe(s, snap, next.cycles(), pwms);
                TelemetrySample sample = readTelemetrySafe(s, snap, tMap);
                synchronized (lock) {
                    if (gen != generation) return;
                    lastState = next;
                    livePwmDuties = pwms;
                    livePwmFreqHz = freqs;
                    applyTelemetryLocked(snap, next, sample, false);
                    busy = false;
                    errorText = "";
                    statusText = "En pausa (" + backend + " @ " + snap.clockFreq().label() + ") · "
                            + formatMs(next.cycles(), snap.fCpuHz());
                }
                notifyState();
            } catch (Exception ex) {
                synchronized (lock) {
                    if (gen != generation) return;
                    busy = false;
                    errorText = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    statusText = "Error: " + errorText;
                }
                notifyState();
            }
        });
    }

    /** Ejecuta exactamente una instrucción de CPU ATmega328P y deja la simulación en pausa. */
    public void stepInstruction() {
        if (closed) return;
        final SimavrSession s;
        final ProgramSpec snap;
        final TelemetryMap tMap;
        final long gen;
        final boolean needLaunch;
        final Backend bSnap;
        synchronized (lock) {
            if (busy) return;
            if (session == null) {
                needLaunch = true;
                bSnap = backend;
                s = null;
                snap = null;
                tMap = TelemetryMap.empty();
                gen = 0;
            } else {
                needLaunch = false;
                bSnap = backend;
                running = false;
                desiredRunning = false;
                busy = true;
                s = session;
                snap = spec;
                tMap = telemetryMap;
                gen = generation;
            }
        }
        if (needLaunch) {
            launchSession(0, false, "Compilando " + bSnap + " para paso +1 instrucción…");
            return;
        }
        notifyState();

        worker.submit(() -> {
            try {
                applyAllInputsTo(s, snap);
                SimavrSession.State next = s.stepInstruction();
                Map<Hardware.PwmChannel, Integer> pwms = readPwmsSafe(s, snap);
                Map<Hardware.PwmChannel, Double> freqs = measurePwmFreqsSafe(s, snap, next.cycles(), pwms);
                TelemetrySample sample = readTelemetrySafe(s, snap, tMap);
                synchronized (lock) {
                    if (gen != generation) return;
                    lastState = next;
                    livePwmDuties = pwms;
                    livePwmFreqHz = freqs;
                    applyTelemetryLocked(snap, next, sample, false);
                    busy = false;
                    errorText = "";
                    statusText = String.format(Locale.ROOT, "Paso +1 instr (%s @ %s) · PC=0x%04X · %d ciclos",
                            backend, snap.clockFreq().label(), next.pc(), next.cycles());
                }
                notifyState();
            } catch (Exception ex) {
                synchronized (lock) {
                    if (gen != generation) return;
                    busy = false;
                    errorText = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    statusText = "Error: " + errorText;
                }
                notifyState();
            }
        });
    }

    public void reset() {
        SimavrSession old;
        synchronized (lock) {
            old = clearSessionLocked();
            statusText = "Listo para simular (" + backend + ")";
            errorText = "";
        }
        closeAsync(old);
        notifyState();
    }

    public void setButtonPressed(String name, boolean pressed) {
        if (name == null || name.isBlank()) return;
        synchronized (lock) {
            if (pressed) pressedButtons.add(name);
            else pressedButtons.remove(name);
        }
        notifyState();
    }

    public void setButtonPinned(String name, boolean pinned) {
        if (name == null || name.isBlank()) return;
        synchronized (lock) {
            if (pinned) pinnedButtons.add(name);
            else pinnedButtons.remove(name);
        }
        notifyState();
    }

    public void toggleButtonPinned(String name) {
        if (name == null || name.isBlank()) return;
        synchronized (lock) {
            if (!pinnedButtons.remove(name)) pinnedButtons.add(name);
        }
        notifyState();
    }

    public void setAnalogMv(String name, int mv) {
        if (name == null || name.isBlank()) return;
        int clamped = Math.clamp(mv, 0, 5000);
        synchronized (lock) {
            analogMv.put(name, clamped);
        }
        notifyState();
    }

    public void sendUartRx(String text) {
        if (closed || text == null || text.isEmpty()) return;
        final SimavrSession s;
        final ProgramSpec snap;
        final TelemetryMap tMap;
        final long gen;
        synchronized (lock) {
            if (session == null) return;
            s = session;
            snap = spec;
            tMap = telemetryMap;
            gen = generation;
        }
        worker.submit(() -> {
            try {
                for (int i = 0; i < text.length(); i++) {
                    boolean ok = s.injectUartRx(text.charAt(i) & 0x7F);
                    if (!ok) {
                        synchronized (lock) {
                            if (gen == generation) {
                                statusText = "UART RX ocupado o aún no habilitado; reintenta.";
                            }
                        }
                        notifyState();
                        return;
                    }
                    SimavrSession.State st = s.advanceMs(5);
                    TelemetrySample sample = readTelemetrySafe(s, snap, tMap);
                    synchronized (lock) {
                        if (gen != generation) return;
                        lastState = st;
                        applyTelemetryLocked(snap, st, sample, false);
                    }
                }
                notifyState();
            } catch (Exception ex) {
                synchronized (lock) {
                    if (gen == generation) {
                        errorText = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                        statusText = "Error UART RX: " + errorText;
                    }
                }
                notifyState();
            }
        });
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            ProgramSpec s = this.spec;
            SimavrSession.State st = this.lastState;
            Map<String, Boolean> pins = new LinkedHashMap<>();
            if (st != null) {
                for (Hardware.Pin p : ALL_PINS) {
                    pins.put(p.label(), st.pinHigh(p));
                }
            }

            Map<String, Boolean> btnPressed = new LinkedHashMap<>();
            Map<String, Boolean> btnPinned = new LinkedHashMap<>();
            for (Hardware.Button b : s.componentsOf(Hardware.Button.class)) {
                btnPressed.put(b.name(), pressedButtons.contains(b.name()) || pinnedButtons.contains(b.name()));
                btnPinned.put(b.name(), pinnedButtons.contains(b.name()));
            }

            Map<String, Boolean> leds = new LinkedHashMap<>();
            for (Hardware.Led l : s.componentsOf(Hardware.Led.class)) {
                leds.put(l.name(), st != null ? st.isLedOn(l) : l.initialOn());
            }

            Map<String, Integer> bcds = new LinkedHashMap<>();
            for (Hardware.BcdDisplay d : s.componentsOf(Hardware.BcdDisplay.class)) {
                bcds.put(d.name(), st != null ? st.readBcd(d) : d.initialDigit());
            }

            Map<String, Integer> analogs = new LinkedHashMap<>();
            for (Hardware.AnalogInput a : s.componentsOf(Hardware.AnalogInput.class)) {
                analogs.put(a.name(), analogMv.getOrDefault(a.name(), 0));
            }

            Map<String, Integer> pwms = new LinkedHashMap<>();
            Map<String, Double> pwmHz = new LinkedHashMap<>();
            for (Hardware.PwmOutput p : s.componentsOf(Hardware.PwmOutput.class)) {
                int duty = livePwmDuties.getOrDefault(p.channel(),
                        st == null ? p.initialDuty() : (st.pinHigh(p.pin()) ? 255 : 0));
                pwms.put(p.name(), duty);
                pwmHz.put(p.name(), livePwmFreqHz.getOrDefault(p.channel(), 0.0));
            }

            long cycles = st != null ? st.cycles() : 0L;
            double elapsedMs = st != null ? (cycles * 1000.0 / s.fCpuHz()) : 0.0;
            String tx = st != null ? st.txAscii() : "";
            boolean rxReady = st != null && st.uartRxReady();

            CpuSnapshot cpuSnap = null;
            if (st != null && lastCpuRegs != null) {
                int[] cur = lastCpuRegs.r();
                long changedMask = 0L;
                if (prevCpuRegs != null) {
                    int[] prev = prevCpuRegs.r();
                    for (int i = 0; i < 32; i++) {
                        if (cur[i] != prev[i]) changedMask |= (1L << i);
                    }
                }
                Map<String, PortRegs> ports = new LinkedHashMap<>();
                ports.put("B", new PortRegs(st.portB().port(), st.portB().ddr(), st.portB().pin()));
                ports.put("C", new PortRegs(st.portC().port(), st.portC().ddr(), st.portC().pin()));
                ports.put("D", new PortRegs(st.portD().port(), st.portD().ddr(), st.portD().pin()));
                cpuSnap = new CpuSnapshot(
                        cur,
                        changedMask,
                        lastCpuRegs.sreg(),
                        prevCpuRegs != null ? prevCpuRegs.sreg() : lastCpuRegs.sreg(),
                        st.pc(),
                        lastCpuRegs.sp(),
                        Collections.unmodifiableMap(ports),
                        telemetryMap.registerAliases(),
                        liveVars,
                        livePhases,
                        liveTimersMs
                );
            }

            return new Snapshot(
                    specVersion.get(),
                    stateSeq.incrementAndGet(),
                    running,
                    desiredRunning,
                    busy,
                    session != null,
                    backend.name(),
                    customFirmware != null ? customFirmware.label() : null,
                    s.clockFreq().label(),
                    s.fCpuHz(),
                    cycles,
                    elapsedMs,
                    statusText,
                    errorText,
                    Collections.unmodifiableMap(pins),
                    Collections.unmodifiableMap(btnPressed),
                    Collections.unmodifiableMap(btnPinned),
                    Collections.unmodifiableMap(leds),
                    Collections.unmodifiableMap(bcds),
                    Collections.unmodifiableMap(analogs),
                    Collections.unmodifiableMap(pwms),
                    Collections.unmodifiableMap(pwmHz),
                    tx,
                    rxReady,
                    cpuSnap,
                    List.copyOf(fsmTransitions),
                    highlight
            );
        }
    }

    private void onTick() {
        if (closed) return;
        final SimavrSession s;
        final ProgramSpec snap;
        final TelemetryMap tMap;
        final long gen;
        synchronized (lock) {
            if (!running || busy || session == null) return;
            busy = true;
            s = session;
            snap = spec;
            tMap = telemetryMap;
            gen = generation;
        }
        worker.submit(() -> {
            try {
                applyAllInputsTo(s, snap);
                SimavrSession.State st = s.advanceMs(20);
                Map<Hardware.PwmChannel, Integer> pwms = readPwmsSafe(s, snap);
                Map<Hardware.PwmChannel, Double> freqs = measurePwmFreqsSafe(s, snap, st.cycles(), pwms);
                TelemetrySample sample = readTelemetrySafe(s, snap, tMap);
                synchronized (lock) {
                    if (gen != generation) return;
                    lastState = st;
                    livePwmDuties = pwms;
                    livePwmFreqHz = freqs;
                    applyTelemetryLocked(snap, st, sample, false);
                    busy = false;
                    if (running) {
                        statusText = "En marcha (" + backend + " @ " + snap.clockFreq().label() + ") · "
                                + formatMs(st.cycles(), snap.fCpuHz());
                    }
                }
                notifyState();
            } catch (Exception ex) {
                synchronized (lock) {
                    if (gen != generation) return;
                    busy = false;
                    running = false;
                    desiredRunning = false;
                    errorText = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    statusText = "Simulación detenida: " + errorText;
                }
                notifyState();
            }
        });
    }

    private static TelemetryMap buildCTelemetryMap(ProgramSpec snap, Path elfFile) {
        try {
            Map<String, Integer> syms = ElfSymbolReader.readSramSymbols(elfFile);
            Map<String, TraceMap.StorageLoc> varLocs = new LinkedHashMap<>();
            Map<String, TraceMap.StorageLoc> phaseLocs = new LinkedHashMap<>();
            Map<String, TraceMap.PairLoc> timerLocs = new LinkedHashMap<>();
            for (State.VarDecl v : snap.vars()) {
                Integer addr = syms.get("var_" + v.name());
                if (addr != null) {
                    varLocs.put(v.name(), TraceMap.StorageLoc.sram(addr, "SRAM 0x" + String.format("%04X", addr)));
                }
            }
            for (State.PhaseGroup g : snap.phaseGroups()) {
                Integer addr = syms.get("fase_" + g.name());
                if (addr != null) {
                    phaseLocs.put(g.name(), TraceMap.StorageLoc.sram(addr, "SRAM 0x" + String.format("%04X", addr)));
                }
            }
            for (State.TimerDecl t : snap.timers()) {
                Integer addr = syms.get("rem_" + t.name());
                if (addr != null && addr + 1 < 0x0900) {
                    timerLocs.put(t.name(), new TraceMap.PairLoc(
                            TraceMap.StorageLoc.sram(addr, "SRAM 0x" + String.format("%04X", addr)),
                            TraceMap.StorageLoc.sram(addr + 1, "SRAM 0x" + String.format("%04X", addr + 1))));
                }
            }
            return new TelemetryMap(Map.of(), varLocs, phaseLocs, timerLocs);
        } catch (Exception ex) {
            return TelemetryMap.empty();
        }
    }

    private static TelemetrySample readTelemetrySafe(SimavrSession s, ProgramSpec snap, TelemetryMap map) {
        try {
            SimavrSession.CpuRegisters regs = s.readRegisters();
            Map<String, Integer> vars = new LinkedHashMap<>();
            for (State.VarDecl v : snap.vars()) {
                TraceMap.StorageLoc loc = map.varLocs().get(v.name());
                if (loc != null) {
                    vars.put(v.name(), readLocByte(s, regs, loc));
                } else {
                    vars.put(v.name(), v.initial());
                }
            }
            Map<String, String> phases = new LinkedHashMap<>();
            for (State.PhaseGroup g : snap.phaseGroups()) {
                TraceMap.StorageLoc loc = map.phaseLocs().get(g.name());
                if (loc != null) {
                    int idx = readLocByte(s, regs, loc);
                    String name = (idx >= 0 && idx < g.phases().size()) ? g.phases().get(idx) : g.initialPhase();
                    phases.put(g.name(), name);
                } else {
                    phases.put(g.name(), g.initialPhase());
                }
            }
            Map<String, Integer> timers = new LinkedHashMap<>();
            for (State.TimerDecl t : snap.timers()) {
                TraceMap.PairLoc pair = map.timerLocs().get(t.name());
                if (pair != null) {
                    int lo = readLocByte(s, regs, pair.lo());
                    int hi = readLocByte(s, regs, pair.hi());
                    timers.put(t.name(), (hi << 8) | lo);
                } else {
                    timers.put(t.name(), 0);
                }
            }
            return new TelemetrySample(regs, vars, phases, timers);
        } catch (Exception ex) {
            return null;
        }
    }

    private static int readLocByte(SimavrSession s, SimavrSession.CpuRegisters regs, TraceMap.StorageLoc loc)
            throws Exception {
        if (loc.inRegister()) {
            return regs.reg(loc.regIndex());
        }
        return s.readMemory(loc.sramAddr(), 1)[0] & 0xFF;
    }

    private void applyTelemetryLocked(ProgramSpec snap, SimavrSession.State st, TelemetrySample sample, boolean initialSample) {
        if (sample == null) return;
        prevCpuRegs = initialSample ? sample.regs() : lastCpuRegs;
        lastCpuRegs = sample.regs();
        double elapsedMs = st.cycles() * 1000.0 / snap.fCpuHz();

        Map<String, String> prevPhases = initialSample ? Map.of() : this.livePhases;
        for (State.PhaseGroup g : snap.phaseGroups()) {
            String nextPhase = sample.phases().get(g.name());
            if (nextPhase == null) continue;
            String fromPhase = initialSample ? g.initialPhase() : prevPhases.get(g.name());
            if (fromPhase != null && !fromPhase.equals(nextPhase)) {
                if (fsmTransitions.size() >= 20) fsmTransitions.removeFirst();
                fsmTransitions.addLast(new FsmTransitionEvent(++fsmEventSeq, g.name(), fromPhase, nextPhase, elapsedMs));
            }
        }
        this.liveVars = Collections.unmodifiableMap(new LinkedHashMap<>(sample.vars()));
        this.livePhases = Collections.unmodifiableMap(new LinkedHashMap<>(sample.phases()));
        this.liveTimersMs = Collections.unmodifiableMap(new LinkedHashMap<>(sample.timersMs()));
    }

    private void applyAllInputsTo(SimavrSession s, ProgramSpec snap) throws Exception {
        Map<Hardware.Button, Boolean> btnSnap = new LinkedHashMap<>();
        Map<Integer, Integer> adcSnap = new LinkedHashMap<>();
        synchronized (lock) {
            for (Hardware.Button b : snap.componentsOf(Hardware.Button.class)) {
                btnSnap.put(b, pressedButtons.contains(b.name()) || pinnedButtons.contains(b.name()));
            }
            for (Hardware.AnalogInput a : snap.componentsOf(Hardware.AnalogInput.class)) {
                adcSnap.put(a.channel(), analogMv.getOrDefault(a.name(), 0));
            }
        }
        for (var e : btnSnap.entrySet()) {
            s.setButton(e.getKey(), e.getValue());
        }
        for (var e : adcSnap.entrySet()) {
            s.setAnalogMv(e.getKey(), e.getValue());
        }
    }

    private static Map<Hardware.PwmChannel, Integer> readPwmsSafe(SimavrSession s, ProgramSpec snap) {
        if (snap.componentsOf(Hardware.PwmOutput.class).isEmpty()) return Map.of();
        try {
            return s.readPwmDuties();
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /** Mide la frecuencia real (Hz) de cada canal PWM directamente a partir de los flancos VCD de simavr. */
    private static Map<Hardware.PwmChannel, Double> measurePwmFreqsSafe(
            SimavrSession s, ProgramSpec snap, long currentCycles, Map<Hardware.PwmChannel, Integer> liveDuties) {
        var pwms = snap.componentsOf(Hardware.PwmOutput.class);
        if (pwms.isEmpty()) return Map.of();
        try {
            return s.measurePwmFrequenciesHz(pwms, snap.fCpuHz(), currentCycles, liveDuties);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private SimavrSession clearSessionLocked() {
        generation++;
        running = false;
        desiredRunning = false;
        busy = false;
        SimavrSession old = session;
        session = null;
        lastState = null;
        telemetryMap = TelemetryMap.empty();
        lastCpuRegs = null;
        prevCpuRegs = null;
        liveVars = Map.of();
        livePhases = Map.of();
        liveTimersMs = Map.of();
        fsmTransitions.clear();
        livePwmDuties = Map.of();
        livePwmFreqHz = Map.of();
        return old;
    }

    private void resetInputsForSpec(ProgramSpec next) {
        pressedButtons.clear();
        pinnedButtons.clear();
        analogMv.clear();
        for (Hardware.AnalogInput a : next.componentsOf(Hardware.AnalogInput.class)) {
            analogMv.put(a.name(), 0);
        }
    }

    private void closeAsync(SimavrSession old) {
        if (old != null) {
            if (!closed) {
                worker.submit(old::close);
            } else {
                old.close();
            }
        }
    }

    private void notifyState() {
        Snapshot snap = snapshot();
        for (Consumer<Snapshot> l : stateListeners) {
            try {
                l.accept(snap);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifySpec(ProgramSpec next) {
        for (Consumer<ProgramSpec> l : specListeners) {
            try {
                l.accept(next);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyHighlight(HighlightState next) {
        for (Consumer<HighlightState> l : highlightListeners) {
            try {
                l.accept(next);
            } catch (Exception ignored) {
            }
        }
    }

    private static String formatMs(long cycles, long fCpuHz) {
        return String.format(Locale.ROOT, "%.1f ms", cycles * 1000.0 / fCpuHz);
    }

    private static List<Hardware.Pin> buildAllPins() {
        List<Hardware.Pin> out = new ArrayList<>(20);
        for (int i = 0; i <= 5; i++) out.add(Hardware.Pin.pb(i));
        for (int i = 0; i <= 5; i++) out.add(Hardware.Pin.pc(i));
        for (int i = 0; i <= 7; i++) out.add(Hardware.Pin.pd(i));
        return List.copyOf(out);
    }

    @Override
    public void close() {
        closed = true;
        ticker.shutdownNow();
        SimavrSession old;
        synchronized (lock) {
            old = clearSessionLocked();
        }
        if (old != null) old.close();
        worker.shutdownNow();
    }
}
