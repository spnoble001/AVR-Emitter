package dev.transpilador.v2;

import dev.transpilador.v2.model.Hardware;
import dev.transpilador.v2.model.ProgramSpec;
import dev.transpilador.v2.sim.SimEngineController;
import dev.transpilador.v2.sim.Toolchain;
import dev.transpilador.v2.ui.AvrCodeCompleter;
import dev.transpilador.v2.ui.CodeViewerPane;
import dev.transpilador.v2.ui.StudioPanel;
import dev.transpilador.v2.ui.StudioTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verificación end-to-end del IDE integrado en la vista de código:
 *  - Compilación raw C99 (avr-gcc) y ensamblado raw AVR ASM (avrasm2) con medición de Flash y parseo exacto de errores.
 *  - Autocompletado offline ATmega328P (instrucciones, registros periféricos y símbolos locales del buffer).
 *  - Carga directa de firmware editado a mano y .hex externo en simavr con verificación eléctrica y Rayos X CPU.
 *  - Protección de ediciones manuales en CodeViewerPane frente a refrescos del modelo.
 */
class EmbeddedIdeTest {

    @Test
    void rawCCompilationSucceedsMeasuresFlashAndParsesLineErrors(@TempDir Path tmp) throws Exception {
        Toolchain tc = Toolchain.detect();
        assertTrue(tc.hasAvrGcc(), "avr-gcc debe estar disponible");

        String validC = """
                #define F_CPU 1000000UL
                #include <avr/io.h>
                int main(void) {
                    uint8_t sin_usar = 7;
                    DDRB |= (1 << PB0);
                    PORTB |= (1 << PB0);
                    while (1) {}
                }
                """;
        Toolchain.RawBuildResult ok = tc.compileRawC("raw_ok", validC, tmp.resolve("ok"));
        assertTrue(ok.success(), "C99 raw con advertencia menor debe compilar: " + ok.rawOutput());
        assertNotNull(ok.artifact());
        assertTrue(Files.isRegularFile(ok.artifact().hexFile()));
        assertTrue(Files.isRegularFile(ok.artifact().elfFile()));
        assertTrue(ok.flashBytes() > 0, "Debe medir bytes reales de Flash");
        assertTrue(ok.warningCount() >= 1, "Debe reportar advertencia de variable sin usar sin abortar");

        String brokenC = """
                #include <avr/io.h>
                int main(void) {
                    DDRB |= (1 << PB0);
                    PORTB |= (1 << PB0)
                    while (1) {}
                }
                """;
        Toolchain.RawBuildResult err = tc.compileRawC("raw_err", brokenC, tmp.resolve("err"));
        assertFalse(err.success(), "C99 con falta de punto y coma debe fallar");
        assertTrue(err.errorCount() >= 1, "Debe producir al menos un error estructurado");
        assertTrue(err.diagnostics().stream().anyMatch(d -> d.severity() == Toolchain.DiagnosticSeverity.ERROR && d.line() >= 4 && d.line() <= 6),
                "Debe detectar la línea exacta del error: " + err.diagnostics());
    }

    @Test
    void rawAsmAssemblySucceedsMeasuresFlashAndParsesLineErrors(@TempDir Path tmp) throws Exception {
        Toolchain tc = Toolchain.detect();
        assertTrue(tc.hasAvrasm2(), "avrasm2 debe estar disponible");

        String validAsm = """
                .include "m328Pdef.inc"
                .org 0x0000
                    rjmp inicio
                inicio:
                    ldi r18, 0x5A
                    sbi DDRB, 0
                    sbi PORTB, 0
                bucle:
                    rjmp bucle
                """;
        Toolchain.RawBuildResult ok = tc.assembleRawAsm("asm_ok", validAsm, tmp.resolve("ok"));
        assertTrue(ok.success(), "ASM raw válido debe ensamblar: " + ok.rawOutput());
        assertNotNull(ok.artifact());
        assertTrue(Files.isRegularFile(ok.artifact().hexFile()));
        assertTrue(ok.flashBytes() > 0, "Debe medir Flash del .hex ensamblado");

        String brokenAsm = """
                .include "m328Pdef.inc"
                .org 0x0000
                inicio:
                    ldi r5, 42
                    rjmp inicio
                """;
        Toolchain.RawBuildResult err = tc.assembleRawAsm("asm_err", brokenAsm, tmp.resolve("err"));
        assertFalse(err.success(), "ldi sobre r5 (< r16) debe fallar en avrasm2");
        assertTrue(err.errorCount() >= 1);
        assertTrue(err.diagnostics().stream().anyMatch(d -> d.severity() == Toolchain.DiagnosticSeverity.ERROR && d.line() == 4),
                "Debe ubicar el error en línea 4: " + err.diagnostics());
    }

    @Test
    void rawEditedFirmwareAndExternalHexRunLiveInSimavrWithXRayTelemetry(@TempDir Path tmp) throws Exception {
        Toolchain tc = Toolchain.detect();
        assertTrue(tc.hasAvrasm2() && tc.hasSimavr(), "Se requieren avrasm2 y simavr");

        // Ensamblar un programa manual mínimo que enciende PB0 y deja 0x5A en r18
        String customAsm = """
                .include "m328Pdef.inc"
                .org 0x0000
                    rjmp inicio
                inicio:
                    ldi r16, low(RAMEND)
                    out SPL, r16
                    ldi r16, high(RAMEND)
                    out SPH, r16
                    ldi r18, 0x5A
                    sbi DDRB, 0
                    sbi PORTB, 0
                bucle:
                    rjmp bucle
                """;
        Toolchain.RawBuildResult built = tc.assembleRawAsm("custom_pb0", customAsm, tmp);
        assertTrue(built.success(), built.rawOutput());

        // Validar como .hex externo y comprobar rechazo de archivo corrupto
        int validatedBytes = Toolchain.validateExternalHex(built.artifact().hexFile());
        assertEquals(built.flashBytes(), validatedBytes);
        Path corrupt = tmp.resolve("corrupt.hex");
        Files.writeString(corrupt, "esto no es intel hex\n", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> Toolchain.validateExternalHex(corrupt));

        ProgramSpec circuit = ProgramSpec.builder("circuito_raw")
                .clock(Hardware.ClockFreq.MHZ_1)
                .led("led_test", Hardware.Pin.pb(0), Hardware.Connection.GND, false)
                .build();

        try (SimEngineController engine = new SimEngineController(circuit, tc)) {
            SimEngineController.CustomFirmware fw = new SimEngineController.CustomFirmware(
                    "Firmware manual (AVR ASM)",
                    SimEngineController.Backend.ASM,
                    built.artifact().hexFile(),
                    null,
                    built.flashBytes());
            engine.setCustomFirmware(fw);
            engine.stepMs(5);

            SimEngineController.Snapshot snap = waitIdle(engine, 0);
            assertTrue(snap.sessionActive(), "Sesión simavr debe estar activa con firmware manual");
            assertEquals("Firmware manual (AVR ASM)", snap.customFirmwareLabel());
            assertEquals(Boolean.TRUE, snap.pinHigh().get("PB0"), "PB0 debe estar en 5V por código raw ASM");
            assertEquals(Boolean.TRUE, snap.ledOn().get("led_test"), "led_test debe encenderse físicamente");
            assertNotNull(snap.cpu(), "Rayos X CPU debe seguir disponible en firmware manual");
            assertEquals(0x5A, snap.cpu().regs()[18], "Rayos X debe leer r18=0x5A cargado por el código raw");

            engine.clearCustomFirmware();
            assertNull(engine.snapshot().customFirmwareLabel());
        }
    }

    @Test
    void avrCodeCompleterSuggestsInstructionsRegistersAndLiveBufferSymbols() {
        String asmDoc = """
                .include "m328Pdef.inc"
                .def mi_contador = r20
                .equ LIMITE_MAX = 15
                mi_etiqueta_fin:
                    rjmp mi_etiqueta_fin
                """;
        List<AvrCodeCompleter.CompletionItem> asmInstr = AvrCodeCompleter.suggest(CodeViewerPane.Language.AVRASM2, asmDoc, "sb");
        assertTrue(asmInstr.stream().anyMatch(i -> i.insertText().equals("sbic")), "Debe sugerir sbic");
        assertTrue(asmInstr.stream().anyMatch(i -> i.insertText().equals("sbis")), "Debe sugerir sbis");

        List<AvrCodeCompleter.CompletionItem> asmLocal = AvrCodeCompleter.suggest(CodeViewerPane.Language.AVRASM2, asmDoc, "mi_");
        assertTrue(asmLocal.stream().anyMatch(i -> i.insertText().equals("mi_contador")), "Debe extraer .def local");
        assertTrue(asmLocal.stream().anyMatch(i -> i.insertText().equals("mi_etiqueta_fin")), "Debe extraer etiqueta local");

        List<AvrCodeCompleter.CompletionItem> asmDir = AvrCodeCompleter.suggest(CodeViewerPane.Language.AVRASM2, asmDoc, ".d");
        assertTrue(asmDir.stream().anyMatch(i -> i.insertText().equals(".def")));
        assertTrue(asmDir.stream().anyMatch(i -> i.insertText().equals(".dseg")));

        String cDoc = """
                #define MI_UMBRAL 512
                static uint8_t mi_sensor_activo(void) { return 1; }
                int main(void) {
                    uint8_t mi_estado = 0;
                    return mi_estado;
                }
                """;
        List<AvrCodeCompleter.CompletionItem> cDelay = AvrCodeCompleter.suggest(CodeViewerPane.Language.C99, cDoc, "_del");
        assertTrue(cDelay.stream().anyMatch(i -> i.insertText().equals("_delay_ms()")));

        List<AvrCodeCompleter.CompletionItem> cReg = AvrCodeCompleter.suggest(CodeViewerPane.Language.C99, cDoc, "DDR");
        assertTrue(cReg.stream().anyMatch(i -> i.insertText().equals("DDRB")));

        List<AvrCodeCompleter.CompletionItem> cLocal = AvrCodeCompleter.suggest(CodeViewerPane.Language.C99, cDoc, "mi_");
        assertTrue(cLocal.stream().anyMatch(i -> i.insertText().equals("mi_sensor_activo()")));
        assertTrue(cLocal.stream().anyMatch(i -> i.insertText().equals("mi_estado")));
    }

    @Test
    void codeViewerPaneIdeModeProtectsManualEditsAndAppliesDiagnosticHighlights() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StudioTheme.install();
            StudioPanel studio = new StudioPanel(ProgramSpec.builder("demo_ide")
                    .led("led0", Hardware.Pin.pb(0), Hardware.Connection.GND, false)
                    .build());
            try {
                CodeViewerPane cPane = studio.cViewer();
                assertFalse(cPane.isEditMode());
                String generated = cPane.getCode();
                assertTrue(generated.contains("int main(void)"));

                String customBroken = "#include <avr/io.h>\nint main(void) {\n    DDRB = 1\n    while(1){}\n}\n";
                cPane.setManualCode(customBroken);
                assertTrue(cPane.isEditMode());
                assertTrue(cPane.isManuallyModified());

                // Un refresco generado no debe sobrescribir el código manual
                cPane.setGeneratedCode("// nuevo generado");
                assertEquals(customBroken, cPane.getCode());

                Toolchain.RawBuildResult res = cPane.verifyNow();
                assertFalse(res.success());
                cPane.applyBuildDiagnostics(res);
                assertTrue(res.errorCount() >= 1);

                // Restaurar devuelve el último código generado
                cPane.restoreGeneratedCode();
                assertFalse(cPane.isEditMode());
                assertEquals("// nuevo generado", cPane.getCode());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            } finally {
                studio.shutdown();
            }
        });
    }

    private static SimEngineController.Snapshot waitIdle(SimEngineController engine, long prevCycles) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 25_000;
        SimEngineController.Snapshot snap = engine.snapshot();
        while (System.currentTimeMillis() < deadline && (snap.busy() || !snap.sessionActive() || snap.cycles() <= prevCycles)) {
            Thread.sleep(25);
            snap = engine.snapshot();
        }
        return snap;
    }
}
