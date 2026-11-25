package dev.transpilador.v2.ui;

import dev.transpilador.v2.emit.AsmEmitter;
import dev.transpilador.v2.emit.CEmitter;
import dev.transpilador.v2.model.Hardware;
import dev.transpilador.v2.model.ProgramSpec;
import dev.transpilador.v2.sim.SimEngineController;
import dev.transpilador.v2.sim.SimavrSession;
import dev.transpilador.v2.sim.Toolchain;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simulador esquemático interactivo estilo Proteus ISIS conectado al motor binario real simavr:
 *  - Hoja técnica punteada con ATmega328P DIP-28, circuito de RESET 10 kΩ, alimentación VCC/GND
 *    y cristal 16 MHz (+22 pF) o RC interno 1/8 MHz.
 *  - Sondas lógicas en tiempo real estilo Proteus: ROJO = ALTO (5V), AZUL = BAJO (0V), VIOLETA = ADC.
 *  - Distinción eléctrica inconfundible entre botón a GND (pull-up interno) y botón a +5V VCC (pull-down 10 kΩ),
 *    LED activo alto (ánodo al pin → GND) vs. activo bajo (+5V → cátodo al pin), CD4511 + 7-seg,
 *    POT-HG 10 kΩ arrastrable con botones −/+, osciloscopio Fast PWM en vivo (duty real de simavr),
 *    Virtual Terminal UART0 y mini analizador lógico de pines.
 */
public final class CircuitCanvasPanel extends JPanel {
    public enum Backend {
        C99("C99 (avr-gcc)"),
        ASM("AVR ASM (avrasm2)");

        private final String label;
        Backend(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private static final int BASE_WIDTH = 860;
    private static final int[] SEGMENT_MASKS = {0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7c, 0x07, 0x7f, 0x67};

    // Paleta esquemática oscura técnica (carbón mineral Lean 4 + señales físicas calibradas)
    private static final Color SHEET_BG = new Color(17, 20, 19);        // #111413
    private static final Color GRID_DOT = new Color(34, 40, 38);        // #222826
    private static final Color IC_BODY = new Color(24, 29, 27);         // #181D1B
    private static final Color IC_BORDER = new Color(64, 75, 70);       // #404B46
    private static final Color IC_LABEL = new Color(236, 239, 237);     // #ECEFED
    private static final Color LOGIC_HIGH = new Color(222, 91, 87);     // Rojo coral = ALTO (5V)
    private static final Color LOGIC_LOW = new Color(75, 147, 186);     // Acero teal = BAJO (0V)
    private static final Color LOGIC_ANALOG = new Color(160, 132, 220); // Violeta técnico = ADC
    private static final Color LOGIC_IDLE = new Color(96, 108, 102);    // Gris mineral = Reposo
    private static final Color WIRE_POWER = new Color(222, 91, 87);
    private static final Color WIRE_GND = new Color(120, 134, 128);
    private static final Color RESISTOR_BODY = new Color(194, 122, 70);

    private record Pad(int number, String label) {}
    private static final List<Pad> PADS = List.of(
            new Pad(1, "PC6/RST"), new Pad(2, "PD0/RX"), new Pad(3, "PD1/TX"), new Pad(4, "PD2"),
            new Pad(5, "PD3/OC2B"), new Pad(6, "PD4"), new Pad(7, "VCC"), new Pad(8, "GND"),
            new Pad(9, "PB6/XT1"), new Pad(10, "PB7/XT2"), new Pad(11, "PD5/OC0B"),
            new Pad(12, "PD6/OC0A"), new Pad(13, "PD7"), new Pad(14, "PB0"),
            new Pad(15, "PB1/OC1A"), new Pad(16, "PB2/OC1B"), new Pad(17, "PB3/OC2A"),
            new Pad(18, "PB4"), new Pad(19, "PB5"), new Pad(20, "AVCC"), new Pad(21, "AREF"),
            new Pad(22, "GND"), new Pad(23, "PC0/ADC0"), new Pad(24, "PC1/ADC1"),
            new Pad(25, "PC2/ADC2"), new Pad(26, "PC3/ADC3"), new Pad(27, "PC4/ADC4"),
            new Pad(28, "PC5/ADC5")
    );

    private record ComponentRow(Hardware.Component component, int y, int height) {}

    private ProgramSpec spec;
    private Backend backend = Backend.C99;
    private SimEngineController.CustomFirmware customFirmware;
    private AsmEmitter.InstructionProfile asmProfile = AsmEmitter.InstructionProfile.ELEMENTARY;
    private AsmEmitter.StorageMode asmStorageMode = AsmEmitter.StorageMode.REGISTERS_FIRST;
    private final List<ComponentRow> rows = new ArrayList<>();
    private int canvasHeight = 560;
    private double scale = 1.0;
    private boolean autoFit = true;
    private int lastFitWidth = -1;

    // Estado de entradas del usuario
    private final Set<String> pressedButtons = new HashSet<>();
    private final Set<String> pinnedButtons = new HashSet<>();
    private final Map<String, Integer> analogMv = new LinkedHashMap<>();
    private Hardware.Button mouseButton;
    private Hardware.AnalogInput draggedAnalog;

    // Telemetría en vivo desde simavr (PWM real + analizador lógico)
    private volatile Map<Hardware.PwmChannel, Integer> livePwmDuties = Map.of();
    private volatile SimEngineController.HighlightState highlight;
    private static final int MAX_TRACE_SAMPLES = 80;
    private final Map<String, Deque<Boolean>> logicTraces = new LinkedHashMap<>();

    // Sesión simavr y hilo de fondo (nunca bloquea el EDT)
    private final Toolchain toolchain = Toolchain.detect();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "simavr-ui-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile SimavrSession session;
    private volatile SimavrSession.State lastState;
    private volatile boolean running;
    private volatile boolean desiredRunning;
    private volatile boolean busy;
    private volatile long generation;
    private final javax.swing.Timer tickTimer;

    // Controles UI
    private final Diagram diagram = new Diagram();
    private final JScrollPane scroll = new JScrollPane(diagram);
    private final LogicTracePanel tracePanel = new LogicTracePanel();
    private final JToggleButton traceToggle = new JToggleButton("Analizador lógico");
    private final JButton btnStart = new JButton("Iniciar");
    private final JButton btnPause = new JButton("Pausar");
    private final JButton btnStep = new JButton("+10 ms");
    private final JButton btnReset = new JButton("Reiniciar");
    private final JComboBox<Backend> backendBox = new JComboBox<>(Backend.values());
    private final JLabel statusLabel = new JLabel("Listo para simular");
    private final JLabel zoomLabel = new JLabel("100%");
    private final JPanel uartBar = new JPanel(new BorderLayout(6, 0));
    private final JTextField uartInput = new JTextField(14);
    private final JButton uartSendBtn = new JButton("Enviar RX");
    private final JTextArea uartLog = new JTextArea(3, 30);

    public CircuitCanvasPanel(ProgramSpec initialSpec) {
        super(new BorderLayout(0, 6));
        setName("circuitCanvasPanel");
        setBackground(StudioTheme.CANVAS_BG);
        setBorder(new EmptyBorder(8, 8, 8, 8));

        // Barra de controles de simulación
        JPanel top = new JPanel(new BorderLayout(0, 5));
        top.setOpaque(false);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        controls.setOpaque(false);
        StudioTheme.StudioIcon.apply(btnStart, StudioTheme.StudioIcon.Kind.PLAY);
        StudioTheme.StudioIcon.apply(btnPause, StudioTheme.StudioIcon.Kind.PAUSE);
        StudioTheme.StudioIcon.apply(btnStep, StudioTheme.StudioIcon.Kind.STEP);
        StudioTheme.StudioIcon.apply(btnReset, StudioTheme.StudioIcon.Kind.RESET);
        StudioTheme.styleCopperPrimary(btnStart);
        StudioTheme.styleGhost(btnPause);
        StudioTheme.styleGhost(btnStep);
        StudioTheme.styleGhost(btnReset);

        btnStart.addActionListener(e -> startSimulation());
        btnPause.addActionListener(e -> pauseSimulation());
        btnStep.addActionListener(e -> stepSimulation());
        btnReset.addActionListener(e -> resetSimulation());

        backendBox.setToolTipText("Elige qué binario .hex ejecutar en simavr (C99 o AVRASM2)");
        backendBox.addActionListener(e -> {
            Backend chosen = (Backend) backendBox.getSelectedItem();
            if (chosen != null && (chosen != backend || customFirmware != null)) {
                backend = chosen;
                customFirmware = null;
                resetSimulation();
            }
        });

        StudioTheme.styleGhost(traceToggle);
        traceToggle.setToolTipText("Mostrar/ocultar trazas digitales en vivo estilo Logic Analyser");
        traceToggle.addActionListener(e -> {
            boolean on = traceToggle.isSelected();
            tracePanel.setVisible(on);
            traceToggle.setBackground(on ? StudioTheme.Role.INPUT.pillBg : StudioTheme.SURFACE_BG);
            traceToggle.setForeground(on ? StudioTheme.Role.INPUT.pillFg : StudioTheme.TEXT_SECONDARY);
            revalidate();
            repaint();
        });

        JButton zoomOut = new JButton("−");
        JButton zoomIn = new JButton("+");
        StudioTheme.styleGhost(zoomOut);
        StudioTheme.styleGhost(zoomIn);
        zoomOut.addActionListener(e -> adjustZoom(-0.12));
        zoomIn.addActionListener(e -> adjustZoom(0.12));

        controls.add(btnStart);
        controls.add(btnPause);
        controls.add(btnStep);
        controls.add(btnReset);
        controls.add(new JLabel("Motor:"));
        controls.add(backendBox);
        controls.add(traceToggle);
        controls.add(zoomOut);
        controls.add(zoomLabel);
        controls.add(zoomIn);

        statusLabel.setForeground(StudioTheme.TEXT_MUTED);
        statusLabel.setBorder(new EmptyBorder(0, 4, 0, 0));

        top.add(controls, BorderLayout.NORTH);
        top.add(statusLabel, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        scroll.setBorder(BorderFactory.createLineBorder(StudioTheme.BORDER_COLOR));
        scroll.getViewport().setBackground(SHEET_BG);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                if (autoFit) updateScale();
            }
        });
        add(scroll, BorderLayout.CENTER);

        // Panel inferior: Analizador lógico (plegable) + Consola UART estilo Virtual Terminal
        JPanel bottomStack = new JPanel();
        bottomStack.setOpaque(false);
        bottomStack.setLayout(new BoxLayout(bottomStack, BoxLayout.Y_AXIS));

        tracePanel.setVisible(false);
        bottomStack.add(tracePanel);

        uartBar.setOpaque(false);
        uartBar.setBorder(new EmptyBorder(4, 0, 0, 0));
        uartLog.setEditable(false);
        uartLog.setFont(StudioTheme.codeFont(12f));
        uartLog.setBackground(StudioTheme.CODE_BG);
        uartLog.setForeground(new Color(134, 239, 172));
        uartLog.setBorder(new EmptyBorder(6, 8, 6, 8));
        StudioTheme.StudioIcon.apply(uartSendBtn, StudioTheme.StudioIcon.Kind.SEND);
        StudioTheme.styleGhost(uartSendBtn);
        uartSendBtn.addActionListener(e -> sendUartInput());
        uartInput.addActionListener(e -> sendUartInput());
        JPanel uartInputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        uartInputRow.setOpaque(false);
        uartInputRow.add(StudioTheme.pill("VIRTUAL TERMINAL · UART0", StudioTheme.Role.ALT));
        uartInputRow.add(uartInput);
        uartInputRow.add(uartSendBtn);
        uartBar.add(uartInputRow, BorderLayout.NORTH);
        uartBar.add(new JScrollPane(uartLog), BorderLayout.CENTER);
        bottomStack.add(uartBar);

        add(bottomStack, BorderLayout.SOUTH);

        tickTimer = new javax.swing.Timer(40, e -> scheduleFrameAdvance());
        setProgram(initialSpec);
    }

    public void setProgram(ProgramSpec next) {
        Objects.requireNonNull(next, "next");
        resetSimulation();
        this.spec = next;
        pressedButtons.clear();
        pinnedButtons.clear();
        analogMv.clear();
        logicTraces.clear();
        for (Hardware.AnalogInput a : spec.componentsOf(Hardware.AnalogInput.class)) {
            analogMv.put(a.name(), 0);
        }
        uartLog.setText("");
        uartBar.setVisible(!spec.componentsOf(Hardware.UartPort.class).isEmpty());
        layoutRows();
        lastFitWidth = -1;
        updateScale();
        refreshControls();
    }

    public Backend selectedBackend() { return backend; }
    public void setSelectedBackend(Backend b) { backendBox.setSelectedItem(b); }
    public void setLogicTraceVisible(boolean visible) {
        traceToggle.setSelected(visible);
        tracePanel.setVisible(visible);
        traceToggle.setBackground(visible ? StudioTheme.Role.INPUT.pillBg : StudioTheme.SURFACE_BG);
        traceToggle.setForeground(visible ? StudioTheme.Role.INPUT.pillFg : StudioTheme.TEXT_SECONDARY);
        revalidate();
        repaint();
    }
    public AsmEmitter.InstructionProfile asmProfile() { return asmProfile; }
    public void setAsmProfile(AsmEmitter.InstructionProfile profile) {
        if (profile != null && profile != this.asmProfile) {
            this.asmProfile = profile;
            if (backend == Backend.ASM) resetSimulation();
        }
    }
    public AsmEmitter.StorageMode asmStorageMode() { return asmStorageMode; }
    public void setAsmStorageMode(AsmEmitter.StorageMode mode) {
        if (mode != null && mode != this.asmStorageMode) {
            this.asmStorageMode = mode;
            if (backend == Backend.ASM) resetSimulation();
        }
    }

    public void setHighlight(SimEngineController.HighlightState highlight) {
        this.highlight = highlight;
        diagram.repaint();
    }

    public void shutdown() {
        tickTimer.stop();
        generation++;
        running = false;
        SimavrSession s = session;
        session = null;
        if (s != null) s.close();
        worker.shutdownNow();
    }

    private void layoutRows() {
        rows.clear();
        int y = 78;
        for (Hardware.Component c : spec.components()) {
            int h = switch (c) {
                case Hardware.BcdDisplay ignored -> 238;
                case Hardware.Button ignored -> 168;
                case Hardware.Led ignored -> 132;
                case Hardware.AnalogInput ignored -> 152;
                case Hardware.PwmOutput ignored -> 138;
                case Hardware.UartPort ignored -> 118;
            };
            rows.add(new ComponentRow(c, y, h));
            y += h + 12;
        }
        canvasHeight = Math.max(y + 30, 628);
    }

    private void adjustZoom(double delta) {
        autoFit = false;
        scale = Math.clamp(scale + delta, 0.40, 1.6);
        updateScale();
    }

    @Override
    public void doLayout() {
        super.doLayout();
        if (autoFit) {
            int w = scroll.getViewport().getWidth();
            if (w <= 0) w = scroll.getWidth() - 4;
            if (w > 120 && w != lastFitWidth) {
                lastFitWidth = w;
                double avail = Math.max(320.0, w - 20.0);
                scale = Math.clamp(avail / BASE_WIDTH, 0.45, 1.15);
                zoomLabel.setText(Math.round(scale * 100) + "%");
                diagram.setPreferredSize(new Dimension((int) Math.ceil(BASE_WIDTH * scale), (int) Math.ceil(canvasHeight * scale)));
                scroll.doLayout();
            }
        }
    }

    private void updateScale() {
        if (autoFit) {
            int w = scroll.getViewport().getWidth();
            if (w <= 0) w = scroll.getWidth() - 4;
            if (w > 120) {
                lastFitWidth = w;
                double avail = Math.max(320.0, w - 20.0);
                scale = Math.clamp(avail / BASE_WIDTH, 0.45, 1.15);
            }
        }
        zoomLabel.setText(Math.round(scale * 100) + "%");
        diagram.setPreferredSize(new Dimension((int) Math.ceil(BASE_WIDTH * scale), (int) Math.ceil(canvasHeight * scale)));
        diagram.revalidate();
        diagram.repaint();
    }

    // ==================== Control del motor simavr ====================

    public void startSimulation() {
        if (busy) {
            desiredRunning = true;
            refreshControls();
            return;
        }
        if (session != null) {
            desiredRunning = true;
            running = true;
            tickTimer.start();
            refreshControls();
            return;
        }
        launchSimulation(2, true, "Compilando " + backend + " y arrancando simavr…");
    }

    public void setCustomFirmware(SimEngineController.CustomFirmware firmware) {
        this.customFirmware = firmware;
        if (firmware != null && firmware.backend() != null) {
            this.backend = firmware.backend() == SimEngineController.Backend.ASM ? Backend.ASM : Backend.C99;
            backendBox.setSelectedItem(this.backend);
        }
        resetSimulation();
        if (firmware != null) {
            statusLabel.setText("Listo (" + firmware.label() + " · " + firmware.flashBytes() + " B Flash)");
        }
    }

    private void launchSimulation(int initialMs, boolean runAfterLaunch, String launchStatus) {
        final ProgramSpec snap = spec;
        final Backend targetBackend = backend;
        final SimEngineController.CustomFirmware targetCustom = customFirmware;
        final String targetLabel = targetCustom != null ? targetCustom.label() : targetBackend.toString();
        final AsmEmitter.InstructionProfile targetAsmProfile = asmProfile;
        final AsmEmitter.StorageMode targetAsmMode = asmStorageMode;
        final long gen = ++generation;
        busy = true;
        running = false;
        desiredRunning = runAfterLaunch;
        statusLabel.setText(targetCustom != null ? "Cargando " + targetLabel + " en simavr…" : launchStatus);
        refreshControls();

        worker.submit(() -> {
            try {
                Path hexToLoad;
                if (targetCustom != null) {
                    hexToLoad = targetCustom.hexFile();
                } else {
                    Path dir = Files.createTempDirectory("studio-v2-sim-");
                    Toolchain.BuildArtifact art;
                    if (targetBackend == Backend.C99) {
                        art = toolchain.compileC(snap.name(), new CEmitter().emit(snap), dir);
                    } else {
                        art = toolchain.assembleAsm(snap.name(), new AsmEmitter().emit(snap, targetAsmProfile, targetAsmMode), dir);
                    }
                    hexToLoad = art.hexFile();
                }
                SimavrSession opened = SimavrSession.open(hexToLoad, snap.fCpuHz(), toolchain);
                applyAllInputsTo(opened, snap);
                SimavrSession.State init = opened.advanceMs(initialMs);
                Map<Hardware.PwmChannel, Integer> pwms = readPwmsSafe(opened, snap);
                SwingUtilities.invokeLater(() -> {
                    if (gen != generation) {
                        opened.close();
                        return;
                    }
                    session = opened;
                    lastState = init;
                    livePwmDuties = pwms;
                    recordTraceSample(init);
                    busy = false;
                    running = desiredRunning;
                    uartLog.setText(init.txAscii());
                    if (running) {
                        tickTimer.start();
                        statusLabel.setText("En marcha (" + targetLabel + " @ " + snap.clockFreq().label() + ") · " + formatMs(init.cycles()));
                    } else {
                        tickTimer.stop();
                        statusLabel.setText("En pausa (" + targetLabel + " @ " + snap.clockFreq().label() + ") · " + formatMs(init.cycles()));
                    }
                    refreshControls();
                    diagram.repaint();
                    tracePanel.repaint();
                });
            } catch (Throwable ex) {
                SwingUtilities.invokeLater(() -> {
                    if (gen != generation) return;
                    busy = false;
                    running = false;
                    desiredRunning = false;
                    statusLabel.setText("Error: " + ex.getMessage());
                    refreshControls();
                });
            }
        });
    }

    public void pauseSimulation() {
        desiredRunning = false;
        running = false;
        tickTimer.stop();
        if (session != null && lastState != null) {
            statusLabel.setText("En pausa (" + backend + ") · " + formatMs(lastState.cycles()));
        } else if (busy) {
            statusLabel.setText("Pausa solicitada (quedará en pausa al terminar de compilar)…");
        }
        refreshControls();
    }

    public void stepSimulation() {
        if (busy) return;
        if (session == null) {
            launchSimulation(10, false, "Compilando " + backend + " para avanzar +10 ms…");
            return;
        }
        pauseSimulation();
        final SimavrSession s = session;
        final ProgramSpec snap = spec;
        final long gen = generation;
        busy = true;
        refreshControls();
        worker.submit(() -> {
            try {
                applyAllInputsTo(s, snap);
                SimavrSession.State next = s.advanceMs(10);
                Map<Hardware.PwmChannel, Integer> pwms = readPwmsSafe(s, snap);
                SwingUtilities.invokeLater(() -> {
                    if (gen != generation) return;
                    lastState = next;
                    livePwmDuties = pwms;
                    recordTraceSample(next);
                    busy = false;
                    uartLog.setText(next.txAscii());
                    statusLabel.setText("En pausa (" + backend + ") · " + formatMs(next.cycles()));
                    refreshControls();
                    diagram.repaint();
                    tracePanel.repaint();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    statusLabel.setText("Error: " + ex.getMessage());
                    refreshControls();
                });
            }
        });
    }

    public void resetSimulation() {
        tickTimer.stop();
        generation++;
        running = false;
        desiredRunning = false;
        busy = false;
        SimavrSession old = session;
        session = null;
        lastState = null;
        livePwmDuties = Map.of();
        logicTraces.clear();
        if (old != null) worker.submit(old::close);
        statusLabel.setText("Listo para simular (" + backend + ")");
        refreshControls();
        diagram.repaint();
        tracePanel.repaint();
    }

    private void scheduleFrameAdvance() {
        if (!running || busy || session == null) return;
        final SimavrSession s = session;
        final ProgramSpec snap = spec;
        final long gen = generation;
        busy = true;
        worker.submit(() -> {
            try {
                applyAllInputsTo(s, snap);
                SimavrSession.State st = s.advanceMs(20);
                Map<Hardware.PwmChannel, Integer> pwms = readPwmsSafe(s, snap);
                SwingUtilities.invokeLater(() -> {
                    if (gen != generation) return;
                    lastState = st;
                    livePwmDuties = pwms;
                    recordTraceSample(st);
                    busy = false;
                    if (!uartLog.getText().equals(st.txAscii())) uartLog.setText(st.txAscii());
                    if (running) statusLabel.setText("En marcha (" + backend + " @ " + snap.clockFreq().label() + ") · " + formatMs(st.cycles()));
                    diagram.repaint();
                    if (tracePanel.isVisible()) tracePanel.repaint();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (gen != generation) return;
                    busy = false;
                    running = false;
                    tickTimer.stop();
                    statusLabel.setText("Simulación detenida: " + ex.getMessage());
                    refreshControls();
                });
            }
        });
    }

    private static Map<Hardware.PwmChannel, Integer> readPwmsSafe(SimavrSession s, ProgramSpec snap) {
        if (snap.componentsOf(Hardware.PwmOutput.class).isEmpty()) return Map.of();
        try {
            return s.readPwmDuties();
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private void recordTraceSample(SimavrSession.State st) {
        if (st == null || spec == null) return;
        for (Hardware.Component c : spec.components()) {
            switch (c) {
                case Hardware.Button b -> pushTrace(b.name() + " (" + b.pin() + ")", st.pinHigh(b.pin()));
                case Hardware.Led l -> pushTrace(l.name() + " (" + l.pin() + ")", st.pinHigh(l.pin()));
                case Hardware.PwmOutput p -> pushTrace(p.name() + " (" + p.pin() + ")", st.pinHigh(p.pin()));
                case Hardware.BcdDisplay d -> pushTrace(d.name() + " [" + d.firstPin() + "]", st.readBcd(d) != 0);
                default -> {}
            }
        }
    }

    private void pushTrace(String label, boolean high) {
        Deque<Boolean> q = logicTraces.computeIfAbsent(label, k -> new ArrayDeque<>(MAX_TRACE_SAMPLES + 1));
        if (q.size() >= MAX_TRACE_SAMPLES) q.removeFirst();
        q.addLast(high);
    }

    private void applyAllInputsTo(SimavrSession s, ProgramSpec snap) throws Exception {
        for (Hardware.Button b : snap.componentsOf(Hardware.Button.class)) {
            boolean down = pressedButtons.contains(b.name()) || pinnedButtons.contains(b.name());
            s.setButton(b, down);
        }
        for (Hardware.AnalogInput a : snap.componentsOf(Hardware.AnalogInput.class)) {
            s.setAnalogMv(a.channel(), analogMv.getOrDefault(a.name(), 0));
        }
    }

    private void sendUartInput() {
        String text = uartInput.getText();
        if (text.isEmpty() || session == null) return;
        final SimavrSession s = session;
        uartInput.setText("");
        worker.submit(() -> {
            try {
                for (int i = 0; i < text.length(); i++) {
                    boolean ok = s.injectUartRx(text.charAt(i) & 0x7F);
                    if (!ok) {
                        SwingUtilities.invokeLater(() -> statusLabel.setText("UART RX ocupado o aún no habilitado; reintenta."));
                        return;
                    }
                    s.advanceMs(5);
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("Error UART RX: " + ex.getMessage()));
            }
        });
    }

    private void refreshControls() {
        btnStart.setEnabled(!running && !busy);
        btnPause.setEnabled(running || (busy && desiredRunning));
        btnStep.setEnabled(!busy);
        btnReset.setEnabled(session != null || busy);
        uartSendBtn.setEnabled(session != null && (lastState == null || lastState.uartRxReady()));
    }

    private String formatMs(long cycles) {
        double cyclesPerMs = spec != null ? (spec.fCpuHz() / 1000.0) : 1000.0;
        return String.format(Locale.ROOT, "%.1f ms", cycles / cyclesPerMs);
    }

    private Color pinLogicColor(Hardware.Pin pin) {
        for (Hardware.AnalogInput a : spec.componentsOf(Hardware.AnalogInput.class)) {
            if (a.pin().equals(pin)) return LOGIC_ANALOG;
        }
        SimavrSession.State st = lastState;
        if (st == null) return LOGIC_IDLE;
        return st.pinHigh(pin) ? LOGIC_HIGH : LOGIC_LOW;
    }

    private Boolean pinLogicLevel(Hardware.Pin pin) {
        SimavrSession.State st = lastState;
        return st == null ? null : st.pinHigh(pin);
    }

    // ==================== Lienzo Esquemático Interactivo estilo Proteus ====================

    private final class Diagram extends JPanel {
        Diagram() {
            setBackground(SHEET_BG);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return;
                    double x = e.getX() / scale, y = e.getY() / scale;
                    for (ComponentRow r : rows) {
                        if (r.component() instanceof Hardware.Button b) {
                            // Botón integrado "Fijar / Soltar" a la derecha
                            if (x >= 728 && x <= 822 && y >= r.y() + 54 && y <= r.y() + 86) {
                                if (!pinnedButtons.remove(b.name())) pinnedButtons.add(b.name());
                                repaint();
                                return;
                            }
                            // Pulsador táctil en el esquema (misma coordenada para GND y VCC)
                            if (x >= 455 && x <= 575 && y >= r.y() + 42 && y <= r.y() + 106) {
                                if (e.isShiftDown()) {
                                    if (!pinnedButtons.remove(b.name())) pinnedButtons.add(b.name());
                                } else {
                                    mouseButton = b;
                                    pressedButtons.add(b.name());
                                }
                                repaint();
                                return;
                            }
                        }
                        if (r.component() instanceof Hardware.AnalogInput a) {
                            // Botones finos [-] y [+] estilo POT-HG de Proteus
                            if (x >= 555 && x <= 583 && y >= r.y() + 104 && y <= r.y() + 128) {
                                stepAnalog(a, -250);
                                return;
                            }
                            if (x >= 775 && x <= 803 && y >= r.y() + 104 && y <= r.y() + 128) {
                                stepAnalog(a, 250);
                                return;
                            }
                            // Deslizador horizontal 0..5 V
                            if (x >= 550 && x <= 810 && y >= r.y() + 62 && y <= r.y() + 98) {
                                draggedAnalog = a;
                                updateAnalogFromMouse(a, x);
                                return;
                            }
                        }
                    }
                }

                @Override public void mouseReleased(MouseEvent e) {
                    draggedAnalog = null;
                    if (mouseButton != null) {
                        pressedButtons.remove(mouseButton.name());
                        mouseButton = null;
                        repaint();
                    }
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    if (draggedAnalog != null) {
                        updateAnalogFromMouse(draggedAnalog, e.getX() / scale);
                    }
                }
            });
        }

        private void stepAnalog(Hardware.AnalogInput a, int deltaMv) {
            int next = Math.clamp(analogMv.getOrDefault(a.name(), 0) + deltaMv, 0, 5000);
            analogMv.put(a.name(), next);
            repaint();
        }

        private void updateAnalogFromMouse(Hardware.AnalogInput a, double rawX) {
            int mv = (int) Math.round(Math.clamp((rawX - 565.0) / 225.0, 0.0, 1.0) * 5000.0);
            analogMv.put(a.name(), mv);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.scale(scale, scale);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                paintSchematicSheet(g);
            } finally {
                g.dispose();
            }
        }
    }

    private void paintSchematicSheet(Graphics2D g) {
        // 1. Fondo cuadriculado fino estilo hoja ISIS
        g.setColor(SHEET_BG);
        g.fillRect(0, 0, BASE_WIDTH, canvasHeight);
        g.setColor(GRID_DOT);
        for (int gx = 16; gx < BASE_WIDTH; gx += 16) {
            for (int gy = 16; gy < canvasHeight; gy += 16) {
                g.fillRect(gx, gy, 1, 1);
            }
        }

        // 2. Cajetín superior técnico
        g.setColor(StudioTheme.SURFACE_BG);
        g.fillRoundRect(16, 12, BASE_WIDTH - 32, 52, 6, 6);
        g.setColor(StudioTheme.BORDER_COLOR);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(16, 12, BASE_WIDTH - 32, 52, 6, 6);

        drawText(g, "Esquemático interactivo · ATmega328P @ " + spec.clockFreq().label(),
                30, 33, 13.5f, true, StudioTheme.TEXT_PRIMARY);
        drawText(g, "Proyecto: " + NaturalLanguage.prettyProjectName(spec.name())
                + "  ·  Clic sostenido o [Fijar] en botones  ·  Arrastra o usa −/+ en potenciómetros",
                30, 51, 10.5f, false, StudioTheme.TEXT_MUTED);

        // Leyenda de sondas lógicas a la derecha del cajetín
        int lx = 578;
        paintLogicLegendItem(g, lx, 28, LOGIC_HIGH, "5V ALTO");
        paintLogicLegendItem(g, lx + 74, 28, LOGIC_LOW, "0V BAJO");
        paintLogicLegendItem(g, lx + 148, 28, LOGIC_ANALOG, "ADC");
        paintLogicLegendItem(g, lx + 196, 28, LOGIC_IDLE, "Reposo");

        // 3. ATmega328P DIP-28 (izquierda)
        paintDip28Proteus(g);

        // 4. Bloques de componentes esquemáticos (derecha)
        if (rows.isEmpty()) {
            g.setColor(StudioTheme.SURFACE_BG);
            g.fillRoundRect(272, 140, 560, 126, 6, 6);
            g.setColor(StudioTheme.BORDER_COLOR);
            g.drawRoundRect(272, 140, 560, 126, 6, 6);
            drawText(g, "Hoja esquemática lista", 296, 182, 15, true, StudioTheme.TEXT_PRIMARY);
            drawText(g, "Agrega botones (GND o +5V VCC), LEDs, displays CD4511, potenciómetros ADC,", 296, 208, 12, false, StudioTheme.TEXT_MUTED);
            drawText(g, "salidas Fast PWM o UART0 en el panel izquierdo para ver su conexión eléctrica real.", 296, 228, 12, false, StudioTheme.TEXT_MUTED);
            return;
        }

        for (ComponentRow row : rows) {
            paintComponentCard(g, row);
        }
    }

    private static void paintLogicLegendItem(Graphics2D g, int x, int y, Color c, String label) {
        g.setColor(c);
        g.fillRect(x, y - 7, 8, 8);
        g.setColor(c.darker());
        g.drawRect(x, y - 7, 8, 8);
        drawText(g, label, x + 12, y, 10f, true, StudioTheme.TEXT_SECONDARY);
    }

    private void paintDip28Proteus(Graphics2D g) {
        Map<String, Hardware.Pin> usedByLabel = new LinkedHashMap<>();
        for (Hardware.Component c : spec.components()) {
            for (Hardware.Pin p : c.occupiedPins()) usedByLabel.put(p.label(), p);
        }

        int bx = 46, by = 124, bw = 174, bh = 354;

        // Pull-up de RESET (pin 1) limpiamente encima de la esquina superior izquierda del integrado
        int rstY = by + 20;
        paintVcc(g, 20, by - 36);
        g.setColor(WIRE_POWER);
        g.setStroke(new BasicStroke(1.6f));
        g.drawLine(20, by - 36, 20, by - 30);
        paintResistorV(g, 20, by - 30, 24, "10k");
        g.setColor(LOGIC_HIGH);
        g.drawLine(20, by - 6, 20, rstY);
        g.drawLine(20, rstY, bx, rstY);
        paintLogicProbe(g, bx - 12, rstY, LOGIC_HIGH);

        // Cuerpo crema estilo Proteus ISIS
        g.setColor(IC_BODY);
        g.fillRoundRect(bx, by, bw, bh, 6, 6);
        g.setColor(IC_BORDER);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(bx, by, bw, bh, 6, 6);

        // Muesca de orientación pin 1
        g.setColor(SHEET_BG);
        g.fillArc(bx + bw / 2 - 14, by - 10, 28, 20, 180, 180);
        g.setColor(IC_BORDER);
        g.drawArc(bx + bw / 2 - 14, by - 10, 28, 20, 180, 180);

        // Etiqueta central U1 · ATmega328P
        drawText(g, "U1", bx + bw / 2 - 8, by + 22, 11, true, IC_BORDER);
        g.rotate(-Math.PI / 2, bx + bw / 2.0, by + bh / 2.0 + 10);
        drawText(g, "ATMEGA328P", bx + bw / 2 - 38, by + bh / 2 + 14, 11.5f, true, IC_BORDER);
        g.rotate(Math.PI / 2, bx + bw / 2.0, by + bh / 2.0 + 10);

        // Pines 1..14 (izquierda) y 28..15 (derecha)
        for (Pad pad : PADS) {
            boolean leftSide = pad.number() <= 14;
            int rowIdx = leftSide ? (pad.number() - 1) : (28 - pad.number());
            int py = by + 20 + rowIdx * 24;
            String pinKey = pad.label().split("/")[0];
            Hardware.Pin activePin = usedByLabel.get(pinKey);
            boolean isPower = pinKey.equals("VCC") || pinKey.equals("AVCC");
            boolean isGnd = pinKey.equals("GND");

            int leadX1 = leftSide ? bx - 12 : bx + bw;
            int leadX2 = leftSide ? bx : bx + bw + 12;

            boolean isHighlightedPin = highlight != null && highlight.pins().contains(pinKey);
            Color leadColor = isHighlightedPin ? new Color(245, 158, 11)
                    : activePin != null ? pinLogicColor(activePin)
                    : isPower ? WIRE_POWER
                    : isGnd ? WIRE_GND
                    : IC_BORDER;
            if (isHighlightedPin) {
                g.setColor(new Color(245, 158, 11, 90));
                g.setStroke(new BasicStroke(6f));
                g.drawLine(leadX1, py, leadX2, py);
            }
            g.setColor(leadColor);
            g.setStroke(new BasicStroke((activePin != null || isHighlightedPin) ? 2.4f : 1.4f));
            g.drawLine(leadX1, py, leadX2, py);

            // Sonda lógica cuadrada estilo Proteus en la punta del terminal (sin tapar el número de pata)
            int probeX = leftSide ? leadX1 : leadX2;
            if (activePin != null) {
                paintLogicProbe(g, probeX, py, pinLogicColor(activePin));
            } else if (isPower) {
                paintLogicProbe(g, probeX, py, LOGIC_HIGH);
            } else if (isGnd) {
                paintLogicProbe(g, probeX, py, LOGIC_LOW);
            }

            // Número de pata fuera (con margen limpio respecto a la sonda) y nombre de señal dentro
            g.setFont(StudioTheme.codeFont(8.5f).deriveFont(activePin != null ? Font.BOLD : Font.PLAIN));
            g.setColor(activePin != null ? StudioTheme.TEXT_PRIMARY : StudioTheme.TEXT_MUTED);
            String numStr = String.valueOf(pad.number());
            int numX = leftSide ? (leadX1 - 7 - g.getFontMetrics().stringWidth(numStr)) : (leadX2 + 8);
            if (!leftSide || pad.number() != 1) {
                g.drawString(numStr, numX, py + 3);
            }

            g.setColor(activePin != null ? IC_LABEL : StudioTheme.TEXT_MUTED);
            int txtX = leftSide ? bx + 6 : (bx + bw - 6 - g.getFontMetrics().stringWidth(pad.label()));
            g.drawString(pad.label(), txtX, py + 3);
        }

        // Bloque inferior de alimentación y reloj (1 / 8 MHz RC interno o 16 MHz Cristal + 22 pF)
        int infoY = by + bh + 18;
        g.setColor(StudioTheme.SURFACE_BG);
        g.fillRoundRect(16, infoY, 232, 118, 6, 6);
        g.setColor(StudioTheme.BORDER_COLOR);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(16, infoY, 232, 118, 6, 6);

        drawText(g, "Alimentación y reloj", 28, infoY + 18, 10.5f, true, StudioTheme.TEXT_MUTED);
        paintVcc(g, 48, infoY + 42);
        drawText(g, "VCC (7), AVCC (20)", 68, infoY + 46, 10, false, StudioTheme.TEXT_SECONDARY);
        paintGnd(g, 48, infoY + 62);
        drawText(g, "GND (8, 22)", 68, infoY + 70, 10, false, StudioTheme.TEXT_SECONDARY);

        if (spec.clockFreq() == Hardware.ClockFreq.MHZ_16) {
            drawText(g, "XTAL 16 MHz (PB6/PB7) + 2×22 pF", 28, infoY + 104, 10, true, StudioTheme.ACCENT_BLUE);
        } else {
            String rc = spec.clockFreq() == Hardware.ClockFreq.MHZ_1
                    ? "RC Interno 1 MHz (CKDIV8 = 0)"
                    : "RC Interno 8 MHz (sin cristal)";
            drawText(g, rc, 28, infoY + 104, 10, true, StudioTheme.ACCENT_EMERALD);
        }
    }

    private void paintComponentCard(Graphics2D g, ComponentRow row) {
        Hardware.Component c = row.component();
        int x = 266, y = row.y(), w = 576, h = row.height();

        Color accent = switch (c) {
            case Hardware.Button b -> b.connection() == Hardware.Connection.VCC ? StudioTheme.ACCENT_AMBER : StudioTheme.ACCENT_BLUE;
            case Hardware.Led l -> l.connection() == Hardware.Connection.VCC ? StudioTheme.ACCENT_AMBER : StudioTheme.ACCENT_EMERALD;
            case Hardware.PwmOutput p -> StudioTheme.ACCENT_EMERALD;
            case Hardware.BcdDisplay d -> StudioTheme.ACCENT_VIOLET;
            case Hardware.AnalogInput a -> StudioTheme.ACCENT_VIOLET;
            case Hardware.UartPort u -> StudioTheme.ACCENT_AMBER;
        };

        boolean isHighlightedComp = highlight != null && highlight.components().contains(c.name());
        g.setColor(isHighlightedComp ? StudioTheme.ACCENT_COPPER_SOFT : StudioTheme.SURFACE_BG);
        g.fillRoundRect(x, y, w, h, 6, 6);
        g.setColor(isHighlightedComp ? StudioTheme.ACCENT_COPPER_GLOW : accent);
        g.fillRoundRect(x, y, 3, h, 2, 2);
        g.setColor(isHighlightedComp ? StudioTheme.ACCENT_COPPER_GLOW : StudioTheme.BORDER_COLOR);
        g.setStroke(new BasicStroke(isHighlightedComp ? 1.8f : 1f));
        g.drawRoundRect(x, y, w, h, 6, 6);

        drawText(g, c.name(), x + 16, y + 22, 13.5f, true, StudioTheme.TEXT_PRIMARY);
        int nameW = g.getFontMetrics(StudioTheme.uiFont(Font.BOLD, 13.5f)).stringWidth(c.name());
        int badgeX = Math.max(x + 115, x + 28 + nameW);

        switch (c) {
            case Hardware.Button b -> paintButtonProteus(g, b, x, y, w, badgeX);
            case Hardware.Led l -> paintLedProteus(g, l, x, y, w, badgeX);
            case Hardware.BcdDisplay d -> paintBcdProteus(g, d, x, y, w, badgeX);
            case Hardware.AnalogInput a -> paintAnalogProteus(g, a, x, y, w, badgeX);
            case Hardware.PwmOutput p -> paintPwmProteus(g, p, x, y, w, badgeX);
            case Hardware.UartPort u -> paintUartProteus(g, u, x, y, w, badgeX);
        }
    }

    private void paintButtonProteus(Graphics2D g, Hardware.Button b, int cx, int cy, int cw, int badgeX) {
        boolean down = pressedButtons.contains(b.name()) || pinnedButtons.contains(b.name());
        boolean pinned = pinnedButtons.contains(b.name());
        boolean isVcc = b.connection() == Hardware.Connection.VCC;
        Color pinCol = pinLogicColor(b.pin());

        // Pill explícito de polaridad eléctrica
        paintBadge(g, badgeX, cy + 9,
                isVcc ? "ACTIVO ALTO · BOTÓN A +5V (PULL-DOWN 10k)" : "ACTIVO BAJO · BOTÓN A GND (PULL-UP INTERNO)",
                isVcc ? StudioTheme.Role.ALT : StudioTheme.Role.INPUT);

        // Botón interactivo integrado "Fijar / Soltar"
        int fx = cx + 462, fy = cy + 54, fw = 94, fh = 30;
        g.setColor(pinned ? StudioTheme.Role.INPUT.pillBg : StudioTheme.SUBTLE_BG);
        g.fillRoundRect(fx, fy, fw, fh, 8, 8);
        g.setColor(pinned ? StudioTheme.ACCENT_BLUE : StudioTheme.BORDER_STRONG);
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(fx, fy, fw, fh, 8, 8);
        String btnLbl = pinned ? "Soltar botón" : "Fijar pulso";
        g.setFont(StudioTheme.uiFont(Font.BOLD, 11f));
        int btnTw = g.getFontMetrics().stringWidth(btnLbl);
        drawText(g, btnLbl, fx + (fw - btnTw) / 2, fy + 19, 11, true,
                pinned ? StudioTheme.Role.INPUT.pillFg : StudioTheme.TEXT_SECONDARY);

        // Lectura eléctrica en vivo debajo del botón Fijar
        Boolean lvl = pinLogicLevel(b.pin());
        String voltText = lvl == null ? "Sin iniciar"
                : (lvl ? "Pin: 5V (ALTO)" : "Pin: 0V (BAJO)");
        drawText(g, voltText, fx, fy + 48, 10.5f, true, pinCol);
        drawText(g, down ? "Estado: PRESIONADO" : "Estado: suelto", fx, fy + 64, 10.5f, false,
                down ? StudioTheme.ACCENT_BLUE : StudioTheme.TEXT_MUTED);

        int wy = cy + 74;
        int swX = cx + 204;
        // Net label del pin MCU alineado a la izquierda en ambas polaridades
        paintNetLabel(g, cx + 16, wy, b.pin() + " · DIP-" + b.pin().dip28Pin(), pinCol);

        if (!isVcc) {
            // === TOPOLOGÍA GND (PULL-UP INTERNO): MCU Pin ---[SW]--- GND ===
            g.setColor(pinCol);
            g.setStroke(new BasicStroke(2.2f));
            g.drawLine(cx + 112, wy, swX, wy);
            drawText(g, "PULL-UP INT.", cx + 120, wy - 8, 9.5f, true, StudioTheme.TEXT_MUTED);

            // Símbolo pulsador táctil SPST
            paintPushSwitch(g, swX, wy, down, false);

            // Cable derecho bajando directo al símbolo GND (3 barras)
            g.setColor(WIRE_GND);
            g.setStroke(new BasicStroke(2.2f));
            g.drawLine(swX + 90, wy, cx + 368, wy);
            g.drawLine(cx + 368, wy, cx + 368, wy + 22);
            paintGnd(g, cx + 368, wy + 22);
            drawText(g, "Al pulsar conecta el pin a GND (0 V)", cx + 16, cy + 156, 10f, false, StudioTheme.TEXT_MUTED);
        } else {
            // === TOPOLOGÍA VCC (PULL-DOWN 10k): MCU Pin ---(•)---[SW]--- +5V  y  (•)--[10k]--GND ===
            int jx = cx + 142;
            g.setColor(pinCol);
            g.setStroke(new BasicStroke(2.2f));
            g.drawLine(cx + 112, wy, swX, wy);
            paintJunction(g, jx, wy, pinCol);

            // Pull-down vertical 10 kΩ desde el nodo (•) hasta GND
            g.setColor(pinCol);
            g.drawLine(jx, wy, jx, wy + 7);
            paintResistorV(g, jx, wy + 7, 26, "10 kΩ");
            g.setColor(WIRE_GND);
            g.drawLine(jx, wy + 33, jx, wy + 40);
            paintGnd(g, jx, wy + 40);

            // Pulsador SPST en la misma posición interactiva
            paintPushSwitch(g, swX, wy, down, true);

            // Cable derecho subiendo directo al símbolo de fuente +5V
            g.setColor(WIRE_POWER);
            g.setStroke(new BasicStroke(2.2f));
            g.drawLine(swX + 90, wy, cx + 368, wy);
            g.drawLine(cx + 368, wy, cx + 368, wy - 18);
            paintVcc(g, cx + 368, wy - 18);

            drawText(g, "Al pulsar aplica +5 V al pin; suelto cae a 0 V por R 10 kΩ", cx + 16, cy + 156, 10f, false, StudioTheme.TEXT_MUTED);
        }
    }

    private static void paintPushSwitch(Graphics2D g, int x, int wy, boolean down, boolean vccStyle) {
        // Caja interactiva sutil alrededor del pulsador
        g.setColor(down ? StudioTheme.ACCENT_TEAL_SOFT : StudioTheme.SUBTLE_BG);
        g.fillRoundRect(x - 10, wy - 34, 110, 56, 6, 6);
        g.setColor(down ? StudioTheme.ACCENT_TEAL : StudioTheme.BORDER_STRONG);
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(x - 10, wy - 34, 110, 56, 6, 6);

        // Terminales metálicos
        g.setColor(StudioTheme.TEXT_PRIMARY);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(x, wy, x + 16, wy);
        g.drawLine(x + 74, wy, x + 90, wy);
        g.setColor(StudioTheme.SURFACE_BG);
        g.fillOval(x + 14, wy - 4, 8, 8);
        g.fillOval(x + 68, wy - 4, 8, 8);
        g.setColor(vccStyle ? WIRE_POWER : StudioTheme.ACCENT_BLUE);
        g.drawOval(x + 14, wy - 4, 8, 8);
        g.drawOval(x + 68, wy - 4, 8, 8);

        // Barra puente + vástago del pulsador
        g.setColor(StudioTheme.TEXT_PRIMARY);
        int barY = down ? wy - 4 : wy - 16;
        g.drawLine(x + 16, barY, x + 74, barY);
        g.drawLine(x + 45, barY - 12, x + 45, barY);
        g.drawLine(x + 35, barY - 12, x + 55, barY - 12);

        drawText(g, down ? "CERRADO" : "Pulsar", x + 22, wy + 18, 9.5f, true,
                down ? StudioTheme.ACCENT_BLUE : StudioTheme.TEXT_MUTED);
    }

    private void paintLedProteus(Graphics2D g, Hardware.Led l, int cx, int cy, int cw, int badgeX) {
        boolean on = lastState != null ? lastState.isLedOn(l) : l.initialOn();
        boolean isVcc = l.connection() == Hardware.Connection.VCC;
        Color pinCol = pinLogicColor(l.pin());

        paintBadge(g, badgeX, cy + 9,
                isVcc ? "ACTIVO BAJO · CÁTODO AL PIN (+5V → 330 Ω → LED)" : "ACTIVO ALTO · ÁNODO AL PIN (LED → 330 Ω → GND)",
                isVcc ? StudioTheme.Role.ALT : StudioTheme.Role.OUTPUT);

        int wy = cy + 78;
        paintNetLabel(g, cx + 16, wy, l.pin() + " · DIP-" + l.pin().dip28Pin(), pinCol);

        g.setColor(pinCol);
        g.setStroke(new BasicStroke(2.2f));
        g.drawLine(cx + 116, wy, cx + 184, wy);

        if (!isVcc) {
            // === LED A GND: Pin ---[330 Ω]--- ▷| --- GND ===
            paintResistorH(g, cx + 184, wy, 68, "330 Ω");
            g.setColor(pinCol);
            g.drawLine(cx + 252, wy, cx + 300, wy);
            paintLedDiode(g, cx + 300, wy, true, on);
            g.setColor(WIRE_GND);
            g.setStroke(new BasicStroke(2.2f));
            g.drawLine(cx + 334, wy, cx + 412, wy);
            g.drawLine(cx + 412, wy, cx + 412, wy + 16);
            paintGnd(g, cx + 412, wy + 16);
        } else {
            // === LED A +5V (ACTIVO BAJO): Pin --- |◁ ---[330 Ω]--- +5V ===
            paintLedDiode(g, cx + 184, wy, false, on);
            g.setColor(WIRE_POWER);
            g.setStroke(new BasicStroke(2.2f));
            g.drawLine(cx + 218, wy, cx + 276, wy);
            paintResistorH(g, cx + 276, wy, 68, "330 Ω");
            g.setColor(WIRE_POWER);
            g.drawLine(cx + 344, wy, cx + 412, wy);
            g.drawLine(cx + 412, wy, cx + 412, wy - 20);
            paintVcc(g, cx + 412, wy - 20);
        }

        // Estado luminoso a la derecha
        paintBadge(g, cx + 462, cy + 64,
                on ? "ENCENDIDO" : "APAGADO",
                on ? StudioTheme.Role.OUTPUT : StudioTheme.Role.STATE);
        drawText(g, isVcc ? "Conduce cuando el pin baja a 0 V" : "Conduce cuando el pin sube a 5 V",
                cx + 16, cy + 120, 10f, false, StudioTheme.TEXT_MUTED);
    }

    private static void paintLedDiode(Graphics2D g, int x, int wy, boolean pointsRight, boolean lit) {
        if (lit) {
            g.setColor(new Color(34, 197, 94, 55));
            g.fillOval(x - 14, wy - 28, 62, 56);
        }
        Path2D tri = new Path2D.Double();
        if (pointsRight) {
            tri.moveTo(x, wy - 13);
            tri.lineTo(x + 30, wy);
            tri.lineTo(x, wy + 13);
            tri.closePath();
        } else {
            tri.moveTo(x + 34, wy - 13);
            tri.lineTo(x + 4, wy);
            tri.lineTo(x + 34, wy + 13);
            tri.closePath();
        }
        g.setColor(lit ? new Color(34, 197, 94) : new Color(203, 213, 225));
        g.fill(tri);
        g.setColor(lit ? new Color(21, 128, 61) : StudioTheme.TEXT_SECONDARY);
        g.setStroke(new BasicStroke(2f));
        g.draw(tri);

        // Barra de cátodo
        int barX = pointsRight ? x + 32 : x + 2;
        g.drawLine(barX, wy - 14, barX, wy + 14);

        // Flechas de emisión de luz ↗↗ estilo esquemático LED
        g.setColor(lit ? new Color(22, 163, 74) : StudioTheme.TEXT_MUTED);
        g.setStroke(new BasicStroke(1.4f));
        int ax = x + 12, ay = wy - 18;
        g.drawLine(ax, ay, ax + 9, ay - 9);
        g.drawLine(ax + 9, ay - 9, ax + 5, ay - 9);
        g.drawLine(ax + 9, ay - 9, ax + 9, ay - 5);
        g.drawLine(ax + 9, ay + 2, ax + 18, ay - 7);
        g.drawLine(ax + 18, ay - 7, ax + 14, ay - 7);
        g.drawLine(ax + 18, ay - 7, ax + 18, ay - 3);
    }

    private void paintBcdProteus(Graphics2D g, Hardware.BcdDisplay d, int cx, int cy, int cw, int badgeX) {
        int digit = lastState != null ? lastState.readBcd(d) : d.initialDigit();
        String bin = String.format("%4s", Integer.toBinaryString(digit & 0xF)).replace(' ', '0');
        paintBadge(g, badgeX, cy + 9,
                "CD4511 + 7-SEG (CÁTODO COMÚN) · BCD " + bin + " = " + (digit <= 9 ? digit : "—"),
                StudioTheme.Role.LOGIC);

        int icX = cx + 190, icY = cy + 48, icW = 112, icH = 132;
        // Integrado CD4511 estilo ISIS
        g.setColor(IC_BODY);
        g.fillRoundRect(icX, icY, icW, icH, 6, 6);
        g.setColor(IC_BORDER);
        g.setStroke(new BasicStroke(1.8f));
        g.drawRoundRect(icX, icY, icW, icH, 6, 6);
        drawText(g, "U·4511", icX + 34, icY + 20, 11.5f, true, IC_BORDER);
        drawText(g, "BCD→7S", icX + 31, icY + 35, 9.5f, false, StudioTheme.TEXT_MUTED);

        // 4 entradas BCD (A, B, C, D) con net labels y sondas rojas/azules en vivo
        for (int i = 0; i < 4; i++) {
            Hardware.Pin p = d.occupiedPins().get(i);
            int py = icY + 48 + i * 21;
            Color c = pinLogicColor(p);
            paintNetLabel(g, cx + 16, py, p + " (b" + i + ")", c);
            g.setColor(c);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(cx + 112, py, icX, py);
            paintLogicProbe(g, icX - 4, py, c);
            drawText(g, String.valueOf((char) ('A' + i)), icX + 6, py + 4, 10, true, IC_LABEL);
        }

        // Pines de control fijos de CD4511 abajo (LT=+5V, BI=+5V, LE=GND)
        drawText(g, "LT=+5V · BI=+5V · LE=GND", icX - 8, icY + icH + 16, 9.5f, false, StudioTheme.TEXT_MUTED);

        // Bus a..g -> banco de resistencias 7x330 Ω -> Display 7 segmentos
        int busY = icY + 68;
        g.setColor(StudioTheme.TEXT_SECONDARY);
        g.setStroke(new BasicStroke(2.2f));
        g.drawLine(icX + icW, busY, icX + icW + 26, busY);
        drawText(g, "a..g", icX + icW - 26, busY + 4, 9.5f, true, IC_LABEL);

        // Caja de resistencias 7x330 Ω
        int rx = icX + icW + 26;
        g.setColor(new Color(255, 251, 235));
        g.fillRoundRect(rx, busY - 18, 62, 36, 6, 6);
        g.setColor(RESISTOR_BODY);
        g.setStroke(new BasicStroke(1.4f));
        g.drawRoundRect(rx, busY - 18, 62, 36, 6, 6);
        drawText(g, "7×330 Ω", rx + 8, busY + 4, 10, true, RESISTOR_BODY);

        g.setColor(StudioTheme.TEXT_SECONDARY);
        g.setStroke(new BasicStroke(2.2f));
        g.drawLine(rx + 62, busY, rx + 94, busY);

        // Display 7 segmentos con cátodo común a GND
        int dispX = rx + 94, dispY = icY + 6;
        paintSevenSegment(g, digit, dispX, dispY);
        g.setColor(WIRE_GND);
        g.setStroke(new BasicStroke(1.8f));
        g.drawLine(dispX + 39, dispY + 118, dispX + 39, dispY + 134);
        paintGnd(g, dispX + 39, dispY + 134);
    }

    private static void paintSevenSegment(Graphics2D g, int digit, int x, int y) {
        g.setColor(new Color(24, 32, 46));
        g.fillRoundRect(x, y, 78, 118, 8, 8);
        g.setColor(StudioTheme.HEADER_BORDER);
        g.setStroke(new BasicStroke(1.4f));
        g.drawRoundRect(x, y, 78, 118, 8, 8);
        int[][] segs = {{16, 12, 44, 7}, {58, 19, 7, 35}, {58, 62, 7, 35}, {16, 97, 44, 7},
                {10, 62, 7, 35}, {10, 19, 7, 35}, {16, 54, 44, 7}};
        for (int i = 0; i < 7; i++) {
            boolean lit = digit >= 0 && digit < 10 && (SEGMENT_MASKS[digit] & (1 << i)) != 0;
            g.setColor(lit ? new Color(255, 67, 57) : new Color(56, 44, 52));
            g.fillRoundRect(x + segs[i][0], y + segs[i][1], segs[i][2], segs[i][3], 4, 4);
        }
    }

    private void paintAnalogProteus(Graphics2D g, Hardware.AnalogInput a, int cx, int cy, int cw, int badgeX) {
        int mv = analogMv.getOrDefault(a.name(), 0);
        int sample10 = (mv * 1023) / 5000;
        int pct = Math.round(mv * 100f / 5000f);

        paintBadge(g, badgeX, cy + 9,
                String.format(Locale.ROOT, "POT-HG 10 kΩ · ADC%d · %.2f V (%d%% · 10b:%d · 8b:%d)",
                        a.channel(), mv / 1000.0, pct, sample10, sample10 >> 2),
                StudioTheme.Role.LOGIC);

        // Símbolo esquemático de potenciómetro vertical entre +5V y GND
        int potX = cx + 216, potTop = cy + 56, potH = 54;
        paintVcc(g, potX, potTop - 8);
        g.setColor(WIRE_POWER);
        g.setStroke(new BasicStroke(1.8f));
        g.drawLine(potX, potTop - 8, potX, potTop);
        paintResistorV(g, potX, potTop, potH, "10 kΩ");
        g.setColor(WIRE_GND);
        g.drawLine(potX, potTop + potH, potX, potTop + potH + 8);
        paintGnd(g, potX, potTop + potH + 8);

        // Flecha cursor (wiper) proporcional al voltaje entrando hacia el pin ADC
        int wiperY = potTop + potH - (int) Math.round((mv / 5000.0) * potH);
        paintNetLabel(g, cx + 16, wiperY, a.pin() + " · DIP-" + a.pin().dip28Pin(), LOGIC_ANALOG);
        g.setColor(LOGIC_ANALOG);
        g.setStroke(new BasicStroke(2.2f));
        g.drawLine(cx + 116, wiperY, potX - 8, wiperY);
        // Punta de flecha apuntando a la pista resistiva
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(potX - 7, wiperY);
        arrow.lineTo(potX - 15, wiperY - 5);
        arrow.lineTo(potX - 15, wiperY + 5);
        arrow.closePath();
        g.fill(arrow);

        // Deslizador interactivo + botones finos [−] y [+] estilo POT-HG a la derecha
        int sx = cx + 299, sy = cy + 74, sw = 225;
        g.setColor(StudioTheme.BORDER_COLOR);
        g.fillRoundRect(sx, sy, sw, 10, 6, 6);
        int fillW = (int) Math.round(sw * mv / 5000.0);
        g.setColor(LOGIC_ANALOG);
        g.fillRoundRect(sx, sy, fillW, 10, 6, 6);
        int knobX = sx + fillW;
        g.setColor(Color.WHITE);
        g.fillOval(knobX - 8, sy - 6, 16, 22);
        g.setColor(StudioTheme.ACCENT_BLUE);
        g.setStroke(new BasicStroke(2f));
        g.drawOval(knobX - 8, sy - 6, 16, 22);

        // Botones [−] y [+]
        paintMiniSchematicBtn(g, cx + 289, cy + 104, 28, 24, "−");
        drawText(g, String.format(Locale.ROOT, "%.2f V  (arrastra o pulsa −/+)", mv / 1000.0),
                cx + 328, cy + 120, 10.5f, true, StudioTheme.TEXT_SECONDARY);
        paintMiniSchematicBtn(g, cx + 509, cy + 104, 28, 24, "+");
    }

    private static void paintMiniSchematicBtn(Graphics2D g, int x, int y, int w, int h, String label) {
        g.setColor(StudioTheme.SUBTLE_BG);
        g.fillRoundRect(x, y, w, h, 6, 6);
        g.setColor(StudioTheme.BORDER_STRONG);
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(x, y, w, h, 6, 6);
        drawText(g, label, x + 10, y + 16, 13, true, StudioTheme.TEXT_PRIMARY);
    }

    private void paintPwmProteus(Graphics2D g, Hardware.PwmOutput p, int cx, int cy, int cw, int badgeX) {
        var ch = p.channel();
        var clk = spec.clockFreq();
        int duty = livePwmDuties.getOrDefault(ch, lastState == null ? p.initialDuty() : (lastState.pinHigh(p.pin()) ? 255 : 0));
        double pct = duty * 100.0 / 255.0;
        Color pinCol = pinLogicColor(p.pin());

        paintBadge(g, badgeX, cy + 9,
                String.format(Locale.ROOT, "FAST PWM · %s (%s ≈%d Hz) · Duty %.1f%% (%d/255)",
                        ch.name(), ch.timer().displayName(), clk.pwmFrequencyHz(), pct, duty),
                StudioTheme.Role.OUTPUT);

        int wy = cy + 78;
        paintNetLabel(g, cx + 16, wy, p.pin() + " · " + ch.name(), pinCol);
        g.setColor(pinCol);
        g.setStroke(new BasicStroke(2.2f));
        g.drawLine(cx + 116, wy, cx + 156, wy);

        // LED indicador con brillo proporcional al duty real (0..255)
        paintResistorH(g, cx + 156, wy, 52, "330 Ω");
        g.setColor(pinCol);
        g.drawLine(cx + 208, wy, cx + 226, wy);
        if (duty > 0) {
            int alpha = Math.clamp((int) Math.round(duty * 0.55), 18, 140);
            g.setColor(new Color(34, 197, 94, alpha));
            g.fillOval(cx + 214, wy - 26, 56, 52);
        }
        paintLedDiode(g, cx + 226, wy, true, duty > 12);
        g.setColor(WIRE_GND);
        g.drawLine(cx + 260, wy, cx + 286, wy);
        g.drawLine(cx + 286, wy, cx + 286, wy + 18);
        paintGnd(g, cx + 286, wy + 18);

        // Mini Osciloscopio PWM con forma de onda cuadrada en vivo
        int ox = cx + 322, oy = cy + 44, ow = 234, oh = 74;
        g.setColor(StudioTheme.CODE_BG);
        g.fillRoundRect(ox, oy, ow, oh, 8, 8);
        g.setColor(StudioTheme.HEADER_BORDER);
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(ox, oy, ow, oh, 8, 8);

        // Grilla tenue del osciloscopio
        g.setColor(new Color(30, 41, 59));
        for (int gx = ox + 26; gx < ox + ow; gx += 26) g.drawLine(gx, oy + 4, gx, oy + oh - 4);
        g.drawLine(ox + 4, oy + oh / 2, ox + ow - 4, oy + oh / 2);

        // Onda cuadrada real de 3 periodos
        g.setColor(new Color(52, 211, 153));
        g.setStroke(new BasicStroke(2f));
        int yHigh = oy + 18, yLow = oy + oh - 16;
        int periodW = 68;
        int xStart = ox + 14;
        if (duty == 0) {
            g.drawLine(xStart, yLow, xStart + periodW * 3, yLow);
        } else if (duty == 255) {
            g.drawLine(xStart, yHigh, xStart + periodW * 3, yHigh);
        } else {
            int highW = Math.max(3, Math.min(periodW - 3, (int) Math.round(periodW * duty / 255.0)));
            Path2D wave = new Path2D.Double();
            wave.moveTo(xStart, yLow);
            for (int k = 0; k < 3; k++) {
                int x0 = xStart + k * periodW;
                wave.lineTo(x0, yHigh);
                wave.lineTo(x0 + highW, yHigh);
                wave.lineTo(x0 + highW, yLow);
                wave.lineTo(x0 + periodW, yLow);
            }
            g.draw(wave);
        }
        drawText(g, String.format(Locale.ROOT, "%.0f%%", pct), ox + ow - 34, oy + 15, 10, true, new Color(167, 243, 208));
    }

    private void paintUartProteus(Graphics2D g, Hardware.UartPort u, int cx, int cy, int cw, int badgeX) {
        paintBadge(g, badgeX, cy + 9,
                "VIRTUAL TERMINAL · USART0 " + u.baud().bps() + " bps 8N1 (" + u.mode() + ")",
                StudioTheme.Role.ALT);

        int yTx = cy + 60, yRx = cy + 92;
        Color txCol = pinLogicColor(Hardware.Pin.pd(1));
        Color rxCol = pinLogicColor(Hardware.Pin.pd(0));

        paintNetLabel(g, cx + 16, yTx, "PD1 / TXD", txCol);
        paintNetLabel(g, cx + 16, yRx, "PD0 / RXD", rxCol);

        g.setStroke(new BasicStroke(2f));
        g.setColor(txCol);
        g.drawLine(cx + 112, yTx, cx + 196, yTx);
        drawText(g, "TX → RX", cx + 128, yTx - 5, 9.5f, true, StudioTheme.TEXT_MUTED);
        g.setColor(rxCol);
        g.drawLine(cx + 112, yRx, cx + 196, yRx);
        drawText(g, "RX ← TX", cx + 128, yRx - 5, 9.5f, true, StudioTheme.TEXT_MUTED);

        // Caja Virtual Terminal estilo Proteus
        int vx = cx + 196, vy = cy + 44, vw = 360, vh = 62;
        g.setColor(StudioTheme.CODE_BG);
        g.fillRoundRect(vx, vy, vw, vh, 8, 8);
        g.setColor(StudioTheme.HEADER_BORDER);
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(vx, vy, vw, vh, 8, 8);

        drawText(g, "RXD", vx + 8, yTx + 4, 9.5f, true, StudioTheme.CODE_KEYWORD);
        drawText(g, "TXD", vx + 8, yRx + 4, 9.5f, true, StudioTheme.CODE_REGISTER);

        String rxState = lastState == null ? "Sin iniciar" : (lastState.uartRxReady() ? "RX listo" : "RX inactivo");
        String preview = lastState == null || lastState.txAscii().isEmpty()
                ? "(sin texto enviado aún · usa la consola inferior)"
                : lastState.txAscii().replace("\r", "").replace("\n", " | ");
        if (preview.length() > 44) preview = "…" + preview.substring(preview.length() - 44);
        drawText(g, "Estado: " + rxState, vx + 48, vy + 22, 10, true, StudioTheme.HEADER_MUTED);
        drawText(g, "> " + preview, vx + 48, vy + 44, 11, false, new Color(134, 239, 172));
    }

    // ==================== Primitivas de símbolos electrónicos estilo ISIS ====================

    private static void paintLogicProbe(Graphics2D g, int cx, int cy, Color c) {
        g.setColor(c);
        g.fillRect(cx - 4, cy - 4, 8, 8);
        g.setColor(c.darker());
        g.setStroke(new BasicStroke(1f));
        g.drawRect(cx - 4, cy - 4, 8, 8);
    }

    private static void paintJunction(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.fillOval(x - 4, y - 4, 8, 8);
    }

    private static void paintNetLabel(Graphics2D g, int x, int cy, String text, Color wireColor) {
        g.setColor(StudioTheme.SUBTLE_BG);
        g.fillRoundRect(x, cy - 10, 96, 20, 6, 6);
        g.setColor(wireColor);
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(x, cy - 10, 96, 20, 6, 6);
        paintLogicProbe(g, x + 8, cy, wireColor);
        drawText(g, text, x + 16, cy + 4, 9.5f, true, StudioTheme.TEXT_PRIMARY);
    }

    private static void paintBadge(Graphics2D g, int x, int y, String text, StudioTheme.Role role) {
        g.setFont(StudioTheme.uiFont(Font.BOLD, 10f));
        int tw = g.getFontMetrics().stringWidth(text);
        g.setColor(role.pillBg);
        g.fillRoundRect(x, y, tw + 14, 18, 9, 9);
        g.setColor(role.border);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x, y, tw + 14, 18, 9, 9);
        g.setColor(role.pillFg);
        g.drawString(text, x + 7, y + 13);
    }

    private static void paintVcc(Graphics2D g, int x, int y) {
        g.setColor(WIRE_POWER);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(x - 10, y, x + 10, y);
        g.drawLine(x, y, x, y + 6);
        drawText(g, "+5V", x - 11, y - 4, 9.5f, true, WIRE_POWER);
    }

    private static void paintGnd(Graphics2D g, int x, int y) {
        g.setColor(WIRE_GND);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(x - 10, y, x + 10, y);
        g.drawLine(x - 6, y + 4, x + 6, y + 4);
        g.drawLine(x - 3, y + 8, x + 3, y + 8);
        drawText(g, "GND", x - 11, y + 20, 9f, true, WIRE_GND);
    }

    private static void paintResistorH(Graphics2D g, int x, int cy, int width, String label) {
        g.setColor(RESISTOR_BODY);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int lead = 8;
        int bodyW = width - lead * 2;
        Path2D p = new Path2D.Double();
        p.moveTo(x, cy);
        p.lineTo(x + lead, cy);
        int peaks = 6;
        double step = bodyW / (double) (peaks * 2);
        for (int i = 0; i < peaks * 2; i++) {
            double px = x + lead + (i + 0.5) * step;
            double py = cy + ((i % 2 == 0) ? -6 : 6);
            p.lineTo(px, py);
        }
        p.lineTo(x + width - lead, cy);
        p.lineTo(x + width, cy);
        g.draw(p);
        drawText(g, label, x + width / 2 - 14, cy - 10, 9.5f, true, RESISTOR_BODY);
    }

    private static void paintResistorV(Graphics2D g, int cx, int y, int height, String label) {
        g.setColor(RESISTOR_BODY);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int lead = 4;
        int bodyH = height - lead * 2;
        Path2D p = new Path2D.Double();
        p.moveTo(cx, y);
        p.lineTo(cx, y + lead);
        int peaks = 5;
        double step = bodyH / (double) (peaks * 2);
        for (int i = 0; i < peaks * 2; i++) {
            double py = y + lead + (i + 0.5) * step;
            double px = cx + ((i % 2 == 0) ? -5 : 5);
            p.lineTo(px, py);
        }
        p.lineTo(cx, y + height - lead);
        p.lineTo(cx, y + height);
        g.draw(p);
        drawText(g, label, cx + 9, y + height / 2 + 4, 9.5f, true, RESISTOR_BODY);
    }

    private static void drawText(Graphics2D g, String s, int x, int y, float size, boolean bold, Color c) {
        g.setFont(StudioTheme.uiFont(bold ? Font.BOLD : Font.PLAIN, size));
        g.setColor(c);
        g.drawString(s, x, y);
    }

    // ==================== Mini Analizador Lógico (Logic Analyser en vivo) ====================

    private final class LogicTracePanel extends JPanel {
        private final Color[] CHANNEL_COLORS = {
                new Color(52, 211, 153), new Color(125, 211, 252),
                new Color(252, 211, 77), new Color(249, 168, 212),
                new Color(196, 181, 253), new Color(253, 164, 175)
        };

        LogicTracePanel() {
            setBackground(StudioTheme.CODE_BG);
            setPreferredSize(new Dimension(400, 124));
            setBorder(BorderFactory.createCompoundBorder(
                    new EmptyBorder(4, 0, 0, 0),
                    BorderFactory.createLineBorder(StudioTheme.HEADER_BORDER, 1, true)));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g.setColor(StudioTheme.CODE_BG);
                g.fillRect(0, 0, w, h);

                drawText(g, "ANALIZADOR LÓGICO EN VIVO (últimas " + MAX_TRACE_SAMPLES + " muestras)",
                        10, 16, 10f, true, StudioTheme.HEADER_MUTED);
                if (logicTraces.isEmpty()) {
                    drawText(g, "Inicia la simulación para ver las ondas digitales de cada pin.",
                            10, 44, 11f, false, StudioTheme.CODE_COMMENT);
                    return;
                }

                int channels = Math.min(logicTraces.size(), 4);
                int rowH = Math.max(20, (h - 24) / Math.max(1, channels));
                int labelW = 136;
                int plotW = Math.max(40, w - labelW - 14);
                int idx = 0;

                for (var entry : logicTraces.entrySet()) {
                    if (idx >= channels) break;
                    int yTop = 24 + idx * rowH;
                    int yHi = yTop + 4;
                    int yLo = yTop + rowH - 5;
                    Color col = CHANNEL_COLORS[idx % CHANNEL_COLORS.length];

                    drawText(g, entry.getKey(), 10, yTop + rowH / 2 + 4, 10f, true, col);

                    g.setColor(new Color(30, 41, 59));
                    g.drawLine(labelW, yLo + 2, labelW + plotW, yLo + 2);

                    List<Boolean> samples = new ArrayList<>(entry.getValue());
                    if (!samples.isEmpty()) {
                        g.setColor(col);
                        g.setStroke(new BasicStroke(1.8f));
                        double dx = plotW / (double) MAX_TRACE_SAMPLES;
                        Path2D trace = new Path2D.Double();
                        for (int i = 0; i < samples.size(); i++) {
                            double x0 = labelW + i * dx;
                            double x1 = labelW + (i + 1) * dx;
                            int yVal = samples.get(i) ? yHi : yLo;
                            if (i == 0) trace.moveTo(x0, yVal);
                            else trace.lineTo(x0, yVal);
                            trace.lineTo(x1, yVal);
                        }
                        g.draw(trace);
                    }
                    idx++;
                }
            } finally {
                g.dispose();
            }
        }
    }
}
