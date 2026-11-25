package dev.transpilador.v2.ui;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.EnumProgress;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Administrador singleton del ciclo de vida de Chromium embebido (JCEF vía {@code jcefmaven}).
 * Descarga/reutiliza los binarios nativos fuera del JAR en {@code local-resources/jcef-bundle},
 * reporta progreso en vivo y se desactiva automáticamente en entornos headless o pruebas JUnit.
 */
public final class JcefManager {
    private static final Object LOCK = new Object();
    private static final List<BiConsumer<String, Float>> PROGRESS_LISTENERS = new CopyOnWriteArrayList<>();

    private static CompletableFuture<CefApp> appFuture;
    private static volatile CefApp activeApp;
    private static volatile String lastStatus = "Preparando motor Chromium (JCEF)…";
    private static volatile float lastPercent = -1f;

    private JcefManager() {}

    public static boolean isEnabled() {
        if (GraphicsEnvironment.isHeadless()) return false;
        return !"false".equalsIgnoreCase(System.getProperty("transpilador.jcef.enabled", "true"));
    }

    public static Path resolveInstallDir() {
        String prop = System.getProperty("transpilador.jcef.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        Path cwdLocal = Path.of("local-resources").toAbsolutePath().normalize();
        if (Files.isDirectory(cwdLocal)) {
            return cwdLocal.resolve("jcef-bundle");
        }
        try {
            Path loc = Path.of(JcefManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path parent = Files.isDirectory(loc) ? loc.getParent() : loc.getParent();
            if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("target")) {
                parent = parent.getParent();
            }
            if (parent != null && Files.isWritable(parent)) {
                return parent.resolve("local-resources").resolve("jcef-bundle");
            }
        } catch (Exception ignored) {
        }
        return Path.of(System.getProperty("user.home"), ".transpilador-avr-v2", "jcef-bundle");
    }

    public static boolean isBundleInstalled() {
        Path dir = resolveInstallDir();
        return Files.isDirectory(dir) && Files.exists(dir.resolve("install.lock"));
    }

    public static CompletableFuture<CefApp> getOrInit(BiConsumer<String, Float> onProgress) {
        if (onProgress != null) {
            PROGRESS_LISTENERS.add(onProgress);
            onProgress.accept(lastStatus, lastPercent);
        }
        synchronized (LOCK) {
            if (appFuture != null) {
                return appFuture;
            }
            if (!isEnabled()) {
                return CompletableFuture.failedFuture(new IllegalStateException("JCEF deshabilitado en este entorno."));
            }
            CompletableFuture<CefApp> future = new CompletableFuture<>();
            appFuture = future;
            Thread t = new Thread(() -> initialize(future), "jcef-bootstrap");
            t.setDaemon(true);
            t.start();
            return future;
        }
    }

    public static void removeProgressListener(BiConsumer<String, Float> listener) {
        if (listener != null) PROGRESS_LISTENERS.remove(listener);
    }

    private static void initialize(CompletableFuture<CefApp> future) {
        try {
            Path installDir = resolveInstallDir();
            Files.createDirectories(installDir);

            CefAppBuilder builder = new CefAppBuilder();
            builder.setInstallDir(installDir.toFile());
            builder.getCefSettings().windowless_rendering_enabled = false;
            builder.addJcefArgs("--disable-background-timer-throttling");
            builder.setProgressHandler((state, percent) -> emitProgress(describe(state), percent));
            builder.setAppHandler(new MavenCefAppHandlerAdapter() {
                @Override
                public void stateHasChanged(CefApp.CefAppState state) {
                    if (state == CefApp.CefAppState.INITIALIZED) {
                        emitProgress("Motor Chromium embebido listo", 100f);
                    }
                }
            });

            emitProgress("Verificando binarios de Chromium en " + installDir.getFileName() + "…", -1f);
            CefApp app = builder.build();
            activeApp = app;
            emitProgress("Motor Chromium embebido listo", 100f);
            future.complete(app);
        } catch (Throwable ex) {
            emitProgress("No se pudo iniciar Chromium: " + ex.getMessage(), 0f);
            synchronized (LOCK) {
                appFuture = null;
            }
            future.completeExceptionally(ex);
        }
    }

    private static void emitProgress(String text, float percent) {
        lastStatus = text;
        lastPercent = percent;
        for (var l : PROGRESS_LISTENERS) {
            try {
                l.accept(text, percent);
            } catch (Exception ignored) {
            }
        }
    }

    private static String describe(EnumProgress state) {
        if (state == null) return "Preparando motor Chromium…";
        return switch (state) {
            case LOCATING -> "Localizando runtime Chromium (JCEF)…";
            case DOWNLOADING -> "Descargando Chromium embebido (solo la primera vez)…";
            case EXTRACTING -> "Descomprimiendo binarios Chromium…";
            case INSTALL -> "Instalando runtime local en local-resources/jcef-bundle…";
            case INITIALIZING -> "Iniciando proceso Chromium embebido…";
            case INITIALIZED -> "Motor Chromium embebido listo";
        };
    }

    public static void shutdown() {
        CefApp app = activeApp;
        activeApp = null;
        if (app != null) {
            try {
                app.dispose();
            } catch (Throwable ignored) {
            }
        }
    }
}
