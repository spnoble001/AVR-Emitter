package dev.transpilador.v2.ui;

import dev.transpilador.v2.arbiter.ResourceArbiter;
import dev.transpilador.v2.model.*;
import dev.transpilador.v2.patterns.PedagogicalPatterns;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

/**
 * Galería Visual de Intenciones Guiadas (No-Code):
 *  - Catálogo navegable por categoría (Botones y Conteo, Displays y Tiempo, Sensores y PWM, UART).
 *  - Botones "+ Crear" inline si falta algún componente físico requerido.
 *  - Vista previa en vivo del estado (variables/fases/timers) y de las tarjetas estándar de 5 bloques
 *    que se agregarán al circuito antes de confirmar.
 */
public final class PatternAssistantDialog extends JDialog {
    private static final String AUTO_COUNTER = "(Crear contador automático 0..9)";

    private ProgramSpec spec;
    private ProgramSpec result;
    private PedagogicalPatterns.Kind selectedKind = PedagogicalPatterns.Kind.EVERY_N_PRESSES;
    private boolean syncing;

    private final Map<PedagogicalPatterns.Kind, StudioTheme.RoundedPanel> galleryCards = new EnumMap<>(PedagogicalPatterns.Kind.class);

    // Panel derecho
    private final JLabel detailCategoryPillWrap = new JLabel();
    private final JLabel detailTitle = new JLabel();
    private final JTextArea detailDesc = new JTextArea(2, 40);
    private final JLabel reqBadge = new JLabel();

    private final JPanel formPanel = new JPanel(new GridBagLayout());
    private final JComboBox<String> primaryBox = new JComboBox<>();
    private final JComboBox<String> secondaryBox = new JComboBox<>();
    private final JComboBox<String> tertiaryBox = new JComboBox<>();
    private final JSpinner paramSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 65000, 1));
    private final JTextField uartOnField = new JTextField("1", 3);
    private final JTextField uartOffField = new JTextField("0", 3);
    private final JButton createPrimaryBtn = new JButton("+ Crear");
    private final JButton createSecondaryBtn = new JButton("+ Crear");
    private final JButton createTertiaryBtn = new JButton("+ Crear");
    private ComponentDialog.Kind primaryCreateKind;
    private ComponentDialog.Kind secondaryCreateKind;
    private ComponentDialog.Kind tertiaryCreateKind;

    private final JPanel previewContent = new JPanel();
    private final JLabel statusHint = new JLabel();
    private final JButton applyBtn = new JButton("Insertar intención");

    private PatternAssistantDialog(Window owner, ProgramSpec base) {
        super(owner, "Galería de Intenciones Guiadas (Sin código)", ModalityType.APPLICATION_MODAL);
        this.spec = Objects.requireNonNull(base, "base");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(StudioTheme.CANVAS_BG);
        root.setBorder(new EmptyBorder(14, 16, 14, 16));

        // Encabezado superior
        JPanel hdr = new JPanel(new BorderLayout(0, 3));
        hdr.setOpaque(false);
        JLabel hTitle = new JLabel("Elige una intención y el estudio generará sus reglas");
        hTitle.setFont(StudioTheme.serifFont(Font.BOLD, 16.5f));
        hTitle.setForeground(StudioTheme.ACCENT_TEAL_BRIGHT);
        JLabel hSub = new JLabel("Luego podrás editar, combinar o reordenar libremente cada regla generada.");
        hSub.setFont(StudioTheme.uiFont(Font.PLAIN, 12.5f));
        hSub.setForeground(StudioTheme.TEXT_MUTED);
        hdr.add(hTitle, BorderLayout.NORTH);
        hdr.add(hSub, BorderLayout.SOUTH);
        root.add(hdr, BorderLayout.NORTH);

        // Split de 2 columnas: Galería izquierda + Config/Preview derecha
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildGalleryColumn(), buildDetailColumn());
        split.setBorder(null);
        split.setOpaque(false);
        split.setDividerLocation(340);
        split.setResizeWeight(0.38);
        root.add(split, BorderLayout.CENTER);

        // Footer
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        statusHint.setFont(StudioTheme.uiFont(Font.BOLD, 11.5f));
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.setOpaque(false);
        JButton cancel = new JButton("Cancelar");
        StudioTheme.styleGhost(cancel);
        StudioTheme.styleCopperPrimary(applyBtn);
        cancel.addActionListener(e -> dispose());
        applyBtn.addActionListener(e -> accept());
        btns.add(cancel);
        btns.add(applyBtn);
        footer.add(statusHint, BorderLayout.CENTER);
        footer.add(btns, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        // Listeners
        primaryBox.addActionListener(e -> { if (!syncing) refreshPreview(); });
        secondaryBox.addActionListener(e -> { if (!syncing) refreshPreview(); });
        tertiaryBox.addActionListener(e -> { if (!syncing) refreshPreview(); });
        paramSpinner.addChangeListener(e -> { if (!syncing) refreshPreview(); });
        uartOnField.addActionListener(e -> { if (!syncing) refreshPreview(); });
        uartOffField.addActionListener(e -> { if (!syncing) refreshPreview(); });
        uartOnField.getDocument().addDocumentListener(docChange(this::refreshPreview));
        uartOffField.getDocument().addDocumentListener(docChange(this::refreshPreview));

        createPrimaryBtn.addActionListener(e -> inlineCreate(primaryCreateKind));
        createSecondaryBtn.addActionListener(e -> inlineCreate(secondaryCreateKind));
        createTertiaryBtn.addActionListener(e -> inlineCreate(tertiaryCreateKind));

        selectKind(pickBestInitialKind());

        setContentPane(root);
        setSize(920, 620);
        setMinimumSize(new Dimension(780, 520));
        setLocationRelativeTo(owner);
    }

    public static PatternAssistantDialog createDialog(Window owner, ProgramSpec base) {
        return new PatternAssistantDialog(owner, base);
    }

    public static Optional<ProgramSpec> showDialog(Window owner, ProgramSpec base) {
        PatternAssistantDialog dlg = new PatternAssistantDialog(owner, base);
        dlg.setVisible(true);
        return Optional.ofNullable(dlg.result);
    }

    private PedagogicalPatterns.Kind pickBestInitialKind() {
        if (!spec.componentsOf(Hardware.BcdDisplay.class).isEmpty() && !spec.componentsOf(Hardware.Button.class).isEmpty()) {
            return PedagogicalPatterns.Kind.BCD_CIRCULAR_PAUSE;
        }
        if (!spec.componentsOf(Hardware.AnalogInput.class).isEmpty() && !spec.componentsOf(Hardware.PwmOutput.class).isEmpty()) {
            return PedagogicalPatterns.Kind.ADC_TO_PWM;
        }
        if (!spec.componentsOf(Hardware.UartPort.class).isEmpty() && !spec.componentsOf(Hardware.Led.class).isEmpty()) {
            return PedagogicalPatterns.Kind.UART_LED_COMMAND;
        }
        return PedagogicalPatterns.Kind.EVERY_N_PRESSES;
    }

    private JScrollPane buildGalleryColumn() {
        JPanel list = new JPanel();
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(new EmptyBorder(2, 2, 8, 8));

        for (PedagogicalPatterns.Category cat : PedagogicalPatterns.Category.values()) {
            JLabel catLbl = new JLabel(cat.label());
            catLbl.setFont(StudioTheme.uiFont(Font.BOLD, 12f));
            catLbl.setForeground(StudioTheme.TEXT_MUTED);
            catLbl.setBorder(new EmptyBorder(10, 4, 5, 4));
            catLbl.setAlignmentX(LEFT_ALIGNMENT);
            list.add(catLbl);

            for (PedagogicalPatterns.Kind k : PedagogicalPatterns.Kind.values()) {
                if (k.category() != cat) continue;
                StudioTheme.RoundedPanel card = buildGalleryItem(k);
                galleryCards.put(k, card);
                list.add(card);
                list.add(Box.createVerticalStrut(4));
            }
        }

        JPanel track = new TrackWidthPanel(new BorderLayout());
        track.setOpaque(false);
        track.add(list, BorderLayout.NORTH);

        JScrollPane sp = new JScrollPane(track);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(20);
        return sp;
    }

    private StudioTheme.RoundedPanel buildGalleryItem(PedagogicalPatterns.Kind kind) {
        StudioTheme.Role role = categoryRole(kind.category());
        StudioTheme.RoundedPanel card = new StudioTheme.RoundedPanel(new BorderLayout(0, 3))
                .fill(StudioTheme.SURFACE_BG).stroke(StudioTheme.BORDER_COLOR).accent(role.accent).arc(10);
        card.setBorder(new EmptyBorder(8, 12, 8, 10));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.setAlignmentX(LEFT_ALIGNMENT);

        JLabel title = new JLabel("<html><b>" + kind.label() + "</b></html>");
        title.setFont(StudioTheme.uiFont(Font.BOLD, 12f));
        title.setForeground(StudioTheme.TEXT_PRIMARY);

        JLabel req = new JLabel(kind.requirementsHint());
        req.setFont(StudioTheme.uiFont(Font.PLAIN, 10.5f));
        req.setForeground(StudioTheme.TEXT_MUTED);

        card.add(title, BorderLayout.NORTH);
        card.add(req, BorderLayout.SOUTH);

        MouseAdapter click = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { selectKind(kind); }
        };
        card.addMouseListener(click);
        title.addMouseListener(click);
        req.addMouseListener(click);
        return card;
    }

    private JPanel buildDetailColumn() {
        JPanel right = new JPanel(new BorderLayout(0, 10));
        right.setOpaque(false);
        right.setBorder(new EmptyBorder(2, 8, 2, 2));

        // Tarjeta superior: descripción + parámetros
        StudioTheme.RoundedPanel configCard = new StudioTheme.RoundedPanel(new BorderLayout(0, 10))
                .fill(StudioTheme.SURFACE_BG).stroke(StudioTheme.BORDER_COLOR);
        configCard.setBorder(new EmptyBorder(12, 14, 12, 14));

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));

        JPanel titleLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        titleLine.setOpaque(false);
        titleLine.setAlignmentX(LEFT_ALIGNMENT);
        detailTitle.setFont(StudioTheme.serifFont(Font.BOLD, 15.5f));
        detailTitle.setForeground(StudioTheme.TEXT_PRIMARY);
        titleLine.add(detailCategoryPillWrap);
        titleLine.add(detailTitle);
        info.add(titleLine);

        detailDesc.setEditable(false);
        detailDesc.setLineWrap(true);
        detailDesc.setWrapStyleWord(true);
        detailDesc.setOpaque(false);
        detailDesc.setFont(StudioTheme.uiFont(Font.PLAIN, 12f));
        detailDesc.setForeground(StudioTheme.TEXT_SECONDARY);
        detailDesc.setBorder(new EmptyBorder(6, 2, 4, 2));
        detailDesc.setAlignmentX(LEFT_ALIGNMENT);
        info.add(detailDesc);

        reqBadge.setFont(StudioTheme.uiFont(Font.BOLD, 11f));
        reqBadge.setForeground(StudioTheme.TEXT_MUTED);
        reqBadge.setBorder(new EmptyBorder(0, 2, 2, 2));
        reqBadge.setAlignmentX(LEFT_ALIGNMENT);
        info.add(reqBadge);

        formPanel.setOpaque(false);
        StudioTheme.styleSoft(createPrimaryBtn, StudioTheme.Role.INPUT);
        StudioTheme.styleSoft(createSecondaryBtn, StudioTheme.Role.OUTPUT);
        StudioTheme.styleSoft(createTertiaryBtn, StudioTheme.Role.OUTPUT);

        configCard.add(info, BorderLayout.NORTH);
        configCard.add(formPanel, BorderLayout.CENTER);
        right.add(configCard, BorderLayout.NORTH);

        // Tarjeta inferior: Vista previa en vivo de tarjetas que se insertarán
        StudioTheme.RoundedPanel prevCard = new StudioTheme.RoundedPanel(new BorderLayout(0, 8))
                .fill(StudioTheme.SURFACE_BG).stroke(StudioTheme.BORDER_COLOR).accent(StudioTheme.ACCENT_EMERALD);
        prevCard.setBorder(new EmptyBorder(10, 14, 10, 12));

        JPanel prevHdr = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        prevHdr.setOpaque(false);
        JLabel prevTitle = new JLabel("Vista previa en vivo");
        prevTitle.setFont(StudioTheme.serifFont(Font.BOLD, 14f));
        prevTitle.setForeground(StudioTheme.ACCENT_TEAL_BRIGHT);
        JLabel prevSub = new JLabel("Estado y reglas que se añadirán al proyecto");
        prevSub.setFont(StudioTheme.uiFont(Font.PLAIN, 12f));
        prevSub.setForeground(StudioTheme.TEXT_MUTED);
        prevHdr.add(prevTitle);
        prevHdr.add(prevSub);
        prevCard.add(prevHdr, BorderLayout.NORTH);

        previewContent.setOpaque(false);
        previewContent.setLayout(new BoxLayout(previewContent, BoxLayout.Y_AXIS));
        JPanel prevTrack = new TrackWidthPanel(new BorderLayout());
        prevTrack.setOpaque(false);
        prevTrack.add(previewContent, BorderLayout.NORTH);
        JScrollPane prevScroll = new JScrollPane(prevTrack);
        prevScroll.setBorder(null);
        prevScroll.setOpaque(false);
        prevScroll.getViewport().setOpaque(false);
        prevScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        prevScroll.getVerticalScrollBar().setUnitIncrement(18);
        prevCard.add(prevScroll, BorderLayout.CENTER);

        right.add(prevCard, BorderLayout.CENTER);
        return right;
    }

    private void selectKind(PedagogicalPatterns.Kind kind) {
        this.selectedKind = kind;
        for (var entry : galleryCards.entrySet()) {
            boolean sel = entry.getKey() == kind;
            StudioTheme.Role role = categoryRole(entry.getKey().category());
            entry.getValue()
                    .fill(sel ? role.softBg : StudioTheme.SURFACE_BG)
                    .stroke(sel ? role.accent : StudioTheme.BORDER_COLOR);
            if (sel) {
                JComponent card = entry.getValue();
                SwingUtilities.invokeLater(() -> card.scrollRectToVisible(new Rectangle(0, 0, card.getWidth(), card.getHeight())));
            }
        }
        rebuildParameterForm();
        refreshPreview();
    }

    private void inlineCreate(ComponentDialog.Kind kind) {
        if (kind == null) return;
        ComponentDialog.showDialog(this, spec, kind).ifPresent(next -> {
            this.spec = next;
            rebuildParameterForm();
            refreshPreview();
        });
    }

    private void rebuildParameterForm() {
        syncing = true;
        StudioTheme.Role catRole = categoryRole(selectedKind.category());
        detailCategoryPillWrap.setText(selectedKind.category().label() + " ·");
        detailCategoryPillWrap.setOpaque(false);
        detailCategoryPillWrap.setForeground(StudioTheme.ACCENT_TEAL_BRIGHT);
        detailCategoryPillWrap.setFont(StudioTheme.uiFont(Font.BOLD, 12f));
        detailCategoryPillWrap.setBorder(null);

        detailTitle.setText(selectedKind.label());
        detailDesc.setText(selectedKind.description());
        reqBadge.setText(selectedKind.requirementsHint());

        formPanel.removeAll();
        primaryBox.removeAllItems();
        secondaryBox.removeAllItems();
        tertiaryBox.removeAllItems();
        primaryCreateKind = null;
        secondaryCreateKind = null;
        tertiaryCreateKind = null;

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        switch (selectedKind) {
            case EVERY_N_PRESSES -> {
                spec.componentsOf(Hardware.Button.class).forEach(b -> primaryBox.addItem(b.name()));
                spec.componentsOf(Hardware.Led.class).forEach(l -> secondaryBox.addItem(l.name()));
                primaryCreateKind = ComponentDialog.Kind.BUTTON;
                secondaryCreateKind = ComponentDialog.Kind.LED;
                paramSpinner.setModel(new SpinnerNumberModel(3, 2, 255, 1));
                addParamRow(gc, row++, "Botón:", primaryBox, createPrimaryBtn, "+ Botón");
                addParamRow(gc, row++, "LED a encender:", secondaryBox, createSecondaryBtn, "+ LED");
                addSimpleRow(gc, row++, "Cada cuántas pulsaciones (N):", paramSpinner);
            }
            case DOUBLE_CLICK -> {
                spec.componentsOf(Hardware.Button.class).forEach(b -> primaryBox.addItem(b.name()));
                spec.componentsOf(Hardware.Led.class).forEach(l -> secondaryBox.addItem(l.name()));
                primaryCreateKind = ComponentDialog.Kind.BUTTON;
                secondaryCreateKind = ComponentDialog.Kind.LED;
                paramSpinner.setModel(new SpinnerNumberModel(400, 50, 10000, 50));
                addParamRow(gc, row++, "Botón:", primaryBox, createPrimaryBtn, "+ Botón");
                addParamRow(gc, row++, "LED a alternar:", secondaryBox, createSecondaryBtn, "+ LED");
                addSimpleRow(gc, row++, "Ventana máxima (ms):", paramSpinner);
            }
            case SHORT_LONG_PRESS -> {
                spec.componentsOf(Hardware.Button.class).forEach(b -> primaryBox.addItem(b.name()));
                secondaryBox.addItem(AUTO_COUNTER);
                spec.vars().forEach(v -> secondaryBox.addItem(v.name()));
                if (spec.vars().size() > 0) secondaryBox.setSelectedIndex(1);
                primaryCreateKind = ComponentDialog.Kind.BUTTON;
                secondaryCreateKind = ComponentDialog.Kind.VAR;
                paramSpinner.setModel(new SpinnerNumberModel(1000, 100, 10000, 100));
                addParamRow(gc, row++, "Botón:", primaryBox, createPrimaryBtn, "+ Botón");
                addParamRow(gc, row++, "Contador / Variable:", secondaryBox, createSecondaryBtn, "+ Contador");
                addSimpleRow(gc, row++, "Umbral pulsación larga (ms):", paramSpinner);
            }
            case SEQUENCE_A_THEN_B -> {
                spec.componentsOf(Hardware.Button.class).forEach(b -> {
                    primaryBox.addItem(b.name());
                    secondaryBox.addItem(b.name());
                });
                if (secondaryBox.getItemCount() > 1) secondaryBox.setSelectedIndex(1);
                spec.componentsOf(Hardware.Led.class).forEach(l -> tertiaryBox.addItem(l.name()));
                primaryCreateKind = ComponentDialog.Kind.BUTTON;
                secondaryCreateKind = ComponentDialog.Kind.BUTTON;
                tertiaryCreateKind = ComponentDialog.Kind.LED;
                paramSpinner.setModel(new SpinnerNumberModel(500, 50, 10000, 50));
                addParamRow(gc, row++, "Primer botón (A):", primaryBox, createPrimaryBtn, "+ Botón");
                addParamRow(gc, row++, "Segundo botón (B):", secondaryBox, createSecondaryBtn, "+ Botón");
                addParamRow(gc, row++, "LED indicador:", tertiaryBox, createTertiaryBtn, "+ LED");
                addSimpleRow(gc, row++, "Ventana máxima (ms):", paramSpinner);
            }
            case BCD_CIRCULAR_PAUSE -> {
                spec.componentsOf(Hardware.BcdDisplay.class).forEach(d -> primaryBox.addItem(d.name()));
                spec.componentsOf(Hardware.Button.class).forEach(b -> secondaryBox.addItem(b.name()));
                primaryCreateKind = ComponentDialog.Kind.BCD;
                secondaryCreateKind = ComponentDialog.Kind.BUTTON;
                paramSpinner.setModel(new SpinnerNumberModel(500, 50, 10000, 50));
                addParamRow(gc, row++, "Display CD4511:", primaryBox, createPrimaryBtn, "+ Display");
                addParamRow(gc, row++, "Botón de pausa:", secondaryBox, createSecondaryBtn, "+ Botón");
                addSimpleRow(gc, row++, "Avanzar dígito cada (ms):", paramSpinner);
            }
            case PERIODIC_BLINK -> {
                spec.componentsOf(Hardware.Led.class).forEach(l -> primaryBox.addItem(l.name()));
                primaryCreateKind = ComponentDialog.Kind.LED;
                paramSpinner.setModel(new SpinnerNumberModel(500, 10, 65000, 50));
                addParamRow(gc, row++, "LED:", primaryBox, createPrimaryBtn, "+ LED");
                addSimpleRow(gc, row++, "Periodo de cambio (ms):", paramSpinner);
            }
            case ADC_THRESHOLD -> {
                spec.componentsOf(Hardware.AnalogInput.class).forEach(a -> primaryBox.addItem(a.name()));
                spec.componentsOf(Hardware.Led.class).forEach(l -> secondaryBox.addItem(l.name()));
                primaryCreateKind = ComponentDialog.Kind.ANALOG;
                secondaryCreateKind = ComponentDialog.Kind.LED;
                paramSpinner.setModel(new SpinnerNumberModel(614, 0, 1023, 10));
                addParamRow(gc, row++, "Potenciómetro / ADC:", primaryBox, createPrimaryBtn, "+ ADC");
                addParamRow(gc, row++, "LED indicador:", secondaryBox, createSecondaryBtn, "+ LED");
                addSimpleRow(gc, row++, "Umbral 10-bit (512≈2.5V, 614≈3.0V):", paramSpinner);
            }
            case ADC_TO_PWM -> {
                spec.componentsOf(Hardware.AnalogInput.class).forEach(a -> primaryBox.addItem(a.name()));
                spec.componentsOf(Hardware.PwmOutput.class).forEach(p -> secondaryBox.addItem(p.name()));
                primaryCreateKind = ComponentDialog.Kind.ANALOG;
                secondaryCreateKind = ComponentDialog.Kind.PWM;
                addParamRow(gc, row++, "Potenciómetro / ADC:", primaryBox, createPrimaryBtn, "+ ADC");
                addParamRow(gc, row++, "Salida Fast PWM:", secondaryBox, createSecondaryBtn, "+ PWM");
            }
            case PWM_RAMP -> {
                spec.componentsOf(Hardware.PwmOutput.class).forEach(p -> primaryBox.addItem(p.name()));
                primaryCreateKind = ComponentDialog.Kind.PWM;
                paramSpinner.setModel(new SpinnerNumberModel(15, 1, 5000, 5));
                addParamRow(gc, row++, "Salida Fast PWM:", primaryBox, createPrimaryBtn, "+ PWM");
                addSimpleRow(gc, row++, "Incrementar brillo cada (ms):", paramSpinner);
            }
            case UART_LED_COMMAND -> {
                spec.componentsOf(Hardware.UartPort.class).stream()
                        .filter(u -> u.mode().usesRx())
                        .forEach(u -> primaryBox.addItem(u.name()));
                spec.componentsOf(Hardware.Led.class).forEach(l -> secondaryBox.addItem(l.name()));
                primaryCreateKind = ComponentDialog.Kind.UART;
                secondaryCreateKind = ComponentDialog.Kind.LED;
                addParamRow(gc, row++, "Puerto UART (con RX):", primaryBox, createPrimaryBtn, "+ UART");
                addParamRow(gc, row++, "LED a controlar:", secondaryBox, createSecondaryBtn, "+ LED");
                JPanel charsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                charsRow.setOpaque(false);
                charsRow.add(new JLabel("Encender:"));
                charsRow.add(uartOnField);
                charsRow.add(new JLabel("  Apagar:"));
                charsRow.add(uartOffField);
                addSimpleRow(gc, row++, "Caracteres ASCII:", charsRow);
            }
        }

        formPanel.revalidate();
        formPanel.repaint();
        syncing = false;
    }

    private void addParamRow(GridBagConstraints gc, int row, String label, JComponent field, JButton createBtn, String btnText) {
        createBtn.setText(btnText);
        gc.gridy = row;
        gc.gridx = 0; gc.weightx = 0;
        formPanel.add(new JLabel(label), gc);
        gc.gridx = 1; gc.weightx = 1;
        formPanel.add(field, gc);
        gc.gridx = 2; gc.weightx = 0;
        formPanel.add(createBtn, gc);
    }

    private void addSimpleRow(GridBagConstraints gc, int row, String label, JComponent field) {
        gc.gridy = row;
        gc.gridx = 0; gc.weightx = 0;
        formPanel.add(new JLabel(label), gc);
        gc.gridx = 1; gc.gridwidth = 2; gc.weightx = 1;
        formPanel.add(field, gc);
        gc.gridwidth = 1;
    }

    private ProgramSpec buildCandidateSpec() {
        String p1 = (String) primaryBox.getSelectedItem();
        String p2 = (String) secondaryBox.getSelectedItem();
        String p3 = (String) tertiaryBox.getSelectedItem();
        int val = (Integer) paramSpinner.getValue();

        return switch (selectedKind) {
            case EVERY_N_PRESSES -> PedagogicalPatterns.addEveryNPresses(spec, req(p1, "botón"), req(p2, "LED"), val, true);
            case DOUBLE_CLICK -> PedagogicalPatterns.addDoubleClick(spec, req(p1, "botón"), req(p2, "LED"), val);
            case SHORT_LONG_PRESS -> {
                String varChoice = AUTO_COUNTER.equals(p2) ? null : p2;
                String firstBcd = spec.componentsOf(Hardware.BcdDisplay.class).isEmpty()
                        ? null : spec.componentsOf(Hardware.BcdDisplay.class).getFirst().name();
                yield PedagogicalPatterns.addShortLongPress(spec, req(p1, "botón"), varChoice, firstBcd, val);
            }
            case SEQUENCE_A_THEN_B -> {
                String b1 = req(p1, "primer botón (A)");
                String b2 = req(p2, "segundo botón (B)");
                if (b1.equals(b2)) throw new IllegalStateException("Elige dos botones distintos (o crea uno nuevo con '+ Botón').");
                yield PedagogicalPatterns.addSequenceAThenB(spec, b1, b2, req(p3, "LED indicador"), val);
            }
            case BCD_CIRCULAR_PAUSE -> PedagogicalPatterns.addBcdCircularWithPause(spec, req(p1, "Display CD4511"), req(p2, "botón de pausa"), val);
            case PERIODIC_BLINK -> PedagogicalPatterns.addPeriodicBlink(spec, req(p1, "LED"), val);
            case ADC_THRESHOLD -> PedagogicalPatterns.addAdcThreshold(spec, req(p1, "entrada ADC"), req(p2, "LED"), val);
            case ADC_TO_PWM -> PedagogicalPatterns.addAdcToPwm(spec, req(p1, "entrada ADC"), req(p2, "salida PWM"));
            case PWM_RAMP -> PedagogicalPatterns.addPwmRamp(spec, req(p1, "salida PWM"), val);
            case UART_LED_COMMAND -> {
                char onC = singleChar(uartOnField.getText(), '1');
                char offC = singleChar(uartOffField.getText(), '0');
                yield PedagogicalPatterns.addUartLedCommand(spec, req(p1, "puerto UART con RX"), req(p2, "LED"), onC, offC);
            }
        };
    }

    private void refreshPreview() {
        previewContent.removeAll();
        try {
            ProgramSpec candidate = buildCandidateSpec();
            var arb = new ResourceArbiter().analyze(candidate);

            // Chips de estado nuevo que esta intención añadirá automáticamente
            JPanel stateChips = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
            stateChips.setOpaque(false);
            stateChips.setAlignmentX(LEFT_ALIGNMENT);
            int addedState = 0;
            for (int i = spec.vars().size(); i < candidate.vars().size(); i++) {
                State.VarDecl v = candidate.vars().get(i);
                stateChips.add(StudioTheme.pill("+ Contador " + v.name() + " [" + v.min() + ".." + v.max() + "]", StudioTheme.Role.STATE));
                addedState++;
            }
            for (int i = spec.phaseGroups().size(); i < candidate.phaseGroups().size(); i++) {
                State.PhaseGroup g = candidate.phaseGroups().get(i);
                stateChips.add(StudioTheme.pill("+ Fases " + g.name() + " " + g.phases(), StudioTheme.Role.LOGIC));
                addedState++;
            }
            for (int i = spec.timers().size(); i < candidate.timers().size(); i++) {
                State.TimerDecl t = candidate.timers().get(i);
                stateChips.add(StudioTheme.pill("+ Temporizador " + t.name(), StudioTheme.Role.ALT));
                addedState++;
            }
            if (addedState > 0) {
                previewContent.add(stateChips);
                previewContent.add(Box.createVerticalStrut(4));
            }

            // Mini tarjetas generadas
            int newRules = candidate.rules().size() - spec.rules().size();
            for (int i = spec.rules().size(); i < candidate.rules().size(); i++) {
                Rule r = candidate.rules().get(i);
                int ord = i - spec.rules().size() + 1;
                StudioTheme.Role rRole = NaturalLanguage.triggerRole(r.trigger());
                StudioTheme.RoundedPanel mini = new StudioTheme.RoundedPanel(new BorderLayout(0, 3))
                        .fill(StudioTheme.SUBTLE_BG).stroke(StudioTheme.BORDER_COLOR).accent(rRole.accent).arc(8);
                mini.setBorder(new EmptyBorder(6, 11, 6, 8));
                mini.setAlignmentX(LEFT_ALIGNMENT);

                JLabel t = new JLabel(ord + ". " + r.title());
                t.setFont(StudioTheme.uiFont(Font.BOLD, 11.5f));
                t.setForeground(StudioTheme.TEXT_PRIMARY);
                String htmlBody = "<html><body style='width:390px'>" + escapeHtml(NaturalLanguage.describeRuleNatural(r)) + "</body></html>";
                JLabel body = new JLabel(htmlBody);
                body.setFont(StudioTheme.uiFont(Font.PLAIN, 11.5f));
                body.setForeground(StudioTheme.TEXT_SECONDARY);

                mini.add(t, BorderLayout.NORTH);
                mini.add(body, BorderLayout.CENTER);
                previewContent.add(mini);
                previewContent.add(Box.createVerticalStrut(6));
            }

            if (arb.valid()) {
                statusHint.setForeground(StudioTheme.ACCENT_EMERALD);
                statusHint.setIcon(new StudioTheme.StudioIcon(StudioTheme.StudioIcon.Kind.CHECK, 13, StudioTheme.ACCENT_EMERALD));
                statusHint.setIconTextGap(6);
                statusHint.setText("Generará " + newRules + (newRules == 1 ? " tarjeta estándar" : " tarjetas estándar")
                        + (addedState > 0 ? " y " + addedState + " elemento(s) de estado." : "."));
                applyBtn.setEnabled(true);
            } else {
                statusHint.setForeground(StudioTheme.ACCENT_ROSE);
                statusHint.setIcon(new StudioTheme.StudioIcon(StudioTheme.StudioIcon.Kind.WARN, 13, StudioTheme.ACCENT_ROSE));
                statusHint.setIconTextGap(6);
                statusHint.setText(arb.errors().getFirst().message());
                applyBtn.setEnabled(false);
            }
        } catch (Exception ex) {
            JLabel msg = new JLabel("<html><body style='width:400px;color:#b45309'><b>Falta un componente:</b> "
                    + escapeHtml(ex.getMessage()) + "<br>Usa el botón <b>+ Crear</b> arriba para agregarlo sin salir de esta galería.</body></html>");
            msg.setBorder(new EmptyBorder(8, 4, 8, 4));
            msg.setAlignmentX(LEFT_ALIGNMENT);
            previewContent.add(msg);
            statusHint.setForeground(StudioTheme.ACCENT_AMBER.darker());
            statusHint.setIcon(new StudioTheme.StudioIcon(StudioTheme.StudioIcon.Kind.WARN, 13, StudioTheme.ACCENT_AMBER.darker()));
            statusHint.setIconTextGap(6);
            statusHint.setText("Agrega los componentes faltantes con '+ Crear'.");
            applyBtn.setEnabled(false);
        }
        previewContent.revalidate();
        previewContent.repaint();
    }

    private void accept() {
        try {
            this.result = buildCandidateSpec();
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Intención guiada", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static String req(String v, String what) {
        if (v == null || v.isBlank()) throw new IllegalStateException("Aún no hay ningún " + what + " en el circuito.");
        return v;
    }

    private static char singleChar(String txt, char fallback) {
        String s = txt == null ? "" : txt.trim();
        return s.isEmpty() ? fallback : s.charAt(0);
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static StudioTheme.Role categoryRole(PedagogicalPatterns.Category cat) {
        return switch (cat) {
            case BUTTONS -> StudioTheme.Role.INPUT;
            case DISPLAYS_TIME -> StudioTheme.Role.LOGIC;
            case SENSORS_PWM -> StudioTheme.Role.OUTPUT;
            case SERIAL -> StudioTheme.Role.ALT;
        };
    }

    private static javax.swing.event.DocumentListener docChange(Runnable r) {
        return new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        };
    }

    private static final class TrackWidthPanel extends JPanel implements Scrollable {
        TrackWidthPanel(LayoutManager layout) { super(layout); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 20; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 80; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
