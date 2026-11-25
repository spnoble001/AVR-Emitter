package dev.transpilador.v2.ui;

import dev.transpilador.v2.arbiter.ResourceArbiter;
import dev.transpilador.v2.model.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Diálogo guiado para agregar componentes físicos (Bloque 1) o estado (Bloque 2),
 * sugiriendo automáticamente pines libres del ATmega328P con su número DIP-28
 * y validando en vivo con ResourceArbiter.
 */
public final class ComponentDialog extends JDialog {
    public enum Kind {
        BUTTON("Botón (entrada digital)", StudioTheme.Role.INPUT, "Entrada digital con pull-up interno (GND) o pull-down externo (VCC)."),
        LED("LED (salida digital)", StudioTheme.Role.OUTPUT, "Salida digital en cualquier pin GPIO libre."),
        BCD("Display BCD · CD4511 (4 pines)", StudioTheme.Role.OUTPUT, "Ocupa 4 pines consecutivos (A, B, C, D) en el mismo puerto."),
        ANALOG("Potenciómetro / Sensor ADC (PC0..PC5)", StudioTheme.Role.LOGIC, "Entrada analógica 0..5 V con resolución de 8 o 10 bits."),
        PWM("Salida Fast PWM 8-bit (OCnx)", StudioTheme.Role.OUTPUT, "Modulación por ancho de pulso hardware (Timer0, Timer1 o Timer2)."),
        UART("Puerto serie UART0 (PD0/PD1)", StudioTheme.Role.ALT, "Comunicación serie 8N1 (2400..115200 bps según reloj)."),
        VAR("Contador / Variable U8 (0..255)", StudioTheme.Role.STATE, "Almacena números con límite circular (WRAP) o tope (CLAMP)."),
        PHASES("Fases de máquina de estados (FSM)", StudioTheme.Role.LOGIC, "Define modos exclusivos (ej. REPOSO, ACTIVO, ALERTA)."),
        TIMER("Temporizador cooperativo (ms)", StudioTheme.Role.ALT, "Cuenta tiempo sin bloquear otros botones, displays ni LEDs.");

        private final String label;
        private final StudioTheme.Role role;
        private final String help;

        Kind(String label, StudioTheme.Role role, String help) {
            this.label = label;
            this.role = role;
            this.help = help;
        }

        public StudioTheme.Role role() { return role; }
        public String help() { return help; }
        @Override public String toString() { return label; }
    }

    private final ProgramSpec base;
    private ProgramSpec result;

    private final JComboBox<Kind> kindBox = new JComboBox<>(Kind.values());
    private final JTextField nameField = new JTextField(18);
    private final JLabel helpLabel = new JLabel();
    private final JPanel detailPanel = new JPanel(new GridBagLayout());

    // Campos dinámicos según tipo
    private final JComboBox<Hardware.Pin> pinBox = new JComboBox<>();
    private final JComboBox<Hardware.Connection> connBox = new JComboBox<>(Hardware.Connection.values());
    private final JCheckBox initialOnBox = new JCheckBox("Encendido al iniciar");
    private final JSpinner initIntSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 255, 1));
    private final JSpinner minSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 255, 1));
    private final JSpinner maxSpinner = new JSpinner(new SpinnerNumberModel(9, 0, 255, 1));
    private final JComboBox<State.VarPolicy> policyBox = new JComboBox<>(State.VarPolicy.values());
    private final JComboBox<Hardware.UartBaud> baudBox = new JComboBox<>(Hardware.UartBaud.values());
    private final JComboBox<Hardware.UartMode> modeBox = new JComboBox<>(Hardware.UartMode.values());
    private final JTextField phasesField = new JTextField("REPOSO, ACTIVO, ALERTA", 22);

    private ComponentDialog(Window owner, ProgramSpec base, Kind initialKind) {
        super(owner, "Agregar componente o estado", ModalityType.APPLICATION_MODAL);
        this.base = base;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        pinBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Hardware.Pin p) {
                    lbl.setText(p.label() + "  (DIP-" + p.dip28Pin() + ")");
                }
                return lbl;
            }
        });
        policyBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value == State.VarPolicy.WRAP) lbl.setText("Circular (reinicia al cruzar límite)");
                else if (value == State.VarPolicy.CLAMP) lbl.setText("Con tope (se detiene en mínimo/máximo)");
                return lbl;
            }
        });

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(new EmptyBorder(16, 18, 14, 18));
        root.setBackground(StudioTheme.CANVAS_BG);

        // Tarjeta superior de selección
        StudioTheme.RoundedPanel topCard = new StudioTheme.RoundedPanel(new BorderLayout(0, 8))
                .fill(StudioTheme.SURFACE_BG).stroke(StudioTheme.BORDER_COLOR);
        topCard.setBorder(new EmptyBorder(12, 14, 12, 14));

        JPanel topGrid = new JPanel(new GridBagLayout());
        topGrid.setOpaque(false);
        GridBagConstraints tc = new GridBagConstraints();
        tc.insets = new Insets(4, 4, 4, 4);
        tc.anchor = GridBagConstraints.WEST;
        tc.fill = GridBagConstraints.HORIZONTAL;
        tc.gridx = 0; tc.gridy = 0; tc.weightx = 0;
        topGrid.add(boldLabel("Tipo:"), tc);
        tc.gridx = 1; tc.weightx = 1;
        topGrid.add(kindBox, tc);
        tc.gridx = 0; tc.gridy = 1; tc.weightx = 0;
        topGrid.add(boldLabel("Nombre (sin espacios):"), tc);
        tc.gridx = 1; tc.weightx = 1;
        topGrid.add(nameField, tc);

        helpLabel.setFont(StudioTheme.uiFont(Font.PLAIN, 11.5f));
        helpLabel.setForeground(StudioTheme.TEXT_MUTED);
        topCard.add(topGrid, BorderLayout.CENTER);
        topCard.add(helpLabel, BorderLayout.SOUTH);
        root.add(topCard, BorderLayout.NORTH);

        // Tarjeta de detalles
        StudioTheme.RoundedPanel detailCard = new StudioTheme.RoundedPanel(new BorderLayout())
                .fill(StudioTheme.SURFACE_BG).stroke(StudioTheme.BORDER_COLOR);
        detailCard.setBorder(new EmptyBorder(10, 14, 10, 14));
        detailPanel.setOpaque(false);
        detailCard.add(detailPanel, BorderLayout.CENTER);
        root.add(detailCard, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton cancel = new JButton("Cancelar");
        JButton ok = new JButton("Agregar al circuito");
        StudioTheme.styleGhost(cancel);
        StudioTheme.styleCopperPrimary(ok);
        cancel.addActionListener(e -> dispose());
        ok.addActionListener(e -> accept());
        actions.add(cancel);
        actions.add(ok);
        root.add(actions, BorderLayout.SOUTH);

        kindBox.addActionListener(e -> rebuildDetails());
        baudBox.setSelectedItem(Hardware.UartBaud.B9600);
        modeBox.setSelectedItem(Hardware.UartMode.FULL_DUPLEX);
        if (initialKind != null) {
            kindBox.setSelectedItem(initialKind);
        }
        rebuildDetails();

        setContentPane(root);
        pack();
        setMinimumSize(new Dimension(490, 310));
        setLocationRelativeTo(owner);
    }

    public static Optional<ProgramSpec> showDialog(Window owner, ProgramSpec base) {
        return showDialog(owner, base, null);
    }

    public static Optional<ProgramSpec> showDialog(Window owner, ProgramSpec base, Kind initialKind) {
        ComponentDialog dlg = new ComponentDialog(owner, base, initialKind);
        dlg.setVisible(true);
        return Optional.ofNullable(dlg.result);
    }

    private static JLabel boldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(StudioTheme.uiFont(Font.BOLD, 12f));
        l.setForeground(StudioTheme.TEXT_PRIMARY);
        return l;
    }

    private Set<Hardware.Pin> usedPins() {
        Set<Hardware.Pin> used = new HashSet<>();
        for (Hardware.Component c : base.components()) used.addAll(c.occupiedPins());
        return used;
    }

    private void rebuildDetails() {
        detailPanel.removeAll();
        Kind k = (Kind) kindBox.getSelectedItem();
        if (k == null) return;
        nameField.setText(suggestName(k));
        helpLabel.setText(k.help());

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(5, 4, 5, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        Set<Hardware.Pin> occupied = usedPins();
        switch (k) {
            case BUTTON -> {
                populatePins(allGpioPins(), occupied);
                connBox.setSelectedItem(Hardware.Connection.GND);
                addRow(gc, row++, "Pin libre:", pinBox);
                addRow(gc, row++, "Conexión (GND = pull-up interno):", connBox);
            }
            case LED -> {
                populatePins(allGpioPins(), occupied);
                connBox.setSelectedItem(Hardware.Connection.GND);
                addRow(gc, row++, "Pin libre:", pinBox);
                addRow(gc, row++, "Conexión (GND = activo alto):", connBox);
                initialOnBox.setOpaque(false);
                addRow(gc, row++, "Estado inicial:", initialOnBox);
            }
            case BCD -> {
                List<Hardware.Pin> validBcdStarts = allGpioPins().stream()
                        .filter(p -> p.bit() + 3 <= p.port().maxBit())
                        .filter(p -> {
                            for (int i = 0; i < 4; i++) {
                                if (occupied.contains(new Hardware.Pin(p.port(), p.bit() + i))) return false;
                            }
                            return true;
                        }).toList();
                pinBox.setModel(new DefaultComboBoxModel<>(validBcdStarts.toArray(Hardware.Pin[]::new)));
                initIntSpinner.setModel(new SpinnerNumberModel(0, 0, 9, 1));
                addRow(gc, row++, "Primer pin (ocupa 4 consecutivos):", pinBox);
                addRow(gc, row++, "Dígito inicial (0..9):", initIntSpinner);
            }
            case ANALOG -> {
                List<Hardware.Pin> adcPins = new ArrayList<>();
                for (int ch = 0; ch <= 5; ch++) adcPins.add(Hardware.Pin.pc(ch));
                populatePins(adcPins, occupied);
                addRow(gc, row++, "Pin analógico (PC0..PC5):", pinBox);
            }
            case PWM -> {
                List<Hardware.Pin> pwmPins = Arrays.stream(Hardware.PwmChannel.values())
                        .map(Hardware.PwmChannel::pin).toList();
                populatePins(pwmPins, occupied);
                initIntSpinner.setModel(new SpinnerNumberModel(0, 0, 255, 1));
                addRow(gc, row++, "Pin Fast PWM (OCnx):", pinBox);
                addRow(gc, row++, "Duty inicial (0..255):", initIntSpinner);
            }
            case UART -> {
                addRow(gc, row++, "Velocidad (bps):", baudBox);
                addRow(gc, row++, "Modo (PD0=RX, PD1=TX):", modeBox);
            }
            case VAR -> {
                minSpinner.setModel(new SpinnerNumberModel(0, 0, 255, 1));
                maxSpinner.setModel(new SpinnerNumberModel(9, 0, 255, 1));
                initIntSpinner.setModel(new SpinnerNumberModel(0, 0, 255, 1));
                addRow(gc, row++, "Valor mínimo:", minSpinner);
                addRow(gc, row++, "Valor máximo:", maxSpinner);
                addRow(gc, row++, "Valor inicial:", initIntSpinner);
                addRow(gc, row++, "Al llegar al límite:", policyBox);
            }
            case PHASES -> {
                addRow(gc, row++, "Fases (separadas por coma):", phasesField);
            }
            case TIMER -> {
                JLabel note = new JLabel("Temporizador cooperativo (1..65000 ms, sin ocupar timers PWM)");
                note.setForeground(StudioTheme.TEXT_MUTED);
                addRow(gc, row++, "Funcionamiento:", note);
            }
        }
        detailPanel.revalidate();
        detailPanel.repaint();
        pack();
    }

    private void addRow(GridBagConstraints gc, int row, String label, Component field) {
        gc.gridy = row;
        gc.gridx = 0; gc.weightx = 0;
        detailPanel.add(new JLabel(label), gc);
        gc.gridx = 1; gc.weightx = 1;
        detailPanel.add(field, gc);
    }

    private void populatePins(List<Hardware.Pin> candidates, Set<Hardware.Pin> occupied) {
        List<Hardware.Pin> free = candidates.stream().filter(p -> !occupied.contains(p)).toList();
        pinBox.setModel(new DefaultComboBoxModel<>(free.toArray(Hardware.Pin[]::new)));
    }

    private static List<Hardware.Pin> allGpioPins() {
        List<Hardware.Pin> list = new ArrayList<>();
        for (int b = 0; b <= 5; b++) list.add(Hardware.Pin.pb(b));
        for (int b = 0; b <= 7; b++) list.add(Hardware.Pin.pd(b));
        for (int b = 0; b <= 5; b++) list.add(Hardware.Pin.pc(b));
        return list;
    }

    private String suggestName(Kind k) {
        String prefix = switch (k) {
            case BUTTON -> "btn";
            case LED -> "led";
            case BCD -> "display";
            case ANALOG -> "pot";
            case PWM -> "pwm";
            case UART -> "serie";
            case VAR -> "cuenta";
            case PHASES -> "estado";
            case TIMER -> "t_espera";
        };
        Set<String> used = new HashSet<>();
        base.components().forEach(c -> used.add(c.name()));
        base.vars().forEach(v -> used.add(v.name()));
        base.phaseGroups().forEach(g -> used.add(g.name()));
        base.timers().forEach(t -> used.add(t.name()));
        if (!used.contains(prefix)) return prefix;
        int n = 2;
        while (used.contains(prefix + "_" + n)) n++;
        return prefix + "_" + n;
    }

    private void accept() {
        try {
            Kind k = (Kind) kindBox.getSelectedItem();
            String name = nameField.getText().trim();
            var comps = new ArrayList<>(base.components());
            var vars = new ArrayList<>(base.vars());
            var phases = new ArrayList<>(base.phaseGroups());
            var timers = new ArrayList<>(base.timers());

            switch (k) {
                case BUTTON -> comps.add(new Hardware.Button(name, requirePin(), (Hardware.Connection) connBox.getSelectedItem()));
                case LED -> comps.add(new Hardware.Led(name, requirePin(), (Hardware.Connection) connBox.getSelectedItem(), initialOnBox.isSelected()));
                case BCD -> comps.add(new Hardware.BcdDisplay(name, requirePin(), (Integer) initIntSpinner.getValue()));
                case ANALOG -> comps.add(new Hardware.AnalogInput(name, requirePin()));
                case PWM -> comps.add(new Hardware.PwmOutput(name, requirePin(), (Integer) initIntSpinner.getValue()));
                case UART -> comps.add(new Hardware.UartPort(name, (Hardware.UartBaud) baudBox.getSelectedItem(), (Hardware.UartMode) modeBox.getSelectedItem()));
                case VAR -> vars.add(new State.VarDecl(name, (Integer) minSpinner.getValue(), (Integer) maxSpinner.getValue(),
                        (Integer) initIntSpinner.getValue(), (State.VarPolicy) policyBox.getSelectedItem()));
                case PHASES -> {
                    List<String> list = Arrays.stream(phasesField.getText().split(","))
                            .map(String::trim).filter(s -> !s.isEmpty()).toList();
                    if (list.size() < 2) throw new IllegalArgumentException("Indica al menos 2 fases separadas por coma.");
                    phases.add(new State.PhaseGroup(name, list, list.getFirst()));
                }
                case TIMER -> timers.add(new State.TimerDecl(name));
            }

            ProgramSpec candidate = new ProgramSpec(base.name(), base.fCpuHz(), comps, vars, phases, timers, base.rules());
            var arb = new ResourceArbiter().analyze(candidate);
            if (!arb.valid()) {
                throw new IllegalArgumentException(arb.errors().getFirst().message());
            }
            this.result = candidate;
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Revisar configuración", JOptionPane.WARNING_MESSAGE);
        }
    }

    private Hardware.Pin requirePin() {
        Hardware.Pin p = (Hardware.Pin) pinBox.getSelectedItem();
        if (p == null) throw new IllegalArgumentException("No hay pines libres compatibles para este componente.");
        return p;
    }
}
