package dev.transpilador.v2;

import dev.transpilador.v2.arbiter.ResourceArbiter;
import dev.transpilador.v2.emit.AsmEmitter;
import dev.transpilador.v2.emit.CEmitter;
import dev.transpilador.v2.model.*;
import dev.transpilador.v2.patterns.BenchmarkCatalog;
import dev.transpilador.v2.persistence.ProjectJson;
import dev.transpilador.v2.sim.SimEngineController;
import dev.transpilador.v2.sim.SimavrSession;
import dev.transpilador.v2.sim.Toolchain;
import dev.transpilador.v2.ui.RuleBuilderDialog;
import dev.transpilador.v2.ui.StudioPanel;
import dev.transpilador.v2.ui.StudioTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.transpilador.v2.model.Effect.*;
import static dev.transpilador.v2.model.Expr.*;
import static dev.transpilador.v2.model.Hardware.*;
import static dev.transpilador.v2.model.Rule.*;
import static dev.transpilador.v2.model.State.VarPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de regresión dedicada que verifica cada uno de los bugs P1 y P2 reportados
 * (incluyendo ejecución real en avr-gcc, avrasm2 y simavr).
 */
class BugFixesRegressionTest {

    @Test
    void p1UndoAfterOpeningAnotherProjectCannotOverwriteOpenedFile(@TempDir Path tempDir) throws Exception {
        ProgramSpec projA = BenchmarkCatalog.b01LedBoton();
        ProgramSpec projB = BenchmarkCatalog.b03CadaTresPulsaciones();
        Path fileA = tempDir.resolve("proyecto_a.avr2.json");
        Path fileB = tempDir.resolve("proyecto_b.avr2.json");
        Files.writeString(fileA, ProjectJson.toJson(projA), StandardCharsets.UTF_8);
        Files.writeString(fileB, ProjectJson.toJson(projB), StandardCharsets.UTF_8);

        SwingUtilities.invokeAndWait(() -> {
            StudioTheme.install();
            StudioPanel studio = new StudioPanel(projA);
            try {
                studio.openProject(fileA);
                // Editar A para poblar la pila de Deshacer y descartar explícitamente al abrir B
                studio.setDocument(projA.withClock(ClockFreq.MHZ_8));
                studio.setUnsavedChangesHandler(action -> StudioPanel.UnsavedDecision.DISCARD);
                // Abrir B desde disco -> debe limpiar la pila de Deshacer
                assertTrue(studio.openProject(fileB));
                assertEquals(fileB, studio.currentFile());
                assertEquals(projB, studio.document());

                // Deshacer NO debe restaurar A sobre B
                studio.undo();
                assertEquals(projB, studio.document(), "Deshacer tras abrir B no debe restaurar A");

                // Guardar debe preservar B intacto en fileB
                studio.saveCurrentProject();
                ProgramSpec onDisk = ProjectJson.parse(Files.readString(fileB, StandardCharsets.UTF_8));
                assertEquals(projB, onDisk, "Guardar tras deshacer no debe sobrescribir B con A");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            } finally {
                studio.shutdown();
            }
        });
    }

    @Test
    void p1AdcInRangeEvaluatesSingleConversionInCAndAsmAndMatchesSimavr(@TempDir Path tempDir) throws Exception {
        ProgramSpec spec = ProgramSpec.builder("reg_adc_rango_unico")
                .clock(ClockFreq.MHZ_8)
                .analog("pot", Pin.pc(0))
                .led("led", Pin.pb(0), Connection.GND)
                .rule("Rango 200..800 sobre una sola muestra ADC",
                        always(),
                        new InRange(adc10("pot"), 200, 800),
                        List.of(ledOn("led")),
                        List.of(ledOff("led")))
                .build();

        String cCode = new CEmitter().emit(spec);
        assertEquals(1, countOccurrences(cCode, "leer_adc10(0)"),
                "C99 debe leer leer_adc10(0) exactamente una vez en InRange:\n" + cCode);
        assertTrue(cCode.contains("en_rango_u10(leer_adc10(0), 200, 800)"));

        var asmEmitter = new AsmEmitter();
        for (AsmEmitter.InstructionProfile profile : AsmEmitter.InstructionProfile.values()) {
            String asmCode = asmEmitter.emit(spec, profile, AsmEmitter.StorageMode.REGISTERS_FIRST);
            assertEquals(1, countOccurrences(asmCode, "rcall leer_adc10"),
                    "ASM (" + profile + ") debe invocar rcall leer_adc10 exactamente una vez en InRange:\n" + asmCode);
        }

        // Verificar comportamiento real en simavr (C99 y ASM Elemental/Estándar)
        Toolchain tc = Toolchain.detect();
        var led = spec.led("led").orElseThrow();
        var cArt = tc.compileC(spec.name(), cCode, tempDir.resolve("c"));
        var asmElemArt = tc.assembleAsm(spec.name(), asmEmitter.emit(spec, AsmEmitter.InstructionProfile.ELEMENTARY, AsmEmitter.StorageMode.REGISTERS_FIRST), tempDir.resolve("asm_elem"));
        var asmStdArt = tc.assembleAsm(spec.name(), asmEmitter.emit(spec, AsmEmitter.InstructionProfile.STANDARD, AsmEmitter.StorageMode.REGISTERS_FIRST), tempDir.resolve("asm_std"));

        for (Path hex : List.of(cArt.hexFile(), asmElemArt.hexFile(), asmStdArt.hexFile())) {
            try (SimavrSession sim = SimavrSession.open(hex, spec.fCpuHz(), tc)) {
                sim.setAnalogMv(0, 0);
                assertFalse(sim.advanceMs(3).isLedOn(led), hex + ": 0 V fuera de 200..800");
                sim.setAnalogMv(0, 2500);
                assertTrue(sim.advanceMs(3).isLedOn(led), hex + ": 2.5 V (~512) dentro de 200..800");
                sim.setAnalogMv(0, 5000);
                assertFalse(sim.advanceMs(3).isLedOn(led), hex + ": 5.0 V (~1023) fuera de 200..800");
            }
        }
    }

    @Test
    void p1ArbiterRejectsOutOfRangeSetVarBcdAndDecimal2() {
        var arbiter = new ResourceArbiter();

        // 1. SetVar(15) sobre contador CLAMP 0..9
        var badSetVar = ProgramSpec.builder("bad_setvar")
                .var("c", 0, 9, 0, CLAMP)
                .bcd("disp", Pin.pb(0), 0)
                .rule("Valor 15 fuera de 0..9", onBoot(), setVar("c", u8(15)), showBcd("disp", var("c")))
                .build();
        assertTrue(arbiter.analyze(badSetVar).errors().stream().anyMatch(d -> d.code().equals("VAR_RANGE")));

        // 2. WriteBcd con Scale(..., 0, 15)
        var badBcdScale = ProgramSpec.builder("bad_bcd_scale")
                .analog("pot", Pin.pc(0))
                .bcd("disp", Pin.pb(0), 0)
                .rule("Escalado hasta 15 en 4511", always(), showBcd("disp", scaleAdc8To("pot", 0, 15)))
                .build();
        assertTrue(arbiter.analyze(badBcdScale).errors().stream().anyMatch(d -> d.code().equals("BCD_RANGE")));

        // 3. WriteDecimal2 con 120
        var badDec2 = ProgramSpec.builder("bad_dec2")
                .bcd("u", Pin.pb(0), 0)
                .bcd("d", Pin.pd(4), 0)
                .rule("120 excede 99", onBoot(), showDecimal2("u", "d", u8(120)))
                .build();
        assertTrue(arbiter.analyze(badDec2).errors().stream().anyMatch(d -> d.code().equals("DECIMAL_RANGE")));

        // 4. Casos dentro de rango siguen siendo válidos
        var valid = ProgramSpec.builder("valid_bounds")
                .analog("pot", Pin.pc(0))
                .var("c", 0, 9, 0, CLAMP)
                .bcd("u", Pin.pb(0), 0)
                .bcd("d", Pin.pd(4), 0)
                .rule("Todo en rango", always(),
                        setVar("c", scaleAdc8To("pot", 0, 9)),
                        showBcd("u", var("c")),
                        showDecimal2("u", "d", u8(99)))
                .build();
        assertTrue(arbiter.analyze(valid).valid(), () -> "Errores inesperados: " + arbiter.analyze(valid).errors());
    }

    @Test
    void p2CaseInsensitivePhaseAndSymbolCollisionsAreRejected() {
        // idle e IDLE colisionan en FASE_MODO_IDLE
        assertThrows(IllegalArgumentException.class,
                () -> new State.PhaseGroup("modo", List.of("idle", "IDLE"), "idle"));

        // Colisión de nombres case-insensitive en AVRASM2
        var dupCase = ProgramSpec.builder("dup_case")
                .led("led_a", Pin.pb(0), Connection.GND)
                .led("LED_A", Pin.pb(1), Connection.GND)
                .rule("Init", onBoot(), ledOn("led_a"))
                .build();
        assertTrue(new ResourceArbiter().analyze(dupCase).errors().stream()
                .anyMatch(d -> d.code().equals("DUPLICATE_NAME")));

        // Colisión entre distintos grupos: a_b + c == a + b_c -> FASE_A_B_C
        var crossGroup = ProgramSpec.builder("cross_group")
                .phases("a_b", "c", "c", "x")
                .phases("a", "b_c", "b_c", "y")
                .rule("Init", onBoot(), setPhase("a_b", "c"), setPhase("a", "b_c"))
                .build();
        assertTrue(new ResourceArbiter().analyze(crossGroup).errors().stream()
                .anyMatch(d -> d.code().equals("DUPLICATE_PHASE_SYMBOL")));
    }

    @Test
    void p2UartHalfDuplexImplementsTurnaroundAndRejectsNulText(@TempDir Path tempDir) throws Exception {
        // Texto UART con byte nulo 0 debe rechazarse
        assertThrows(IllegalArgumentException.class, () -> sendText("serie", "A\0B"));

        ProgramSpec spec = ProgramSpec.builder("reg_uart_half_duplex")
                .clock(ClockFreq.MHZ_8)
                .uart("serie", UartBaud.B9600, UartMode.HALF_DUPLEX)
                .rule("Responder OK al recibir k",
                        onUartRx("serie"), uartChar("serie", 'k'),
                        List.of(sendText("serie", "OK")), List.of())
                .build();

        String cCode = new CEmitter().emit(spec);
        assertFalse(cCode.contains("(1 << RXEN0) | (1 << TXEN0)"), "Half-duplex no debe activar RX y TX simultáneamente");
        assertTrue(cCode.contains("TXC0"), "Half-duplex debe esperar TXC0 antes de devolver turno a RX");

        String asmCode = new AsmEmitter().emit(spec);
        assertFalse(asmCode.contains("(1 << RXEN0) | (1 << TXEN0)"), "ASM half-duplex no debe activar RX y TX simultáneamente");
        assertTrue(asmCode.contains("TXC0"), "ASM half-duplex debe esperar TXC0 antes de devolver turno a RX");

        Toolchain tc = Toolchain.detect();
        var cArt = tc.compileC(spec.name(), cCode, tempDir.resolve("c"));
        var asmArt = tc.assembleAsm(spec.name(), asmCode, tempDir.resolve("asm"));
        for (Path hex : List.of(cArt.hexFile(), asmArt.hexFile())) {
            try (SimavrSession sim = SimavrSession.open(hex, spec.fCpuHz(), tc)) {
                sim.advanceMs(3);
                assertTrue(sim.injectUartRx('k'), hex + ": RX debe estar listo en reposo");
                sim.advanceMs(15);
                assertEquals("OK", sim.state().txAscii(), hex + ": primera respuesta half-duplex");
                assertTrue(sim.injectUartRx('k'), hex + ": tras transmitir debe volver al turno RX");
                sim.advanceMs(15);
                assertEquals("OKOK", sim.state().txAscii(), hex + ": segunda respuesta half-duplex");
            }
        }
    }

    @Test
    void p2PauseDuringStartupPreventsRunningAndWindowsUnsafeTitleCompiles(@TempDir Path tempDir) throws Exception {
        ProgramSpec spec = ProgramSpec.builder("Práctica 1: parpadeo / prueba?")
                .clock(ClockFreq.MHZ_8)
                .led("led", Pin.pb(0), Connection.GND)
                .rule("Parpadeo", everyMs(20), toggleLed("led"))
                .build();

        // Verificar compilación directa con título que contiene ':', '/', '?' y acentos
        Toolchain tc = Toolchain.detect();
        var cArt = tc.compileC(spec.name(), new CEmitter().emit(spec), tempDir.resolve("c"));
        var asmArt = tc.assembleAsm(spec.name(), new AsmEmitter().emit(spec), tempDir.resolve("asm"));
        assertTrue(Files.isRegularFile(cArt.hexFile()));
        assertTrue(Files.isRegularFile(asmArt.hexFile()));

        // Iniciar -> Pausar inmediatamente mientras compila -> al terminar debe quedar en PAUSA, no corriendo
        try (SimEngineController engine = new SimEngineController(spec, tc)) {
            engine.start();
            engine.pause();

            long deadline = System.currentTimeMillis() + 25_000;
            SimEngineController.Snapshot snap = engine.snapshot();
            while (System.currentTimeMillis() < deadline && (snap.busy() || !snap.sessionActive())) {
                Thread.sleep(50);
                snap = engine.snapshot();
            }
            assertTrue(snap.sessionActive(), "La sesión debe quedar lista tras compilar: " + snap.status());
            assertFalse(snap.running(), "Tras Iniciar -> Pausar durante carga, el motor NO debe quedar corriendo");
            long frozenCycles = snap.cycles();
            Thread.sleep(220);
            assertEquals(frozenCycles, engine.snapshot().cycles(), "Los ciclos no deben avanzar mientras está en pausa");
        }
    }

    @Test
    void p2SimavrMeasuresRealPwmFrequencyFromVcd(@TempDir Path tempDir) throws Exception {
        ProgramSpec spec = ProgramSpec.builder("reg_pwm_vcd_freq")
                .clock(ClockFreq.MHZ_16)
                .pwm("pwm_activo", Pin.pb(1), 128)
                .pwm("pwm_cero", Pin.pd(6), 0)
                .rule("Init", onBoot(), setPwm("pwm_activo", u8(128)))
                .build();

        Toolchain tc = Toolchain.detect();
        var art = tc.compileC(spec.name(), new CEmitter().emit(spec), tempDir);
        try (SimavrSession sim = SimavrSession.open(art.hexFile(), spec.fCpuHz(), tc)) {
            sim.advanceMs(12);
            var freqs = sim.measurePwmFrequenciesHz(spec.componentsOf(PwmOutput.class), spec.fCpuHz());
            double fOc1a = freqs.get(PwmChannel.OC1A);
            double fOc0a = freqs.get(PwmChannel.OC0A);
            // 16 MHz / (64 * 256) = 976.5625 Hz
            assertEquals(976.56, fOc1a, 8.0, "Frecuencia medida en VCD para OC1A debe ser ~976.6 Hz, fue: " + fOc1a);
            assertEquals(0.0, fOc0a, 0.01, "OC0A en duty=0 no debe reportar frecuencia de conmutación");
        }
    }

    @Test
    void ruleBuilderRoundTripsAll256UartExpectedBytesWithoutCorruption() throws Exception {
        ProgramSpec base = ProgramSpec.builder("reg_uart_bytes")
                .uart("serie", UartBaud.B9600, UartMode.FULL_DUPLEX)
                .led("led", Pin.pb(0), Connection.GND)
                .build();

        SwingUtilities.invokeAndWait(() -> {
            StudioTheme.install();
            for (int code = 0; code <= 255; code++) {
                Rule orig = new Rule("Byte " + code, onUartRx("serie"),
                        new UartByteEquals("serie", code), List.of(ledOn("led")), List.of());
                ProgramSpec spec = new ProgramSpec(base.name(), base.fCpuHz(), base.components(),
                        base.vars(), base.phaseGroups(), base.timers(), List.of(orig));
                RuleBuilderDialog dlg = RuleBuilderDialog.createDialog(null, spec, orig, 0);
                try {
                    Rule reloaded = dlg.currentRule();
                    assertEquals(orig, reloaded, "Corrupción al editar byte UART esperado " + code);
                } finally {
                    dlg.dispose();
                }
            }
        });
    }

    @Test
    void projectJsonRejectsTrailingGarbageAndOverflowedFormatVersion() {
        String validJson = ProjectJson.toJson(BenchmarkCatalog.b01LedBoton());

        // Contenido extra tras el cierre del objeto JSON
        assertThrows(IllegalArgumentException.class,
                () -> ProjectJson.parse(validJson + " basura_extra"));

        // Versión 4294967298 (0x100000002) NO debe truncarse a 2
        String overflowed = validJson.replace("\"formatVersion\": 2", "\"formatVersion\": 4294967298");
        assertNotEquals(validJson, overflowed);
        assertThrows(IllegalArgumentException.class,
                () -> ProjectJson.parse(overflowed));
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
