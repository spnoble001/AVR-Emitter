package dev.transpilador.v2.ui;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Set;

/**
 * Sistema visual unificado del Estudio Didáctico ATmega328P (v2),
 * inspirado en la estética editorial de ingeniería de Lean 4:
 *  - Superficies carbón mineral mate (#0F1110 / #161918) con filetes arquitectónicos de 1 px.
 *  - Dos acentos con rol estricto: Veridian teal (#36ADA3) para jerarquía y verificación,
 *    y cobre quemado (#AD6B3B) para el CTA primario único y trazabilidad física.
 *  - Tipografía editorial en Noto Serif / Georgia + Segoe UI (sentence case) + Consolas tabular.
 */
public final class StudioTheme {
    // Superficies carbón mineral (Lean 4)
    public static final Color CANVAS_BG = new Color(15, 17, 16);       // #0F1110
    public static final Color SURFACE_BG = new Color(22, 25, 24);      // #161918
    public static final Color SUBTLE_BG = new Color(28, 32, 30);       // #1C201E
    public static final Color HOVER_BG = new Color(34, 39, 37);        // #222725
    public static final Color BORDER_COLOR = new Color(38, 43, 41);    // #262B29
    public static final Color BORDER_STRONG = new Color(54, 62, 58);   // #363E3A

    // Tipografía (contraste WCAG AA/AAA verificado sobre #0F1110)
    public static final Color TEXT_PRIMARY = new Color(236, 239, 237);   // #ECEFED
    public static final Color TEXT_SECONDARY = new Color(178, 187, 183); // #B2BBB7
    public static final Color TEXT_MUTED = new Color(132, 142, 137);     // #848E89

    // Barra superior de una sola línea
    public static final Color HEADER_BG = new Color(18, 21, 20);       // #121514
    public static final Color HEADER_BTN_BG = new Color(26, 30, 28);   // #1A1E1C
    public static final Color HEADER_BORDER = new Color(38, 43, 41);   // #262B29
    public static final Color HEADER_TEXT = TEXT_PRIMARY;
    public static final Color HEADER_MUTED = TEXT_MUTED;

    // Acentos principales Lean 4
    public static final Color ACCENT_TEAL = new Color(54, 173, 163);        // #36ADA3
    public static final Color ACCENT_TEAL_BRIGHT = new Color(76, 198, 188); // #4CC6BC
    public static final Color ACCENT_TEAL_SOFT = new Color(20, 38, 36);     // #142624
    public static final Color ACCENT_TEAL_BORDER = new Color(39, 92, 87);   // #275C57

    public static final Color ACCENT_COPPER = new Color(173, 107, 59);       // #AD6B3B
    public static final Color ACCENT_COPPER_HOVER = new Color(194, 122, 70); // #C27A46
    public static final Color ACCENT_COPPER_GLOW = new Color(217, 140, 78);  // #D98C4E
    public static final Color ACCENT_COPPER_SOFT = new Color(36, 26, 19);    // #241A13

    // Semánticos de hardware / diagnóstico (reservados a física y conflictos reales)
    public static final Color ACCENT_BLUE = new Color(75, 147, 186);    // #4B93BA (0V / señales)
    public static final Color ACCENT_EMERALD = ACCENT_TEAL;             // #36ADA3 (verificado / activo)
    public static final Color ACCENT_AMBER = ACCENT_COPPER_GLOW;        // #D98C4E (foco / timer)
    public static final Color ACCENT_VIOLET = new Color(160, 132, 220); // #A084DC (ADC / analógico)
    public static final Color ACCENT_ROSE = new Color(222, 91, 87);     // #DE5B57 (5V / error)
    public static final Color ACCENT_SLATE = TEXT_MUTED;                // #848E89

    // Visores de código C99 y AVR ASM (mismo carbón mineral, cero azul slate genérico)
    public static final Color CODE_BG = new Color(17, 20, 19);          // #111413
    public static final Color CODE_BAR_BG = new Color(22, 25, 24);      // #161918
    public static final Color CODE_GUTTER_BG = new Color(19, 22, 21);   // #131615
    public static final Color CODE_GUTTER_FG = new Color(96, 107, 102); // #606B66
    public static final Color CODE_TEXT = new Color(228, 232, 230);     // #E4E8E6
    public static final Color CODE_KEYWORD = new Color(76, 198, 188);   // Veridian teal
    public static final Color CODE_TYPE = new Color(181, 156, 230);     // Lavanda técnica
    public static final Color CODE_REGISTER = new Color(224, 163, 101); // Cobre cálido
    public static final Color CODE_NUMBER = new Color(230, 133, 126);   // Coral apagado
    public static final Color CODE_STRING = new Color(142, 201, 162);   // Salvia
    public static final Color CODE_COMMENT = new Color(111, 122, 117);  // Gris mineral
    public static final Color CODE_LABEL = new Color(217, 140, 78);     // Cobre

    private static final String SERIF_FONT_FAMILY = detectFontFamily("Noto Serif", "Georgia", "Liberation Serif", Font.SERIF);
    private static final String UI_FONT_FAMILY = detectFontFamily("Segoe UI", "Inter", Font.SANS_SERIF);
    private static final String CODE_FONT_FAMILY = detectFontFamily("JetBrains Mono", "Cascadia Code", "Consolas", Font.MONOSPACED);

    public enum Role {
        INPUT(ACCENT_TEAL, ACCENT_TEAL_SOFT, ACCENT_TEAL_BRIGHT, ACCENT_TEAL_BORDER, new Color(18, 30, 29)),
        LOGIC(ACCENT_VIOLET, new Color(33, 27, 46), new Color(196, 178, 238), new Color(78, 63, 114), new Color(26, 22, 36)),
        OUTPUT(ACCENT_TEAL, ACCENT_TEAL_SOFT, ACCENT_TEAL_BRIGHT, ACCENT_TEAL_BORDER, new Color(18, 30, 29)),
        ALT(ACCENT_COPPER_GLOW, ACCENT_COPPER_SOFT, new Color(232, 174, 122), new Color(110, 70, 39), new Color(30, 23, 18)),
        DANGER(ACCENT_ROSE, new Color(46, 22, 22), new Color(245, 166, 163), new Color(128, 49, 47), new Color(38, 19, 19)),
        STATE(TEXT_MUTED, SUBTLE_BG, TEXT_SECONDARY, BORDER_STRONG, SURFACE_BG);

        public final Color accent;
        public final Color pillBg;
        public final Color pillFg;
        public final Color border;
        public final Color softBg;

        Role(Color accent, Color pillBg, Color pillFg, Color border, Color softBg) {
            this.accent = accent;
            this.pillBg = pillBg;
            this.pillFg = pillFg;
            this.border = border;
            this.softBg = softBg;
        }
    }

    private StudioTheme() {}

    public static void install() {
        FlatDarkLaf.setup();
        UIManager.put("defaultFont", new javax.swing.plaf.FontUIResource(UI_FONT_FAMILY, Font.PLAIN, 13));
        UIManager.put("Component.arc", 6);
        UIManager.put("Button.arc", 6);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.focusColor", ACCENT_TEAL);
        UIManager.put("Component.borderColor", BORDER_COLOR);
        UIManager.put("Component.focusedBorderColor", ACCENT_TEAL);
        UIManager.put("Component.disabledBorderColor", BORDER_COLOR);

        UIManager.put("Panel.background", CANVAS_BG);
        UIManager.put("Viewport.background", CANVAS_BG);
        UIManager.put("ScrollPane.background", CANVAS_BG);
        UIManager.put("SplitPane.background", CANVAS_BG);
        UIManager.put("SplitPaneDivider.draggingColor", ACCENT_TEAL);

        UIManager.put("Label.foreground", TEXT_PRIMARY);
        UIManager.put("CheckBox.foreground", TEXT_PRIMARY);
        UIManager.put("CheckBox.background", SURFACE_BG);

        UIManager.put("Button.background", SURFACE_BG);
        UIManager.put("Button.foreground", TEXT_PRIMARY);
        UIManager.put("Button.hoverBackground", HOVER_BG);
        UIManager.put("Button.borderColor", BORDER_STRONG);

        UIManager.put("TextField.background", CANVAS_BG);
        UIManager.put("TextField.foreground", TEXT_PRIMARY);
        UIManager.put("TextField.caretForeground", TEXT_PRIMARY);
        UIManager.put("TextArea.background", CANVAS_BG);
        UIManager.put("TextArea.foreground", TEXT_PRIMARY);
        UIManager.put("ComboBox.background", SURFACE_BG);
        UIManager.put("ComboBox.foreground", TEXT_PRIMARY);
        UIManager.put("ComboBox.buttonBackground", SUBTLE_BG);
        UIManager.put("ComboBox.selectionBackground", ACCENT_TEAL_SOFT);
        UIManager.put("ComboBox.selectionForeground", ACCENT_TEAL_BRIGHT);
        UIManager.put("Spinner.background", CANVAS_BG);
        UIManager.put("Spinner.foreground", TEXT_PRIMARY);

        UIManager.put("TabbedPane.background", SURFACE_BG);
        UIManager.put("TabbedPane.foreground", TEXT_MUTED);
        UIManager.put("TabbedPane.selectedForeground", TEXT_PRIMARY);
        UIManager.put("TabbedPane.selectedBackground", SURFACE_BG);
        UIManager.put("TabbedPane.hoverColor", HOVER_BG);
        UIManager.put("TabbedPane.underlineColor", ACCENT_TEAL);
        UIManager.put("TabbedPane.inactiveUnderlineColor", ACCENT_TEAL_BORDER);
        UIManager.put("TabbedPane.contentSeparatorColor", BORDER_COLOR);
        UIManager.put("TabbedPane.showTabSeparators", false);
        UIManager.put("TabbedPane.tabHeight", 34);

        UIManager.put("Table.background", CANVAS_BG);
        UIManager.put("Table.foreground", TEXT_PRIMARY);
        UIManager.put("Table.gridColor", BORDER_COLOR);
        UIManager.put("Table.selectionBackground", ACCENT_TEAL_SOFT);
        UIManager.put("Table.selectionForeground", TEXT_PRIMARY);
        UIManager.put("TableHeader.background", SURFACE_BG);
        UIManager.put("TableHeader.foreground", TEXT_MUTED);
        UIManager.put("TableHeader.bottomSeparatorColor", BORDER_COLOR);

        UIManager.put("PopupMenu.background", SURFACE_BG);
        UIManager.put("PopupMenu.borderColor", BORDER_STRONG);
        UIManager.put("MenuItem.background", SURFACE_BG);
        UIManager.put("MenuItem.foreground", TEXT_PRIMARY);
        UIManager.put("MenuItem.selectionBackground", ACCENT_TEAL_SOFT);
        UIManager.put("MenuItem.selectionForeground", ACCENT_TEAL_BRIGHT);
        UIManager.put("Separator.foreground", BORDER_COLOR);

        UIManager.put("ToolTip.background", SURFACE_BG);
        UIManager.put("ToolTip.foreground", TEXT_PRIMARY);
        UIManager.put("ToolTip.border", new LineBorder(BORDER_STRONG, 1));
        UIManager.put("OptionPane.background", SURFACE_BG);
        UIManager.put("OptionPane.messageForeground", TEXT_PRIMARY);

        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.width", 9);
        UIManager.put("ScrollBar.track", CANVAS_BG);
        UIManager.put("ScrollBar.thumb", new Color(49, 56, 53));
        UIManager.put("ScrollBar.hoverThumbColor", new Color(72, 83, 78));
    }

    /** Tipografía editorial Serif para marca, encabezados y títulos de regla (Lean 4). */
    public static Font serifFont(int style, float size) {
        return new Font(SERIF_FONT_FAMILY, style, Math.round(size));
    }

    /** Usa constructor entero para evitar el bug de métricas fraccionales de OpenJDK en Windows. */
    public static Font uiFont(int style, float size) {
        return new Font(UI_FONT_FAMILY, style, Math.round(size));
    }

    public static Font codeFont(float size) {
        return new Font(CODE_FONT_FAMILY, Font.PLAIN, Math.round(size));
    }

    public static Font codeFont(int style, float size) {
        return new Font(CODE_FONT_FAMILY, style, Math.round(size));
    }

    private static String detectFontFamily(String... candidates) {
        Set<String> available = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String c : candidates) {
            if (available.contains(c)) return c;
        }
        return candidates[candidates.length - 1];
    }

    /** Acción primaria única por sección (cobre quemado estilo "View all courses" de Lean 4). */
    public static void styleCopperPrimary(AbstractButton b) {
        stylePrimary(b, ACCENT_COPPER);
    }

    /** Acción secundaria delineada en Veridian teal (estilo "See pricing" de Lean 4). */
    public static void styleTealOutline(AbstractButton b) {
        b.setFont(uiFont(Font.PLAIN, 13f));
        b.setForeground(ACCENT_TEAL_BRIGHT);
        b.setBackground(CANVAS_BG);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.putClientProperty("FlatLaf.style", "background:#111716; foreground:" + hex(ACCENT_TEAL_BRIGHT)
                + "; hoverBackground:#172624; borderColor:" + hex(ACCENT_TEAL_BORDER)
                + "; arc:6; margin:6,12,6,12");
    }

    public static void stylePrimary(AbstractButton b, Color bg) {
        Color hover = bg.equals(ACCENT_COPPER) ? ACCENT_COPPER_HOVER : bg.brighter();
        b.setFont(uiFont(Font.BOLD, 13f));
        b.setForeground(TEXT_PRIMARY);
        b.setBackground(bg);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.putClientProperty("FlatLaf.style", "background:" + hex(bg)
                + "; foreground:#F5F7F6; hoverBackground:" + hex(hover)
                + "; borderColor:" + hex(bg) + "; arc:6; margin:6,13,6,13");
    }

    public static void styleSoft(AbstractButton b, Role role) {
        b.setFont(uiFont(Font.PLAIN, 12.5f));
        b.setForeground(role.pillFg);
        b.setBackground(role.pillBg);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.putClientProperty("FlatLaf.style", "background:" + hex(role.pillBg)
                + "; foreground:" + hex(role.pillFg)
                + "; hoverBackground:" + hex(role.border)
                + "; borderColor:" + hex(role.border) + "; arc:6; margin:5,11,5,11");
    }

    public static void styleGhost(AbstractButton b) {
        b.setFont(uiFont(Font.PLAIN, 12.5f));
        b.setForeground(TEXT_SECONDARY);
        b.setBackground(SURFACE_BG);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.putClientProperty("FlatLaf.style", "background:#161918; foreground:#DCE1DE; hoverBackground:#222725; borderColor:#2F3633; arc:6; margin:5,11,5,11");
    }

    public static void styleHeaderButton(AbstractButton b, boolean accent) {
        b.setFont(uiFont(accent ? Font.BOLD : Font.PLAIN, 12.5f));
        b.setForeground(HEADER_TEXT);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (accent) {
            styleCopperPrimary(b);
            return;
        }
        b.putClientProperty("FlatLaf.style", "background:#121514; foreground:#B2BBB7; hoverBackground:#1E2321"
                + "; hoverForeground:#ECEFED; disabledBackground:#121514; disabledText:#4B5450"
                + "; borderColor:#121514; hoverBorderColor:#2C3330; disabledBorderColor:#121514; arc:6; margin:5,9,5,9");
    }

    /** Estila un segmento dentro de un conmutador conectado de modo de trabajo. */
    public static void styleSegmentButton(AbstractButton b, boolean active) {
        b.setFont(uiFont(active ? Font.BOLD : Font.PLAIN, 12.5f));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (active) {
            b.putClientProperty("FlatLaf.style", "background:#1B2B29; foreground:#4CC6BC; hoverBackground:#1F3330"
                    + "; borderColor:#275C57; arc:5; margin:4,12,4,12");
        } else {
            b.putClientProperty("FlatLaf.style", "background:#141716; foreground:#848E89; hoverBackground:#1D2220"
                    + "; hoverForeground:#DCE1DE; borderColor:#141716; arc:5; margin:4,12,4,12");
        }
    }

    public static void styleMiniButton(AbstractButton b, Color fg) {
        b.setFont(uiFont(Font.PLAIN, 12f));
        b.setForeground(fg);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.putClientProperty("FlatLaf.style", "background:#161918; foreground:" + hex(fg)
                + "; hoverBackground:#222725; borderColor:#262B29; arc:5; margin:2,7,2,7");
    }

    public static void styleFlatLinkButton(AbstractButton b, Color fg) {
        b.setFont(uiFont(Font.PLAIN, 12f));
        b.setForeground(fg);
        b.setOpaque(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.putClientProperty("FlatLaf.style", "background:#00000000; foreground:" + hex(fg)
                + "; hoverBackground:#222826; borderColor:#00000000; hoverBorderColor:#343D39; arc:5; margin:2,7,2,7");
    }

    public static Border cardBorder(Color accent) {
        return new CompoundBorder(
                new LineBorder(BORDER_COLOR, 1, false),
                new CompoundBorder(new MatteBorder(0, 3, 0, 0, accent), new EmptyBorder(8, 12, 8, 12)));
    }

    /** Etiqueta semántica sobria para diálogos/tablas (sin cápsulas pastel chillonas). */
    public static JLabel pill(String text, Role role) {
        return pill(text, role.pillBg, role.pillFg, role.border);
    }

    public static JLabel pill(String text, Color bg, Color fg, Color border) {
        JLabel lbl = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(bg);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g.setColor(border);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        lbl.setOpaque(false);
        lbl.setForeground(fg);
        lbl.setFont(uiFont(Font.BOLD, 11f));
        lbl.setBorder(new EmptyBorder(2, 7, 2, 7));
        return lbl;
    }

    public static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** Superficie con borde fino de 1 px y filete lateral opcional. */
    public static class RoundedPanel extends JPanel {
        private Color fillColor = SURFACE_BG;
        private Color strokeColor = BORDER_COLOR;
        private Color leftAccent;
        private int arc = 6;
        private boolean hairlineBottomOnly;

        public RoundedPanel(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        public RoundedPanel fill(Color c) { this.fillColor = c; repaint(); return this; }
        public RoundedPanel stroke(Color c) { this.strokeColor = c; repaint(); return this; }
        public RoundedPanel accent(Color c) { this.leftAccent = c; repaint(); return this; }
        public RoundedPanel arc(int a) { this.arc = a; repaint(); return this; }
        public RoundedPanel hairlineBottom(boolean onlyBottom) { this.hairlineBottomOnly = onlyBottom; repaint(); return this; }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            if (hairlineBottomOnly) {
                g.setColor(fillColor);
                g.fillRect(0, 0, w, h);
                if (leftAccent != null) {
                    g.setColor(leftAccent);
                    g.fillRect(0, 0, 3, h);
                }
                g.setColor(strokeColor);
                g.drawLine(0, h - 1, w - 1, h - 1);
            } else {
                Shape shape = new RoundRectangle2D.Double(0, 0, w - 1, h - 1, arc, arc);
                g.setColor(fillColor);
                g.fill(shape);
                if (leftAccent != null) {
                    Shape oldClip = g.getClip();
                    g.clip(shape);
                    g.setColor(leftAccent);
                    g.fillRect(0, 0, 3, h);
                    g.setClip(oldClip);
                }
                g.setColor(strokeColor);
                g.draw(shape);
            }
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    public record StudioIcon(Kind kind, int size, Color overrideColor) implements Icon {
        public enum Kind { PLAY, PAUSE, STEP, RESET, ZOOM_IN, ZOOM_OUT, SEND, PLUS, SPARKLES, UNDO, REDO, CHECK, WARN, CHEVRON_DOWN, MORE }

        public StudioIcon(Kind kind) { this(kind, 14, null); }
        public StudioIcon(Kind kind, int size) { this(kind, size, null); }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.scale(size / 18.0, size / 18.0);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fg = overrideColor != null ? overrideColor : (c.isEnabled() ? c.getForeground() : TEXT_MUTED);
            g.setColor(fg);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case PLAY, STEP -> {
                    Path2D p = new Path2D.Double();
                    p.moveTo(4, 3); p.lineTo(13, 9); p.lineTo(4, 15); p.closePath();
                    g.draw(p);
                    if (kind == Kind.STEP) g.drawLine(15, 3, 15, 15);
                }
                case PAUSE -> { g.drawLine(6, 3, 6, 15); g.drawLine(12, 3, 12, 15); }
                case RESET -> { g.drawArc(3, 3, 12, 12, 40, 285); g.drawLine(3, 2, 3, 7); g.drawLine(3, 7, 8, 7); }
                case SEND -> { g.drawLine(2, 9, 16, 9); g.drawLine(10, 3, 16, 9); g.drawLine(16, 9, 10, 15); }
                case PLUS -> { g.drawLine(9, 3, 9, 15); g.drawLine(3, 9, 15, 9); }
                case SPARKLES -> {
                    Path2D star = new Path2D.Double();
                    star.moveTo(9, 2); star.lineTo(11, 7); star.lineTo(16, 9); star.lineTo(11, 11);
                    star.lineTo(9, 16); star.lineTo(7, 11); star.lineTo(2, 9); star.lineTo(7, 7); star.closePath();
                    g.draw(star);
                }
                case UNDO -> { g.drawArc(4, 5, 11, 10, -40, 190); g.drawLine(4, 6, 4, 11); g.drawLine(4, 11, 9, 11); }
                case REDO -> { g.drawArc(3, 5, 11, 10, 30, 190); g.drawLine(14, 6, 14, 11); g.drawLine(14, 11, 9, 11); }
                case CHECK -> {
                    g.drawLine(3, 9, 7, 13);
                    g.drawLine(7, 13, 15, 5);
                }
                case WARN -> {
                    Path2D tri = new Path2D.Double();
                    tri.moveTo(9, 2); tri.lineTo(16, 15); tri.lineTo(2, 15); tri.closePath();
                    g.draw(tri);
                    g.drawLine(9, 7, 9, 10);
                    g.fillOval(8, 12, 2, 2);
                }
                case CHEVRON_DOWN -> {
                    g.drawLine(4, 6, 9, 12);
                    g.drawLine(9, 12, 14, 6);
                }
                case MORE -> {
                    g.fillOval(3, 8, 2, 2);
                    g.fillOval(8, 8, 2, 2);
                    g.fillOval(13, 8, 2, 2);
                }
                case ZOOM_IN, ZOOM_OUT -> {
                    g.drawOval(2, 2, 10, 10);
                    g.drawLine(11, 11, 16, 16);
                    g.drawLine(4, 7, 10, 7);
                    if (kind == Kind.ZOOM_IN) g.drawLine(7, 4, 7, 10);
                }
            }
            g.dispose();
        }

        public static void apply(AbstractButton btn, Kind kind) {
            btn.setIcon(new StudioIcon(kind));
            btn.setIconTextGap(6);
        }
    }
}
