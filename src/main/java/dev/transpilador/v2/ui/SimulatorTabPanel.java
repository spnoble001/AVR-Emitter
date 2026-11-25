package dev.transpilador.v2.ui;

import dev.transpilador.v2.emit.AsmEmitter;
import dev.transpilador.v2.model.ProgramSpec;
import dev.transpilador.v2.sim.SimEngineController;
import dev.transpilador.v2.sim.web.SimBridgeServer;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.URI;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Contenedor de la pestaña Simulador con arquitectura desacoplada:
 *  - Modo principal: motor Chromium embebido (JCEF) conectado al puente HTTP/SSE {@link SimBridgeServer}
 *    sobre {@link SimEngineController} ({@code simavr} real).
 *  - Modo respaldo: {@link CircuitCanvasPanel} en Swing puro (automático en pruebas headless o si JCEF no está disponible).
 *  - Botón rápido para abrir el mismo puente en el navegador del sistema (DevTools / segundo monitor).
 */
public final class SimulatorTabPanel extends JPanel {
    private static final String CARD_JCEF = "jcef";
    private static final String CARD_SWING = "swing";

    private final CircuitCanvasPanel swingView;
    private final SimEngineController webEngine;
    private final SimBridgeServer bridgeServer;

    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final JPanel jcefContainer = new JPanel(new BorderLayout());
    private final JLabel jcefStatusLabel = new JLabel("Preparando motor Chromium (JCEF)…", SwingConstants.CENTER);
    private final JProgressBar jcefProgress = new JProgressBar(0, 100);
    private final JLabel bridgeBadge = new JLabel();

    private final JToggleButton btnModeJcef = new JToggleButton("Chromium (JCEF)");
    private final JToggleButton btnModeSwing = new JToggleButton("Vista Swing");
    private final JButton btnOpenBrowser = new JButton("Abrir en navegador");

    private CefClient cefClient;
    private CefBrowser cefBrowser;
    private BiConsumer<String, Float> progressListener;
    private boolean jcefBootRequested;
    private String activeCard = CARD_SWING;

    public SimulatorTabPanel(ProgramSpec initialSpec) {
        super(new BorderLayout(0, 0));
        Objects.requireNonNull(initialSpec, "initialSpec");
        setName("simulatorTabPanel");
        setBackground(StudioTheme.CANVAS_BG);

        this.swingView = new CircuitCanvasPanel(initialSpec);
        this.webEngine = new SimEngineController(initialSpec);
        SimBridgeServer srv = null;
        try {
            srv = new SimBridgeServer(webEngine);
        } catch (Exception ex) {
            jcefStatusLabel.setText("No se pudo iniciar puente local HTTP/SSE: " + ex.getMessage());
        }
        this.bridgeServer = srv;

        add(buildModeBar(), BorderLayout.NORTH);
        buildJcefPlaceholder();
        cardHost.setOpaque(false);
        cardHost.add(jcefContainer, CARD_JCEF);
        cardHost.add(swingView, CARD_SWING);
        add(cardHost, BorderLayout.CENTER);

        // En modo gráfico real con JCEF habilitado, iniciar en Chromium; en headless/tests, quedarse en Swing
        if (JcefManager.isEnabled() && bridgeServer != null) {
            switchToCard(CARD_JCEF);
            bootJcefAsync();
        } else {
            btnModeJcef.setEnabled(false);
            btnModeJcef.setToolTipText("Motor Chromium deshabilitado en modo headless/pruebas");
            switchToCard(CARD_SWING);
        }
    }

    private JPanel buildModeBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(StudioTheme.HEADER_BG);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, StudioTheme.BORDER_COLOR),
                new EmptyBorder(5, 12, 5, 12)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        JLabel lbl = new JLabel("Motor visual:");
        lbl.setFont(StudioTheme.uiFont(Font.PLAIN, 12f));
        lbl.setForeground(StudioTheme.TEXT_MUTED);

        ButtonGroup grp = new ButtonGroup();
        grp.add(btnModeJcef);
        grp.add(btnModeSwing);
        StudioTheme.styleSegmentButton(btnModeJcef, true);
        StudioTheme.styleSegmentButton(btnModeSwing, false);
        btnModeJcef.addActionListener(e -> {
            switchToCard(CARD_JCEF);
            if (!jcefBootRequested && JcefManager.isEnabled() && bridgeServer != null) {
                bootJcefAsync();
            }
        });
        btnModeSwing.addActionListener(e -> switchToCard(CARD_SWING));

        left.add(lbl);
        left.add(btnModeJcef);
        left.add(btnModeSwing);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        bridgeBadge.setFont(StudioTheme.codeFont(11.5f));
        bridgeBadge.setForeground(StudioTheme.TEXT_MUTED);
        bridgeBadge.setText(bridgeServer != null ? "SSE :" + bridgeServer.port() : "Puente inactivo");

        StudioTheme.styleGhost(btnOpenBrowser);
        btnOpenBrowser.setFont(StudioTheme.uiFont(Font.PLAIN, 12f));
        btnOpenBrowser.setEnabled(bridgeServer != null && !GraphicsEnvironment.isHeadless());
        btnOpenBrowser.setToolTipText("Abre el simulador web conectado al mismo simavr en tu navegador (ideal para DevTools o segundo monitor)");
        btnOpenBrowser.addActionListener(e -> openInExternalBrowser());

        right.add(bridgeBadge);
        right.add(btnOpenBrowser);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private void buildJcefPlaceholder() {
        jcefContainer.setBackground(StudioTheme.CANVAS_BG);
        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBorder(new EmptyBorder(64, 48, 64, 48));

        JLabel title = new JLabel("Motor Chromium embebido (JCEF)", SwingConstants.CENTER);
        title.setAlignmentX(CENTER_ALIGNMENT);
        title.setFont(StudioTheme.serifFont(Font.BOLD, 17f));
        title.setForeground(StudioTheme.ACCENT_TEAL_BRIGHT);

        jcefStatusLabel.setAlignmentX(CENTER_ALIGNMENT);
        jcefStatusLabel.setFont(StudioTheme.uiFont(Font.PLAIN, 13f));
        jcefStatusLabel.setForeground(StudioTheme.TEXT_MUTED);

        jcefProgress.setAlignmentX(CENTER_ALIGNMENT);
        jcefProgress.setMaximumSize(new Dimension(360, 10));
        jcefProgress.setPreferredSize(new Dimension(360, 10));
        jcefProgress.setIndeterminate(true);

        JButton fallbackBtn = new JButton("Usar vista Swing mientras tanto");
        fallbackBtn.setAlignmentX(CENTER_ALIGNMENT);
        StudioTheme.styleTealOutline(fallbackBtn);
        fallbackBtn.addActionListener(e -> switchToCard(CARD_SWING));

        center.add(Box.createVerticalGlue());
        center.add(title);
        center.add(Box.createVerticalStrut(10));
        center.add(jcefStatusLabel);
        center.add(Box.createVerticalStrut(14));
        center.add(jcefProgress);
        center.add(Box.createVerticalStrut(18));
        center.add(fallbackBtn);
        center.add(Box.createVerticalGlue());

        jcefContainer.add(center, BorderLayout.CENTER);
    }

    private void bootJcefAsync() {
        jcefBootRequested = true;
        progressListener = (text, pct) -> SwingUtilities.invokeLater(() -> {
            jcefStatusLabel.setText(text);
            if (pct >= 0f) {
                jcefProgress.setIndeterminate(false);
                jcefProgress.setValue(Math.clamp(Math.round(pct), 0, 100));
            } else {
                jcefProgress.setIndeterminate(true);
            }
        });

        JcefManager.getOrInit(progressListener).whenComplete((app, err) -> SwingUtilities.invokeLater(() -> {
            JcefManager.removeProgressListener(progressListener);
            if (err != null || app == null) {
                String msg = err != null && err.getMessage() != null ? err.getMessage() : "Error al iniciar JCEF";
                jcefStatusLabel.setText("Chromium no disponible (" + msg + "). Usando vista Swing.");
                jcefProgress.setIndeterminate(false);
                jcefProgress.setValue(0);
                switchToCard(CARD_SWING);
                return;
            }
            mountBrowser(app);
        }));
    }

    private void mountBrowser(CefApp app) {
        if (bridgeServer == null) return;
        try {
            cefClient = app.createClient();
            cefBrowser = cefClient.createBrowser(bridgeServer.baseUrl(), false, false);
            Component ui = cefBrowser.getUIComponent();
            jcefContainer.removeAll();
            jcefContainer.add(ui, BorderLayout.CENTER);
            jcefContainer.revalidate();
            jcefContainer.repaint();
        } catch (Throwable ex) {
            jcefStatusLabel.setText("Error montando navegador Chromium: " + ex.getMessage());
            switchToCard(CARD_SWING);
        }
    }

    private void switchToCard(String card) {
        this.activeCard = card;
        boolean isJcef = CARD_JCEF.equals(card);
        btnModeJcef.setSelected(isJcef);
        btnModeSwing.setSelected(!isJcef);
        styleToggle(btnModeJcef, isJcef);
        styleToggle(btnModeSwing, !isJcef);
        // Evitar que dos sesiones simavr corran a la vez al alternar vistas
        if (isJcef) {
            swingView.pauseSimulation();
        } else {
            webEngine.pause();
        }
        cards.show(cardHost, card);
    }

    private static void styleToggle(JToggleButton btn, boolean active) {
        StudioTheme.styleSegmentButton(btn, active);
    }

    private void openInExternalBrowser() {
        if (bridgeServer == null) return;
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(bridgeServer.baseUrl()));
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Abre esta dirección en tu navegador:\n" + bridgeServer.baseUrl(),
                    "Simulador Web Local", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    public CircuitCanvasPanel swingView() {
        return swingView;
    }

    public SimBridgeServer bridgeServer() {
        return bridgeServer;
    }

    public SimEngineController webEngine() {
        return webEngine;
    }

    public String activeViewCard() {
        return activeCard;
    }

    public void setProgram(ProgramSpec next) {
        swingView.setProgram(next);
        webEngine.setProgram(next);
    }

    public void setAsmProfile(AsmEmitter.InstructionProfile profile) {
        swingView.setAsmProfile(profile);
        webEngine.setAsmProfile(profile);
    }

    public void setAsmStorageMode(AsmEmitter.StorageMode mode) {
        swingView.setAsmStorageMode(mode);
        webEngine.setAsmStorageMode(mode);
    }

    public void setHighlight(SimEngineController.HighlightState highlight) {
        swingView.setHighlight(highlight);
        webEngine.setHighlight(highlight);
    }

    /** Inyecta firmware manual o .hex externo en ambos motores de vista (Chromium y Swing) y opcionalmente lo arranca. */
    public void loadCustomFirmware(SimEngineController.CustomFirmware firmware, boolean autoStart) {
        swingView.setCustomFirmware(firmware);
        webEngine.setCustomFirmware(firmware);
        if (autoStart) {
            if (CARD_JCEF.equals(activeCard)) {
                webEngine.start();
            } else {
                swingView.startSimulation();
            }
        }
    }

    public void clearCustomFirmware() {
        swingView.setCustomFirmware(null);
        webEngine.clearCustomFirmware();
    }

    public void shutdown() {
        JcefManager.removeProgressListener(progressListener);
        if (cefBrowser != null) {
            try {
                cefBrowser.close(true);
            } catch (Throwable ignored) {
            }
            cefBrowser = null;
        }
        if (cefClient != null) {
            try {
                cefClient.dispose();
            } catch (Throwable ignored) {
            }
            cefClient = null;
        }
        if (bridgeServer != null) {
            bridgeServer.close();
        }
        webEngine.close();
        swingView.shutdown();
    }
}
