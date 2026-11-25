package dev.transpilador.v2.ui;

import dev.transpilador.v2.emit.AvrInstructionCatalog;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autocompletado inteligente offline para el IDE integrado ATmega328P (C99 y AVRASM2).
 * Combina el catálogo didáctico de instrucciones AVR, registros físicos de periféricos
 * y símbolos escaneados en vivo del archivo actual (etiquetas, .def, .equ, funciones y variables).
 */
public final class AvrCodeCompleter {
    public record CompletionItem(String insertText, String display, String detail, String kind, int baseRank) {}

    private static final int MAX_ITEMS = 16;

    private static final List<CompletionItem> ASM_STATIC = buildAsmStatic();
    private static final List<CompletionItem> C99_STATIC = buildC99Static();

    private static final Pattern ASM_LABEL = Pattern.compile("(?m)^[ \\t]*([A-Za-z_][A-Za-z0-9_]*):");
    private static final Pattern ASM_DEF = Pattern.compile("(?mi)^\\s*\\.def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(\\S+)");
    private static final Pattern ASM_EQU = Pattern.compile("(?mi)^\\s*\\.equ\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^\\r\\n;]+)");

    private static final Pattern C_FUNC = Pattern.compile("(?m)\\b(?:static\\s+)?(?:void|uint8_t|uint16_t|uint32_t|int|char|bool)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern C_VAR = Pattern.compile("(?m)\\b(?:volatile\\s+)?(?:uint8_t|uint16_t|uint32_t|int8_t|int16_t|int|char)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|;|\\[)");
    private static final Pattern C_DEFINE = Pattern.compile("(?m)^[ \\t]*#define\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern C_ENUM_VAL = Pattern.compile("\\b(EST_[A-Za-z0-9_]+|FASE_[A-Za-z0-9_]+)\\b");

    private final JTextPane editor;
    private final CodeViewerPane.Language language;
    private final BooleanSupplier editableCheck;

    private final DefaultListModel<CompletionItem> listModel = new DefaultListModel<>();
    private final JList<CompletionItem> list = new JList<>(listModel);
    private final JPopupMenu popup = new JPopupMenu();

    private int tokenStartOffset = -1;

    private AvrCodeCompleter(JTextPane editor, CodeViewerPane.Language language, BooleanSupplier editableCheck) {
        this.editor = editor;
        this.language = language;
        this.editableCheck = editableCheck;
        buildPopup();
        bindEvents();
    }

    public static AvrCodeCompleter install(JTextPane editor, CodeViewerPane.Language language, BooleanSupplier editableCheck) {
        return new AvrCodeCompleter(editor, language, editableCheck);
    }

    public void dismiss() {
        if (popup.isVisible()) {
            popup.setVisible(false);
        }
        tokenStartOffset = -1;
    }

    public boolean isPopupVisible() {
        return popup.isVisible();
    }

    /** Abre el menú de sugerencias explícitamente en la posición actual del cursor (Ctrl+Espacio). */
    public void triggerManual() {
        if (!editableCheck.getAsBoolean()) return;
        updateSuggestions(true);
    }

    private void buildPopup() {
        popup.setFocusable(false);
        popup.setBorder(BorderFactory.createLineBorder(StudioTheme.BORDER_STRONG, 1));
        popup.setBackground(StudioTheme.SURFACE_BG);

        list.setFocusable(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(StudioTheme.SURFACE_BG);
        list.setForeground(StudioTheme.TEXT_PRIMARY);
        list.setFixedCellHeight(26);
        list.setCellRenderer(new CompletionRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1) {
                    acceptSelected();
                }
            }
        });

        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(null);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setPreferredSize(new Dimension(370, 210));
        sp.getViewport().setBackground(StudioTheme.SURFACE_BG);

        popup.add(sp);
    }

    private void bindEvents() {
        editor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!editableCheck.getAsBoolean()) return;
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_SPACE) {
                    e.consume();
                    updateSuggestions(true);
                    return;
                }
                if (!popup.isVisible()) return;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> {
                        e.consume();
                        int n = listModel.getSize();
                        if (n > 0) {
                            int next = (list.getSelectedIndex() + 1) % n;
                            list.setSelectedIndex(next);
                            list.ensureIndexIsVisible(next);
                        }
                    }
                    case KeyEvent.VK_UP -> {
                        e.consume();
                        int n = listModel.getSize();
                        if (n > 0) {
                            int prev = (list.getSelectedIndex() - 1 + n) % n;
                            list.setSelectedIndex(prev);
                            list.ensureIndexIsVisible(prev);
                        }
                    }
                    case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                        e.consume();
                        acceptSelected();
                    }
                    case KeyEvent.VK_ESCAPE -> {
                        e.consume();
                        dismiss();
                    }
                    case KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_HOME, KeyEvent.VK_END -> dismiss();
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (!editableCheck.getAsBoolean()) return;
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_UP || code == KeyEvent.VK_DOWN || code == KeyEvent.VK_ENTER
                        || code == KeyEvent.VK_TAB || code == KeyEvent.VK_ESCAPE
                        || code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT) {
                    return;
                }
                char ch = e.getKeyChar();
                if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '.' || ch == '#' || code == KeyEvent.VK_BACK_SPACE) {
                    updateSuggestions(false);
                } else {
                    dismiss();
                }
            }
        });

        editor.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { dismiss(); }
        });
        editor.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { dismiss(); }
        });
    }

    private void updateSuggestions(boolean manualTrigger) {
        int caret = editor.getCaretPosition();
        Document doc = editor.getDocument();
        String fullText;
        try {
            fullText = doc.getText(0, doc.getLength());
        } catch (BadLocationException ex) {
            dismiss();
            return;
        }
        int start = caret;
        while (start > 0 && isTokenChar(fullText.charAt(start - 1), language)) {
            start--;
        }
        String prefix = fullText.substring(start, caret);
        boolean specialPrefix = (language == CodeViewerPane.Language.AVRASM2 && prefix.startsWith("."))
                || (language == CodeViewerPane.Language.C99 && prefix.startsWith("#"));
        if (!manualTrigger && !specialPrefix && prefix.length() < 2) {
            dismiss();
            return;
        }

        List<CompletionItem> items = suggest(language, fullText, prefix);
        if (items.isEmpty() || (items.size() == 1 && items.getFirst().insertText().equals(prefix))) {
            dismiss();
            return;
        }

        this.tokenStartOffset = start;
        listModel.clear();
        for (CompletionItem it : items) listModel.addElement(it);
        list.setSelectedIndex(0);
        list.ensureIndexIsVisible(0);

        int visibleRows = Math.min(8, items.size());
        JScrollPane sp = (JScrollPane) popup.getComponent(0);
        sp.setPreferredSize(new Dimension(380, visibleRows * 26 + 4));
        popup.pack();

        try {
            var r = editor.modelToView2D(caret);
            if (r == null) return;
            int px = (int) Math.round(r.getX());
            int py = (int) Math.round(r.getY() + r.getHeight() + 3);
            if (!popup.isVisible()) {
                popup.show(editor, px, py);
            } else {
                popup.setVisible(false);
                popup.show(editor, px, py);
            }
            editor.requestFocusInWindow();
        } catch (BadLocationException ex) {
            dismiss();
        }
    }

    private void acceptSelected() {
        CompletionItem chosen = list.getSelectedValue();
        int start = tokenStartOffset;
        dismiss();
        if (chosen == null || start < 0) return;
        int caret = editor.getCaretPosition();
        if (caret < start) return;
        try {
            Document doc = editor.getDocument();
            doc.remove(start, caret - start);
            doc.insertString(start, chosen.insertText(), null);
            int nextPos = start + chosen.insertText().length();
            if (chosen.insertText().endsWith("()")) {
                nextPos -= 1; // Colocar cursor dentro de los paréntesis
            }
            editor.setCaretPosition(Math.min(nextPos, doc.getLength()));
        } catch (BadLocationException ignored) {
        }
    }

    private static boolean isTokenChar(char c, CodeViewerPane.Language lang) {
        if (Character.isLetterOrDigit(c) || c == '_') return true;
        if (lang == CodeViewerPane.Language.AVRASM2 && c == '.') return true;
        return lang == CodeViewerPane.Language.C99 && c == '#';
    }

    /** API pura (testeable headless) que devuelve sugerencias ordenadas para un prefijo y buffer dados. */
    public static List<CompletionItem> suggest(CodeViewerPane.Language language, String documentText, String rawPrefix) {
        String prefix = rawPrefix == null ? "" : rawPrefix.trim();
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);

        Map<String, CompletionItem> merged = new LinkedHashMap<>();
        // 1) Símbolos locales del buffer (prioridad alta)
        for (CompletionItem local : extractLocalSymbols(language, documentText == null ? "" : documentText)) {
            merged.putIfAbsent(local.insertText().toLowerCase(Locale.ROOT), local);
        }
        // 2) Catálogo estático del lenguaje y ATmega328P
        List<CompletionItem> catalog = (language == CodeViewerPane.Language.AVRASM2) ? ASM_STATIC : C99_STATIC;
        for (CompletionItem item : catalog) {
            merged.putIfAbsent(item.insertText().toLowerCase(Locale.ROOT), item);
        }

        List<CompletionItem> matched = new ArrayList<>();
        for (CompletionItem item : merged.values()) {
            if (lowerPrefix.isEmpty()) {
                matched.add(item);
                continue;
            }
            String cand = item.insertText().toLowerCase(Locale.ROOT);
            if (cand.startsWith(lowerPrefix) || cand.contains(lowerPrefix)) {
                matched.add(item);
            }
        }

        matched.sort((a, b) -> {
            int sa = score(a, prefix, lowerPrefix);
            int sb = score(b, prefix, lowerPrefix);
            if (sa != sb) return Integer.compare(sa, sb);
            return a.display().compareToIgnoreCase(b.display());
        });
        return matched.size() > MAX_ITEMS ? List.copyOf(matched.subList(0, MAX_ITEMS)) : List.copyOf(matched);
    }

    private static int score(CompletionItem item, String prefix, String lowerPrefix) {
        if (lowerPrefix.isEmpty()) return item.baseRank();
        String text = item.insertText();
        String lower = text.toLowerCase(Locale.ROOT);
        if (text.startsWith(prefix)) return item.baseRank();
        if (lower.startsWith(lowerPrefix)) return item.baseRank() + 10;
        return item.baseRank() + 40;
    }

    private static List<CompletionItem> extractLocalSymbols(CodeViewerPane.Language language, String text) {
        List<CompletionItem> out = new ArrayList<>();
        if (text.isEmpty()) return out;
        if (language == CodeViewerPane.Language.AVRASM2) {
            Matcher mDef = ASM_DEF.matcher(text);
            while (mDef.find()) {
                out.add(new CompletionItem(mDef.group(1), mDef.group(1), "Alias .def = " + mDef.group(2), "sym", 5));
            }
            Matcher mEqu = ASM_EQU.matcher(text);
            while (mEqu.find()) {
                out.add(new CompletionItem(mEqu.group(1), mEqu.group(1), "Constante .equ = " + mEqu.group(2).trim(), "sym", 6));
            }
            Matcher mLbl = ASM_LABEL.matcher(text);
            while (mLbl.find()) {
                String lbl = mLbl.group(1);
                out.add(new CompletionItem(lbl, lbl, "Etiqueta local", "sym", 7));
            }
        } else {
            Matcher mFn = C_FUNC.matcher(text);
            while (mFn.find()) {
                String fn = mFn.group(1);
                if (!fn.equals("main")) {
                    out.add(new CompletionItem(fn + "()", fn + "()", "Función local", "sym", 5));
                }
            }
            Matcher mDef = C_DEFINE.matcher(text);
            while (mDef.find()) {
                out.add(new CompletionItem(mDef.group(1), mDef.group(1), "Macro #define local", "sym", 6));
            }
            Matcher mVar = C_VAR.matcher(text);
            while (mVar.find()) {
                out.add(new CompletionItem(mVar.group(1), mVar.group(1), "Variable local", "sym", 7));
            }
            Matcher mEnum = C_ENUM_VAL.matcher(text);
            while (mEnum.find()) {
                out.add(new CompletionItem(mEnum.group(1), mEnum.group(1), "Estado FSM local", "sym", 7));
            }
        }
        return out;
    }

    private static List<CompletionItem> buildAsmStatic() {
        List<CompletionItem> list = new ArrayList<>();
        for (var doc : AvrInstructionCatalog.all().values()) {
            list.add(new CompletionItem(doc.mnemonic(), doc.mnemonic(),
                    doc.summary() + " (" + doc.cycles() + ")", "instr", 10));
        }
        // Directivas AVRASM2
        add(list, ".include", "Incluir definiciones m328Pdef.inc", "dir", 12);
        add(list, ".def", "Asignar alias simbólico a un registro", "dir", 12);
        add(list, ".equ", "Definir constante simbólica", "dir", 12);
        add(list, ".org", "Fijar dirección en Flash o SRAM", "dir", 12);
        add(list, ".cseg", "Segmento de código en Flash", "dir", 12);
        add(list, ".dseg", "Segmento de datos en SRAM (0x0100)", "dir", 12);
        add(list, ".db", "Bytes constantes en Flash", "dir", 12);
        add(list, ".byte", "Reservar bytes en SRAM", "dir", 12);
        add(list, "low()", "Byte bajo de expresión de 16 bits", "macro", 16);
        add(list, "high()", "Byte alto de expresión de 16 bits", "macro", 16);
        add(list, "RAMEND", "Última dirección de SRAM (0x08FF)", "io", 16);

        // Registros generales y punteros
        for (int i = 0; i < 32; i++) {
            String note = i >= 16 ? "Registro alto (soporta ldi/cpi/andi/ori)" : "Registro general bajo";
            list.add(new CompletionItem("r" + i, "r" + i, note, "reg", i >= 16 ? 18 : 22));
        }
        for (String ptr : List.of("XL", "XH", "YL", "YH", "ZL", "ZH", "SPL", "SPH", "SREG")) {
            add(list, ptr, "Registro especial CPU ATmega328P", "reg", 15);
        }
        addAtmegaIoRegs(list);
        return List.copyOf(list);
    }

    private static List<CompletionItem> buildC99Static() {
        List<CompletionItem> list = new ArrayList<>();
        // Tipos y palabras clave C99
        for (String kw : List.of("uint8_t", "uint16_t", "uint32_t", "int8_t", "int16_t", "void", "volatile", "static", "const", "enum")) {
            add(list, kw, "Tipo / calificador C99", "type", 10);
        }
        for (String kw : List.of("while", "for", "if", "else", "switch", "case", "return", "break", "continue")) {
            add(list, kw, "Palabra clave C99", "kw", 14);
        }
        // Preprocesador y utilidades AVR-Libc
        add(list, "#include <avr/io.h>", "Registros y bits del ATmega328P", "dir", 11);
        add(list, "#include <stdint.h>", "Enteros exactos uint8_t / uint16_t", "dir", 11);
        add(list, "#include <util/delay.h>", "Retardos _delay_ms / _delay_us", "dir", 11);
        add(list, "#include <avr/interrupt.h>", "Interrupciones ISR() / sei() / cli()", "dir", 12);
        add(list, "#define F_CPU ", "Frecuencia del reloj en Hz", "dir", 11);
        add(list, "_delay_ms()", "Retardo bloqueante en milisegundos", "macro", 12);
        add(list, "_delay_us()", "Retardo bloqueante en microsegundos", "macro", 13);
        add(list, "sei()", "Habilita interrupciones globales (SREG.I = 1)", "macro", 14);
        add(list, "cli()", "Deshabilita interrupciones globales (SREG.I = 0)", "macro", 14);
        add(list, "F_CPU", "Frecuencia configurada del ATmega328P", "macro", 14);

        // Pines PB/PC/PD
        for (int i = 0; i <= 5; i++) add(list, "PB" + i, "Bit " + i + " del Puerto B", "io", 16);
        for (int i = 0; i <= 5; i++) add(list, "PC" + i, "Bit " + i + " del Puerto C / ADC" + i, "io", 16);
        for (int i = 0; i <= 7; i++) add(list, "PD" + i, "Bit " + i + " del Puerto D", "io", 16);

        // Bits de periféricos frecuentes
        for (String bit : List.of("REFS0", "ADLAR", "ADEN", "ADSC", "ADPS2", "ADPS1", "ADPS0",
                "WGM00", "WGM01", "COM0A1", "COM0B1", "CS00", "CS01", "CS02",
                "WGM10", "WGM12", "COM1A1", "COM1B1", "CS10", "CS11", "CS12",
                "WGM20", "WGM21", "COM2A1", "COM2B1", "CS20", "CS21", "CS22",
                "RXEN0", "TXEN0", "UDRE0", "RXC0", "UCSZ00", "UCSZ01")) {
            add(list, bit, "Bit de control ATmega328P", "io", 18);
        }
        addAtmegaIoRegs(list);
        return List.copyOf(list);
    }

    private static void addAtmegaIoRegs(List<CompletionItem> list) {
        add(list, "DDRB", "Dirección Puerto B (1=salida, 0=entrada)", "io", 13);
        add(list, "PORTB", "Salida / pull-up Puerto B (PB0..PB5)", "io", 13);
        add(list, "PINB", "Lectura lógica física Puerto B", "io", 13);
        add(list, "DDRC", "Dirección Puerto C (PC0..PC5)", "io", 13);
        add(list, "PORTC", "Salida / pull-up Puerto C", "io", 13);
        add(list, "PINC", "Lectura lógica física Puerto C", "io", 13);
        add(list, "DDRD", "Dirección Puerto D (PD0..PD7)", "io", 13);
        add(list, "PORTD", "Salida / pull-up Puerto D", "io", 13);
        add(list, "PIND", "Lectura lógica física Puerto D", "io", 13);
        add(list, "ADMUX", "Selección de referencia y canal ADC", "io", 15);
        add(list, "ADCSRA", "Control, arranque (ADSC) y prescaler ADC", "io", 15);
        add(list, "ADCL", "Byte bajo del resultado ADC (10 bits)", "io", 16);
        add(list, "ADCH", "Byte alto del resultado ADC", "io", 16);
        add(list, "ADC", "Resultado ADC completo de 16 bits (C99)", "io", 15);
        add(list, "TCCR0A", "Control Timer0 A (Fast PWM OC0A/OC0B)", "io", 15);
        add(list, "TCCR0B", "Control Timer0 B (prescaler)", "io", 15);
        add(list, "OCR0A", "Ciclo de trabajo PWM PD6 (OC0A)", "io", 15);
        add(list, "OCR0B", "Ciclo de trabajo PWM PD5 (OC0B)", "io", 15);
        add(list, "TCCR1A", "Control Timer1 A (PWM OC1A/OC1B)", "io", 15);
        add(list, "TCCR1B", "Control Timer1 B (prescaler)", "io", 15);
        add(list, "OCR1A", "Ciclo de trabajo PWM PB1 (OC1A)", "io", 15);
        add(list, "OCR1B", "Ciclo de trabajo PWM PB2 (OC1B)", "io", 15);
        add(list, "OCR1AL", "Byte bajo OCR1A (ASM)", "io", 16);
        add(list, "OCR1AH", "Byte alto OCR1A (ASM)", "io", 16);
        add(list, "OCR1BL", "Byte bajo OCR1B (ASM)", "io", 16);
        add(list, "OCR1BH", "Byte alto OCR1B (ASM)", "io", 16);
        add(list, "TCCR2A", "Control Timer2 A (PWM OC2A/OC2B)", "io", 15);
        add(list, "TCCR2B", "Control Timer2 B (prescaler)", "io", 15);
        add(list, "OCR2A", "Ciclo de trabajo PWM PB3 (OC2A)", "io", 15);
        add(list, "OCR2B", "Ciclo de trabajo PWM PD3 (OC2B)", "io", 15);
        add(list, "UBRR0L", "Baudrate USART0 byte bajo", "io", 15);
        add(list, "UBRR0H", "Baudrate USART0 byte alto", "io", 15);
        add(list, "UCSR0A", "Estado USART0 (UDRE0 / RXC0)", "io", 15);
        add(list, "UCSR0B", "Habilitación RX/TX USART0", "io", 15);
        add(list, "UCSR0C", "Formato trama USART0 (8N1)", "io", 15);
        add(list, "UDR0", "Registro de datos TX/RX USART0", "io", 15);
    }

    private static void add(List<CompletionItem> list, String text, String detail, String kind, int rank) {
        list.add(new CompletionItem(text, text, detail, kind, rank));
    }

    private static final class CompletionRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object val, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel lbl = (JLabel) super.getListCellRendererComponent(list, "", index, isSelected, cellHasFocus);
            lbl.setOpaque(true);
            lbl.setBackground(isSelected ? StudioTheme.ACCENT_TEAL_SOFT : StudioTheme.SURFACE_BG);
            lbl.setBorder(new EmptyBorder(3, 9, 3, 9));
            if (val instanceof CompletionItem item) {
                String nameColor = isSelected ? "#ECEFED" : switch (item.kind()) {
                    case "instr", "kw" -> "#4CC6BC";
                    case "reg", "io" -> "#E0A365";
                    case "dir", "type" -> "#B59CE6";
                    case "sym" -> "#ECEFED";
                    default -> "#36ADA3";
                };
                String disp = esc(item.display());
                String det = esc(item.detail());
                lbl.setText("<html><span style='font-family:Consolas,monospace; font-size:11.5px; font-weight:bold; color:"
                        + nameColor + ";'>" + disp + "</span>&nbsp;&nbsp;"
                        + "<span style='font-family:Segoe UI,sans-serif; font-size:11px; color:#848E89;'>"
                        + det + "</span></html>");
            }
            return lbl;
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
