package dev.transpilador.v2;

import dev.transpilador.v2.emit.AsmEmitter;
import dev.transpilador.v2.emit.CEmitter;
import dev.transpilador.v2.model.ProgramSpec;
import dev.transpilador.v2.patterns.BenchmarkCatalog;
import dev.transpilador.v2.persistence.ProjectJson;
import dev.transpilador.v2.ui.JcefManager;
import dev.transpilador.v2.ui.StudioPanel;
import dev.transpilador.v2.ui.StudioTheme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Punto de entrada único (GUI Swing/FlatLaf y CLI C99 / AVRASM2) de Transpilador AVR v2. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equals("--gui"))) {
            launchGui();
            return;
        }
        System.exit(runCli(args, System.out, System.err));
    }

    private static void launchGui() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("La interfaz requiere escritorio gráfico. Usa la CLI para transpilar sin GUI.");
        }
        SwingUtilities.invokeLater(() -> {
            // Mantener menús y tooltips de Swing siempre por encima del componente nativo Chromium (JCEF)
            JPopupMenu.setDefaultLightWeightPopupEnabled(false);
            ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
            StudioTheme.install();
            StudioPanel panel = new StudioPanel();
            JFrame frame = new JFrame("Transpilador AVR v2: ATmega328P");
            frame.setContentPane(panel);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    if (panel.confirmClose()) {
                        frame.dispose();
                    }
                }
                @Override public void windowClosed(WindowEvent e) {
                    panel.shutdown();
                    JcefManager.shutdown();
                }
            });
            frame.setSize(1420, 860);
            frame.setMinimumSize(new Dimension(1020, 640));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    public static int runCli(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            out.println("""
                    Transpilador AVR v2 · Estudio Didáctico ATmega328P
                      GUI:                java -jar transpilador-v2.jar --gui
                      Transpilar C:       java -jar transpilador-v2.jar proyecto.avr2.json salida.c
                      Transpilar ASM:     java -jar transpilador-v2.jar proyecto.avr2.json salida.asm [--asm-standard] [--asm-sram]
                      Ejemplos:           java -jar transpilador-v2.jar --list-examples""");
            return 0;
        }
        if (args.length == 1 && args[0].equals("--list-examples")) {
            for (ProgramSpec s : BenchmarkCatalog.all()) {
                out.println(s.name());
            }
            return 0;
        }
        if (args.length < 2 || args.length > 4) {
            err.println("Uso: java -jar transpilador-v2.jar <proyecto.avr2.json> <salida.c|salida.asm> [--asm-standard] [--asm-sram]");
            return 2;
        }
        AsmEmitter.InstructionProfile asmProfile = AsmEmitter.InstructionProfile.ELEMENTARY;
        AsmEmitter.StorageMode asmMode = AsmEmitter.StorageMode.REGISTERS_FIRST;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--asm-standard" -> asmProfile = AsmEmitter.InstructionProfile.STANDARD;
                case "--asm-sram" -> asmMode = AsmEmitter.StorageMode.SRAM_DATA_SEGMENT;
                default -> {
                    err.println("Opción desconocida: " + args[i]);
                    return 2;
                }
            }
        }
        try {
            Path in = Path.of(args[0]).toAbsolutePath().normalize();
            Path outFile = Path.of(args[1]).toAbsolutePath().normalize();
            ProgramSpec spec = ProjectJson.parse(Files.readString(in, StandardCharsets.UTF_8));
            String lower = outFile.getFileName().toString().toLowerCase(Locale.ROOT);
            String emitted;
            if (lower.endsWith(".c")) {
                emitted = new CEmitter().emit(spec);
            } else if (lower.endsWith(".asm")) {
                emitted = new AsmEmitter().emit(spec, asmProfile, asmMode);
            } else {
                throw new IllegalArgumentException("La salida debe terminar en .c o .asm");
            }
            Files.writeString(outFile, emitted, StandardCharsets.UTF_8);
            out.println("Generado: " + outFile);
            return 0;
        } catch (IllegalArgumentException | IOException ex) {
            err.println("Error: " + ex.getMessage());
            return 1;
        }
    }
}
