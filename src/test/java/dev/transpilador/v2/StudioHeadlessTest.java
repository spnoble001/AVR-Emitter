package dev.transpilador.v2;

import dev.transpilador.v2.model.Hardware;
import dev.transpilador.v2.model.ProgramSpec;
import dev.transpilador.v2.model.Rule;
import dev.transpilador.v2.patterns.BenchmarkCatalog;
import dev.transpilador.v2.patterns.PedagogicalPatterns;
import dev.transpilador.v2.ui.CircuitCanvasPanel;
import dev.transpilador.v2.ui.PatternAssistantDialog;
import dev.transpilador.v2.ui.RuleBuilderDialog;
import dev.transpilador.v2.ui.StudioPanel;
import dev.transpilador.v2.ui.StudioTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.transpilador.v2.model.Effect.*;
import static dev.transpilador.v2.model.Rule.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verificación headless de la interfaz unificada del Estudio Didáctico v2:
 * carga de ejemplos, vistas duales C99/AVR ASM en vivo, deshacer/rehacer y renderizado del circuito DIP-28.
 */
class StudioHeadlessTest {

    @Test
    void studioPanelUpdatesLiveCodeUndoRedoAndRendersCircuitCanvasCleanly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StudioPanel studio = new StudioPanel(BenchmarkCatalog.b01LedBoton());
            try {
                assertTrue(studio.emittedC().contains("int main(void)"));
                assertTrue(studio.emittedAsm().contains(".include \"m328Pdef.inc\""));

                // Cambiar al integrador completo (FSM + UART + ADC)
                studio.setDocument(BenchmarkCatalog.b15MaquinaTresEstadosUartAdc());
                assertTrue(studio.emittedC().contains("ADVERTENCIA"));
                assertTrue(studio.emittedAsm().contains("uart_enviar_texto"));

                // Aplicar un patrón pedagógico y probar Deshacer / Rehacer
                var withBlink = PedagogicalPatterns.addPeriodicBlink(studio.document(), "led_latido", 100);
                int rulesBefore = studio.document().rules().size();
                studio.setDocument(withBlink);
                assertEquals(rulesBefore + 1, studio.document().rules().size());
                studio.undo();
                assertEquals(rulesBefore, studio.document().rules().size());
                studio.redo();
                assertEquals(rulesBefore + 1, studio.document().rules().size());

                // Cambiar motor de simulación entre C99 y AVR ASM
                studio.circuitCanvas().setSelectedBackend(CircuitCanvasPanel.Backend.ASM);
                assertEquals(CircuitCanvasPanel.Backend.ASM, studio.circuitCanvas().selectedBackend());

                // Renderizar el estudio y el lienzo DIP-28 a imagen sin excepciones
                studio.setSize(1280, 760);
                studio.doLayout();
                BufferedImage img = new BufferedImage(1280, 760, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                studio.paint(g);
                g.dispose();
            } finally {
                studio.shutdown();
            }
        });
    }

    @Test
    void ruleBuilderRoundTripsEveryRuleAcrossAllFifteenBenchmarksWithoutLosingGuardsOrEffects() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StudioTheme.install();
            for (ProgramSpec spec : BenchmarkCatalog.all()) {
                for (int i = 0; i < spec.rules().size(); i++) {
                    Rule original = spec.rules().get(i);
                    RuleBuilderDialog dlg = RuleBuilderDialog.createDialog(null, spec, original, i);
                    try {
                        Rule reloaded = dlg.currentRule();
                        assertEquals(original, reloaded,
                                () -> "RuleBuilderDialog alteró la regla '" + original.title() + "' en " + spec.name());
                    } finally {
                        dlg.dispose();
                    }
                }
            }

            // Verificar también que la Galería de Intenciones construye y renderiza limpiamente
            PatternAssistantDialog gallery = PatternAssistantDialog.createDialog(null, BenchmarkCatalog.b15MaquinaTresEstadosUartAdc());
            try {
                gallery.setSize(920, 620);
                gallery.validate();
                BufferedImage img = new BufferedImage(920, 620, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                gallery.getContentPane().printAll(g);
                g.dispose();
            } finally {
                gallery.dispose();
            }
        });
    }

    @Test
    void proteusSchematicRendersAllPolaritiesPeripheralsAndExportsRealHex(@TempDir Path tempDir) throws Exception {
        ProgramSpec fullCircuit = ProgramSpec.builder("esquematico_completo")
                .clock(Hardware.ClockFreq.MHZ_16)
                .button("btn_gnd", Hardware.Pin.pd(2), Hardware.Connection.GND)
                .button("btn_vcc", Hardware.Pin.pd(4), Hardware.Connection.VCC)
                .led("led_gnd", Hardware.Pin.pb(0), Hardware.Connection.GND, true)
                .led("led_vcc", Hardware.Pin.pb(5), Hardware.Connection.VCC, false)
                .bcd("disp_unidades", Hardware.Pin.pc(1), 7)
                .analog("pot_vel", Hardware.Pin.pc(0))
                .pwm("pwm_motor", Hardware.Pin.pd(6), 160)
                .uart("consola", Hardware.UartBaud.B9600, Hardware.UartMode.FULL_DUPLEX)
                .rule("Control rápido", onPress("btn_gnd"), toggleLed("led_gnd"))
                .rule("Control activo alto", onPress("btn_vcc"), toggleLed("led_vcc"))
                .build();

        SwingUtilities.invokeAndWait(() -> {
            StudioTheme.install();
            StudioPanel studio = new StudioPanel(fullCircuit);
            try {
                studio.circuitCanvas().setLogicTraceVisible(true);
                studio.setSize(1280, 900);
                studio.validate();
                BufferedImage img = new BufferedImage(1280, 900, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                studio.printAll(g);
                g.dispose();

                Path cHex = studio.exportHexTo(true, tempDir.resolve("esquematico_c99.hex"));
                Path asmHex = studio.exportHexTo(false, tempDir.resolve("esquematico_asm.hex"));
                assertTrue(Files.readString(cHex).contains(":00000001FF"), "HEX C99 válido");
                assertTrue(Files.readString(asmHex).contains(":00000001FF"), "HEX AVR ASM válido");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            } finally {
                studio.shutdown();
            }
        });
    }
}
