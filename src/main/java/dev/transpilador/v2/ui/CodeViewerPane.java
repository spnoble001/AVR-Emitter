package dev.transpilador.v2.ui;

import dev.transpilador.v2.emit.AvrInstructionCatalog;
import dev.transpilador.v2.emit.TraceMap;
import dev.transpilador.v2.sim.SimEngineController;
import dev.transpilador.v2.sim.Toolchain;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Visor y editor IDE integrado en tema carbón Lean 4 para C99 y AVRASM2:
 *  - Modo Sincronizado (por defecto): solo lectura con trazabilidad 4 vías y tooltips ASM.
 *  - Modo Manual / IDE (opt-in con "Editar"): edición directa con resaltado en vivo,
 *    autocompletado ATmega328P (Ctrl+Espacio), deshacer/rehacer local, verificación real con
 *    avr-gcc / avrasm2, marcas de error en línea, uso real de Flash y carga directa a simavr.
 */
public final class CodeViewerPane extends JPanel {
    public enum Language { C99, AVRASM2 }

    private static final int ATMEGA328P_FLASH_BYTES = 32_768;

    private static final Set<String> C_KEYWORDS = Set.of(
            "if", "else", "while", "for", "do", "return", "static", "const", "volatile",
            "switch", "case", "break", "continue", "enum", "struct", "typedef", "sizeof"
    );
    private static final Set<String> C_TYPES = Set.of(
            "void", "int", "uint8_t", "uint16_t", "uint32_t", "int8_t", "int16_t", "bool", "char"
    );
    private static final Set<String> ASM_MNEMONICS = Set.of(
            "ldi", "lds", "sts", "ld", "st", "lpm", "mov", "movw", "in", "out", "sbi", "cbi",
            "sbic", "sbis", "sbrs", "sbrc", "andi", "ori", "and", "or", "eor", "com", "neg",
            "add", "adc", "sub", "subi", "sbc", "sbci", "inc", "dec", "adiw", "sbiw", "clr", "ser",
            "tst", "cp", "cpc", "cpi", "lsl", "lsr", "rol", "ror", "asr", "swap", "mul",
            "rjmp", "jmp", "rcall", "call", "ret", "reti", "breq", "brne", "brlo", "brsh",
            "brlt", "brge", "brcs", "brcc", "push", "pop", "nop", "cli", "sei"
    );

    private static final Pattern C_TOKENS = Pattern.compile(
            "(?<COMMENT>//[^\\n]*|/\\*[\\s\\S]*?\\*/)"
                    + "|(?<PREPROC>^[ \\t]*#[a-zA-Z_]+)"
                    + "|(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
                    + "|(?<NUMBER>\\b0[xX][0-9a-fA-F]+\\b|\\b0[bB][01]+\\b|\\b\\d+[uUlL]*\\b)"
                    + "|(?<IDENT>\\b[a-zA-Z_][a-zA-Z0-9_]*\\b)",
            Pattern.MULTILINE
    );

    private static final Pattern ASM_TOKENS = Pattern.compile(
            "(?<COMMENT>;[^\\n]*)"
                    + "|(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
                    + "|(?<DIRECTIVE>\\.[a-zA-Z_]+\\b)"
                    + "|(?<LABEL>^[ \\t]*[a-zA-Z_][a-zA-Z0-9_]*:)"
                    + "|(?<REG>\\b(?:r(?:3[01]|[12]\\d|\\d)|XL|XH|YL|YH|ZL|ZH|SPL|SPH|SREG)\\b)"
                    + "|(?<NUMBER>\\b0[xX][0-9a-fA-F]+\\b|\\b0[bB][01]+\\b|\\b\\d+\\b)"
                    + "|(?<IDENT>\\b[a-zA-Z_][a-zA-Z0-9_]*\\b)",
            Pattern.MULTILINE
    );

    private static final Highlighter.HighlightPainter BLOCK_PAINTER =
            new LineBandPainter(new Color(173, 107, 59, 44), new Color(217, 140, 78, 235));
    private static final Highlighter.HighlightPainter ERROR_PAINTER =
            new LineBandPainter(new Color(222, 91, 87, 52), new Color(222, 91, 87, 240));
    private static final Highlighter.HighlightPainter WARN_PAINTER =
            new LineBandPainter(new Color(217, 140, 78, 36), new Color(217, 140, 78, 210));

    private static final ExecutorService BUILD_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "avr-ide-build-worker");
        t.setDaemon(true);
        return t;
    });

    private final Language language;
    private final JTextPane editor;
    private final JTextArea gutter = new JTextArea();
    private final JLabel metricsLabel = new JLabel();
    private final JPanel rightBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

    // Controles del modo IDE (edición raw + compilación + carga HEX)
    private final JToggleButton btnEditToggle = new JToggleButton("Editar");
    private final JPanel ideBar = new JPanel(new BorderLayout(8, 0));
    private final JLabel ideStatusLabel = new JLabel();
    private final JButton btnVerify = new JButton("Verificar");
    private final JButton btnLoadSim = new JButton("Cargar en simavr");
    private final JButton btnRestore = new JButton("Restaurar");

    // Consola inferior de compilación y diagnósticos clicables
    private final JPanel consolePanel = new JPanel(new BorderLayout(0, 0));
    private final JLabel consoleSummaryLabel = new JLabel();
    private final DefaultListModel<Toolchain.BuildDiagnostic> diagListModel = new DefaultListModel<>();
    private final JList<Toolchain.BuildDiagnostic> diagList = new JList<>(diagListModel);

    private final UndoManager undoManager = new UndoManager();
    private final AvrCodeCompleter completer;
    private final javax.swing.Timer rehighlightTimer;

    private String lastGeneratedCode = "";
    private boolean editMode;
    private boolean manuallyModified;
    private boolean programmaticUpdate;
    private boolean stylingInProgress;
    private boolean building;

    private TraceMap.LineRange highlightedRange;
    private Object highlightTag;
    private final List<Object> diagHighlightTags = new ArrayList<>();
    private IntConsumer lineClickListener;
    private Supplier<String> projectNameSupplier = () -> "firmware_manual";
    private Consumer<SimEngineController.CustomFirmware> loadFirmwareHandler;
    private Toolchain.RawBuildResult lastBuildResult;

    private final SimpleAttributeSet sPlain = attr(StudioTheme.CODE_TEXT, false);
    private final SimpleAttributeSet sKeyword = attr(StudioTheme.CODE_KEYWORD, true);
    private final SimpleAttributeSet sType = attr(StudioTheme.CODE_TYPE, true);
    private final SimpleAttributeSet sRegister = attr(StudioTheme.CODE_REGISTER, false);
    private final SimpleAttributeSet sNumber = attr(StudioTheme.CODE_NUMBER, false);
    private final SimpleAttributeSet sString = attr(StudioTheme.CODE_STRING, false);
    private final SimpleAttributeSet sComment = attr(StudioTheme.CODE_COMMENT, false);
    private final SimpleAttributeSet sLabel = attr(StudioTheme.CODE_LABEL, true);

    public CodeViewerPane(Language language, Runnable onSave) {
        super(new BorderLayout());
        this.language = language;
        setBackground(StudioTheme.CODE_BG);

        this.editor = new JTextPane() {
            @Override
            public String getToolTipText(MouseEvent e) {
                if (language != Language.AVRASM2 || e == null) return null;
                String line = lineTextAtPoint(e.getPoint());
                return AvrInstructionCatalog.tooltipHtmlForLine(line);
            }
        };
        if (language == Language.AVRASM2) {
            ToolTipManager.sharedInstance().registerComponent(editor);
        }

        Font mono = StudioTheme.codeFont(13f);
        editor.setEditable(false);
        editor.setFont(mono);
        editor.setBackground(StudioTheme.CODE_BG);
        editor.setForeground(StudioTheme.CODE_TEXT);
        editor.setCaretColor(StudioTheme.ACCENT_TEAL_BRIGHT);
        editor.setSelectionColor(new Color(39, 92, 87, 150));
        editor.setSelectedTextColor(StudioTheme.TEXT_PRIMARY);
        editor.setBorder(new EmptyBorder(10, 12, 10, 12));
        editor.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (!editMode && SwingUtilities.isLeftMouseButton(e) && lineClickListener != null) {
                    int line = lineNumberAtPoint(e.getPoint());
                    if (line >= 1) lineClickListener.accept(line);
                }
            }
        });

        this.completer = AvrCodeCompleter.install(editor, language, () -> editMode);
        this.rehighlightTimer = new javax.swing.Timer(110, e -> rehighlightCurrentDocument());
        this.rehighlightTimer.setRepeats(false);
        installEditorKeyBindings();

        gutter.setEditable(false);
        gutter.setFocusable(false);
        gutter.setFont(mono);
        gutter.setBackground(StudioTheme.CODE_GUTTER_BG);
        gutter.setForeground(StudioTheme.CODE_GUTTER_FG);
        gutter.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Barra superior principal del visor
        JPanel topStack = new JPanel(new BorderLayout());
        topStack.setBackground(StudioTheme.CODE_BAR_BG);

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(StudioTheme.CODE_BAR_BG);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, StudioTheme.BORDER_COLOR),
                new EmptyBorder(7, 12, 7, 12)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        JLabel langLabel = new JLabel(language == Language.C99 ? "C99" : "AVR ASM");
        langLabel.setFont(StudioTheme.serifFont(Font.BOLD, 14f));
        langLabel.setForeground(StudioTheme.ACCENT_TEAL_BRIGHT);
        metricsLabel.setFont(StudioTheme.codeFont(11.5f));
        metricsLabel.setForeground(StudioTheme.TEXT_MUTED);
        left.add(langLabel);
        left.add(metricsLabel);

        rightBar.setOpaque(false);
        btnEditToggle.setName(language == Language.C99 ? "editC99Toggle" : "editAsmToggle");
        btnEditToggle.setToolTipText("Activar modo IDE para editar código raw, autocompletar y compilar directo a simavr");
        StudioTheme.styleSegmentButton(btnEditToggle, false);
        btnEditToggle.addActionListener(e -> setEditMode(btnEditToggle.isSelected()));

        JButton btnCopy = new JButton("Copiar");
        JButton btnSave = new JButton(language == Language.C99 ? "Guardar .c" : "Guardar .asm");
        btnSave.setToolTipText("Exportar archivo fuente " + (language == Language.C99 ? ".c" : ".asm"));
        StudioTheme.styleGhost(btnCopy);
        StudioTheme.styleGhost(btnSave);
        btnCopy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(getCode()), null);
            btnCopy.setText("¡Copiado!");
            javax.swing.Timer t = new javax.swing.Timer(1200, ev -> btnCopy.setText("Copiar"));
            t.setRepeats(false);
            t.start();
        });
        btnSave.addActionListener(e -> onSave.run());
        rightBar.add(btnEditToggle);
        rightBar.add(btnCopy);
        rightBar.add(btnSave);

        bar.add(left, BorderLayout.CENTER);
        bar.add(rightBar, BorderLayout.EAST);

        // Sub-barra IDE (visible solo cuando Editar está activo)
        buildIdeSubBar();
        topStack.add(bar, BorderLayout.NORTH);
        topStack.add(ideBar, BorderLayout.SOUTH);

        // Área de código sin wrap horizontal
        JPanel noWrap = new JPanel(new BorderLayout());
        noWrap.setBackground(StudioTheme.CODE_BG);
        noWrap.add(editor, BorderLayout.CENTER);

        JScrollPane scroll = new JScrollPane(noWrap);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(StudioTheme.CODE_BG);
        scroll.setRowHeaderView(gutter);
        scroll.getVerticalScrollBar().setUnitIncrement(22);

        // Consola inferior de compilación y diagnósticos clicables
        buildConsolePanel();

        add(topStack, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(consolePanel, BorderLayout.SOUTH);
    }

    private void buildIdeSubBar() {
        ideBar.setBackground(new Color(20, 24, 22));
        ideBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, StudioTheme.BORDER_COLOR),
                new EmptyBorder(6, 12, 6, 12)));
        ideBar.setVisible(false);

        ideStatusLabel.setFont(StudioTheme.uiFont(Font.PLAIN, 12f));
        ideStatusLabel.setForeground(StudioTheme.TEXT_MUTED);
        ideStatusLabel.setText("Modo IDE · Ctrl+Espacio · Ctrl+B · F5");

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);

        btnVerify.setName(language == Language.C99 ? "verifyC99Btn" : "verifyAsmBtn");
        btnVerify.setToolTipText("Compilar/ensamblar con " + (language == Language.C99 ? "avr-gcc" : "avrasm2") + " y comprobar errores (Ctrl+B)");
        StudioTheme.styleTealOutline(btnVerify);
        btnVerify.addActionListener(e -> runBuildAsync(false));

        btnLoadSim.setName(language == Language.C99 ? "loadSimC99Btn" : "loadSimAsmBtn");
        btnLoadSim.setToolTipText("Compilar este código y cargarlo en simavr para probarlo en el Laboratorio (F5)");
        StudioTheme.styleCopperPrimary(btnLoadSim);
        btnLoadSim.addActionListener(e -> runBuildAsync(true));

        btnRestore.setToolTipText("Descartar edición manual y volver al código sincronizado con el modelo");
        StudioTheme.styleGhost(btnRestore);
        btnRestore.addActionListener(e -> restoreGeneratedCode());

        actions.add(btnRestore);
        actions.add(btnVerify);
        actions.add(btnLoadSim);

        ideBar.add(ideStatusLabel, BorderLayout.CENTER);
        ideBar.add(actions, BorderLayout.EAST);
    }

    private void buildConsolePanel() {
        consolePanel.setBackground(StudioTheme.SURFACE_BG);
        consolePanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, StudioTheme.BORDER_COLOR));
        consolePanel.setVisible(false);

        JPanel hdr = new JPanel(new BorderLayout(8, 0));
        hdr.setBackground(new Color(20, 24, 22));
        hdr.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, StudioTheme.BORDER_COLOR),
                new EmptyBorder(5, 12, 5, 10)));

        consoleSummaryLabel.setFont(StudioTheme.codeFont(Font.BOLD, 12f));
        consoleSummaryLabel.setForeground(StudioTheme.ACCENT_TEAL_BRIGHT);

        JButton btnCloseConsole = new JButton("×");
        StudioTheme.styleFlatLinkButton(btnCloseConsole, StudioTheme.TEXT_MUTED);
        btnCloseConsole.setToolTipText("Ocultar consola de compilación");
        btnCloseConsole.addActionListener(e -> consolePanel.setVisible(false));

        hdr.add(consoleSummaryLabel, BorderLayout.CENTER);
        hdr.add(btnCloseConsole, BorderLayout.EAST);

        diagList.setBackground(StudioTheme.CANVAS_BG);
        diagList.setForeground(StudioTheme.TEXT_PRIMARY);
        diagList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        diagList.setFixedCellHeight(24);
        diagList.setCellRenderer(new DiagnosticCellRenderer());
        diagList.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        diagList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                Toolchain.BuildDiagnostic d = diagList.getSelectedValue();
                if (d != null && d.line() > 0) {
                    jumpToLine(d.line(), d.column());
                }
            }
        });

        JScrollPane sp = new JScrollPane(diagList);
        sp.setBorder(null);
        sp.setPreferredSize(new Dimension(200, 104));
        sp.getViewport().setBackground(StudioTheme.CANVAS_BG);

        consolePanel.add(hdr, BorderLayout.NORTH);
        consolePanel.add(sp, BorderLayout.CENTER);
    }

    private void installEditorKeyBindings() {
        // Tab inserta 4 espacios (solo si el popup de autocompletado no está abierto)
        editor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!editMode) return;
                if (completer.isPopupVisible()) return;

                if (e.getKeyCode() == KeyEvent.VK_TAB && !e.isControlDown() && !e.isAltDown()) {
                    e.consume();
                    insertTextAtCaret("    ");
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isControlDown() && !e.isAltDown()) {
                    e.consume();
                    insertAutoIndentedNewline();
                } else if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_Z) {
                    e.consume();
                    if (undoManager.canUndo()) undoManager.undo();
                } else if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_Y) {
                    e.consume();
                    if (undoManager.canRedo()) undoManager.redo();
                } else if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_B) {
                    e.consume();
                    runBuildAsync(false);
                } else if (e.getKeyCode() == KeyEvent.VK_F5) {
                    e.consume();
                    runBuildAsync(true);
                }
            }
        });
    }

    private void insertTextAtCaret(String text) {
        try {
            Document doc = editor.getDocument();
            int start = editor.getSelectionStart();
            int end = editor.getSelectionEnd();
            if (end > start) doc.remove(start, end - start);
            doc.insertString(start, text, sPlain);
        } catch (BadLocationException ignored) {
        }
    }

    private void insertAutoIndentedNewline() {
        try {
            Document doc = editor.getDocument();
            int pos = editor.getCaretPosition();
            Element root = doc.getDefaultRootElement();
            int lineIdx = root.getElementIndex(pos);
            Element lineEl = root.getElement(lineIdx);
            int lineStart = lineEl.getStartOffset();
            String before = doc.getText(lineStart, Math.max(0, pos - lineStart));
            StringBuilder indent = new StringBuilder();
            for (int i = 0; i < before.length(); i++) {
                char c = before.charAt(i);
                if (c == ' ' || c == '\t') indent.append(c);
                else break;
            }
            String trimmed = before.trim();
            if ((language == Language.C99 && trimmed.endsWith("{"))
                    || (language == Language.AVRASM2 && trimmed.endsWith(":"))) {
                indent.append("    ");
            }
            insertTextAtCaret("\n" + indent);
        } catch (BadLocationException ignored) {
        }
    }

    /** Inserta un control opcional a la izquierda de los botones Editar/Copiar/Guardar. */
    public void addHeaderControl(JComponent control) {
        rightBar.add(control, 0);
        rightBar.revalidate();
        rightBar.repaint();
    }

    public void setProjectNameSupplier(Supplier<String> supplier) {
        this.projectNameSupplier = supplier != null ? supplier : () -> "firmware_manual";
    }

    public void setLoadFirmwareHandler(Consumer<SimEngineController.CustomFirmware> handler) {
        this.loadFirmwareHandler = handler;
    }

    public Language language() {
        return language;
    }

    public boolean isEditMode() {
        return editMode;
    }

    public boolean isManuallyModified() {
        return manuallyModified;
    }

    public Toolchain.RawBuildResult lastBuildResult() {
        return lastBuildResult;
    }

    public AvrCodeCompleter completer() {
        return completer;
    }

    /** Activa o desactiva el modo de edición raw (IDE integrado). */
    public void setEditMode(boolean enabled) {
        if (this.editMode == enabled) return;
        this.editMode = enabled;
        btnEditToggle.setSelected(enabled);
        btnEditToggle.setText(enabled ? "Editando" : "Editar");
        StudioTheme.styleSegmentButton(btnEditToggle, enabled);
        editor.setEditable(enabled);
        ideBar.setVisible(enabled);
        if (enabled) {
            highlightLines(null, false);
            updateIdeStatusLabel();
            editor.requestFocusInWindow();
        } else {
            completer.dismiss();
            clearDiagnosticHighlights();
            consolePanel.setVisible(false);
        }
        revalidate();
        repaint();
    }

    /** Descarta ediciones manuales y vuelve al código generado por el transpilador. */
    public void restoreGeneratedCode() {
        if (manuallyModified && !GraphicsEnvironment.isHeadless() && isDisplayable()) {
            int ans = JOptionPane.showConfirmDialog(this,
                    "¿Descartar los cambios manuales en " + (language == Language.C99 ? "C99" : "AVR ASM")
                            + " y restaurar el código generado por el modelo?",
                    "Restaurar código generado",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (ans != JOptionPane.YES_OPTION) return;
        }
        manuallyModified = false;
        setEditMode(false);
        setCode(lastGeneratedCode);
    }

    /** Restablece de golpe al cargar un proyecto o ejemplo distinto. */
    public void resetToSyncedMode() {
        manuallyModified = false;
        lastBuildResult = null;
        clearDiagnosticHighlights();
        diagListModel.clear();
        consolePanel.setVisible(false);
        setEditMode(false);
    }

    public String getCode() {
        try {
            Document doc = editor.getDocument();
            return doc.getText(0, doc.getLength());
        } catch (BadLocationException ex) {
            return lastGeneratedCode;
        }
    }

    public TraceMap.LineRange highlightedRange() {
        return highlightedRange;
    }

    public void setLineClickListener(IntConsumer listener) {
        this.lineClickListener = listener;
    }

    /**
     * Actualiza el código generado por el transpilador:
     * si el usuario está en modo manual con cambios propios, los protege sin sobrescribirlos.
     */
    public void setGeneratedCode(String code) {
        this.lastGeneratedCode = code == null ? "" : code;
        if (editMode && manuallyModified) {
            ideStatusLabel.setText("Modelo actualizado · pulsa Restaurar para sincronizar cambios del circuito");
            ideStatusLabel.setForeground(StudioTheme.ACCENT_COPPER_GLOW);
            return;
        }
        setCode(this.lastGeneratedCode);
    }

    public void setCode(String code) {
        String text = code == null ? "" : code;
        this.lastGeneratedCode = text;
        this.highlightedRange = null;
        this.highlightTag = null;
        clearDiagnosticHighlights();

        programmaticUpdate = true;
        try {
            StyledDocument doc = new DefaultStyledDocument();
            try {
                doc.insertString(0, text, sPlain);
                stylingInProgress = true;
                applyHighlight(doc, text);
            } catch (BadLocationException ignored) {
            } finally {
                stylingInProgress = false;
            }
            attachDocumentListeners(doc);
            undoManager.discardAllEdits();
            editor.setDocument(doc);
            editor.setCaretPosition(0);
            updateGutterAndMetrics(text);
        } finally {
            programmaticUpdate = false;
        }
    }

    /** Reemplaza el contenido simulando una edición manual del usuario (útil también en pruebas). */
    public void setManualCode(String code) {
        setEditMode(true);
        programmaticUpdate = true;
        try {
            StyledDocument doc = (StyledDocument) editor.getDocument();
            doc.remove(0, doc.getLength());
            doc.insertString(0, code == null ? "" : code, sPlain);
            stylingInProgress = true;
            try {
                applyHighlight(doc, code == null ? "" : code);
            } finally {
                stylingInProgress = false;
            }
            manuallyModified = true;
            updateGutterAndMetrics(getCode());
            updateIdeStatusLabel();
        } catch (BadLocationException ignored) {
        } finally {
            programmaticUpdate = false;
        }
    }

    private void attachDocumentListeners(StyledDocument doc) {
        doc.addUndoableEditListener(e -> {
            if (programmaticUpdate || stylingInProgress) return;
            // Ignorar cambios puramente de atributos de color de sintaxis
            if (e.getEdit() instanceof AbstractDocument.DefaultDocumentEvent dde
                    && dde.getType() == DocumentEvent.EventType.CHANGE) {
                return;
            }
            undoManager.addEdit(e.getEdit());
        });
        doc.addDocumentListener(new DocumentListener() {
            private void onEdit() {
                if (programmaticUpdate || stylingInProgress) return;
                if (editMode) {
                    manuallyModified = true;
                    updateIdeStatusLabel();
                    rehighlightTimer.restart();
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { onEdit(); }
            @Override public void removeUpdate(DocumentEvent e) { onEdit(); }
            @Override public void changedUpdate(DocumentEvent e) {}
        });
    }

    private void rehighlightCurrentDocument() {
        StyledDocument doc = (StyledDocument) editor.getDocument();
        String text = getCode();
        stylingInProgress = true;
        try {
            doc.setCharacterAttributes(0, doc.getLength(), sPlain, true);
            applyHighlight(doc, text);
        } finally {
            stylingInProgress = false;
        }
        updateGutterAndMetrics(text);
    }

    private void updateIdeStatusLabel() {
        if (building) {
            ideStatusLabel.setText(language == Language.C99 ? "Compilando con avr-gcc…" : "Ensamblando con avrasm2…");
            ideStatusLabel.setForeground(StudioTheme.ACCENT_TEAL_BRIGHT);
        } else if (manuallyModified) {
            ideStatusLabel.setText("Editado manualmente · Ctrl+Espacio · Ctrl+B · F5");
            ideStatusLabel.setForeground(StudioTheme.ACCENT_COPPER_GLOW);
        } else {
            ideStatusLabel.setText("Modo IDE · Ctrl+Espacio · Ctrl+B · F5");
            ideStatusLabel.setForeground(StudioTheme.TEXT_MUTED);
        }
    }

    // ==================== Compilación real y carga en simavr ====================

    /** Compila/ensambla el buffer actual de forma síncrona (útil para API y pruebas). */
    public Toolchain.RawBuildResult verifyNow() throws Exception {
        Toolchain tc = Toolchain.detect();
        Path workDir = Files.createTempDirectory("transpilador-v2-ide-");
        String src = getCode();
        String name = projectNameSupplier.get();
        return language == Language.C99
                ? tc.compileRawC(name, src, workDir)
                : tc.assembleRawAsm(name, src, workDir);
    }

    public void runBuildAsync(boolean loadIntoSimavr) {
        if (building) return;
        building = true;
        btnVerify.setEnabled(false);
        btnLoadSim.setEnabled(false);
        updateIdeStatusLabel();
        final String src = getCode();
        final String projName = projectNameSupplier.get();

        BUILD_WORKER.submit(() -> {
            Toolchain.RawBuildResult res;
            try {
                Toolchain tc = Toolchain.detect();
                Path workDir = Files.createTempDirectory("transpilador-v2-ide-");
                res = language == Language.C99
                        ? tc.compileRawC(projName, src, workDir)
                        : tc.assembleRawAsm(projName, src, workDir);
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                res = new Toolchain.RawBuildResult(false, null,
                        List.of(new Toolchain.BuildDiagnostic(0, 0, Toolchain.DiagnosticSeverity.ERROR, msg)),
                        0, msg);
            }
            final Toolchain.RawBuildResult finalRes = res;
            SwingUtilities.invokeLater(() -> onBuildCompleted(finalRes, loadIntoSimavr));
        });
    }

    private void onBuildCompleted(Toolchain.RawBuildResult res, boolean loadIntoSimavr) {
        building = false;
        btnVerify.setEnabled(true);
        btnLoadSim.setEnabled(true);
        lastBuildResult = res;
        updateIdeStatusLabel();
        applyBuildDiagnostics(res);

        if (res.success() && loadIntoSimavr && loadFirmwareHandler != null && res.artifact() != null) {
            SimEngineController.Backend b = language == Language.C99
                    ? SimEngineController.Backend.C99
                    : SimEngineController.Backend.ASM;
            String label = language == Language.C99 ? "Firmware manual (C99)" : "Firmware manual (AVR ASM)";
            loadFirmwareHandler.accept(new SimEngineController.CustomFirmware(
                    label, b, res.artifact().hexFile(), res.artifact().elfFile(), res.flashBytes()));
        }
    }

    /** Aplica el resultado de compilación a la consola inferior y pinta marcas de error/advertencia en línea. */
    public void applyBuildDiagnostics(Toolchain.RawBuildResult res) {
        clearDiagnosticHighlights();
        diagListModel.clear();
        if (res == null) {
            consolePanel.setVisible(false);
            return;
        }
        for (Toolchain.BuildDiagnostic d : res.diagnostics()) {
            diagListModel.addElement(d);
            if (d.line() > 0 && (d.severity() == Toolchain.DiagnosticSeverity.ERROR
                    || d.severity() == Toolchain.DiagnosticSeverity.WARNING)) {
                markDiagnosticLine(d.line(), d.severity() == Toolchain.DiagnosticSeverity.ERROR);
            }
        }
        if (res.success()) {
            double pct = (res.flashBytes() * 100.0) / ATMEGA328P_FLASH_BYTES;
            String warnPart = res.warningCount() > 0
                    ? " · " + res.warningCount() + (res.warningCount() == 1 ? " advertencia" : " advertencias")
                    : "";
            consoleSummaryLabel.setForeground(StudioTheme.ACCENT_TEAL_BRIGHT);
            consoleSummaryLabel.setText(String.format(Locale.ROOT,
                    "Compilación correcta (%s) · Flash: %d B (%.1f%% de 32 KB)%s",
                    language == Language.C99 ? "avr-gcc" : "avrasm2", res.flashBytes(), pct, warnPart));
            if (diagListModel.isEmpty()) {
                diagListModel.addElement(new Toolchain.BuildDiagnostic(0, 0, Toolchain.DiagnosticSeverity.NOTE,
                        "Sin errores ni advertencias. Pulsa «Cargar en simavr» (F5) para probar el .hex."));
            }
        } else {
            long errs = res.errorCount();
            long warns = res.warningCount();
            consoleSummaryLabel.setForeground(StudioTheme.ACCENT_ROSE);
            consoleSummaryLabel.setText(errs + (errs == 1 ? " error" : " errores")
                    + (warns > 0 ? " · " + warns + (warns == 1 ? " advertencia" : " advertencias") : "")
                    + " · haz clic en un diagnóstico para saltar a la línea");
            // Saltar automáticamente al primer error con número de línea
            res.diagnostics().stream()
                    .filter(d -> d.severity() == Toolchain.DiagnosticSeverity.ERROR && d.line() > 0)
                    .findFirst()
                    .ifPresent(d -> jumpToLine(d.line(), d.column()));
        }
        consolePanel.setVisible(true);
        revalidate();
        repaint();
    }

    private void markDiagnosticLine(int oneBasedLine, boolean isError) {
        Element root = editor.getDocument().getDefaultRootElement();
        int count = root.getElementCount();
        if (oneBasedLine < 1 || oneBasedLine > count) return;
        Element el = root.getElement(oneBasedLine - 1);
        int start = el.getStartOffset();
        int end = Math.min(editor.getDocument().getLength(), Math.max(start + 1, el.getEndOffset()));
        try {
            Object tag = editor.getHighlighter().addHighlight(start, end, isError ? ERROR_PAINTER : WARN_PAINTER);
            diagHighlightTags.add(tag);
        } catch (BadLocationException ignored) {
        }
    }

    private void clearDiagnosticHighlights() {
        Highlighter hl = editor.getHighlighter();
        for (Object tag : diagHighlightTags) {
            hl.removeHighlight(tag);
        }
        diagHighlightTags.clear();
    }

    public void jumpToLine(int oneBasedLine, int oneBasedColumn) {
        Element root = editor.getDocument().getDefaultRootElement();
        int count = root.getElementCount();
        if (count == 0) return;
        int idx = Math.clamp(oneBasedLine - 1, 0, count - 1);
        Element el = root.getElement(idx);
        int colOff = oneBasedColumn > 1 ? (oneBasedColumn - 1) : 0;
        int pos = Math.min(el.getEndOffset() - 1, el.getStartOffset() + colOff);
        pos = Math.clamp(pos, 0, editor.getDocument().getLength());
        editor.setCaretPosition(pos);
        try {
            var r = editor.modelToView2D(pos);
            if (r != null) {
                Rectangle rect = r.getBounds();
                rect.grow(0, 36);
                editor.scrollRectToVisible(rect);
            }
        } catch (BadLocationException ignored) {
        }
        editor.requestFocusInWindow();
    }

    /** Resalta visualmente un rango inclusivo de líneas (1-based) y opcionalmente desplaza la vista hasta él. */
    public void highlightLines(TraceMap.LineRange range, boolean scrollIntoView) {
        this.highlightedRange = range;
        Highlighter hl = editor.getHighlighter();
        if (highlightTag != null) {
            hl.removeHighlight(highlightTag);
            highlightTag = null;
        }
        String text = getCode();
        if (range == null || text.isEmpty()) {
            editor.repaint();
            return;
        }
        Element root = editor.getDocument().getDefaultRootElement();
        int lineCount = root.getElementCount();
        int startIdx = Math.clamp(range.startLine() - 1, 0, Math.max(0, lineCount - 1));
        int endIdx = Math.clamp(range.endLine() - 1, startIdx, Math.max(0, lineCount - 1));
        int startOff = root.getElement(startIdx).getStartOffset();
        int endOff = Math.min(editor.getDocument().getLength(), root.getElement(endIdx).getEndOffset());
        try {
            highlightTag = hl.addHighlight(startOff, endOff, BLOCK_PAINTER);
            if (scrollIntoView) {
                var rStart = editor.modelToView2D(startOff);
                var rEnd = editor.modelToView2D(Math.max(startOff, endOff - 1));
                if (rStart != null && rEnd != null) {
                    Rectangle union = rStart.getBounds().union(rEnd.getBounds());
                    union.grow(0, 24);
                    editor.scrollRectToVisible(union);
                }
            }
        } catch (BadLocationException ignored) {
        }
        editor.repaint();
    }

    public String tooltipForLine(int oneBasedLine) {
        if (language != Language.AVRASM2) return null;
        Element root = editor.getDocument().getDefaultRootElement();
        if (oneBasedLine < 1 || oneBasedLine > root.getElementCount()) return null;
        Element el = root.getElement(oneBasedLine - 1);
        try {
            String line = editor.getDocument().getText(el.getStartOffset(), el.getEndOffset() - el.getStartOffset());
            return AvrInstructionCatalog.tooltipHtmlForLine(line);
        } catch (BadLocationException ex) {
            return null;
        }
    }

    private int lineNumberAtPoint(Point pt) {
        if (editor.getDocument().getLength() == 0) return -1;
        int pos = editor.viewToModel2D(pt);
        if (pos < 0) return -1;
        return editor.getDocument().getDefaultRootElement().getElementIndex(pos) + 1;
    }

    private String lineTextAtPoint(Point pt) {
        int line = lineNumberAtPoint(pt);
        if (line < 1) return null;
        Element el = editor.getDocument().getDefaultRootElement().getElement(line - 1);
        try {
            return editor.getDocument().getText(el.getStartOffset(), el.getEndOffset() - el.getStartOffset());
        } catch (BadLocationException ex) {
            return null;
        }
    }

    private void updateGutterAndMetrics(String text) {
        int lines = text.isEmpty() ? 1 : (int) text.chars().filter(c -> c == '\n').count() + 1;
        StringBuilder nums = new StringBuilder(lines * 5);
        int width = Math.max(2, String.valueOf(lines).length());
        String fmt = "%" + width + "d";
        for (int i = 1; i <= lines; i++) {
            if (i > 1) nums.append('\n');
            nums.append(String.format(fmt, i));
        }
        gutter.setText(nums.toString());

        if (language == Language.AVRASM2) {
            long instrs = text.lines()
                    .map(l -> l.replaceFirst(";.*", "").trim())
                    .filter(l -> !l.isEmpty() && !l.startsWith(".") && !l.endsWith(":"))
                    .count();
            metricsLabel.setText(lines + " lín · " + instrs + " instr");
        } else {
            metricsLabel.setText(lines + " líneas");
        }
    }

    private void applyHighlight(StyledDocument doc, String text) {
        if (language == Language.C99) {
            Matcher m = C_TOKENS.matcher(text);
            while (m.find()) {
                SimpleAttributeSet style = null;
                if (m.group("COMMENT") != null) style = sComment;
                else if (m.group("PREPROC") != null) style = sLabel;
                else if (m.group("STRING") != null) style = sString;
                else if (m.group("NUMBER") != null) style = sNumber;
                else if (m.group("IDENT") != null) {
                    String id = m.group("IDENT");
                    if (C_KEYWORDS.contains(id)) style = sKeyword;
                    else if (C_TYPES.contains(id)) style = sType;
                    else if (id.equals(id.toUpperCase()) && id.length() >= 2 && Character.isLetter(id.charAt(0))) style = sRegister;
                }
                if (style != null) {
                    doc.setCharacterAttributes(m.start(), m.end() - m.start(), style, true);
                }
            }
        } else {
            Matcher m = ASM_TOKENS.matcher(text);
            while (m.find()) {
                SimpleAttributeSet style = null;
                if (m.group("COMMENT") != null) style = sComment;
                else if (m.group("STRING") != null) style = sString;
                else if (m.group("DIRECTIVE") != null) style = sType;
                else if (m.group("LABEL") != null) style = sLabel;
                else if (m.group("REG") != null) style = sRegister;
                else if (m.group("NUMBER") != null) style = sNumber;
                else if (m.group("IDENT") != null) {
                    String id = m.group("IDENT");
                    if (ASM_MNEMONICS.contains(id.toLowerCase())) style = sKeyword;
                    else if (id.equals(id.toUpperCase()) && id.length() >= 2 && Character.isLetter(id.charAt(0))) style = sRegister;
                }
                if (style != null) {
                    doc.setCharacterAttributes(m.start(), m.end() - m.start(), style, true);
                }
            }
        }
    }

    private static SimpleAttributeSet attr(Color fg, boolean bold) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, fg);
        StyleConstants.setBold(a, bold);
        return a;
    }

    private record LineBandPainter(Color fill, Color bar) implements Highlighter.HighlightPainter {
        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            try {
                var r0 = c.modelToView2D(p0);
                var r1 = c.modelToView2D(Math.max(p0, p1 - 1));
                if (r0 == null || r1 == null) return;
                int y = (int) Math.floor(r0.getY());
                int h = (int) Math.ceil(r1.getY() + r1.getHeight() - r0.getY());
                int w = c.getWidth();
                g.setColor(fill);
                g.fillRect(0, y, w, Math.max(1, h));
                g.setColor(bar);
                g.fillRect(0, y, 3, Math.max(1, h));
            } catch (BadLocationException ignored) {
            }
        }
    }

    private static final class DiagnosticCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object val, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel lbl = (JLabel) super.getListCellRendererComponent(list, "", index, isSelected, cellHasFocus);
            lbl.setOpaque(true);
            lbl.setBackground(isSelected ? StudioTheme.HOVER_BG : StudioTheme.CANVAS_BG);
            lbl.setBorder(new EmptyBorder(3, 12, 3, 12));
            if (val instanceof Toolchain.BuildDiagnostic d) {
                String loc = d.line() > 0 ? String.format("Lín %-4d", d.line()) : "General ";
                String sevText = switch (d.severity()) {
                    case ERROR -> "Error";
                    case WARNING -> "Aviso";
                    case NOTE -> "Info ";
                };
                String sevColor = switch (d.severity()) {
                    case ERROR -> "#DE5B57";
                    case WARNING -> "#D98C4E";
                    case NOTE -> "#4CC6BC";
                };
                String msg = d.message().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                lbl.setText("<html><span style='font-family:Consolas,monospace; font-size:11px; color:#848E89;'>"
                        + loc.replace(" ", "&nbsp;") + "</span>&nbsp;&nbsp;"
                        + "<span style='font-family:Consolas,monospace; font-size:11px; font-weight:bold; color:" + sevColor + ";'>"
                        + sevText + "</span>&nbsp;&nbsp;"
                        + "<span style='font-family:Segoe UI,sans-serif; font-size:11.5px; color:#ECEFED;'>"
                        + msg + "</span></html>");
            }
            return lbl;
        }
    }
}
