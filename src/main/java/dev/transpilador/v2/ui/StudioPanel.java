package dev.transpilador.v2.ui;

import dev.transpilador.v2.arbiter.ResourceArbiter;
import dev.transpilador.v2.emit.AsmEmitter;
import dev.transpilador.v2.emit.CEmitter;
import dev.transpilador.v2.emit.EmissionPlan;
import dev.transpilador.v2.emit.TraceMap;
import dev.transpilador.v2.model.*;
import dev.transpilador.v2.patterns.BenchmarkCatalog;
import dev.transpilador.v2.persistence.ProjectJson;
import dev.transpilador.v2.sim.SimEngineController;
import dev.transpilador.v2.sim.Toolchain;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Function;

/**
 * Estudio Didáctico ATmega328P (v2) con estética editorial inspirada en Lean 4
 * y flujo de tres espacios de trabajo sincronizados:
 *  - Diseño: Hardware y Estado + lista editorial de reglas + código C99/ASM/Árbitro en vivo.
 *  - Laboratorio: Simulador simavr a ancho completo (Esquemático/Protoboard + Rayos X/FSM/Osciloscopio)
 *    con panel lateral acoplado (Reglas | C99 | AVR ASM) para trazabilidad simultánea en 4 vías.
 *  - Código: Comparador lado a lado C99 vs. AVR ASM + tabla de recursos del Árbitro.
 */
public final class StudioPanel extends JPanel {
    public enum UnsavedDecision { SAVE, DISCARD, CANCEL }
    public enum WorkspaceMode { DESIGN, LAB, CODE }

    private ProgramSpec document;
    private ProgramSpec savedDocument;
    private Path currentFile;
    private Function<String, UnsavedDecision> unsavedChangesHandler = this::promptUnsavedChangesDialog;
    private final Deque<ProgramSpec> undoStack = new ArrayDeque<>();
    private final Deque<ProgramSpec> redoStack = new ArrayDeque<>();

    private final ResourceArbiter arbiter = new ResourceArbiter();
    private final CEmitter cEmitter = new CEmitter();
    private final AsmEmitter asmEmitter = new AsmEmitter();

    // Barra superior de 1 sola línea
    private final JLabel projectTitle = new JLabel();
    private final JComboBox<Hardware.ClockFreq> clockBox = new JComboBox<>(Hardware.ClockFreq.values());
    private boolean updatingClockBox;
    private final JButton btnUndo = new JButton();
    private final JButton btnRedo = new JButton();
    private final JButton arbiterStatusBtn = new JButton();

    // Conmutador de espacios de trabajo (Diseño | Laboratorio | Código)
    private WorkspaceMode workspaceMode = WorkspaceMode.DESIGN;
    private final JButton btnModeDesign = new JButton("Diseño");
    private final JButton btnModeLab = new JButton("Laboratorio");
    private final JButton btnModeCode = new JButton("Código");
    private final CardLayout workspaceCards = new CardLayout();
    private final JPanel workspaceHost = new JPanel(workspaceCards);

    // Contenedores anfitriones (simulatorTab permanece fijo en LAB para no reparentar JCEF)
    private final JPanel designRulesHost = new JPanel(new BorderLayout());
    private final JPanel designCHost = new JPanel(new BorderLayout());
    private final JPanel designAsmHost = new JPanel(new BorderLayout());
    private final JPanel designArbiterHost = new JPanel(new BorderLayout());
    private final JTabbedPane designRightTabs = new JTabbedPane();

    private final JPanel labRulesHost = new JPanel(new BorderLayout());
    private final JPanel labCHost = new JPanel(new BorderLayout());
    private final JPanel labAsmHost = new JPanel(new BorderLayout());
    private final JTabbedPane labRightTabs = new JTabbedPane();

    private final JPanel codeCHost = new JPanel(new BorderLayout());
    private final JPanel codeAsmHost = new JPanel(new BorderLayout());
    private final JPanel codeArbiterHost = new JPanel(new BorderLayout());

    // Paneles lógicos compartidos
    private final JLabel hwSummaryLabel = new JLabel();
    private final JPanel hwContainer = new JPanel();
    private final JPanel hardwarePanel;

    private final JLabel rulesCountLabel = new JLabel();
    private final JPanel rulesContainer = new JPanel();
    private final JPanel rulesPanel;
    private final List<StudioTheme.RoundedPanel> ruleCardPanels = new ArrayList<>();
    private final List<StudioTheme.Role> ruleCardRoles = new ArrayList<>();
    private TraceMap traceMap;
    private int selectedRuleIndex = -1;

    private final SimulatorTabPanel simulatorTab;
    private final CodeViewerPane cViewer;
    private final CodeViewerPane asmViewer;
    private final JPanel arbiterPanel;
    private final JComboBox<AsmEmitter.InstructionProfile> asmProfileBox = new JComboBox<>(AsmEmitter.InstructionProfile.values());
    private AsmEmitter.InstructionProfile asmProfile = AsmEmitter.InstructionProfile.ELEMENTARY;
    private final JComboBox<AsmEmitter.StorageMode> asmStorageBox = new JComboBox<>(AsmEmitter.StorageMode.values());
    private AsmEmitter.StorageMode asmStorageMode = AsmEmitter.StorageMode.REGISTERS_FIRST;
    private final DefaultTableModel arbiterTableModel = new DefaultTableModel(
            new Object[]{"Dueño", "Recurso físico", "Contrato / Detalle"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTextArea diagnosticArea = new JTextArea(4, 30);
    private final JLabel arbiterBanner = new JLabel();

    public StudioPanel() {
        this(BenchmarkCatalog.b03CadaTresPulsaciones());
    }

    public StudioPanel(ProgramSpec initial) {
        super(new BorderLayout(0, 0));
        setName("studioPanelV2");
        setBackground(StudioTheme.CANVAS_BG);
        this.document = Objects.requireNonNull(initial, "initial");
        this.savedDocument = this.document;
        this.simulatorTab = new SimulatorTabPanel(document);
        this.cViewer = new CodeViewerPane(CodeViewerPane.Language.C99, () -> exportCode(true));
        this.asmViewer = new CodeViewerPane(CodeViewerPane.Language.AVRASM2, () -> exportCode(false));
        this.cViewer.setProjectNameSupplier(() -> document.name());
        this.asmViewer.setProjectNameSupplier(() -> document.name());
        this.cViewer.setLoadFirmwareHandler(this::loadCustomFirmwareAndSimulate);
        this.asmViewer.setLoadFirmwareHandler(this::loadCustomFirmwareAndSimulate);
        this.cViewer.setLineClickListener(line -> onCodeLineClicked(true, line));
        this.asmViewer.setLineClickListener(line -> onCodeLineClicked(false, line));
        this.simulatorTab.webEngine().addRuleSelectListener(idx -> SwingUtilities.invokeLater(() -> selectRule(idx)));

        asmStorageBox.setName("asmStorageMode");
        asmStorageBox.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value == AsmEmitter.StorageMode.REGISTERS_FIRST) lbl.setText("Registros");
                else if (value == AsmEmitter.StorageMode.SRAM_DATA_SEGMENT) lbl.setText("SRAM (.dseg)");
                return lbl;
            }
        });
        asmStorageBox.setToolTipText("Elige si contadores, temporizadores y banderas en ASM usan solo registros (por defecto) o SRAM (.dseg)");
        asmStorageBox.addActionListener(e -> {
            AsmEmitter.StorageMode chosen = (AsmEmitter.StorageMode) asmStorageBox.getSelectedItem();
            if (chosen != null && chosen != asmStorageMode) {
                asmStorageMode = chosen;
                simulatorTab.setAsmStorageMode(chosen);
                regenerateCodeAndTrace();
            }
        });
        asmProfileBox.setName("asmInstructionProfile");
        asmProfileBox.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value == AsmEmitter.InstructionProfile.ELEMENTARY) lbl.setText("Elemental");
                else if (value == AsmEmitter.InstructionProfile.STANDARD) lbl.setText("Estándar");
                return lbl;
            }
        });
        asmProfileBox.setToolTipText("Elemental: solo instrucciones básicas de curso (sin subi/sbci/cpc/tst). Estándar: set completo ATmega328P.");
        asmProfileBox.addActionListener(e -> {
            AsmEmitter.InstructionProfile chosen = (AsmEmitter.InstructionProfile) asmProfileBox.getSelectedItem();
            if (chosen != null && chosen != asmProfile) {
                asmProfile = chosen;
                simulatorTab.setAsmProfile(chosen);
                regenerateCodeAndTrace();
            }
        });
        asmViewer.addHeaderControl(asmStorageBox);
        asmViewer.addHeaderControl(asmProfileBox);

        this.hardwarePanel = buildHardwarePanel();
        this.rulesPanel = buildRulesPanel();
        this.arbiterPanel = buildArbiterTab();

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildWorkspaces(), BorderLayout.CENTER);
        installShortcuts();
        setWorkspaceMode(WorkspaceMode.DESIGN);
        refreshAll();
    }

    // ==================== Barra superior de 1 sola línea (Lean 4) ====================

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBackground(StudioTheme.HEADER_BG);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, StudioTheme.HEADER_BORDER),
                new EmptyBorder(7, 16, 7, 16)));

        // Izquierda: Marca Serif + título del proyecto + menús discretos
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);

        JLabel wordmark = new JLabel("AVR v2");
        wordmark.setFont(StudioTheme.serifFont(Font.BOLD, 16f));
        wordmark.setForeground(StudioTheme.ACCENT_TEAL_BRIGHT);
        wordmark.setBorder(new EmptyBorder(0, 0, 0, 4));

        projectTitle.setForeground(StudioTheme.TEXT_PRIMARY);
        projectTitle.setFont(StudioTheme.serifFont(Font.BOLD, 15.5f));
        projectTitle.setBorder(new EmptyBorder(0, 2, 0, 10));

        JButton btnFile = headerMenuBtn("Archivo");
        JButton btnExamples = headerMenuBtn("Ejemplos");
        JButton btnExport = headerMenuBtn("Exportar");

        btnFile.addActionListener(e -> showFileMenu(btnFile));
        btnExamples.addActionListener(e -> showExamplesMenu(btnExamples));
        btnExport.addActionListener(e -> showExportMenu(btnExport));

        left.add(wordmark);
        left.add(headerSeparator());
        left.add(projectTitle);
        left.add(headerSeparator());
        left.add(btnFile);
        left.add(btnExamples);
        left.add(btnExport);

        // Centro: selector segmentado de modo de trabajo (Diseño | Laboratorio | Código)
        JPanel centerWrap = new JPanel(new GridBagLayout());
        centerWrap.setOpaque(false);

        StudioTheme.RoundedPanel segTrack = new StudioTheme.RoundedPanel(new FlowLayout(FlowLayout.CENTER, 2, 2))
                .fill(new Color(14, 17, 16))
                .stroke(StudioTheme.BORDER_COLOR)
                .arc(7);
        segTrack.setBorder(new EmptyBorder(1, 2, 1, 2));

        btnModeDesign.setName("modeDesignBtn");
        btnModeLab.setName("modeLabBtn");
        btnModeCode.setName("modeCodeBtn");
        btnModeDesign.setToolTipText("Especificar hardware, estado y reglas junto al código generado");
        btnModeLab.setToolTipText("Simular firmware real en simavr con Rayos X, FSM y trazabilidad simultánea");
        btnModeCode.setToolTipText("Comparar C99 y AVR ASM lado a lado con el Árbitro de Recursos");

        btnModeDesign.addActionListener(e -> setWorkspaceMode(WorkspaceMode.DESIGN));
        btnModeLab.addActionListener(e -> setWorkspaceMode(WorkspaceMode.LAB));
        btnModeCode.addActionListener(e -> setWorkspaceMode(WorkspaceMode.CODE));

        segTrack.add(btnModeDesign);
        segTrack.add(btnModeLab);
        segTrack.add(btnModeCode);
        centerWrap.add(segTrack);

        // Derecha: reloj + undo/redo + estado semántico del árbitro
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);

        clockBox.setName("clockSelector");
        clockBox.setFont(StudioTheme.codeFont(12f));
        clockBox.setToolTipText("Frecuencia del ATmega328P (recalcula retardos, ADC, PWM y UART)");
        clockBox.addActionListener(e -> {
            if (updatingClockBox) return;
            Hardware.ClockFreq freq = (Hardware.ClockFreq) clockBox.getSelectedItem();
            if (freq != null && freq.hz() != document.fCpuHz()) {
                setDocument(document.withClock(freq));
            }
        });

        StudioTheme.styleHeaderButton(btnUndo, false);
        StudioTheme.styleHeaderButton(btnRedo, false);
        StudioTheme.StudioIcon.apply(btnUndo, StudioTheme.StudioIcon.Kind.UNDO);
        StudioTheme.StudioIcon.apply(btnRedo, StudioTheme.StudioIcon.Kind.REDO);
        btnUndo.setToolTipText("Deshacer (Ctrl+Z)");
        btnRedo.setToolTipText("Rehacer (Ctrl+Y)");
        btnUndo.addActionListener(e -> undo());
        btnRedo.addActionListener(e -> redo());

        arbiterStatusBtn.setFont(StudioTheme.uiFont(Font.PLAIN, 12.5f));
        arbiterStatusBtn.setFocusPainted(false);
        arbiterStatusBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        arbiterStatusBtn.setToolTipText("Ver mapa de recursos físicos y diagnóstico del Árbitro");
        arbiterStatusBtn.addActionListener(e -> openArbiterView());

        right.add(clockBox);
        right.add(headerSeparator());
        right.add(btnUndo);
        right.add(btnRedo);
        right.add(headerSeparator());
        right.add(arbiterStatusBtn);

        bar.add(left, BorderLayout.WEST);
        bar.add(centerWrap, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private static JButton headerMenuBtn(String text) {
        JButton b = new JButton(text);
        StudioTheme.styleHeaderButton(b, false);
        b.setIcon(new StudioTheme.StudioIcon(StudioTheme.StudioIcon.Kind.CHEVRON_DOWN, 11));
        b.setHorizontalTextPosition(SwingConstants.LEADING);
        b.setIconTextGap(5);
        return b;
    }

    private static JSeparator headerSeparator() {
        JSeparator s = new JSeparator(SwingConstants.VERTICAL);
        s.setPreferredSize(new Dimension(1, 18));
        s.setForeground(StudioTheme.BORDER_COLOR);
        return s;
    }

    // ==================== 3 Espacios de trabajo sincronizados ====================

    private JPanel buildWorkspaces() {
        workspaceHost.setBackground(StudioTheme.CANVAS_BG);

        // 1) Modo DISEÑO: Hardware | Reglas | Pestañas (C99, AVR ASM, Árbitro)
        designRulesHost.setBackground(StudioTheme.CANVAS_BG);
        designCHost.setBackground(StudioTheme.CODE_BG);
        designAsmHost.setBackground(StudioTheme.CODE_BG);
        designArbiterHost.setBackground(StudioTheme.CANVAS_BG);

        designRightTabs.setName("designRightTabs");
        designRightTabs.setFont(StudioTheme.uiFont(Font.PLAIN, 13f));
        designRightTabs.addTab("C99", designCHost);
        designRightTabs.addTab("AVR ASM", designAsmHost);
        designRightTabs.addTab("Árbitro", designArbiterHost);

        JSplitPane designLeftCenter = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, hardwarePanel, designRulesHost);
        designLeftCenter.setBorder(null);
        designLeftCenter.setResizeWeight(0.34);
        designLeftCenter.setDividerLocation(290);
        designLeftCenter.setDividerSize(5);

        JSplitPane designSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, designLeftCenter, designRightTabs);
        designSplit.setBorder(null);
        designSplit.setResizeWeight(0.56);
        designSplit.setDividerLocation(810);
        designSplit.setDividerSize(5);

        // 2) Modo LABORATORIO: Simulador Chromium/simavr fijo a la izquierda + Panel lateral sincronizado
        labRulesHost.setBackground(StudioTheme.CANVAS_BG);
        labCHost.setBackground(StudioTheme.CODE_BG);
        labAsmHost.setBackground(StudioTheme.CODE_BG);

        labRightTabs.setName("labRightTabs");
        labRightTabs.setFont(StudioTheme.uiFont(Font.PLAIN, 13f));
        labRightTabs.addTab("Reglas", labRulesHost);
        labRightTabs.addTab("C99", labCHost);
        labRightTabs.addTab("AVR ASM", labAsmHost);

        JSplitPane labSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, simulatorTab, labRightTabs);
        labSplit.setBorder(null);
        labSplit.setResizeWeight(0.68);
        labSplit.setDividerLocation(940);
        labSplit.setDividerSize(5);

        // 3) Modo CÓDIGO: C99 y AVR ASM lado a lado + Árbitro abajo
        codeCHost.setBackground(StudioTheme.CODE_BG);
        codeAsmHost.setBackground(StudioTheme.CODE_BG);
        codeArbiterHost.setBackground(StudioTheme.CANVAS_BG);

        JSplitPane codeHoriz = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, codeCHost, codeAsmHost);
        codeHoriz.setBorder(null);
        codeHoriz.setResizeWeight(0.50);
        codeHoriz.setDividerLocation(700);
        codeHoriz.setDividerSize(5);

        JSplitPane codeVert = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codeHoriz, codeArbiterHost);
        codeVert.setBorder(null);
        codeVert.setResizeWeight(0.74);
        codeVert.setDividerLocation(570);
        codeVert.setDividerSize(5);

        workspaceHost.add(designSplit, WorkspaceMode.DESIGN.name());
        workspaceHost.add(labSplit, WorkspaceMode.LAB.name());
        workspaceHost.add(codeVert, WorkspaceMode.CODE.name());
        return workspaceHost;
    }

    public WorkspaceMode workspaceMode() {
        return workspaceMode;
    }

    /** Cambia entre los espacios Diseño, Laboratorio y Código manteniendo el mismo estado y sesión simavr. */
    public void setWorkspaceMode(WorkspaceMode mode) {
        Objects.requireNonNull(mode, "mode");
        this.workspaceMode = mode;
        StudioTheme.styleSegmentButton(btnModeDesign, mode == WorkspaceMode.DESIGN);
        StudioTheme.styleSegmentButton(btnModeLab, mode == WorkspaceMode.LAB);
        StudioTheme.styleSegmentButton(btnModeCode, mode == WorkspaceMode.CODE);

        switch (mode) {
            case DESIGN -> {
                mount(designRulesHost, rulesPanel);
                mount(designCHost, cViewer);
                mount(designAsmHost, asmViewer);
                mount(designArbiterHost, arbiterPanel);
            }
            case LAB -> {
                mount(labRulesHost, rulesPanel);
                mount(labCHost, cViewer);
                mount(labAsmHost, asmViewer);
            }
            case CODE -> {
                mount(codeCHost, cViewer);
                mount(codeAsmHost, asmViewer);
                mount(codeArbiterHost, arbiterPanel);
            }
        }
        workspaceCards.show(workspaceHost, mode.name());
        workspaceHost.revalidate();
        workspaceHost.repaint();
    }

    private static void mount(JPanel host, JComponent child) {
        if (child.getParent() == host) return;
        Container prev = child.getParent();
        if (prev != null) {
            prev.remove(child);
            prev.revalidate();
        }
        host.removeAll();
        host.add(child, BorderLayout.CENTER);
        host.revalidate();
        host.repaint();
    }

    private void openArbiterView() {
        if (workspaceMode == WorkspaceMode.DESIGN) {
            designRightTabs.setSelectedIndex(2);
        } else {
            setWorkspaceMode(WorkspaceMode.CODE);
        }
    }

    // ==================== Panel de Hardware y Estado (libro mayor sobrio) ====================

    private JPanel buildHardwarePanel() {
        JPanel pane = new JPanel(new BorderLayout(0, 12));
        pane.setBackground(StudioTheme.CANVAS_BG);
        pane.setBorder(new EmptyBorder(16, 16, 14, 12));

        JPanel hdr = new JPanel(new BorderLayout(0, 10));
        hdr.setOpaque(false);

        JPanel titles = new JPanel(new BorderLayout(0, 3));
        titles.setOpaque(false);
        JLabel hwTitle = new JLabel("Circuito y estado");
        hwTitle.setFont(StudioTheme.serifFont(Font.BOLD, 16.5f));
        hwTitle.setForeground(StudioTheme.ACCENT_TEAL_BRIGHT);
        hwSummaryLabel.setFont(StudioTheme.uiFont(Font.PLAIN, 12f));
        hwSummaryLabel.setForeground(StudioTheme.TEXT_MUTED);
        titles.add(hwTitle, BorderLayout.NORTH);
        titles.add(hwSummaryLabel, BorderLayout.SOUTH);

        JButton btnAddHw = new JButton("Agregar componente o estado");
        StudioTheme.StudioIcon.apply(btnAddHw, StudioTheme.StudioIcon.Kind.PLUS);
        StudioTheme.styleTealOutline(btnAddHw);
        btnAddHw.addActionListener(e -> addHardwareOrState(null));

        hdr.add(titles, BorderLayout.NORTH);
        hdr.add(btnAddHw, BorderLayout.SOUTH);

        hwContainer.setOpaque(false);
        hwContainer.setLayout(new BoxLayout(hwContainer, BoxLayout.Y_AXIS));
        JPanel hwTrack = new TrackWidthPanel(new BorderLayout());
        hwTrack.setOpaque(false);
        hwTrack.add(hwContainer, BorderLayout.NORTH);

        JScrollPane sp = new JScrollPane(hwTrack);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(20);

        pane.add(hdr, BorderLayout.NORTH);
        pane.add(sp, BorderLayout.CENTER);
        return pane;
    }

    // ==================== Panel de Comportamiento (lista editorial Lean 4) ====================

    private JPanel buildRulesPanel() {
        JPanel pane = new JPanel(new BorderLayout(0, 12));
        pane.setBackground(StudioTheme.CANVAS_BG);
        pane.setBorder(new EmptyBorder(16, 16, 14, 16));

        JPanel hdr = new JPanel(new BorderLayout(0, 10));
        hdr.setOpaque(false);

        JPanel titleBlock = new JPanel(new BorderLayout(0, 3));
        titleBlock.setOpaque(false);
        JLabel ruleTitle = new JLabel("Comportamiento");
        ruleTitle.setFont(StudioTheme.serifFont(Font.BOLD, 16.5f));
        ruleTitle.setForeground(StudioTheme.ACCENT_TEAL_BRIGHT);
        rulesCountLabel.setFont(StudioTheme.uiFont(Font.PLAIN, 12f));
        rulesCountLabel.setForeground(StudioTheme.TEXT_MUTED);
        titleBlock.add(ruleTitle, BorderLayout.NORTH);
        titleBlock.add(rulesCountLabel, BorderLayout.SOUTH);

        // Par de acciones estilo Lean 4: 1 CTA primario cobre + 1 secundario delineado teal
        JPanel ctaRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ctaRow.setOpaque(false);

        JButton btnPattern = new JButton("Agregar intención");
        StudioTheme.styleCopperPrimary(btnPattern);
        btnPattern.setToolTipText("Abrir catálogo guiado de intenciones (cada N pulsaciones, doble clic, contador 4511, PWM, UART)");
        btnPattern.addActionListener(e -> applyPattern());

        JButton btnNewRule = new JButton("Regla manual");
        StudioTheme.styleTealOutline(btnNewRule);
        btnNewRule.setToolTipText("Crear una regla paso a paso con el modelo completo de 5 bloques");
        btnNewRule.addActionListener(e -> addRule());

        ctaRow.add(btnPattern);
        ctaRow.add(btnNewRule);

        hdr.add(titleBlock, BorderLayout.NORTH);
        hdr.add(ctaRow, BorderLayout.SOUTH);

        rulesContainer.setOpaque(false);
        rulesContainer.setLayout(new BoxLayout(rulesContainer, BoxLayout.Y_AXIS));
        JPanel rulesTrack = new TrackWidthPanel(new BorderLayout());
        rulesTrack.setOpaque(false);
        rulesTrack.add(rulesContainer, BorderLayout.NORTH);

        JScrollPane sp = new JScrollPane(rulesTrack);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(22);

        pane.add(hdr, BorderLayout.NORTH);
        pane.add(sp, BorderLayout.CENTER);
        return pane;
    }

    private JPanel buildArbiterTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(StudioTheme.CANVAS_BG);
        p.setBorder(new EmptyBorder(12, 14, 12, 14));

        arbiterBanner.setFont(StudioTheme.uiFont(Font.BOLD, 13f));
        arbiterBanner.setOpaque(true);
        arbiterBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(StudioTheme.BORDER_COLOR, 1),
                new EmptyBorder(9, 12, 9, 12)));

        JTable table = new JTable(arbiterTableModel);
        table.setRowHeight(28);
        table.setFont(StudioTheme.uiFont(Font.PLAIN, 12.5f));
        table.getTableHeader().setFont(StudioTheme.uiFont(Font.BOLD, 12f));
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setGridColor(StudioTheme.BORDER_COLOR);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createLineBorder(StudioTheme.BORDER_COLOR, 1));

        diagnosticArea.setEditable(false);
        diagnosticArea.setFont(StudioTheme.codeFont(12f));
        diagnosticArea.setBackground(StudioTheme.SURFACE_BG);
        diagnosticArea.setForeground(StudioTheme.TEXT_SECONDARY);
        diagnosticArea.setBorder(new EmptyBorder(8, 10, 8, 10));

        JScrollPane diagScroll = new JScrollPane(diagnosticArea);
        diagScroll.setBorder(BorderFactory.createLineBorder(StudioTheme.BORDER_COLOR, 1));

        p.add(arbiterBanner, BorderLayout.NORTH);
        p.add(tableScroll, BorderLayout.CENTER);
        p.add(diagScroll, BorderLayout.SOUTH);
        return p;
    }

    // ==================== Atajos y API pública ====================

    private void installShortcuts() {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control Z"), "undo");
        getActionMap().put("undo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { undo(); }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control Y"), "redo");
        getActionMap().put("redo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { redo(); }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control S"), "save");
        getActionMap().put("save", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { saveProject(); }
        });
    }

    public ProgramSpec document() { return document; }
    public Path currentFile() { return currentFile; }
    public boolean hasUnsavedChanges() { return !Objects.equals(document, savedDocument); }
    public String emittedC() { return cViewer.getCode(); }
    public String emittedAsm() { return asmViewer.getCode(); }
    public CodeViewerPane cViewer() { return cViewer; }
    public CodeViewerPane asmViewer() { return asmViewer; }
    public TraceMap traceMap() { return traceMap; }
    public int selectedRuleIndex() { return selectedRuleIndex; }
    public CircuitCanvasPanel circuitCanvas() { return simulatorTab.swingView(); }
    public SimulatorTabPanel simulatorTab() { return simulatorTab; }

    /**
     * Selecciona una regla por su índice (o -1 para limpiar) e ilumina simultáneamente:
     * la regla en la lista editorial, su bloque exacto en C99, su bloque exacto en AVR ASM
     * y los pines/cables/componentes físicos involucrados en el simulador (Chromium y Swing).
     */
    public void selectRule(int ruleIndex) {
        if (traceMap == null || ruleIndex < 0 || ruleIndex >= document.rules().size()) {
            selectedRuleIndex = -1;
            applyRuleCardSelectionStyles();
            cViewer.highlightLines(null, false);
            asmViewer.highlightLines(null, false);
            simulatorTab.setHighlight(null);
            return;
        }
        selectedRuleIndex = ruleIndex;
        applyRuleCardSelectionStyles();
        if (ruleIndex < ruleCardPanels.size()) {
            JComponent card = ruleCardPanels.get(ruleIndex);
            card.scrollRectToVisible(new Rectangle(0, 0, card.getWidth(), card.getHeight()));
        }
        var traceOpt = traceMap.rule(ruleIndex);
        if (traceOpt.isPresent()) {
            TraceMap.RuleTrace rt = traceOpt.get();
            cViewer.highlightLines(rt.cRange(), true);
            asmViewer.highlightLines(rt.asmRange(), true);
            simulatorTab.setHighlight(new SimEngineController.HighlightState(
                    rt.ruleIndex(), rt.title(), rt.components(), rt.pins()));
        }
    }

    private void onCodeLineClicked(boolean fromC, int lineNumber) {
        if (traceMap == null) return;
        var opt = fromC ? traceMap.ruleAtCLine(lineNumber) : traceMap.ruleAtAsmLine(lineNumber);
        if (opt.isPresent()) {
            selectRule(opt.get().ruleIndex());
        } else {
            selectRule(-1);
        }
    }

    private void applyRuleCardSelectionStyles() {
        Color selFill = StudioTheme.ACCENT_COPPER_SOFT;
        Color selAccent = StudioTheme.ACCENT_COPPER_GLOW;
        for (int i = 0; i < ruleCardPanels.size(); i++) {
            StudioTheme.RoundedPanel card = ruleCardPanels.get(i);
            boolean sel = (i == selectedRuleIndex);
            card.fill(sel ? selFill : StudioTheme.CANVAS_BG);
            card.stroke(sel ? StudioTheme.ACCENT_COPPER : StudioTheme.BORDER_COLOR);
            card.accent(sel ? selAccent : null);
            card.repaint();
        }
    }

    public void setUnsavedChangesHandler(Function<String, UnsavedDecision> handler) {
        this.unsavedChangesHandler = Objects.requireNonNull(handler, "handler");
    }

    public void setDocument(ProgramSpec next) {
        Objects.requireNonNull(next, "next");
        if (!next.equals(document)) {
            undoStack.push(document);
            redoStack.clear();
            document = next;
            refreshAll();
        }
    }

    public void loadDocument(ProgramSpec next, Path associatedFile) {
        Objects.requireNonNull(next, "next");
        undoStack.clear();
        redoStack.clear();
        currentFile = associatedFile;
        document = next;
        savedDocument = next;
        cViewer.resetToSyncedMode();
        asmViewer.resetToSyncedMode();
        simulatorTab.clearCustomFirmware();
        refreshAll();
    }

    /** Carga un firmware manual (compilado en el IDE o .hex externo) en simavr y abre el Laboratorio. */
    public void loadCustomFirmwareAndSimulate(SimEngineController.CustomFirmware firmware) {
        Objects.requireNonNull(firmware, "firmware");
        simulatorTab.loadCustomFirmware(firmware, true);
        setWorkspaceMode(WorkspaceMode.LAB);
    }

    /** Valida y carga un archivo Intel .hex externo en simavr contra el circuito actual. */
    public void loadExternalHex(Path hexPath) throws IOException {
        Objects.requireNonNull(hexPath, "hexPath");
        int flashBytes = Toolchain.validateExternalHex(hexPath);
        String label = "HEX externo: " + hexPath.getFileName();
        loadCustomFirmwareAndSimulate(new SimEngineController.CustomFirmware(
                label, SimEngineController.Backend.ASM, hexPath.toAbsolutePath().normalize(), null, flashBytes));
    }

    public boolean requestLoadDocument(ProgramSpec next, Path associatedFile, String actionDescription) {
        Objects.requireNonNull(next, "next");
        if (!confirmDiscardOrSave(actionDescription)) return false;
        loadDocument(next, associatedFile);
        return true;
    }

    public boolean openProject(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!confirmDiscardOrSave("abrir otro proyecto")) return false;
        ProgramSpec loaded = ProjectJson.parse(Files.readString(path, StandardCharsets.UTF_8));
        loadDocument(loaded, path);
        return true;
    }

    public boolean confirmClose() {
        return confirmDiscardOrSave("cerrar el estudio");
    }

    public boolean confirmDiscardOrSave(String actionDescription) {
        if (!hasUnsavedChanges()) return true;
        UnsavedDecision decision = unsavedChangesHandler.apply(actionDescription);
        if (decision == null || decision == UnsavedDecision.CANCEL) return false;
        if (decision == UnsavedDecision.DISCARD) return true;
        return saveProject();
    }

    private UnsavedDecision promptUnsavedChangesDialog(String actionDescription) {
        if (GraphicsEnvironment.isHeadless() || !isDisplayable()) {
            return UnsavedDecision.CANCEL;
        }
        Object[] options = {"Guardar", "Descartar", "Cancelar"};
        int choice = JOptionPane.showOptionDialog(
                this,
                "El proyecto «" + NaturalLanguage.prettyProjectName(document.name())
                        + "» tiene cambios sin guardar.\n¿Qué deseas hacer antes de " + actionDescription + "?",
                "Cambios sin guardar",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]
        );
        return switch (choice) {
            case 0 -> UnsavedDecision.SAVE;
            case 1 -> UnsavedDecision.DISCARD;
            default -> UnsavedDecision.CANCEL;
        };
    }

    public void saveCurrentProject() throws IOException {
        if (currentFile == null) {
            throw new IllegalStateException("No hay un archivo de proyecto asociado.");
        }
        Files.writeString(currentFile, ProjectJson.toJson(document), StandardCharsets.UTF_8);
        savedDocument = document;
        updateProjectTitle();
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(document);
        document = undoStack.pop();
        refreshAll();
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(document);
        document = redoStack.pop();
        refreshAll();
    }

    public void shutdown() {
        simulatorTab.shutdown();
    }

    // ==================== Refresco reactivo completo ====================

    private void updateProjectTitle() {
        String title = NaturalLanguage.prettyProjectName(document.name());
        projectTitle.setText(hasUnsavedChanges() ? title + " *" : title);
    }

    private void refreshAll() {
        updateProjectTitle();
        updatingClockBox = true;
        clockBox.setSelectedItem(document.clockFreq());
        updatingClockBox = false;
        btnUndo.setEnabled(!undoStack.isEmpty());
        btnRedo.setEnabled(!redoStack.isEmpty());

        rebuildHardwareCards();
        rebuildRuleCards();

        // Árbitro + C99 + AVR ASM en vivo
        var res = arbiter.analyze(document);
        arbiterTableModel.setRowCount(0);
        for (var alloc : res.allocations()) {
            arbiterTableModel.addRow(new Object[]{alloc.owner(), alloc.resource(), alloc.detail()});
        }
        if (res.valid()) {
            arbiterStatusBtn.setText(res.allocations().size() + " recursos");
            arbiterStatusBtn.setIcon(new StudioTheme.StudioIcon(StudioTheme.StudioIcon.Kind.CHECK, 13, StudioTheme.ACCENT_TEAL_BRIGHT));
            arbiterStatusBtn.setIconTextGap(6);
            arbiterStatusBtn.putClientProperty("FlatLaf.style",
                    "background:#121514; foreground:#4CC6BC; hoverBackground:#182725; borderColor:#224A46; arc:6; margin:4,10,4,10");

            arbiterBanner.setBackground(StudioTheme.ACCENT_TEAL_SOFT);
            arbiterBanner.setForeground(StudioTheme.ACCENT_TEAL_BRIGHT);
            arbiterBanner.setIcon(new StudioTheme.StudioIcon(StudioTheme.StudioIcon.Kind.CHECK, 14, StudioTheme.ACCENT_TEAL_BRIGHT));
            arbiterBanner.setIconTextGap(7);
            arbiterBanner.setText("Sin conflictos físicos ni lógicos (" + res.allocations().size()
                    + " recursos arbitrados en ATmega328P a " + document.clockFreq().label() + ")");
            diagnosticArea.setText("Validación correcta: todos los pines, periféricos y referencias son consistentes para C99 y AVR ASM.");

            regenerateCodeAndTrace();
        } else {
            int nErr = res.errors().size();
            arbiterStatusBtn.setText(nErr + (nErr == 1 ? " conflicto" : " conflictos"));
            arbiterStatusBtn.setIcon(new StudioTheme.StudioIcon(StudioTheme.StudioIcon.Kind.WARN, 13, StudioTheme.ACCENT_ROSE));
            arbiterStatusBtn.setIconTextGap(6);
            arbiterStatusBtn.putClientProperty("FlatLaf.style",
                    "background:#2A1515; foreground:#F5A6A3; hoverBackground:#381B1B; borderColor:#7F3331; arc:6; margin:4,10,4,10");

            arbiterBanner.setBackground(StudioTheme.Role.DANGER.softBg);
            arbiterBanner.setForeground(StudioTheme.Role.DANGER.pillFg);
            arbiterBanner.setIcon(new StudioTheme.StudioIcon(StudioTheme.StudioIcon.Kind.WARN, 14, StudioTheme.Role.DANGER.pillFg));
            arbiterBanner.setIconTextGap(7);
            arbiterBanner.setText("Se detectaron " + nErr + " conflicto(s) en el Árbitro de Recursos");

            String errs = res.diagnostics().stream()
                    .map(d -> "[" + d.severity() + " · " + d.code() + "] " + d.message())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            diagnosticArea.setText(errs);
            traceMap = null;
            selectedRuleIndex = -1;
            cViewer.setCode("/* Corrige los conflictos del árbitro para generar C99:\n" + errs + "\n*/");
            asmViewer.setCode("; Corrige los conflictos del árbitro para generar ASM:\n; " + errs.replace("\n", "\n; "));
        }
        selectedRuleIndex = -1;
        simulatorTab.setProgram(document);
        simulatorTab.setHighlight(null);
    }

    private void regenerateCodeAndTrace() {
        var res = arbiter.analyze(document);
        if (!res.valid()) return;
        EmissionPlan plan = EmissionPlan.analyze(document);
        var cOut = cEmitter.emitWithTrace(plan);
        var asmOut = asmEmitter.emitWithTrace(plan, asmProfile, asmStorageMode);
        traceMap = TraceMap.build(document, cOut.ruleLines(), asmOut.ruleLines());
        cViewer.setGeneratedCode(cOut.code());
        asmViewer.setGeneratedCode(asmOut.code());
        if (selectedRuleIndex >= 0 && selectedRuleIndex < document.rules().size()) {
            var rt = traceMap.rule(selectedRuleIndex);
            if (rt.isPresent()) {
                cViewer.highlightLines(rt.get().cRange(), false);
                asmViewer.highlightLines(rt.get().asmRange(), false);
            }
        }
    }

    // ==================== Filas sobrias de Hardware y Estado ====================

    private void rebuildHardwareCards() {
        hwContainer.removeAll();
        int stateCount = document.vars().size() + document.phaseGroups().size() + document.timers().size();
        hwSummaryLabel.setText(document.components().size() + " físicos · " + stateCount + " de estado");

        List<JPanel> inputs = new ArrayList<>();
        List<JPanel> outputs = new ArrayList<>();
        List<JPanel> comm = new ArrayList<>();
        List<JPanel> state = new ArrayList<>();

        for (int i = 0; i < document.components().size(); i++) {
            Hardware.Component c = document.components().get(i);
            final int compIdx = i;
            switch (c) {
                case Hardware.Button b -> inputs.add(hwItemRow("Botón", b.name(),
                        b.pin() + " · DIP-" + b.pin().dip28Pin() + " (" + b.connection() + ")",
                        StudioTheme.ACCENT_TEAL, () -> removeHardwareItem(0, compIdx)));
                case Hardware.AnalogInput a -> inputs.add(hwItemRow("ADC" + a.channel(), a.name(),
                        a.pin() + " · DIP-" + a.pin().dip28Pin() + " (0..5 V)",
                        StudioTheme.ACCENT_VIOLET, () -> removeHardwareItem(0, compIdx)));
                case Hardware.Led l -> outputs.add(hwItemRow("LED", l.name(),
                        l.pin() + " · DIP-" + l.pin().dip28Pin() + " (" + l.connection() + (l.initialOn() ? ", ON" : "") + ")",
                        StudioTheme.ACCENT_TEAL, () -> removeHardwareItem(0, compIdx)));
                case Hardware.BcdDisplay d -> outputs.add(hwItemRow("CD4511", d.name(),
                        d.firstPin() + ".." + d.occupiedPins().getLast() + " (inicial " + d.initialDigit() + ")",
                        StudioTheme.ACCENT_TEAL, () -> removeHardwareItem(0, compIdx)));
                case Hardware.PwmOutput p -> outputs.add(hwItemRow("PWM", p.name(),
                        p.pin() + " · " + p.channel().name() + " (duty " + p.initialDuty() + ")",
                        StudioTheme.ACCENT_TEAL, () -> removeHardwareItem(0, compIdx)));
                case Hardware.UartPort u -> comm.add(hwItemRow("UART", u.name(),
                        u.baud().bps() + " bps · " + u.mode() + " (PD0/PD1)",
                        StudioTheme.ACCENT_COPPER_GLOW, () -> removeHardwareItem(0, compIdx)));
            }
        }
        for (int i = 0; i < document.vars().size(); i++) {
            State.VarDecl v = document.vars().get(i);
            final int idx = i;
            String pol = v.policy() == State.VarPolicy.WRAP ? "circular" : "tope";
            state.add(hwItemRow("Variable", v.name(),
                    "Rango " + v.min() + ".." + v.max() + " (inicial " + v.initial() + ", " + pol + ")",
                    StudioTheme.TEXT_MUTED, () -> removeHardwareItem(1, idx)));
        }
        for (int i = 0; i < document.phaseGroups().size(); i++) {
            State.PhaseGroup g = document.phaseGroups().get(i);
            final int idx = i;
            state.add(hwItemRow("FSM", g.name(),
                    String.join(" -> ", g.phases()),
                    StudioTheme.ACCENT_TEAL, () -> removeHardwareItem(2, idx)));
        }
        for (int i = 0; i < document.timers().size(); i++) {
            State.TimerDecl t = document.timers().get(i);
            final int idx = i;
            state.add(hwItemRow("Timer", t.name(),
                    "Cooperativo en milisegundos",
                    StudioTheme.ACCENT_COPPER_GLOW, () -> removeHardwareItem(3, idx)));
        }

        addHwSection("Entradas", inputs, ComponentDialog.Kind.BUTTON);
        addHwSection("Salidas", outputs, ComponentDialog.Kind.LED);
        if (!comm.isEmpty()) addHwSection("Comunicación", comm, ComponentDialog.Kind.UART);
        addHwSection("Estado y tiempo", state, ComponentDialog.Kind.VAR);

        hwContainer.revalidate();
        hwContainer.repaint();
    }

    private void addHwSection(String title, List<JPanel> items, ComponentDialog.Kind quickKind) {
        JPanel hdr = new JPanel(new BorderLayout());
        hdr.setOpaque(false);
        hdr.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, StudioTheme.BORDER_COLOR),
                new EmptyBorder(10, 2, 5, 2)));
        hdr.setAlignmentX(LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(title + " (" + items.size() + ")");
        lbl.setFont(StudioTheme.uiFont(Font.BOLD, 12.5f));
        lbl.setForeground(StudioTheme.TEXT_MUTED);
        JButton quick = new JButton("+");
        StudioTheme.styleFlatLinkButton(quick, StudioTheme.ACCENT_TEAL_BRIGHT);
        quick.setToolTipText("Agregar a " + title.toLowerCase(Locale.ROOT));
        quick.addActionListener(e -> addHardwareOrState(quickKind));

        hdr.add(lbl, BorderLayout.WEST);
        hdr.add(quick, BorderLayout.EAST);
        hwContainer.add(hdr);

        if (items.isEmpty()) {
            JLabel empty = new JLabel("Sin elementos definidos");
            empty.setFont(StudioTheme.uiFont(Font.PLAIN, 12f));
            empty.setForeground(StudioTheme.TEXT_MUTED);
            empty.setBorder(new EmptyBorder(2, 4, 10, 4));
            empty.setAlignmentX(LEFT_ALIGNMENT);
            hwContainer.add(empty);
        } else {
            for (JPanel row : items) {
                hwContainer.add(row);
            }
            hwContainer.add(Box.createVerticalStrut(6));
        }
    }

    private JPanel hwItemRow(String kind, String name, String detail, Color kindColor, Runnable onRemove) {
        StudioTheme.RoundedPanel row = new StudioTheme.RoundedPanel(new BorderLayout(6, 2))
                .hairlineBottom(true)
                .fill(StudioTheme.CANVAS_BG)
                .stroke(new Color(28, 32, 30));
        row.setBorder(new EmptyBorder(7, 4, 7, 2));
        row.setAlignmentX(LEFT_ALIGNMENT);

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);

        JLabel nameLbl = new JLabel(name);
        nameLbl.setFont(StudioTheme.uiFont(Font.BOLD, 13f));
        nameLbl.setForeground(StudioTheme.TEXT_PRIMARY);

        JPanel rightMeta = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightMeta.setOpaque(false);
        JLabel kindLbl = new JLabel(kind);
        kindLbl.setFont(StudioTheme.uiFont(Font.PLAIN, 12f));
        kindLbl.setForeground(kindColor);

        JButton del = new JButton("×");
        StudioTheme.styleFlatLinkButton(del, StudioTheme.TEXT_MUTED);
        del.setToolTipText("Quitar " + name);
        del.addActionListener(e -> onRemove.run());

        rightMeta.add(kindLbl);
        rightMeta.add(del);

        top.add(nameLbl, BorderLayout.CENTER);
        top.add(rightMeta, BorderLayout.EAST);

        JTextArea det = new JTextArea(detail);
        det.setEditable(false);
        det.setLineWrap(true);
        det.setWrapStyleWord(true);
        det.setOpaque(false);
        det.setFont(StudioTheme.codeFont(11.5f));
        det.setForeground(StudioTheme.TEXT_MUTED);
        det.setBorder(new EmptyBorder(1, 0, 0, 2));

        row.add(top, BorderLayout.NORTH);
        row.add(det, BorderLayout.CENTER);
        return row;
    }

    // ==================== Lista editorial de Reglas (estilo Lean 4) ====================

    private void rebuildRuleCards() {
        rulesContainer.removeAll();
        ruleCardPanels.clear();
        ruleCardRoles.clear();
        int n = document.rules().size();
        rulesCountLabel.setText(n == 0
                ? "Define el comportamiento con una intención guiada o una regla manual"
                : n + (n == 1 ? " regla en orden de evaluación" : " reglas en orden de evaluación")
                  + " · clic para trazabilidad · doble clic para editar");

        if (n == 0) {
            rulesContainer.add(buildEmptyRulesCard());
        } else {
            // Filete superior de la lista editorial (igual que Lean 4 Academy)
            JSeparator topLine = new JSeparator(SwingConstants.HORIZONTAL);
            topLine.setForeground(StudioTheme.BORDER_COLOR);
            topLine.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
            topLine.setAlignmentX(LEFT_ALIGNMENT);
            rulesContainer.add(topLine);
            for (int i = 0; i < n; i++) {
                rulesContainer.add(buildRuleCard(document.rules().get(i), i, n));
            }
        }
        rulesContainer.revalidate();
        rulesContainer.repaint();
    }

    private JPanel buildEmptyRulesCard() {
        StudioTheme.RoundedPanel empty = new StudioTheme.RoundedPanel(new BorderLayout(0, 10))
                .fill(StudioTheme.SURFACE_BG).stroke(StudioTheme.BORDER_COLOR).arc(6);
        empty.setBorder(new EmptyBorder(20, 18, 20, 18));
        empty.setAlignmentX(LEFT_ALIGNMENT);

        JLabel title = new JLabel("Describe el comportamiento del microcontrolador");
        title.setFont(StudioTheme.serifFont(Font.BOLD, 15.5f));
        title.setForeground(StudioTheme.TEXT_PRIMARY);

        JTextArea desc = wrapText(
                "Usa «Agregar intención» para insertar patrones listos (cada N pulsaciones, doble clic, contador 4511 con pausa, PWM o UART) o crea una «Regla manual» con el modelo completo de 5 bloques.",
                StudioTheme.uiFont(Font.PLAIN, 13f), StudioTheme.TEXT_SECONDARY);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btns.setOpaque(false);
        JButton b1 = new JButton("Agregar intención");
        JButton b2 = new JButton("Regla manual");
        StudioTheme.styleCopperPrimary(b1);
        StudioTheme.styleTealOutline(b2);
        b1.addActionListener(e -> applyPattern());
        b2.addActionListener(e -> addRule());
        btns.add(b1);
        btns.add(b2);

        empty.add(title, BorderLayout.NORTH);
        empty.add(desc, BorderLayout.CENTER);
        empty.add(btns, BorderLayout.SOUTH);
        return empty;
    }

    private JPanel buildRuleCard(Rule r, int idx, int total) {
        StudioTheme.Role role = NaturalLanguage.triggerRole(r.trigger());
        StudioTheme.RoundedPanel row = new StudioTheme.RoundedPanel(new BorderLayout(12, 0))
                .hairlineBottom(true)
                .fill(StudioTheme.CANVAS_BG)
                .stroke(StudioTheme.BORDER_COLOR);
        row.setBorder(new EmptyBorder(13, 12, 13, 8));
        row.setAlignmentX(LEFT_ALIGNMENT);

        // Columna izquierda: índice secuencial real 01, 02, 03 (como en Lean 4)
        JLabel ordinal = new JLabel(String.format("%02d", idx + 1));
        ordinal.setFont(StudioTheme.codeFont(12.5f));
        ordinal.setForeground(StudioTheme.TEXT_MUTED);
        ordinal.setVerticalAlignment(SwingConstants.TOP);
        ordinal.setBorder(new EmptyBorder(3, 2, 0, 0));
        ordinal.setPreferredSize(new Dimension(28, 22));

        // Columna central + cabecera de regla
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel headerRow = new JPanel(new BorderLayout(8, 0));
        headerRow.setOpaque(false);
        headerRow.setAlignmentX(LEFT_ALIGNMENT);

        JLabel trigLbl = new JLabel(NaturalLanguage.triggerBadge(r.trigger()));
        trigLbl.setFont(StudioTheme.uiFont(Font.BOLD, 12f));
        trigLbl.setForeground(StudioTheme.ACCENT_TEAL_BRIGHT);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightControls.setOpaque(false);

        JButton bEdit = new JButton("Editar");
        StudioTheme.styleFlatLinkButton(bEdit, StudioTheme.TEXT_SECONDARY);
        bEdit.addActionListener(e -> editRuleAt(idx));

        JButton bMore = new JButton();
        bMore.setIcon(new StudioTheme.StudioIcon(StudioTheme.StudioIcon.Kind.MORE, 13, StudioTheme.TEXT_MUTED));
        StudioTheme.styleFlatLinkButton(bMore, StudioTheme.TEXT_MUTED);
        bMore.setToolTipText("Más acciones de la regla");
        bMore.addActionListener(e -> showRuleActionsMenu(bMore, idx, total));

        rightControls.add(bEdit);
        rightControls.add(bMore);

        headerRow.add(trigLbl, BorderLayout.WEST);
        headerRow.add(rightControls, BorderLayout.EAST);
        content.add(headerRow);

        JTextArea titleArea = wrapText(r.title(), StudioTheme.serifFont(Font.BOLD, 15f), StudioTheme.TEXT_PRIMARY);
        titleArea.setBorder(new EmptyBorder(1, 0, 5, 0));
        content.add(titleArea);

        content.add(semanticRow("Cuando", StudioTheme.TEXT_MUTED, NaturalLanguage.describeTrigger(r.trigger())));
        if (!(r.guard() instanceof Expr.Truth t && t.value())) {
            content.add(Box.createVerticalStrut(3));
            content.add(semanticRow("Si", StudioTheme.ACCENT_TEAL, NaturalLanguage.describeBool(r.guard())));
        }
        content.add(Box.createVerticalStrut(3));
        content.add(semanticRow("Entonces", StudioTheme.TEXT_MUTED, NaturalLanguage.describeEffectsList(r.thenEffects())));
        if (!r.elseEffects().isEmpty()) {
            content.add(Box.createVerticalStrut(3));
            content.add(semanticRow("Si no", StudioTheme.ACCENT_COPPER_GLOW, NaturalLanguage.describeEffectsList(r.elseEffects())));
        }

        // Referencias físicas en texto monoespaciado limpio (cero chips grises)
        List<String> refs = NaturalLanguage.referencedNames(r);
        if (!refs.isEmpty()) {
            JLabel refsLbl = new JLabel(String.join(" · ", refs));
            refsLbl.setFont(StudioTheme.codeFont(11.5f));
            refsLbl.setForeground(StudioTheme.TEXT_MUTED);
            refsLbl.setAlignmentX(LEFT_ALIGNMENT);
            refsLbl.setBorder(new EmptyBorder(6, 0, 0, 0));
            content.add(refsLbl);
        }

        MouseAdapter cardClick = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editRuleAt(idx);
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    selectRule(idx == selectedRuleIndex ? -1 : idx);
                }
            }
            @Override public void mouseEntered(MouseEvent e) {
                if (idx != selectedRuleIndex) {
                    row.fill(new Color(20, 24, 22));
                }
            }
            @Override public void mouseExited(MouseEvent e) {
                if (idx != selectedRuleIndex) {
                    row.fill(StudioTheme.CANVAS_BG);
                }
            }
        };
        row.addMouseListener(cardClick);
        titleArea.addMouseListener(cardClick);
        ordinal.addMouseListener(cardClick);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        row.add(ordinal, BorderLayout.WEST);
        row.add(content, BorderLayout.CENTER);
        ruleCardPanels.add(row);
        ruleCardRoles.add(role);
        return row;
    }

    private void showRuleActionsMenu(Component anchor, int idx, int total) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem mEdit = new JMenuItem("Editar regla");
        JMenuItem mDup = new JMenuItem("Duplicar regla");
        JMenuItem mUp = new JMenuItem("Subir en orden de evaluación");
        JMenuItem mDn = new JMenuItem("Bajar en orden de evaluación");
        JMenuItem mDel = new JMenuItem("Eliminar regla");
        mUp.setEnabled(idx > 0);
        mDn.setEnabled(idx + 1 < total);
        mDel.setForeground(StudioTheme.ACCENT_ROSE);

        mEdit.addActionListener(e -> editRuleAt(idx));
        mDup.addActionListener(e -> duplicateRuleAt(idx));
        mUp.addActionListener(e -> moveRuleAt(idx, -1));
        mDn.addActionListener(e -> moveRuleAt(idx, 1));
        mDel.addActionListener(e -> removeRuleAt(idx));

        menu.add(mEdit);
        menu.add(mDup);
        menu.addSeparator();
        menu.add(mUp);
        menu.add(mDn);
        menu.addSeparator();
        menu.add(mDel);
        menu.show(anchor, 0, anchor.getHeight() + 2);
    }

    private static JPanel semanticRow(String labelText, Color labelColor, String text) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);

        JLabel key = new JLabel(labelText);
        key.setFont(StudioTheme.uiFont(Font.BOLD, 12f));
        key.setForeground(labelColor);
        key.setVerticalAlignment(SwingConstants.TOP);
        key.setBorder(new EmptyBorder(1, 0, 0, 0));
        key.setPreferredSize(new Dimension(66, 20));

        JTextArea area = wrapText(text, StudioTheme.uiFont(Font.PLAIN, 13f), StudioTheme.TEXT_SECONDARY);
        row.add(key, BorderLayout.WEST);
        row.add(area, BorderLayout.CENTER);
        return row;
    }

    private static JTextArea wrapText(String text, Font font, Color fg) {
        JTextArea a = new JTextArea(text);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        a.setFont(font);
        a.setForeground(fg);
        a.setAlignmentX(LEFT_ALIGNMENT);
        return a;
    }

    // ==================== Menús y acciones ====================

    private void showFileMenu(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem mNew = new JMenuItem("Nuevo proyecto en blanco");
        JMenuItem mOpen = new JMenuItem("Abrir proyecto (.avr2.json)");
        JMenuItem mSave = new JMenuItem("Guardar proyecto (.avr2.json)");
        JMenuItem mLoadHex = new JMenuItem("Cargar firmware .hex externo en simavr…");
        mNew.addActionListener(e -> newProject());
        mOpen.addActionListener(e -> openProject());
        mSave.addActionListener(e -> saveProject());
        mLoadHex.addActionListener(e -> openExternalHexDialog());
        menu.add(mNew);
        menu.add(mOpen);
        menu.add(mSave);
        menu.addSeparator();
        menu.add(mLoadHex);
        menu.show(anchor, 0, anchor.getHeight() + 2);
    }

    private void openExternalHexDialog() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Firmware Intel HEX (*.hex)", "hex"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            loadExternalHex(fc.getSelectedFile().toPath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo cargar el archivo .hex:\n" + ex.getMessage(),
                    "HEX inválido", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showExportMenu(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem mC = new JMenuItem("Exportar código C99 (.c)");
        JMenuItem mAsm = new JMenuItem("Exportar código AVR ASM (.asm)");
        JMenuItem mHexC = new JMenuItem("Compilar y exportar firmware .hex (C99)");
        JMenuItem mHexAsm = new JMenuItem("Ensamblar y exportar firmware .hex (AVR ASM)");
        mC.addActionListener(e -> exportCode(true));
        mAsm.addActionListener(e -> exportCode(false));
        mHexC.addActionListener(e -> exportHexDialog(true));
        mHexAsm.addActionListener(e -> exportHexDialog(false));
        menu.add(mC);
        menu.add(mAsm);
        menu.addSeparator();
        menu.add(mHexC);
        menu.add(mHexAsm);
        menu.show(anchor, 0, anchor.getHeight() + 2);
    }

    private void showExamplesMenu(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        for (ProgramSpec example : BenchmarkCatalog.all()) {
            String pretty = NaturalLanguage.prettyProjectName(example.name());
            JMenuItem item = new JMenuItem(pretty);
            item.addActionListener(e -> requestLoadDocument(example, null, "cargar el ejemplo «" + pretty + "»"));
            menu.add(item);
        }
        menu.show(anchor, 0, anchor.getHeight() + 2);
    }

    private void newProject() {
        if (!confirmDiscardOrSave("crear un proyecto nuevo")) return;
        String name = JOptionPane.showInputDialog(this, "Nombre del nuevo proyecto:", "mi_proyecto");
        if (name == null || name.isBlank()) return;
        loadDocument(ProgramSpec.builder(name.trim()).build(), null);
    }

    private void openProject() {
        if (!confirmDiscardOrSave("abrir otro proyecto")) return;
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Proyecto Transpilador v2 (*.avr2.json)", "json"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path path = fc.getSelectedFile().toPath();
            ProgramSpec loaded = ProjectJson.parse(Files.readString(path, StandardCharsets.UTF_8));
            loadDocument(loaded, path);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo abrir: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private boolean saveProject() {
        Path target = currentFile;
        if (target == null) {
            if (GraphicsEnvironment.isHeadless() || !isDisplayable()) return false;
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new java.io.File(Toolchain.safeFileStem(document.name()) + ".avr2.json"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
            target = fc.getSelectedFile().toPath();
        }
        try {
            Files.writeString(target, ProjectJson.toJson(document), StandardCharsets.UTF_8);
            currentFile = target;
            savedDocument = document;
            updateProjectTitle();
            return true;
        } catch (IOException ex) {
            if (!GraphicsEnvironment.isHeadless() && isDisplayable()) {
                JOptionPane.showMessageDialog(this, "No se pudo guardar: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
            return false;
        }
    }

    private void exportCode(boolean isC) {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(Toolchain.safeFileStem(document.name()) + (isC ? ".c" : ".asm")));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            String code = isC
                    ? (cViewer.isManuallyModified() ? cViewer.getCode() : cEmitter.emit(document))
                    : (asmViewer.isManuallyModified() ? asmViewer.getCode() : asmEmitter.emit(document, asmProfile, asmStorageMode));
            Files.writeString(fc.getSelectedFile().toPath(), code, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo exportar: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public Path exportHexTo(boolean isC, Path destination) throws Exception {
        var check = arbiter.analyze(document);
        if (!check.valid()) {
            throw new IllegalStateException("El circuito tiene errores del Árbitro: " + check.errors().getFirst().message());
        }
        Toolchain tc = Toolchain.detect();
        Path tmp = Files.createTempDirectory("transpilador-v2-hex-");
        Toolchain.BuildArtifact art = isC
                ? tc.compileC(document.name(), cEmitter.emit(document), tmp)
                : tc.assembleAsm(document.name(), asmEmitter.emit(document, asmProfile, asmStorageMode), tmp);
        if (destination.getParent() != null) Files.createDirectories(destination.getParent());
        Files.copy(art.hexFile(), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return destination;
    }

    private void exportHexDialog(boolean isC) {
        var check = arbiter.analyze(document);
        if (!check.valid()) {
            JOptionPane.showMessageDialog(this,
                    "Corrige primero el conflicto del Árbitro antes de generar el .hex:\n" + check.errors().getFirst().message(),
                    "Circuito con errores", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Firmware Intel HEX (*.hex)", "hex"));
        fc.setSelectedFile(new java.io.File(Toolchain.safeFileStem(document.name()) + (isC ? "_c99.hex" : "_asm.hex")));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path dest = fc.getSelectedFile().toPath();
        if (!dest.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".hex")) {
            dest = dest.resolveSibling(dest.getFileName() + ".hex");
        }
        try {
            exportHexTo(isC, dest);
            String origin = isC
                    ? "C99 (avr-gcc -Os)"
                    : "AVR ASM (" + asmProfile + " · " + asmStorageMode + ")";
            JOptionPane.showMessageDialog(this,
                    "Firmware Intel .hex generado correctamente:\n" + dest
                            + "\n\nMotor: " + origin
                            + "\nReloj configurado: " + document.clockFreq().label()
                            + "\n(Configura la misma frecuencia de reloj en Proteus o tu programador avrdude).",
                    "Firmware .hex listo", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "No se pudo generar el .hex:\n" + ex.getMessage(),
                    "Error de compilación", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void addHardwareOrState(ComponentDialog.Kind initialKind) {
        ComponentDialog.showDialog(SwingUtilities.getWindowAncestor(this), document, initialKind)
                .ifPresent(this::setDocument);
    }

    private void removeHardwareItem(int bucket, int index) {
        var comps = new ArrayList<>(document.components());
        var vars = new ArrayList<>(document.vars());
        var groups = new ArrayList<>(document.phaseGroups());
        var timers = new ArrayList<>(document.timers());

        switch (bucket) {
            case 0 -> comps.remove(index);
            case 1 -> vars.remove(index);
            case 2 -> groups.remove(index);
            case 3 -> timers.remove(index);
        }

        ProgramSpec candidate = new ProgramSpec(document.name(), document.fCpuHz(), comps, vars, groups, timers, document.rules());
        var check = arbiter.analyze(candidate);
        if (!check.valid()) {
            JOptionPane.showMessageDialog(this,
                    "No se puede quitar porque alguna regla aún lo usa:\n" + check.errors().getFirst().message(),
                    "Elemento en uso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        setDocument(candidate);
    }

    private void addRule() {
        RuleBuilderDialog.showDialog(SwingUtilities.getWindowAncestor(this), document, null, -1).ifPresent(out -> {
            ProgramSpec base = out.updatedSpec();
            var nextRules = new ArrayList<>(base.rules());
            nextRules.add(out.rule());
            setDocument(new ProgramSpec(base.name(), base.fCpuHz(), base.components(),
                    base.vars(), base.phaseGroups(), base.timers(), nextRules));
        });
    }

    private void applyPattern() {
        PatternAssistantDialog.showDialog(SwingUtilities.getWindowAncestor(this), document)
                .ifPresent(this::setDocument);
    }

    private void editRuleAt(int idx) {
        if (idx < 0 || idx >= document.rules().size()) return;
        RuleBuilderDialog.showDialog(SwingUtilities.getWindowAncestor(this), document, document.rules().get(idx), idx)
                .ifPresent(out -> {
                    ProgramSpec base = out.updatedSpec();
                    var nextRules = new ArrayList<>(base.rules());
                    nextRules.set(idx, out.rule());
                    setDocument(new ProgramSpec(base.name(), base.fCpuHz(), base.components(),
                            base.vars(), base.phaseGroups(), base.timers(), nextRules));
                });
    }

    private void duplicateRuleAt(int idx) {
        if (idx < 0 || idx >= document.rules().size()) return;
        Rule orig = document.rules().get(idx);
        Rule copy = new Rule(orig.title() + " (copia)", orig.trigger(), orig.guard(), orig.thenEffects(), orig.elseEffects());
        var nextRules = new ArrayList<>(document.rules());
        nextRules.add(idx + 1, copy);
        setDocument(new ProgramSpec(document.name(), document.fCpuHz(), document.components(),
                document.vars(), document.phaseGroups(), document.timers(), nextRules));
    }

    private void moveRuleAt(int idx, int delta) {
        int target = idx + delta;
        if (idx < 0 || target < 0 || target >= document.rules().size()) return;
        var nextRules = new ArrayList<>(document.rules());
        Collections.swap(nextRules, idx, target);
        setDocument(new ProgramSpec(document.name(), document.fCpuHz(), document.components(),
                document.vars(), document.phaseGroups(), document.timers(), nextRules));
    }

    private void removeRuleAt(int idx) {
        if (idx < 0 || idx >= document.rules().size()) return;
        var nextRules = new ArrayList<>(document.rules());
        nextRules.remove(idx);
        setDocument(new ProgramSpec(document.name(), document.fCpuHz(), document.components(),
                document.vars(), document.phaseGroups(), document.timers(), nextRules));
    }

    private static final class TrackWidthPanel extends JPanel implements Scrollable {
        TrackWidthPanel(LayoutManager layout) { super(layout); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 22; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 88; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
