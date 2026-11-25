package dev.transpilador.v2.ui;

import dev.transpilador.v2.arbiter.ResourceArbiter;
import dev.transpilador.v2.model.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Constructor visual guiado de tarjetas de comportamiento (100 % del núcleo de 5 bloques):
 *  - Cero cascadas de JOptionPane: todas las condiciones y acciones se editan inline.
 *  - Soporta condiciones simples y compuestas (Y / O / XOR / NO, Compare 8/10-bit, InRange,
 *    Modulo, InPhase, TimerActive, UartByteEquals) y conserva expresiones avanzadas al editar.
 *  - Soporta los 14 tipos de Effect y todas las fuentes de valor (Const, Var, ADC 8/10, Scale, UART RX).
 *  - Permite crear componentes o estado inline sin abandonar la tarjeta.
 *  - Muestra vista previa en español natural y validación del ResourceArbiter en vivo.
 */
public final class RuleBuilderDialog extends JDialog {

    public record BuildOutcome(ProgramSpec updatedSpec, Rule rule) {}

    private ProgramSpec spec;
    private final int editingIndex;
    private BuildOutcome outcome;

    private boolean syncingUi;
    private boolean userEditedTitle;

    // Cabecera
    private final JTextField titleField = new JTextField(32);

    // 1. CUÁNDO
    private enum TriggerKind {
        ALWAYS("Continuamente en cada revisión"),
        ON_BOOT("Al encender el microcontrolador (una vez)"),
        ON_PRESS("Al presionar un botón"),
        ON_RELEASE("Al soltar un botón"),
        EVERY_MS("Cada N milisegundos"),
        ON_TIMER("Al vencer un temporizador"),
        ON_UART_RX("Al recibir un byte por UART");

        final String label;
        TriggerKind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final JComboBox<TriggerKind> triggerKindBox = new JComboBox<>(TriggerKind.values());
    private final JComboBox<String> triggerTargetBox = new JComboBox<>();
    private final JSpinner triggerMsSpinner = new JSpinner(new SpinnerNumberModel(500, 1, 65000, 50));
    private final JLabel triggerMsUnit = new JLabel("ms");
    private final JLabel triggerMissingHint = new JLabel();

    // 2. SI SE CUMPLE
    private enum GuardMode {
        NONE("Sin condición adicional (siempre que ocurra el evento)"),
        ALL("Se cumplen todas (Y / AND)"),
        ANY("Se cumple al menos una (O / OR)"),
        XOR("Se cumple exactamente una (XOR)"),
        PRESERVED("Expresión lógica avanzada preservada");

        final String label;
        GuardMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final JComboBox<GuardMode> guardModeBox = new JComboBox<>();
    private final JPanel conditionRowsBox = new JPanel();
    private final List<ConditionRow> conditionRows = new ArrayList<>();
    private final JButton btnAddCondition = new JButton("+ Agregar condición");
    private Expr.BoolExpr preservedGuard;
    private final JPanel preservedBanner = new JPanel(new BorderLayout(8, 0));
    private final JLabel preservedLabel = new JLabel();

    // 3. ENTONCES y 4. SI NO
    private final JPanel thenRowsBox = new JPanel();
    private final List<EffectRow> thenRows = new ArrayList<>();
    private final JPanel elseRowsBox = new JPanel();
    private final List<EffectRow> elseRows = new ArrayList<>();
    private final JPanel elseContentWrap = new JPanel(new BorderLayout(0, 6));
    private final JButton btnToggleElse = new JButton("+ Agregar respuesta alternativa (SI NO)");

    // Vista previa y estado del árbitro en vivo
    private final JTextArea previewArea = new JTextArea(2, 50);
    private final JLabel arbiterBanner = new JLabel();
    private final JButton saveButton = new JButton("Guardar tarjeta");

    private RuleBuilderDialog(Window owner, ProgramSpec initialSpec, Rule editing, int editingIndex) {
        super(owner, editing == null ? "Nueva tarjeta de comportamiento" : "Editar tarjeta de comportamiento",
                ModalityType.APPLICATION_MODAL);
        this.spec = Objects.requireNonNull(initialSpec, "initialSpec");
        this.editingIndex = editingIndex;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(StudioTheme.CANVAS_BG);
        root.setBorder(new EmptyBorder(14, 16, 14, 16));

        root.add(buildHeaderStrip(), BorderLayout.NORTH);
        root.add(buildScrollBody(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);

        if (editing != null) {
            userEditedTitle = true;
            loadExisting(editing);
        } else {
            syncingUi = true;
            rebuildGuardModeChoices(false);
            triggerKindBox.setSelectedItem(TriggerKind.ALWAYS);
            refreshTriggerFields();
            guardModeBox.setSelectedItem(GuardMode.NONE);
            refreshGuardArea();
            syncingUi = false;
            addEffectRow(thenRows, thenRowsBox, null);
            updateLivePreview();
        }

        setContentPane(root);
        setSize(860, 700);
        setMinimumSize(new Dimension(740, 560));
        setLocationRelativeTo(owner);
    }

    public static RuleBuilderDialog createDialog(Window owner, ProgramSpec spec, Rule editing, int editingIndex) {
        return new RuleBuilderDialog(owner, spec, editing, editingIndex);
    }

    /** Devuelve la regla construida actualmente en el diálogo (útil para verificación headless). */
    public Rule currentRule() {
        return buildCandidateRule();
    }

    public static Optional<BuildOutcome> showDialog(Window owner, ProgramSpec spec, Rule editing, int editingIndex) {
        RuleBuilderDialog dlg = new RuleBuilderDialog(owner, spec, editing, editingIndex);
        dlg.setVisible(true);
        return Optional.ofNullable(dlg.outcome);
    }

    public static Optional<Rule> showDialog(Window owner, ProgramSpec spec, Rule editing) {
        return showDialog(owner, spec, editing, -1).map(BuildOutcome::rule);
    }

    private JPanel buildHeaderStrip() {
        StudioTheme.RoundedPanel card = new StudioTheme.RoundedPanel(new BorderLayout(0, 8))
                .fill(StudioTheme.SURFACE_BG).stroke(StudioTheme.BORDER_COLOR);
        card.setBorder(new EmptyBorder(10, 14, 10, 14));

        JPanel titleRow = new JPanel(new BorderLayout(10, 0));
        titleRow.setOpaque(false);
        JLabel lbl = new JLabel("Nombre de la tarjeta:");
        lbl.setFont(StudioTheme.uiFont(Font.BOLD, 12.5f));
        lbl.setForeground(StudioTheme.TEXT_PRIMARY);
        titleField.setFont(StudioTheme.uiFont(Font.PLAIN, 13f));
        titleField.getDocument().addDocumentListener(simpleDocListener(() -> {
            if (!syncingUi) userEditedTitle = !titleField.getText().isBlank();
        }));
        titleRow.add(lbl, BorderLayout.WEST);
        titleRow.add(titleField, BorderLayout.CENTER);

        // Barra inline para crear componentes/estado sin salir del diálogo
        JPanel quickAdd = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        quickAdd.setOpaque(false);
        JLabel qLbl = new JLabel("¿Te falta algo en el circuito? Crear aquí:");
        qLbl.setFont(StudioTheme.uiFont(Font.PLAIN, 11.5f));
        qLbl.setForeground(StudioTheme.TEXT_MUTED);
        quickAdd.add(qLbl);
        quickAdd.add(quickAddBtn("+ Botón", ComponentDialog.Kind.BUTTON, StudioTheme.Role.INPUT));
        quickAdd.add(quickAddBtn("+ LED", ComponentDialog.Kind.LED, StudioTheme.Role.OUTPUT));
        quickAdd.add(quickAddBtn("+ Display", ComponentDialog.Kind.BCD, StudioTheme.Role.OUTPUT));
        quickAdd.add(quickAddBtn("+ Contador", ComponentDialog.Kind.VAR, StudioTheme.Role.STATE));
        quickAdd.add(quickAddBtn("+ Fases FSM", ComponentDialog.Kind.PHASES, StudioTheme.Role.LOGIC));
        quickAdd.add(quickAddBtn("+ Timer", ComponentDialog.Kind.TIMER, StudioTheme.Role.ALT));

        card.add(titleRow, BorderLayout.NORTH);
        card.add(quickAdd, BorderLayout.SOUTH);
        return card;
    }

    private JButton quickAddBtn(String text, ComponentDialog.Kind kind, StudioTheme.Role role) {
        JButton b = new JButton(text);
        StudioTheme.styleSoft(b, role);
        b.addActionListener(e -> ComponentDialog.showDialog(this, spec, kind).ifPresent(next -> {
            this.spec = next;
            refreshAllDynamicOptions();
            updateLivePreview();
        }));
        return b;
    }

    private JScrollPane buildScrollBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        body.add(buildTriggerSection());
        body.add(Box.createVerticalStrut(10));
        body.add(buildGuardSection());
        body.add(Box.createVerticalStrut(10));
        body.add(buildEffectsSection("Entonces", "Acciones que se ejecutan en orden", StudioTheme.Role.OUTPUT, thenRows, thenRowsBox, false));
        body.add(Box.createVerticalStrut(10));
        body.add(buildElseSection());

        JPanel wrapper = new ScrollableTrackPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(body, BorderLayout.NORTH);

        JScrollPane sp = new JScrollPane(wrapper);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(20);
        return sp;
    }

    private JPanel buildTriggerSection() {
        JPanel content = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        content.setOpaque(false);

        triggerKindBox.addActionListener(e -> {
            if (syncingUi) return;
            refreshTriggerFields();
            updateLivePreview();
        });
        triggerTargetBox.addActionListener(e -> { if (!syncingUi) updateLivePreview(); });
        triggerMsSpinner.addChangeListener(e -> { if (!syncingUi) updateLivePreview(); });
        triggerMissingHint.setForeground(StudioTheme.ACCENT_ROSE);
        triggerMissingHint.setFont(StudioTheme.uiFont(Font.BOLD, 11.5f));

        content.add(triggerKindBox);
        content.add(triggerTargetBox);
        content.add(triggerMsSpinner);
        content.add(triggerMsUnit);
        content.add(triggerMissingHint);

        return sectionCard("Cuando", "Evento o momento que dispara esta regla", StudioTheme.Role.INPUT, content, null);
    }

    private JPanel buildGuardSection() {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        modeRow.setOpaque(false);
        modeRow.setAlignmentX(LEFT_ALIGNMENT);
        modeRow.add(guardModeBox);
        StudioTheme.styleSoft(btnAddCondition, StudioTheme.Role.LOGIC);
        btnAddCondition.addActionListener(e -> {
            if (guardModeBox.getSelectedItem() == GuardMode.NONE) {
                syncingUi = true;
                guardModeBox.setSelectedItem(GuardMode.ALL);
                syncingUi = false;
            }
            addConditionRow(null);
            refreshGuardArea();
            updateLivePreview();
        });
        modeRow.add(btnAddCondition);
        content.add(modeRow);

        preservedBanner.setOpaque(true);
        preservedBanner.setBackground(StudioTheme.Role.LOGIC.softBg);
        preservedBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(StudioTheme.Role.LOGIC.border, 1, true),
                new EmptyBorder(6, 10, 6, 10)));
        preservedBanner.setAlignmentX(LEFT_ALIGNMENT);
        preservedLabel.setFont(StudioTheme.uiFont(Font.PLAIN, 12f));
        preservedLabel.setForeground(StudioTheme.Role.LOGIC.pillFg);
        JButton btnReplacePreserved = new JButton("Reemplazar con editor visual");
        StudioTheme.styleGhost(btnReplacePreserved);
        btnReplacePreserved.addActionListener(e -> {
            preservedGuard = null;
            syncingUi = true;
            rebuildGuardModeChoices(false);
            guardModeBox.setSelectedItem(GuardMode.ALL);
            syncingUi = false;
            conditionRows.clear();
            conditionRowsBox.removeAll();
            addConditionRow(null);
            refreshGuardArea();
            updateLivePreview();
        });
        preservedBanner.add(preservedLabel, BorderLayout.CENTER);
        preservedBanner.add(btnReplacePreserved, BorderLayout.EAST);
        content.add(Box.createVerticalStrut(6));
        content.add(preservedBanner);

        conditionRowsBox.setOpaque(false);
        conditionRowsBox.setLayout(new BoxLayout(conditionRowsBox, BoxLayout.Y_AXIS));
        conditionRowsBox.setAlignmentX(LEFT_ALIGNMENT);
        content.add(conditionRowsBox);

        guardModeBox.addActionListener(e -> {
            if (syncingUi) return;
            GuardMode m = (GuardMode) guardModeBox.getSelectedItem();
            if (m != GuardMode.PRESERVED) preservedGuard = null;
            if ((m == GuardMode.ALL || m == GuardMode.ANY) && conditionRows.isEmpty()) {
                addConditionRow(null);
            } else if (m == GuardMode.XOR) {
                while (conditionRows.size() < 2) addConditionRow(null);
            }
            refreshGuardArea();
            updateLivePreview();
        });

        return sectionCard("Si se cumple", "Filtro opcional (soporta Y / O / XOR / NO, rangos, módulo, FSM y timers)",
                StudioTheme.Role.LOGIC, content, null);
    }

    private JPanel buildEffectsSection(String badge, String subtitle, StudioTheme.Role role,
                                       List<EffectRow> list, JPanel rowsBox, boolean allowEmpty) {
        rowsBox.setOpaque(false);
        rowsBox.setLayout(new BoxLayout(rowsBox, BoxLayout.Y_AXIS));

        JButton addBtn = new JButton("+ Agregar acción");
        StudioTheme.styleSoft(addBtn, role);
        addBtn.addActionListener(e -> {
            addEffectRow(list, rowsBox, null);
            updateLivePreview();
        });

        return sectionCard(badge, subtitle, role, rowsBox, addBtn);
    }

    private JPanel buildElseSection() {
        elseRowsBox.setOpaque(false);
        elseRowsBox.setLayout(new BoxLayout(elseRowsBox, BoxLayout.Y_AXIS));

        JButton addElseBtn = new JButton("+ Otra acción alternativa");
        StudioTheme.styleSoft(addElseBtn, StudioTheme.Role.ALT);
        addElseBtn.addActionListener(e -> {
            addEffectRow(elseRows, elseRowsBox, null);
            refreshElseVisibility();
            updateLivePreview();
        });

        StudioTheme.styleSoft(btnToggleElse, StudioTheme.Role.ALT);
        btnToggleElse.addActionListener(e -> {
            if (elseRows.isEmpty()) addEffectRow(elseRows, elseRowsBox, null);
            refreshElseVisibility();
            updateLivePreview();
        });

        elseContentWrap.setOpaque(false);
        elseContentWrap.add(elseRowsBox, BorderLayout.CENTER);
        JPanel addWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        addWrap.setOpaque(false);
        addWrap.add(addElseBtn);
        elseContentWrap.add(addWrap, BorderLayout.SOUTH);

        JPanel container = new JPanel(new BorderLayout(0, 6));
        container.setOpaque(false);
        JPanel collapsedWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        collapsedWrap.setOpaque(false);
        collapsedWrap.add(btnToggleElse);
        container.add(collapsedWrap, BorderLayout.NORTH);
        container.add(elseContentWrap, BorderLayout.CENTER);

        return sectionCard("Si no se cumple", "Opcional · acciones cuando la condición es falsa",
                StudioTheme.Role.ALT, container, null);
    }

    private JPanel buildPreviewSection() {
        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setOpaque(false);

        previewArea.setEditable(false);
        previewArea.setLineWrap(true);
        previewArea.setWrapStyleWord(true);
        previewArea.setFont(StudioTheme.uiFont(Font.PLAIN, 13f));
        previewArea.setForeground(StudioTheme.TEXT_PRIMARY);
        previewArea.setBackground(StudioTheme.SUBTLE_BG);
        previewArea.setBorder(new EmptyBorder(6, 8, 6, 8));

        arbiterBanner.setFont(StudioTheme.uiFont(Font.BOLD, 12f));
        content.add(previewArea, BorderLayout.CENTER);
        content.add(arbiterBanner, BorderLayout.SOUTH);

        return sectionCard("Vista previa", "Resumen en español natural y validación en vivo",
                StudioTheme.Role.STATE, content, null);
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(0, 10));
        footer.setOpaque(false);
        footer.add(buildPreviewSection(), BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.setOpaque(false);
        JButton cancel = new JButton("Cancelar");
        StudioTheme.styleGhost(cancel);
        StudioTheme.styleCopperPrimary(saveButton);
        cancel.addActionListener(e -> dispose());
        saveButton.addActionListener(e -> accept());
        btns.add(cancel);
        btns.add(saveButton);
        footer.add(btns, BorderLayout.SOUTH);
        return footer;
    }

    private static JPanel sectionCard(String titleText, String subtitle, StudioTheme.Role role,
                                      JComponent content, JComponent headerRight) {
        StudioTheme.RoundedPanel card = new StudioTheme.RoundedPanel(new BorderLayout(0, 8))
                .fill(StudioTheme.SURFACE_BG).stroke(StudioTheme.BORDER_COLOR).accent(role.accent).arc(6);
        card.setBorder(new EmptyBorder(10, 14, 10, 12));
        card.setAlignmentX(LEFT_ALIGNMENT);

        JPanel hdr = new JPanel(new BorderLayout(8, 0));
        hdr.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        JLabel titleLbl = new JLabel(titleText);
        titleLbl.setFont(StudioTheme.serifFont(Font.BOLD, 14f));
        titleLbl.setForeground(role == StudioTheme.Role.STATE ? StudioTheme.TEXT_PRIMARY : StudioTheme.ACCENT_TEAL_BRIGHT);
        left.add(titleLbl);
        JLabel sub = new JLabel(subtitle);
        sub.setFont(StudioTheme.uiFont(Font.PLAIN, 12f));
        sub.setForeground(StudioTheme.TEXT_MUTED);
        left.add(sub);
        hdr.add(left, BorderLayout.WEST);
        if (headerRight != null) hdr.add(headerRight, BorderLayout.EAST);

        card.add(hdr, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    // ==================== Refresco de campos y estado ====================

    private void rebuildGuardModeChoices(boolean includePreserved) {
        guardModeBox.removeAllItems();
        guardModeBox.addItem(GuardMode.NONE);
        guardModeBox.addItem(GuardMode.ALL);
        guardModeBox.addItem(GuardMode.ANY);
        guardModeBox.addItem(GuardMode.XOR);
        if (includePreserved) guardModeBox.addItem(GuardMode.PRESERVED);
    }

    private void refreshTriggerFields() {
        TriggerKind kind = (TriggerKind) triggerKindBox.getSelectedItem();
        String prev = (String) triggerTargetBox.getSelectedItem();
        triggerTargetBox.removeAllItems();
        triggerTargetBox.setVisible(false);
        triggerMsSpinner.setVisible(false);
        triggerMsUnit.setVisible(false);
        triggerMissingHint.setText("");
        if (kind == null) return;

        switch (kind) {
            case ON_PRESS, ON_RELEASE -> {
                var btns = spec.componentsOf(Hardware.Button.class);
                btns.forEach(b -> triggerTargetBox.addItem(b.name()));
                triggerTargetBox.setVisible(!btns.isEmpty());
                if (btns.isEmpty()) triggerMissingHint.setText("Agrega primero un botón (+ Botón arriba)");
            }
            case EVERY_MS -> {
                triggerMsSpinner.setVisible(true);
                triggerMsUnit.setVisible(true);
            }
            case ON_TIMER -> {
                var timers = spec.timers();
                timers.forEach(t -> triggerTargetBox.addItem(t.name()));
                triggerTargetBox.setVisible(!timers.isEmpty());
                if (timers.isEmpty()) triggerMissingHint.setText("Agrega primero un temporizador (+ Timer arriba)");
            }
            case ON_UART_RX -> {
                var uarts = spec.componentsOf(Hardware.UartPort.class).stream()
                        .filter(u -> u.mode().usesRx()).toList();
                uarts.forEach(u -> triggerTargetBox.addItem(u.name()));
                triggerTargetBox.setVisible(!uarts.isEmpty());
                if (uarts.isEmpty()) triggerMissingHint.setText("Agrega primero un puerto UART con RX en el circuito");
            }
            case ALWAYS, ON_BOOT -> {}
        }
        if (prev != null) triggerTargetBox.setSelectedItem(prev);
    }

    private void refreshGuardArea() {
        GuardMode mode = (GuardMode) guardModeBox.getSelectedItem();
        boolean isPreserved = mode == GuardMode.PRESERVED && preservedGuard != null;
        boolean showRows = mode == GuardMode.ALL || mode == GuardMode.ANY || mode == GuardMode.XOR;

        preservedBanner.setVisible(isPreserved);
        if (isPreserved) {
            preservedLabel.setText("Condición: " + NaturalLanguage.describeBool(preservedGuard));
        }
        conditionRowsBox.setVisible(showRows);
        btnAddCondition.setVisible(!isPreserved && (mode != GuardMode.XOR || conditionRows.size() < 2));
        revalidate();
        repaint();
    }

    private void refreshElseVisibility() {
        boolean hasElse = !elseRows.isEmpty();
        elseContentWrap.setVisible(hasElse);
        btnToggleElse.setVisible(!hasElse);
        revalidate();
        repaint();
    }

    private void refreshAllDynamicOptions() {
        syncingUi = true;
        refreshTriggerFields();
        conditionRows.forEach(ConditionRow::refreshFromSpec);
        thenRows.forEach(EffectRow::refreshFromSpec);
        elseRows.forEach(EffectRow::refreshFromSpec);
        syncingUi = false;
        revalidate();
        repaint();
    }

    private void addConditionRow(Expr.BoolExpr existing) {
        ConditionRow row = new ConditionRow();
        if (existing != null) row.load(existing);
        conditionRows.add(row);
        conditionRowsBox.add(row);
        conditionRowsBox.revalidate();
        conditionRowsBox.repaint();
    }

    private void removeConditionRow(ConditionRow row) {
        conditionRows.remove(row);
        conditionRowsBox.remove(row);
        if (conditionRows.isEmpty()) {
            syncingUi = true;
            guardModeBox.setSelectedItem(GuardMode.NONE);
            syncingUi = false;
        }
        refreshGuardArea();
        updateLivePreview();
    }

    private void addEffectRow(List<EffectRow> list, JPanel container, Effect existing) {
        EffectRow row = new EffectRow(list, container);
        if (existing != null) row.load(existing);
        list.add(row);
        rebuildEffectContainer(list, container);
    }

    private void rebuildEffectContainer(List<EffectRow> list, JPanel container) {
        container.removeAll();
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setOrdinal(i + 1, list.size());
            container.add(list.get(i));
            if (i + 1 < list.size()) container.add(Box.createVerticalStrut(5));
        }
        container.revalidate();
        container.repaint();
        refreshElseVisibility();
    }

    // ==================== Carga fiel de regla existente ====================

    private void loadExisting(Rule r) {
        syncingUi = true;
        titleField.setText(r.title());

        switch (r.trigger()) {
            case Rule.Always ignored -> triggerKindBox.setSelectedItem(TriggerKind.ALWAYS);
            case Rule.OnBoot ignored -> triggerKindBox.setSelectedItem(TriggerKind.ON_BOOT);
            case Rule.EveryMs e -> {
                triggerKindBox.setSelectedItem(TriggerKind.EVERY_MS);
                triggerMsSpinner.setValue(e.periodMs());
            }
            case Rule.OnPress p -> {
                triggerKindBox.setSelectedItem(TriggerKind.ON_PRESS);
                refreshTriggerFields();
                triggerTargetBox.setSelectedItem(p.buttonName());
            }
            case Rule.OnRelease rel -> {
                triggerKindBox.setSelectedItem(TriggerKind.ON_RELEASE);
                refreshTriggerFields();
                triggerTargetBox.setSelectedItem(rel.buttonName());
            }
            case Rule.OnTimerExpired te -> {
                triggerKindBox.setSelectedItem(TriggerKind.ON_TIMER);
                refreshTriggerFields();
                triggerTargetBox.setSelectedItem(te.timerName());
            }
            case Rule.OnUartRx u -> {
                triggerKindBox.setSelectedItem(TriggerKind.ON_UART_RX);
                refreshTriggerFields();
                triggerTargetBox.setSelectedItem(u.uartName());
            }
        }
        refreshTriggerFields();

        loadExistingGuard(r.guard());

        for (Effect e : r.thenEffects()) addEffectRow(thenRows, thenRowsBox, e);
        for (Effect e : r.elseEffects()) addEffectRow(elseRows, elseRowsBox, e);
        refreshElseVisibility();

        syncingUi = false;
        updateLivePreview();
    }

    private void loadExistingGuard(Expr.BoolExpr g) {
        conditionRows.clear();
        conditionRowsBox.removeAll();
        preservedGuard = null;

        if (g instanceof Expr.Truth t && t.value()) {
            rebuildGuardModeChoices(false);
            guardModeBox.setSelectedItem(GuardMode.NONE);
        } else if (isSingleClause(g)) {
            rebuildGuardModeChoices(false);
            guardModeBox.setSelectedItem(GuardMode.ALL);
            addConditionRow(g);
        } else if (g instanceof Expr.And a && a.items().stream().allMatch(RuleBuilderDialog::isSingleClause)) {
            rebuildGuardModeChoices(false);
            guardModeBox.setSelectedItem(GuardMode.ALL);
            a.items().forEach(this::addConditionRow);
        } else if (g instanceof Expr.Or o && o.items().stream().allMatch(RuleBuilderDialog::isSingleClause)) {
            rebuildGuardModeChoices(false);
            guardModeBox.setSelectedItem(GuardMode.ANY);
            o.items().forEach(this::addConditionRow);
        } else if (g instanceof Expr.Xor x && isSingleClause(x.left()) && isSingleClause(x.right())) {
            rebuildGuardModeChoices(false);
            guardModeBox.setSelectedItem(GuardMode.XOR);
            addConditionRow(x.left());
            addConditionRow(x.right());
        } else {
            preservedGuard = g;
            rebuildGuardModeChoices(true);
            guardModeBox.setSelectedItem(GuardMode.PRESERVED);
        }
        refreshGuardArea();
    }

    private static boolean isSingleClause(Expr.BoolExpr b) {
        if (b instanceof Expr.Not n) return isAtomicPredicate(n.inner());
        return isAtomicPredicate(b);
    }

    private static boolean isAtomicPredicate(Expr.BoolExpr b) {
        return b instanceof Expr.Pressed
                || b instanceof Expr.Compare
                || b instanceof Expr.InRange
                || b instanceof Expr.Modulo
                || b instanceof Expr.InPhase
                || b instanceof Expr.TimerActive
                || b instanceof Expr.UartByteEquals;
    }

    // ==================== Construcción y validación en vivo ====================

    private Rule buildCandidateRule() {
        Rule.Trigger trig = buildTrigger();
        Expr.BoolExpr guard = buildGuard();
        List<Effect> thenList = new ArrayList<>();
        for (EffectRow row : thenRows) thenList.add(row.buildEffect());
        List<Effect> elseList = new ArrayList<>();
        for (EffectRow row : elseRows) elseList.add(row.buildEffect());

        String title = titleField.getText().trim();
        if (title.isEmpty()) title = NaturalLanguage.suggestTitle(trig, guard, thenList);
        return new Rule(title, trig, guard, thenList, elseList);
    }

    private Rule.Trigger buildTrigger() {
        TriggerKind kind = (TriggerKind) triggerKindBox.getSelectedItem();
        String target = (String) triggerTargetBox.getSelectedItem();
        if (kind == null) return Rule.always();
        return switch (kind) {
            case ALWAYS -> Rule.always();
            case ON_BOOT -> Rule.onBoot();
            case EVERY_MS -> Rule.everyMs((Integer) triggerMsSpinner.getValue());
            case ON_PRESS -> {
                if (target == null) throw new IllegalStateException("Selecciona o crea un botón para el disparador.");
                yield Rule.onPress(target);
            }
            case ON_RELEASE -> {
                if (target == null) throw new IllegalStateException("Selecciona o crea un botón para el disparador.");
                yield Rule.onRelease(target);
            }
            case ON_TIMER -> {
                if (target == null) throw new IllegalStateException("Selecciona o crea un temporizador para el disparador.");
                yield Rule.onTimer(target);
            }
            case ON_UART_RX -> {
                if (target == null) throw new IllegalStateException("Agrega un puerto UART con RX al circuito.");
                yield Rule.onUartRx(target);
            }
        };
    }

    private Expr.BoolExpr buildGuard() {
        GuardMode mode = (GuardMode) guardModeBox.getSelectedItem();
        if (mode == null || mode == GuardMode.NONE) return Expr.alwaysTrue();
        if (mode == GuardMode.PRESERVED && preservedGuard != null) return preservedGuard;
        if (conditionRows.isEmpty()) return Expr.alwaysTrue();

        List<Expr.BoolExpr> built = new ArrayList<>();
        for (ConditionRow row : conditionRows) built.add(row.buildExpr());
        return switch (mode) {
            case ALL -> built.size() == 1 ? built.getFirst() : Expr.and(built.toArray(Expr.BoolExpr[]::new));
            case ANY -> built.size() == 1 ? built.getFirst() : Expr.or(built.toArray(Expr.BoolExpr[]::new));
            case XOR -> {
                if (built.size() != 2) throw new IllegalStateException("XOR requiere exactamente 2 condiciones.");
                yield Expr.xor(built.get(0), built.get(1));
            }
            default -> Expr.alwaysTrue();
        };
    }

    private void updateLivePreview() {
        if (syncingUi) return;
        try {
            Rule candidate = buildCandidateRule();
            if (!userEditedTitle) {
                syncingUi = true;
                titleField.setText(NaturalLanguage.suggestTitle(candidate.trigger(), candidate.guard(), candidate.thenEffects()));
                syncingUi = false;
            }
            previewArea.setText(NaturalLanguage.describeRuleNatural(candidate));

            // Validar contra ResourceArbiter con la regla insertada/actualizada
            var nextRules = new ArrayList<>(spec.rules());
            if (editingIndex >= 0 && editingIndex < nextRules.size()) {
                nextRules.set(editingIndex, candidate);
            } else {
                nextRules.add(candidate);
            }
            ProgramSpec testSpec = new ProgramSpec(spec.name(), spec.fCpuHz(), spec.components(),
                    spec.vars(), spec.phaseGroups(), spec.timers(), nextRules);
            var arb = new ResourceArbiter().analyze(testSpec);
            if (arb.valid()) {
                arbiterBanner.setForeground(StudioTheme.ACCENT_EMERALD);
                arbiterBanner.setIcon(new StudioTheme.StudioIcon(StudioTheme.StudioIcon.Kind.CHECK, 13, StudioTheme.ACCENT_EMERALD));
                arbiterBanner.setIconTextGap(6);
                arbiterBanner.setText("Tarjeta válida · compatible con C99, AVR ASM y simavr.");
                saveButton.setEnabled(true);
            } else {
                arbiterBanner.setForeground(StudioTheme.ACCENT_ROSE);
                arbiterBanner.setIcon(new StudioTheme.StudioIcon(StudioTheme.StudioIcon.Kind.WARN, 13, StudioTheme.ACCENT_ROSE));
                arbiterBanner.setIconTextGap(6);
                arbiterBanner.setText(arb.errors().getFirst().message());
                saveButton.setEnabled(false);
            }
        } catch (Exception ex) {
            previewArea.setText("Completa los campos marcados para ver el comportamiento.");
            arbiterBanner.setForeground(StudioTheme.ACCENT_AMBER.darker());
            arbiterBanner.setIcon(new StudioTheme.StudioIcon(StudioTheme.StudioIcon.Kind.WARN, 13, StudioTheme.ACCENT_AMBER.darker()));
            arbiterBanner.setIconTextGap(6);
            arbiterBanner.setText(ex.getMessage());
            saveButton.setEnabled(false);
        }
    }

    private void accept() {
        try {
            Rule built = buildCandidateRule();
            this.outcome = new BuildOutcome(spec, built);
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Revisar tarjeta", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ==================== Subcomponente inline: Cláusula de condición ====================

    private enum CondKind {
        BTN_PRESSED("Botón presionado"),
        COMPARE("Comparar valores"),
        IN_RANGE("Valor en rango [min..max]"),
        MODULO("Múltiplo / Módulo (%)"),
        IN_PHASE("Está en fase FSM"),
        TIMER_ACTIVE("Temporizador corriendo"),
        UART_BYTE("Byte UART recibido es");

        final String label;
        CondKind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final class ConditionRow extends JPanel {
        private final JCheckBox notBox = new JCheckBox("NO");
        private final JComboBox<CondKind> kindBox = new JComboBox<>(CondKind.values());
        private final JPanel paramsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));

        // Controles reutilizables
        private final JComboBox<String> targetBox = new JComboBox<>();
        private final JComboBox<String> phaseBox = new JComboBox<>();
        private final ValuePicker leftVal = new ValuePicker(true, false, false);
        private final JComboBox<Expr.CmpOp> opBox = new JComboBox<>(Expr.CmpOp.values());
        private final ValuePicker rightVal = new ValuePicker(true, false, false);
        private final ValuePicker rangeVal = new ValuePicker(true, false, false);
        private final JSpinner minSpin = new JSpinner(new SpinnerNumberModel(0, 0, 1023, 1));
        private final JSpinner maxSpin = new JSpinner(new SpinnerNumberModel(9, 0, 1023, 1));
        private final JSpinner modDivSpin = new JSpinner(new SpinnerNumberModel(3, 2, 255, 1));
        private final JSpinner modRemSpin = new JSpinner(new SpinnerNumberModel(0, 0, 254, 1));
        private final JCheckBox modIncZero = new JCheckBox("incluir 0", true);
        private final JTextField uartCharField = new JTextField("'a'", 5);

        ConditionRow() {
            super(new BorderLayout(6, 0));
            setOpaque(true);
            setBackground(StudioTheme.SUBTLE_BG);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(4, 0, 2, 0),
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(StudioTheme.BORDER_COLOR, 1, true),
                            new EmptyBorder(5, 8, 5, 8))));
            setAlignmentX(LEFT_ALIGNMENT);

            notBox.setOpaque(false);
            notBox.setFont(StudioTheme.uiFont(Font.BOLD, 11f));
            notBox.setForeground(StudioTheme.ACCENT_VIOLET);
            notBox.setToolTipText("Negar esta condición (NO)");
            notBox.addActionListener(e -> { if (!syncingUi) updateLivePreview(); });

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            left.setOpaque(false);
            left.add(notBox);
            left.add(kindBox);

            paramsPanel.setOpaque(false);
            opBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                    JLabel lbl = (JLabel) super.getListCellRendererComponent(l, v, i, s, f);
                    if (v instanceof Expr.CmpOp op) lbl.setText(NaturalLanguage.describeOp(op));
                    return lbl;
                }
            });

            kindBox.addActionListener(e -> { rebuildParams(); if (!syncingUi) updateLivePreview(); });
            targetBox.addActionListener(e -> {
                if (kindBox.getSelectedItem() == CondKind.IN_PHASE) populatePhases();
                if (!syncingUi) updateLivePreview();
            });
            phaseBox.addActionListener(e -> { if (!syncingUi) updateLivePreview(); });
            opBox.addActionListener(e -> { if (!syncingUi) updateLivePreview(); });
            minSpin.addChangeListener(e -> { if (!syncingUi) updateLivePreview(); });
            maxSpin.addChangeListener(e -> { if (!syncingUi) updateLivePreview(); });
            modDivSpin.addChangeListener(e -> {
                int div = (Integer) modDivSpin.getValue();
                int rem = (Integer) modRemSpin.getValue();
                if (rem >= div) modRemSpin.setValue(div - 1);
                ((SpinnerNumberModel) modRemSpin.getModel()).setMaximum(div - 1);
                if (!syncingUi) updateLivePreview();
            });
            modRemSpin.addChangeListener(e -> { if (!syncingUi) updateLivePreview(); });
            modIncZero.setOpaque(false);
            modIncZero.addActionListener(e -> { if (!syncingUi) updateLivePreview(); });
            uartCharField.getDocument().addDocumentListener(simpleDocListener(() -> { if (!syncingUi) updateLivePreview(); }));

            JButton del = new JButton("×");
            StudioTheme.styleMiniButton(del, StudioTheme.ACCENT_ROSE);
            del.setToolTipText("Quitar esta condición");
            del.addActionListener(e -> removeConditionRow(this));

            JPanel mainLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            mainLine.setOpaque(false);
            mainLine.add(left);
            mainLine.add(paramsPanel);

            add(mainLine, BorderLayout.CENTER);
            add(del, BorderLayout.EAST);

            // Elegir un tipo por defecto que exista en el circuito actual
            pickBestDefaultKind();
            rebuildParams();
        }

        private void pickBestDefaultKind() {
            if (!spec.componentsOf(Hardware.Button.class).isEmpty()) kindBox.setSelectedItem(CondKind.BTN_PRESSED);
            else if (!spec.vars().isEmpty()) kindBox.setSelectedItem(CondKind.COMPARE);
            else if (!spec.phaseGroups().isEmpty()) kindBox.setSelectedItem(CondKind.IN_PHASE);
            else if (!spec.componentsOf(Hardware.AnalogInput.class).isEmpty()) kindBox.setSelectedItem(CondKind.COMPARE);
            else if (!spec.timers().isEmpty()) kindBox.setSelectedItem(CondKind.TIMER_ACTIVE);
            else kindBox.setSelectedItem(CondKind.UART_BYTE);
        }

        void refreshFromSpec() {
            leftVal.refreshFromSpec();
            rightVal.refreshFromSpec();
            rangeVal.refreshFromSpec();
            rebuildParams();
        }

        private void rebuildParams() {
            String prevTarget = (String) targetBox.getSelectedItem();
            String prevPhase = (String) phaseBox.getSelectedItem();
            paramsPanel.removeAll();
            targetBox.removeAllItems();
            CondKind k = (CondKind) kindBox.getSelectedItem();
            if (k == null) return;

            switch (k) {
                case BTN_PRESSED -> {
                    spec.componentsOf(Hardware.Button.class).forEach(b -> targetBox.addItem(b.name()));
                    paramsPanel.add(targetBox);
                }
                case COMPARE -> {
                    leftVal.refreshFromSpec();
                    rightVal.refreshFromSpec();
                    paramsPanel.add(leftVal);
                    paramsPanel.add(opBox);
                    paramsPanel.add(rightVal);
                }
                case IN_RANGE -> {
                    rangeVal.refreshFromSpec();
                    paramsPanel.add(rangeVal);
                    paramsPanel.add(new JLabel("entre"));
                    paramsPanel.add(minSpin);
                    paramsPanel.add(new JLabel("y"));
                    paramsPanel.add(maxSpin);
                }
                case MODULO -> {
                    spec.vars().forEach(v -> targetBox.addItem(v.name()));
                    paramsPanel.add(targetBox);
                    paramsPanel.add(new JLabel("%"));
                    paramsPanel.add(modDivSpin);
                    paramsPanel.add(new JLabel("="));
                    paramsPanel.add(modRemSpin);
                    paramsPanel.add(modIncZero);
                }
                case IN_PHASE -> {
                    spec.phaseGroups().forEach(g -> targetBox.addItem(g.name()));
                    if (prevTarget != null) targetBox.setSelectedItem(prevTarget);
                    populatePhases();
                    if (prevPhase != null) phaseBox.setSelectedItem(prevPhase);
                    paramsPanel.add(targetBox);
                    paramsPanel.add(new JLabel("="));
                    paramsPanel.add(phaseBox);
                }
                case TIMER_ACTIVE -> {
                    spec.timers().forEach(t -> targetBox.addItem(t.name()));
                    paramsPanel.add(targetBox);
                }
                case UART_BYTE -> {
                    spec.componentsOf(Hardware.UartPort.class).stream()
                            .filter(u -> u.mode().usesRx())
                            .forEach(u -> targetBox.addItem(u.name()));
                    paramsPanel.add(targetBox);
                    paramsPanel.add(new JLabel("carácter:"));
                    paramsPanel.add(uartCharField);
                }
            }
            if (prevTarget != null && k != CondKind.IN_PHASE) targetBox.setSelectedItem(prevTarget);
            paramsPanel.revalidate();
            paramsPanel.repaint();
        }

        private void populatePhases() {
            phaseBox.removeAllItems();
            String gName = (String) targetBox.getSelectedItem();
            if (gName != null) {
                spec.phaseGroup(gName).ifPresent(g -> g.phases().forEach(phaseBox::addItem));
            }
        }

        void load(Expr.BoolExpr expr) {
            Expr.BoolExpr inner = expr;
            if (expr instanceof Expr.Not n) {
                notBox.setSelected(true);
                inner = n.inner();
            } else {
                notBox.setSelected(false);
            }
            switch (inner) {
                case Expr.Pressed p -> {
                    kindBox.setSelectedItem(CondKind.BTN_PRESSED);
                    rebuildParams();
                    targetBox.setSelectedItem(p.buttonName());
                }
                case Expr.Compare c -> {
                    kindBox.setSelectedItem(CondKind.COMPARE);
                    rebuildParams();
                    leftVal.load(c.left());
                    opBox.setSelectedItem(c.op());
                    rightVal.load(c.right());
                }
                case Expr.InRange r -> {
                    kindBox.setSelectedItem(CondKind.IN_RANGE);
                    rebuildParams();
                    rangeVal.load(r.value());
                    minSpin.setValue(r.min());
                    maxSpin.setValue(r.max());
                }
                case Expr.Modulo m -> {
                    kindBox.setSelectedItem(CondKind.MODULO);
                    rebuildParams();
                    targetBox.setSelectedItem(m.varName());
                    modDivSpin.setValue(m.divisor());
                    modRemSpin.setValue(m.remainder());
                    modIncZero.setSelected(m.includeZero());
                }
                case Expr.InPhase ip -> {
                    kindBox.setSelectedItem(CondKind.IN_PHASE);
                    rebuildParams();
                    targetBox.setSelectedItem(ip.groupName());
                    populatePhases();
                    phaseBox.setSelectedItem(ip.phaseName());
                }
                case Expr.TimerActive ta -> {
                    kindBox.setSelectedItem(CondKind.TIMER_ACTIVE);
                    rebuildParams();
                    targetBox.setSelectedItem(ta.timerName());
                }
                case Expr.UartByteEquals u -> {
                    kindBox.setSelectedItem(CondKind.UART_BYTE);
                    rebuildParams();
                    targetBox.setSelectedItem(u.uartName());
                    uartCharField.setText(formatAsciiOrInt(u.expectedByte()));
                }
                default -> {}
            }
        }

        Expr.BoolExpr buildExpr() {
            CondKind k = (CondKind) kindBox.getSelectedItem();
            String target = (String) targetBox.getSelectedItem();
            Expr.BoolExpr raw = switch (k) {
                case BTN_PRESSED -> {
                    if (target == null) throw new IllegalStateException("Falta un botón en la condición.");
                    yield Expr.pressed(target);
                }
                case COMPARE -> {
                    boolean want10 = leftVal.is10Bit() || rightVal.is10Bit();
                    Expr.ValueExpr l = leftVal.build(want10);
                    Expr.ValueExpr r = rightVal.build(want10);
                    yield Expr.cmp(l, (Expr.CmpOp) opBox.getSelectedItem(), r);
                }
                case IN_RANGE -> {
                    Expr.ValueExpr v = rangeVal.build(rangeVal.is10Bit());
                    yield new Expr.InRange(v, (Integer) minSpin.getValue(), (Integer) maxSpin.getValue());
                }
                case MODULO -> {
                    if (target == null) throw new IllegalStateException("Falta una variable/contador para el módulo.");
                    yield new Expr.Modulo(target, (Integer) modDivSpin.getValue(),
                            (Integer) modRemSpin.getValue(), modIncZero.isSelected());
                }
                case IN_PHASE -> {
                    String ph = (String) phaseBox.getSelectedItem();
                    if (target == null || ph == null) throw new IllegalStateException("Falta un grupo de fases FSM en la condición.");
                    yield Expr.inPhase(target, ph);
                }
                case TIMER_ACTIVE -> {
                    if (target == null) throw new IllegalStateException("Falta un temporizador en la condición.");
                    yield Expr.timerActive(target);
                }
                case UART_BYTE -> {
                    if (target == null) throw new IllegalStateException("Falta un puerto UART con RX.");
                    int code = parseAsciiOrInt(uartCharField.getText());
                    yield new Expr.UartByteEquals(target, code);
                }
            };
            return notBox.isSelected() ? Expr.not(raw) : raw;
        }
    }

    // ==================== Subcomponente inline: Acción (Effect) ====================

    private enum EffectKind {
        LED_ON("Encender LED"),
        LED_OFF("Apagar LED"),
        LED_TOGGLE("Alternar LED"),
        VAR_INC("Avanzar contador (+1)"),
        VAR_DEC("Retroceder contador (−1)"),
        VAR_RESET("Reiniciar contador"),
        VAR_SET("Fijar variable = valor"),
        BCD_SHOW("Mostrar en Display BCD"),
        DECIMAL2_SHOW("Mostrar 00..99 en 2 Displays"),
        PWM_SET("Fijar brillo PWM (0..255)"),
        FSM_SET("Cambiar fase FSM"),
        TIMER_START("Iniciar temporizador"),
        TIMER_STOP("Detener temporizador"),
        UART_TEXT("Enviar texto por UART"),
        UART_BYTE("Enviar byte por UART"),
        DELAY_MS("Esperar N ms (bloqueante)");

        final String label;
        EffectKind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final class EffectRow extends JPanel {
        private final List<EffectRow> ownerList;
        private final JPanel ownerContainer;
        private final JLabel ordLabel = StudioTheme.pill("1", StudioTheme.Role.STATE);
        private final JComboBox<EffectKind> kindBox = new JComboBox<>(EffectKind.values());
        private final JPanel paramsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));

        private final JComboBox<String> target1Box = new JComboBox<>();
        private final JComboBox<String> target2Box = new JComboBox<>();
        private final ValuePicker valPicker = new ValuePicker(false, true, true);
        private final JSpinner msSpin = new JSpinner(new SpinnerNumberModel(500, 1, 65000, 50));
        private final JTextField uartTextField = new JTextField("OK", 12);
        private final JCheckBox uartCrlfBox = new JCheckBox("+ \\r\\n", true);
        private final JButton upBtn = new JButton("↑");
        private final JButton downBtn = new JButton("↓");

        EffectRow(List<EffectRow> ownerList, JPanel ownerContainer) {
            super(new BorderLayout(6, 0));
            this.ownerList = ownerList;
            this.ownerContainer = ownerContainer;
            setOpaque(true);
            setBackground(StudioTheme.SUBTLE_BG);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(StudioTheme.BORDER_COLOR, 1, true),
                    new EmptyBorder(5, 8, 5, 8)));
            setAlignmentX(LEFT_ALIGNMENT);

            paramsPanel.setOpaque(false);
            uartCrlfBox.setOpaque(false);

            kindBox.addActionListener(e -> { rebuildParams(); if (!syncingUi) updateLivePreview(); });
            target1Box.addActionListener(e -> {
                if (kindBox.getSelectedItem() == EffectKind.FSM_SET) populateFsmPhases();
                if (!syncingUi) updateLivePreview();
            });
            target2Box.addActionListener(e -> { if (!syncingUi) updateLivePreview(); });
            msSpin.addChangeListener(e -> { if (!syncingUi) updateLivePreview(); });
            uartTextField.getDocument().addDocumentListener(simpleDocListener(() -> { if (!syncingUi) updateLivePreview(); }));
            uartCrlfBox.addActionListener(e -> { if (!syncingUi) updateLivePreview(); });

            JPanel main = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
            main.setOpaque(false);
            main.add(ordLabel);
            main.add(kindBox);
            main.add(paramsPanel);

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 2));
            btns.setOpaque(false);
            StudioTheme.styleMiniButton(upBtn, StudioTheme.TEXT_SECONDARY);
            StudioTheme.styleMiniButton(downBtn, StudioTheme.TEXT_SECONDARY);
            JButton delBtn = new JButton("×");
            StudioTheme.styleMiniButton(delBtn, StudioTheme.ACCENT_ROSE);
            upBtn.addActionListener(e -> moveRow(-1));
            downBtn.addActionListener(e -> moveRow(1));
            delBtn.addActionListener(e -> {
                ownerList.remove(this);
                rebuildEffectContainer(ownerList, ownerContainer);
                updateLivePreview();
            });
            btns.add(upBtn);
            btns.add(downBtn);
            btns.add(delBtn);

            add(main, BorderLayout.CENTER);
            add(btns, BorderLayout.EAST);

            pickBestDefaultEffect();
            rebuildParams();
        }

        private void pickBestDefaultEffect() {
            if (!spec.componentsOf(Hardware.Led.class).isEmpty()) kindBox.setSelectedItem(EffectKind.LED_ON);
            else if (!spec.vars().isEmpty()) kindBox.setSelectedItem(EffectKind.VAR_INC);
            else if (!spec.componentsOf(Hardware.BcdDisplay.class).isEmpty()) kindBox.setSelectedItem(EffectKind.BCD_SHOW);
            else if (!spec.componentsOf(Hardware.PwmOutput.class).isEmpty()) kindBox.setSelectedItem(EffectKind.PWM_SET);
            else if (!spec.phaseGroups().isEmpty()) kindBox.setSelectedItem(EffectKind.FSM_SET);
            else if (!spec.timers().isEmpty()) kindBox.setSelectedItem(EffectKind.TIMER_START);
            else if (spec.componentsOf(Hardware.UartPort.class).stream().anyMatch(u -> u.mode().usesTx()))
                kindBox.setSelectedItem(EffectKind.UART_TEXT);
            else kindBox.setSelectedItem(EffectKind.DELAY_MS);
        }

        void setOrdinal(int idx, int total) {
            ordLabel.setText(String.valueOf(idx));
            upBtn.setEnabled(idx > 1);
            downBtn.setEnabled(idx < total);
        }

        private void moveRow(int delta) {
            int idx = ownerList.indexOf(this);
            int target = idx + delta;
            if (idx < 0 || target < 0 || target >= ownerList.size()) return;
            Collections.swap(ownerList, idx, target);
            rebuildEffectContainer(ownerList, ownerContainer);
            updateLivePreview();
        }

        void refreshFromSpec() {
            valPicker.refreshFromSpec();
            rebuildParams();
        }

        private void rebuildParams() {
            String prev1 = (String) target1Box.getSelectedItem();
            String prev2 = (String) target2Box.getSelectedItem();
            paramsPanel.removeAll();
            target1Box.removeAllItems();
            target2Box.removeAllItems();
            EffectKind k = (EffectKind) kindBox.getSelectedItem();
            if (k == null) return;

            switch (k) {
                case LED_ON, LED_OFF, LED_TOGGLE -> {
                    spec.componentsOf(Hardware.Led.class).forEach(l -> target1Box.addItem(l.name()));
                    paramsPanel.add(target1Box);
                }
                case VAR_INC, VAR_DEC, VAR_RESET -> {
                    spec.vars().forEach(v -> target1Box.addItem(v.name()));
                    paramsPanel.add(target1Box);
                }
                case VAR_SET -> {
                    spec.vars().forEach(v -> target1Box.addItem(v.name()));
                    valPicker.refreshFromSpec();
                    paramsPanel.add(target1Box);
                    paramsPanel.add(new JLabel("="));
                    paramsPanel.add(valPicker);
                }
                case BCD_SHOW -> {
                    spec.componentsOf(Hardware.BcdDisplay.class).forEach(d -> target1Box.addItem(d.name()));
                    valPicker.refreshFromSpec();
                    paramsPanel.add(target1Box);
                    paramsPanel.add(new JLabel("←"));
                    paramsPanel.add(valPicker);
                }
                case DECIMAL2_SHOW -> {
                    var bcds = spec.componentsOf(Hardware.BcdDisplay.class);
                    bcds.forEach(d -> { target1Box.addItem(d.name()); target2Box.addItem(d.name()); });
                    if (target2Box.getItemCount() > 1 && prev2 == null) target2Box.setSelectedIndex(1);
                    valPicker.refreshFromSpec();
                    paramsPanel.add(new JLabel("Dec:"));
                    paramsPanel.add(target1Box);
                    paramsPanel.add(new JLabel("Uni:"));
                    paramsPanel.add(target2Box);
                    paramsPanel.add(new JLabel("←"));
                    paramsPanel.add(valPicker);
                }
                case PWM_SET -> {
                    spec.componentsOf(Hardware.PwmOutput.class).forEach(p -> target1Box.addItem(p.name()));
                    valPicker.refreshFromSpec();
                    paramsPanel.add(target1Box);
                    paramsPanel.add(new JLabel("="));
                    paramsPanel.add(valPicker);
                }
                case FSM_SET -> {
                    spec.phaseGroups().forEach(g -> target1Box.addItem(g.name()));
                    if (prev1 != null) target1Box.setSelectedItem(prev1);
                    populateFsmPhases();
                    if (prev2 != null) target2Box.setSelectedItem(prev2);
                    paramsPanel.add(target1Box);
                    paramsPanel.add(new JLabel("→"));
                    paramsPanel.add(target2Box);
                }
                case TIMER_START -> {
                    spec.timers().forEach(t -> target1Box.addItem(t.name()));
                    ((SpinnerNumberModel) msSpin.getModel()).setMaximum(65000);
                    paramsPanel.add(target1Box);
                    paramsPanel.add(msSpin);
                    paramsPanel.add(new JLabel("ms"));
                }
                case TIMER_STOP -> {
                    spec.timers().forEach(t -> target1Box.addItem(t.name()));
                    paramsPanel.add(target1Box);
                }
                case UART_TEXT -> {
                    spec.componentsOf(Hardware.UartPort.class).stream()
                            .filter(u -> u.mode().usesTx())
                            .forEach(u -> target1Box.addItem(u.name()));
                    paramsPanel.add(target1Box);
                    paramsPanel.add(uartTextField);
                    paramsPanel.add(uartCrlfBox);
                }
                case UART_BYTE -> {
                    spec.componentsOf(Hardware.UartPort.class).stream()
                            .filter(u -> u.mode().usesTx())
                            .forEach(u -> target1Box.addItem(u.name()));
                    valPicker.refreshFromSpec();
                    paramsPanel.add(target1Box);
                    paramsPanel.add(valPicker);
                }
                case DELAY_MS -> {
                    ((SpinnerNumberModel) msSpin.getModel()).setMaximum(10000);
                    if ((Integer) msSpin.getValue() > 10000) msSpin.setValue(250);
                    paramsPanel.add(msSpin);
                    paramsPanel.add(new JLabel("ms"));
                }
            }
            if (prev1 != null && k != EffectKind.FSM_SET) target1Box.setSelectedItem(prev1);
            if (prev2 != null && k == EffectKind.DECIMAL2_SHOW) target2Box.setSelectedItem(prev2);
            paramsPanel.revalidate();
            paramsPanel.repaint();
        }

        private void populateFsmPhases() {
            target2Box.removeAllItems();
            String gName = (String) target1Box.getSelectedItem();
            if (gName != null) spec.phaseGroup(gName).ifPresent(g -> g.phases().forEach(target2Box::addItem));
        }

        void load(Effect e) {
            switch (e) {
                case Effect.WriteLed w -> {
                    kindBox.setSelectedItem(w.state() == Effect.LedState.ON ? EffectKind.LED_ON : EffectKind.LED_OFF);
                    rebuildParams();
                    target1Box.setSelectedItem(w.ledName());
                }
                case Effect.ToggleLed t -> {
                    kindBox.setSelectedItem(EffectKind.LED_TOGGLE);
                    rebuildParams();
                    target1Box.setSelectedItem(t.ledName());
                }
                case Effect.StepVar s -> {
                    kindBox.setSelectedItem(s.direction() == Effect.StepDir.INC ? EffectKind.VAR_INC : EffectKind.VAR_DEC);
                    rebuildParams();
                    target1Box.setSelectedItem(s.varName());
                }
                case Effect.ResetVar r -> {
                    kindBox.setSelectedItem(EffectKind.VAR_RESET);
                    rebuildParams();
                    target1Box.setSelectedItem(r.varName());
                }
                case Effect.SetVar s -> {
                    kindBox.setSelectedItem(EffectKind.VAR_SET);
                    rebuildParams();
                    target1Box.setSelectedItem(s.varName());
                    valPicker.load(s.value());
                }
                case Effect.WriteBcd b -> {
                    kindBox.setSelectedItem(EffectKind.BCD_SHOW);
                    rebuildParams();
                    target1Box.setSelectedItem(b.displayName());
                    valPicker.load(b.value());
                }
                case Effect.WriteDecimal2 d -> {
                    kindBox.setSelectedItem(EffectKind.DECIMAL2_SHOW);
                    rebuildParams();
                    target1Box.setSelectedItem(d.tensDisplay());
                    target2Box.setSelectedItem(d.unitsDisplay());
                    valPicker.load(d.value());
                }
                case Effect.WritePwm p -> {
                    kindBox.setSelectedItem(EffectKind.PWM_SET);
                    rebuildParams();
                    target1Box.setSelectedItem(p.pwmName());
                    valPicker.load(p.duty());
                }
                case Effect.SetPhase sp -> {
                    kindBox.setSelectedItem(EffectKind.FSM_SET);
                    rebuildParams();
                    target1Box.setSelectedItem(sp.groupName());
                    populateFsmPhases();
                    target2Box.setSelectedItem(sp.phaseName());
                }
                case Effect.StartTimer st -> {
                    kindBox.setSelectedItem(EffectKind.TIMER_START);
                    rebuildParams();
                    target1Box.setSelectedItem(st.timerName());
                    msSpin.setValue(st.durationMs());
                }
                case Effect.StopTimer st -> {
                    kindBox.setSelectedItem(EffectKind.TIMER_STOP);
                    rebuildParams();
                    target1Box.setSelectedItem(st.timerName());
                }
                case Effect.SendUartText s -> {
                    kindBox.setSelectedItem(EffectKind.UART_TEXT);
                    rebuildParams();
                    target1Box.setSelectedItem(s.uartName());
                    String raw = s.ascii();
                    boolean crlf = raw.endsWith("\r\n");
                    uartCrlfBox.setSelected(crlf);
                    uartTextField.setText(crlf ? raw.substring(0, raw.length() - 2) : raw);
                }
                case Effect.SendUartByte b -> {
                    kindBox.setSelectedItem(EffectKind.UART_BYTE);
                    rebuildParams();
                    target1Box.setSelectedItem(b.uartName());
                    valPicker.load(b.value());
                }
                case Effect.DelayMs d -> {
                    kindBox.setSelectedItem(EffectKind.DELAY_MS);
                    rebuildParams();
                    msSpin.setValue(d.durationMs());
                }
            }
        }

        Effect buildEffect() {
            EffectKind k = (EffectKind) kindBox.getSelectedItem();
            String t1 = (String) target1Box.getSelectedItem();
            String t2 = (String) target2Box.getSelectedItem();
            return switch (k) {
                case LED_ON -> Effect.ledOn(req(t1, "LED"));
                case LED_OFF -> Effect.ledOff(req(t1, "LED"));
                case LED_TOGGLE -> Effect.toggleLed(req(t1, "LED"));
                case VAR_INC -> Effect.inc(req(t1, "contador/variable"));
                case VAR_DEC -> Effect.dec(req(t1, "contador/variable"));
                case VAR_RESET -> Effect.resetVar(req(t1, "contador/variable"));
                case VAR_SET -> Effect.setVar(req(t1, "variable"), valPicker.build(false));
                case BCD_SHOW -> Effect.showBcd(req(t1, "Display BCD"), valPicker.build(false));
                case DECIMAL2_SHOW -> Effect.showDecimal2(req(t2, "Display unidades"), req(t1, "Display decenas"), valPicker.build(false));
                case PWM_SET -> Effect.setPwm(req(t1, "salida PWM"), valPicker.build(false));
                case FSM_SET -> Effect.setPhase(req(t1, "grupo FSM"), req(t2, "fase"));
                case TIMER_START -> Effect.startTimer(req(t1, "temporizador"), (Integer) msSpin.getValue());
                case TIMER_STOP -> Effect.stopTimer(req(t1, "temporizador"));
                case UART_TEXT -> {
                    String txt = uartTextField.getText();
                    if (txt.isEmpty()) throw new IllegalStateException("Escribe el texto a enviar por UART.");
                    yield Effect.sendText(req(t1, "puerto UART TX"), uartCrlfBox.isSelected() ? txt + "\r\n" : txt);
                }
                case UART_BYTE -> Effect.sendByte(req(t1, "puerto UART TX"), valPicker.build(false));
                case DELAY_MS -> Effect.delayMs((Integer) msSpin.getValue());
            };
        }

        private String req(String val, String what) {
            if (val == null || val.isBlank()) throw new IllegalStateException("Selecciona o crea un " + what + ".");
            return val;
        }
    }

    // ==================== Subcomponente inline: Selector de ValueExpr ====================

    private enum ValSource {
        CONST("Número"),
        VAR("Variable"),
        ADC8("ADC 8-bit"),
        ADC10("ADC 10-bit"),
        SCALE8("Escalar ADC 8b"),
        SCALE10("Escalar ADC 10b"),
        UART_RX("Byte UART RX");

        final String label;
        ValSource(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final class ValuePicker extends JPanel {
        private final boolean allow10Bit;
        private final boolean allowScale;
        private final boolean allowUartRx;

        private final JComboBox<ValSource> srcBox = new JComboBox<>();
        private final JComboBox<String> nameBox = new JComboBox<>();
        private final JSpinner numSpin = new JSpinner(new SpinnerNumberModel(0, 0, 1023, 1));
        private final JSpinner scaleMinSpin = new JSpinner(new SpinnerNumberModel(0, 0, 255, 1));
        private final JSpinner scaleMaxSpin = new JSpinner(new SpinnerNumberModel(9, 0, 255, 1));
        private final JLabel toLbl = new JLabel("..");

        ValuePicker(boolean allow10Bit, boolean allowScale, boolean allowUartRx) {
            super(new FlowLayout(FlowLayout.LEFT, 3, 0));
            this.allow10Bit = allow10Bit;
            this.allowScale = allowScale;
            this.allowUartRx = allowUartRx;
            setOpaque(false);

            srcBox.addItem(ValSource.CONST);
            srcBox.addItem(ValSource.VAR);
            srcBox.addItem(ValSource.ADC8);
            if (allow10Bit) srcBox.addItem(ValSource.ADC10);
            if (allowScale) {
                srcBox.addItem(ValSource.SCALE8);
                srcBox.addItem(ValSource.SCALE10);
            }
            if (allowUartRx) srcBox.addItem(ValSource.UART_RX);

            srcBox.addActionListener(e -> { updateControls(); if (!syncingUi) updateLivePreview(); });
            nameBox.addActionListener(e -> { if (!syncingUi) updateLivePreview(); });
            numSpin.addChangeListener(e -> { if (!syncingUi) updateLivePreview(); });
            scaleMinSpin.addChangeListener(e -> { if (!syncingUi) updateLivePreview(); });
            scaleMaxSpin.addChangeListener(e -> { if (!syncingUi) updateLivePreview(); });

            add(srcBox);
            add(nameBox);
            add(numSpin);
            add(scaleMinSpin);
            add(toLbl);
            add(scaleMaxSpin);

            if (!spec.vars().isEmpty()) srcBox.setSelectedItem(ValSource.VAR);
            updateControls();
        }

        boolean is10Bit() {
            return srcBox.getSelectedItem() == ValSource.ADC10;
        }

        void refreshFromSpec() {
            updateControls();
        }

        private void updateControls() {
            ValSource s = (ValSource) srcBox.getSelectedItem();
            String prev = (String) nameBox.getSelectedItem();
            nameBox.removeAllItems();
            nameBox.setVisible(false);
            numSpin.setVisible(false);
            scaleMinSpin.setVisible(false);
            toLbl.setVisible(false);
            scaleMaxSpin.setVisible(false);
            if (s == null) return;

            switch (s) {
                case CONST -> {
                    ((SpinnerNumberModel) numSpin.getModel()).setMaximum(allow10Bit ? 1023 : 255);
                    if (!allow10Bit && (Integer) numSpin.getValue() > 255) numSpin.setValue(0);
                    numSpin.setVisible(true);
                }
                case VAR -> {
                    spec.vars().forEach(v -> nameBox.addItem(v.name()));
                    nameBox.setVisible(true);
                }
                case ADC8, ADC10 -> {
                    spec.componentsOf(Hardware.AnalogInput.class).forEach(a -> nameBox.addItem(a.name()));
                    nameBox.setVisible(true);
                }
                case SCALE8, SCALE10 -> {
                    spec.componentsOf(Hardware.AnalogInput.class).forEach(a -> nameBox.addItem(a.name()));
                    nameBox.setVisible(true);
                    scaleMinSpin.setVisible(true);
                    toLbl.setVisible(true);
                    scaleMaxSpin.setVisible(true);
                }
                case UART_RX -> {
                    spec.componentsOf(Hardware.UartPort.class).stream()
                            .filter(u -> u.mode().usesRx())
                            .forEach(u -> nameBox.addItem(u.name()));
                    nameBox.setVisible(true);
                }
            }
            if (prev != null) nameBox.setSelectedItem(prev);
            revalidate();
            repaint();
        }

        void load(Expr.ValueExpr v) {
            switch (v) {
                case Expr.ConstU8 c -> { srcBox.setSelectedItem(ValSource.CONST); updateControls(); numSpin.setValue(c.value()); }
                case Expr.ConstU10 c -> { srcBox.setSelectedItem(ValSource.CONST); updateControls(); numSpin.setValue(c.value()); }
                case Expr.ReadVar rv -> { srcBox.setSelectedItem(ValSource.VAR); updateControls(); nameBox.setSelectedItem(rv.varName()); }
                case Expr.ReadAdc8 a -> { srcBox.setSelectedItem(ValSource.ADC8); updateControls(); nameBox.setSelectedItem(a.analogName()); }
                case Expr.ReadAdc10 a -> { srcBox.setSelectedItem(ValSource.ADC10); updateControls(); nameBox.setSelectedItem(a.analogName()); }
                case Expr.ReadUartByte u -> { srcBox.setSelectedItem(ValSource.UART_RX); updateControls(); nameBox.setSelectedItem(u.uartName()); }
                case Expr.Scale sc -> {
                    if (sc.source() instanceof Expr.ReadAdc10 a10) {
                        srcBox.setSelectedItem(ValSource.SCALE10);
                        updateControls();
                        nameBox.setSelectedItem(a10.analogName());
                    } else if (sc.source() instanceof Expr.ReadAdc8 a8) {
                        srcBox.setSelectedItem(ValSource.SCALE8);
                        updateControls();
                        nameBox.setSelectedItem(a8.analogName());
                    }
                    scaleMinSpin.setValue(sc.outMin());
                    scaleMaxSpin.setValue(sc.outMax());
                }
            }
        }

        Expr.ValueExpr build(boolean as10BitConst) {
            ValSource s = (ValSource) srcBox.getSelectedItem();
            String nm = (String) nameBox.getSelectedItem();
            return switch (s) {
                case CONST -> {
                    int n = (Integer) numSpin.getValue();
                    yield as10BitConst ? Expr.u10(n) : Expr.u8(Math.min(255, n));
                }
                case VAR -> {
                    if (nm == null) throw new IllegalStateException("Falta seleccionar o crear una variable.");
                    yield Expr.var(nm);
                }
                case ADC8 -> {
                    if (nm == null) throw new IllegalStateException("Falta una entrada analógica ADC.");
                    yield Expr.adc8(nm);
                }
                case ADC10 -> {
                    if (nm == null) throw new IllegalStateException("Falta una entrada analógica ADC.");
                    yield Expr.adc10(nm);
                }
                case SCALE8 -> {
                    if (nm == null) throw new IllegalStateException("Falta una entrada analógica ADC para escalar.");
                    yield Expr.scaleAdc8To(nm, (Integer) scaleMinSpin.getValue(), (Integer) scaleMaxSpin.getValue());
                }
                case SCALE10 -> {
                    if (nm == null) throw new IllegalStateException("Falta una entrada analógica ADC para escalar.");
                    yield Expr.scaleAdc10To(nm, (Integer) scaleMinSpin.getValue(), (Integer) scaleMaxSpin.getValue());
                }
                case UART_RX -> {
                    if (nm == null) throw new IllegalStateException("Falta un puerto UART con RX.");
                    yield Expr.uartByte(nm);
                }
            };
        }
    }

    // ==================== Utilidades ====================

    /**
     * Formatea un byte esperado de UART sin ambigüedad entre dígitos ASCII ('7' = 55)
     * y códigos numéricos (7 = BEL): imprimibles 32..126 se muestran entre comillas simples ('7', 'a'),
     * y códigos de control/extendidos como número decimal (7, 13, 255).
     */
    private static String formatAsciiOrInt(int code) {
        if (code == '\'') return "'\\''";
        if (code == '\\') return "'\\\\'";
        if (code >= 32 && code <= 126) return "'" + (char) code + "'";
        return String.valueOf(code);
    }

    private static int parseAsciiOrInt(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) throw new IllegalStateException("Indica un carácter ASCII (ej. 'a', '7') o código 0..255.");
        if (s.startsWith("'") && s.endsWith("'") && s.length() >= 3) {
            String inner = s.substring(1, s.length() - 1);
            if (inner.length() == 1) return inner.charAt(0) & 0xFF;
            if (inner.equals("\\n")) return '\n';
            if (inner.equals("\\r")) return '\r';
            if (inner.equals("\\t")) return '\t';
            if (inner.equals("\\'")) return '\'';
            if (inner.equals("\\\\")) return '\\';
            throw new IllegalStateException("Literal de carácter inválido: " + s);
        }
        if (s.startsWith("0x") || s.startsWith("0X")) {
            try {
                int n = Integer.parseInt(s.substring(2), 16);
                if (n < 0 || n > 255) throw new IllegalStateException("Código hexadecimal fuera de rango 0x00..0xFF.");
                return n;
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("Código hexadecimal inválido: " + s);
            }
        }
        if (s.chars().allMatch(Character::isDigit)) {
            try {
                int n = Integer.parseInt(s);
                if (n < 0 || n > 255) throw new IllegalStateException("Código de byte fuera de rango 0..255.");
                return n;
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("Código de byte fuera de rango 0..255.");
            }
        }
        if (s.length() == 1) return s.charAt(0) & 0xFF;
        throw new IllegalStateException("Escribe un carácter entre comillas (ej. 'a', '7'), una letra (ej. r) o un número 0..255.");
    }

    private static DocumentListener simpleDocListener(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    /** Panel que siempre adopta el ancho exacto del JViewport para evitar scroll horizontal. */
    private static final class ScrollableTrackPanel extends JPanel implements Scrollable {
        ScrollableTrackPanel(LayoutManager layout) { super(layout); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 20; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 80; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // Métodos públicos conservados por compatibilidad con utilidades existentes
    public static String describeEffect(Effect e) { return NaturalLanguage.describeEffect(e); }
    public static String describeValue(Expr.ValueExpr v) { return NaturalLanguage.describeValue(v); }
}
