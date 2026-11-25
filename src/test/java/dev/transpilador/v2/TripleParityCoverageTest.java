package dev.transpilador.v2;

import dev.transpilador.v2.emit.AsmEmitter;
import dev.transpilador.v2.emit.CEmitter;
import dev.transpilador.v2.model.*;
import dev.transpilador.v2.sim.ReferenceOracle;
import dev.transpilador.v2.sim.SimavrSession;
import dev.transpilador.v2.sim.Toolchain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static dev.transpilador.v2.model.Effect.*;
import static dev.transpilador.v2.model.Expr.*;
import static dev.transpilador.v2.model.Hardware.*;
import static dev.transpilador.v2.model.Rule.*;
import static dev.transpilador.v2.model.State.VarPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Nivel 2 · Triple Paridad Ejecutable (ReferenceOracle ≡ C99 en simavr ≡ AVRASM2 en simavr)
 * cubriendo sistemáticamente todas las ramas de los emisores no cubiertas por los 15 benchmarks:
 *   1. >4 variables (4 en registros r22..r25 + 2 en .dseg SRAM) con INC/DEC × WRAP/CLAMP + SetVar + ResetVar
 *   2. Polaridades alternas: Botón a VCC (pull-down) y LED a VCC (activo bajo) + ToggleLed
 *   3. Displays BCD con desplazamiento (PB2 y PD4) preservando bits vecinos + WriteDecimal2
 *   4. Los 6 operadores de comparación (EQ, NE, LT, LE, GT, GE) en 8-bit y 10-bit (ADC vs ADC)
 *   5. InRange y Modulo (con y sin incluir cero) de 0 a 9
 *   6. Scale de 10 bits (escalar_10b con outMin > 0) + SendUartByte binario
 *   7. Los 6 canales hardware Fast PWM (OC0A/B, OC1A/B, OC2A/B) en extremos 0/255 y onda activa a 1 y 16 MHz
 *   8. TimerActive + StopTimer + DelayMs bloqueante a 1 y 16 MHz
 *   9. Tabla de verdad exhaustiva (16 filas) de Not(Or(And(a,b), Xor(c,d)))
 */
class TripleParityCoverageTest {
    private static Toolchain tc;
    private static final CEmitter C_EMIT = new CEmitter();
    private static final AsmEmitter ASM_EMIT = new AsmEmitter();
    private static final Path WORK = Path.of("target/triple-parity").toAbsolutePath();

    @BeforeAll
    static void checkTools() {
        tc = Toolchain.detect();
        assertTrue(tc.hasAvrGcc() && tc.hasAvrasm2() && tc.hasSimavr());
    }

    @Test
    void sramOverflowSixVariablesStepPoliciesAndComparisons() throws Exception {
        // v0..v3 en registros r22..r25; v4 (WRAP 1..4) y v5 (CLAMP 0..3) en .dseg SRAM
        var spec = ProgramSpec.builder("cov_sram_6_vars")
                .button("btn_inc", Pin.pd(2), Connection.GND)
                .button("btn_dec", Pin.pd(3), Connection.GND)
                .button("btn_rst", Pin.pd(4), Connection.GND)
                .uart("serie", UartBaud.B9600, UartMode.TX_ONLY)
                .led("led_gt", Pin.pb(0), Connection.GND)
                .var("v0", 0, 9, 2, WRAP)
                .var("v1", 0, 9, 1, WRAP)
                .var("v2", 0, 9, 0, WRAP)
                .var("v3", 0, 9, 0, WRAP)
                .var("v4", 1, 4, 1, WRAP)   // SRAM #1
                .var("v5", 0, 3, 0, CLAMP)  // SRAM #2
                .rule("Avanzar SRAM v4(WRAP 1..4) y v5(CLAMP 0..3) y reportar bytes", onPress("btn_inc"),
                        inc("v4"), inc("v5"), sendByte("serie", var("v4")), sendByte("serie", var("v5")))
                .rule("Retroceder SRAM v4 y v5 y reportar bytes", onPress("btn_dec"),
                        dec("v4"), dec("v5"), sendByte("serie", var("v4")), sendByte("serie", var("v5")))
                .rule("Reiniciar SRAM v4 y fijar v5=2", onPress("btn_rst"),
                        resetVar("v4"), setVar("v5", u8(2)), sendByte("serie", var("v4")), sendByte("serie", var("v5")))
                .rule("Comparar SRAM v4 > registro v0 (2)", always(),
                        cmp(var("v4"), CmpOp.GT, var("v0")),
                        List.of(ledOn("led_gt")), List.of(ledOff("led_gt")))
                .build();

        var oracle = new ReferenceOracle(spec);
        var bInc = spec.button("btn_inc").orElseThrow();
        var bDec = spec.button("btn_dec").orElseThrow();
        var bRst = spec.button("btn_rst").orElseThrow();
        var ledGt = spec.led("led_gt").orElseThrow();

        // Secuencia de estímulos sobre el oráculo
        oraclePulse(oracle, "btn_inc"); // v4=2, v5=1 -> ledGt=false (2>2 false)
        oraclePulse(oracle, "btn_inc"); // v4=3, v5=2 -> ledGt=true (3>2)
        oraclePulse(oracle, "btn_inc"); // v4=4, v5=3 -> ledGt=true
        oraclePulse(oracle, "btn_inc"); // v4=1(wrap), v5=3(clamp!) -> ledGt=false
        oraclePulse(oracle, "btn_dec"); // v4=4(wrap!), v5=2 -> ledGt=true
        oraclePulse(oracle, "btn_rst"); // v4=1, v5=2 -> ledGt=false
        List<Integer> expectedTx = oracle.uartTxBytes();
        boolean expectedLed = oracle.isLedOn("led_gt");
        assertEquals(List.of(2, 1, 3, 2, 4, 3, 1, 3, 4, 2, 1, 2), expectedTx);
        assertFalse(expectedLed);

        // Verifica triple paridad tanto en modo registros (sin SRAM) como en modo SRAM (.dseg) opcional
        String asmReg = ASM_EMIT.emit(spec, AsmEmitter.StorageMode.REGISTERS_FIRST);
        String asmSram = ASM_EMIT.emit(spec, AsmEmitter.StorageMode.SRAM_DATA_SEGMENT);
        assertFalse(asmReg.contains(".dseg"), "6 variables deben caber en registros sin .dseg");
        assertTrue(asmSram.contains(".dseg"), "Modo SRAM opcional debe usar .dseg");

        runBackends(spec, List.of(
                new BackendHex("C99", tc.compileC(spec.name(), C_EMIT.emit(spec), WORK.resolve(spec.name()).resolve("c")).hexFile()),
                new BackendHex("ASM-REG", tc.assembleAsm(spec.name() + "_reg", asmReg, WORK.resolve(spec.name()).resolve("asm_reg")).hexFile()),
                new BackendHex("ASM-SRAM", tc.assembleAsm(spec.name() + "_sram", asmSram, WORK.resolve(spec.name()).resolve("asm_sram")).hexFile())
        ), (sim, be) -> {
            sim.advanceMs(5);
            simPulse(sim, bInc, 5, 10);
            assertFalse(sim.state().isLedOn(ledGt), be + ": tras inc1 v4=2 -> ledGt OFF");
            simPulse(sim, bInc, 5, 10);
            assertTrue(sim.state().isLedOn(ledGt), be + ": tras inc2 v4=3 -> ledGt ON");
            simPulse(sim, bInc, 5, 10);
            simPulse(sim, bInc, 5, 10);
            assertFalse(sim.state().isLedOn(ledGt), be + ": tras wrap v4=1 -> ledGt OFF");
            simPulse(sim, bDec, 5, 10);
            assertTrue(sim.state().isLedOn(ledGt), be + ": tras dec wrap v4=4 -> ledGt ON");
            simPulse(sim, bRst, 5, 10);
            assertEquals(expectedLed, sim.state().isLedOn(ledGt), be + ": ledGt final vs Oráculo");
            assertEquals(expectedTx, sim.state().txBytes(), be + ": secuencia bytes SRAM vs Oráculo");
        });
    }

    @Test
    void alternatePolaritiesButtonVccAndLedVccActiveLow() throws Exception {
        var spec = ProgramSpec.builder("cov_polaridades_vcc")
                .button("btn_vcc", Pin.pd(2), Connection.VCC)        // presionado = 1
                .led("led_vcc_off", Pin.pb(0), Connection.VCC, false) // encendido = pin LOW
                .led("led_vcc_on", Pin.pb(1), Connection.VCC, true)   // inicia encendido (pin LOW)
                .rule("Seguir botón VCC en LED VCC", always(), pressed("btn_vcc"),
                        List.of(ledOn("led_vcc_off")), List.of(ledOff("led_vcc_off")))
                .rule("Alternar LED VCC inicial ON al presionar", onPress("btn_vcc"),
                        toggleLed("led_vcc_on"))
                .build();

        var oracle = new ReferenceOracle(spec);
        var btn = spec.button("btn_vcc").orElseThrow();
        var ledOff = spec.led("led_vcc_off").orElseThrow();
        var ledOn = spec.led("led_vcc_on").orElseThrow();

        runBothBackends(spec, (sim, be) -> {
            var s0 = sim.advanceMs(3);
            assertEquals(oracle.isLedOn("led_vcc_off"), s0.isLedOn(ledOff), be + ": inicial OFF");
            assertEquals(oracle.isLedOn("led_vcc_on"), s0.isLedOn(ledOn), be + ": inicial ON");
            // Verificar polaridad eléctrica real en pines (activo bajo!)
            assertTrue(s0.pinHigh(ledOff.pin()), be + ": LED a VCC apagado debe poner pin en ALTO");
            assertFalse(s0.pinHigh(ledOn.pin()), be + ": LED a VCC encendido debe poner pin en BAJO");

            sim.setButton(btn, true);
            var s1 = sim.advanceMs(3);
            assertTrue(s1.isLedOn(ledOff), be + ": botón VCC presionado enciende LED a VCC");
            assertFalse(s1.pinHigh(ledOff.pin()), be + ": LED a VCC encendido debe poner pin en BAJO");
            assertFalse(s1.isLedOn(ledOn), be + ": toggle apaga LED a VCC inicial ON");
            assertTrue(s1.pinHigh(ledOn.pin()), be + ": LED a VCC apagado tras toggle debe estar en ALTO");
        });
    }

    @Test
    void shiftedBcdDisplaysPreserveNeighborPins() throws Exception {
        // Display unidades en PB2..PB5 (shift 2 -> lsl x2) + LED testigo en PB0 (no debe borrarse!)
        // Display decenas en PD4..PD7 (shift 4 -> lsl x4) + LED testigo en PD2 (no debe borrarse!)
        var spec = ProgramSpec.builder("cov_bcd_desplazado")
                .led("led_pb0", Pin.pb(0), Connection.GND, true)
                .led("led_pd2", Pin.pd(2), Connection.GND, true)
                .bcd("uni", Pin.pb(2), 0)
                .bcd("dec", Pin.pd(4), 0)
                .var("num", 0, 99, 74, WRAP)
                .rule("Escribir 74 en displays desplazados al iniciar", onBoot(),
                        showDecimal2("uni", "dec", var("num")))
                .build();

        var oracle = new ReferenceOracle(spec);
        assertEquals(7, oracle.bcd("dec"));
        assertEquals(4, oracle.bcd("uni"));

        var uni = spec.bcd("uni").orElseThrow();
        var dec = spec.bcd("dec").orElseThrow();
        var ledB = spec.led("led_pb0").orElseThrow();
        var ledD = spec.led("led_pd2").orElseThrow();

        runBothBackends(spec, (sim, be) -> {
            var st = sim.advanceMs(3);
            assertEquals(7, st.readBcd(dec), be + ": decenas desplazadas en PD4..PD7 = 7");
            assertEquals(4, st.readBcd(uni), be + ": unidades desplazadas en PB2..PB5 = 4");
            assertTrue(st.isLedOn(ledB), be + ": PB0 vecino debe permanecer encendido");
            assertTrue(st.isLedOn(ledD), be + ": PD2 vecino debe permanecer encendido");
        });
    }

    @Test
    void allSixComparisonOperatorsIn8BitAnd10BitAdcVsAdc() throws Exception {
        // 6 LEDs reflejan simultáneamente los 6 operadores de comparación entre ADC0 y ADC1 (10 bits)
        var spec = ProgramSpec.builder("cov_6_operadores_adc10")
                .analog("a0", Pin.pc(0))
                .analog("a1", Pin.pc(1))
                .led("l_eq", Pin.pb(0), Connection.GND)
                .led("l_ne", Pin.pb(1), Connection.GND)
                .led("l_lt", Pin.pb(2), Connection.GND)
                .led("l_le", Pin.pb(3), Connection.GND)
                .led("l_gt", Pin.pb(4), Connection.GND)
                .led("l_ge", Pin.pb(5), Connection.GND)
                .rule("EQ", always(), cmp(adc10("a0"), CmpOp.EQ, adc10("a1")), List.of(ledOn("l_eq")), List.of(ledOff("l_eq")))
                .rule("NE", always(), cmp(adc10("a0"), CmpOp.NE, adc10("a1")), List.of(ledOn("l_ne")), List.of(ledOff("l_ne")))
                .rule("LT", always(), cmp(adc10("a0"), CmpOp.LT, adc10("a1")), List.of(ledOn("l_lt")), List.of(ledOff("l_lt")))
                .rule("LE", always(), cmp(adc10("a0"), CmpOp.LE, adc10("a1")), List.of(ledOn("l_le")), List.of(ledOff("l_le")))
                .rule("GT", always(), cmp(adc10("a0"), CmpOp.GT, adc10("a1")), List.of(ledOn("l_gt")), List.of(ledOff("l_gt")))
                .rule("GE", always(), cmp(adc10("a0"), CmpOp.GE, adc10("a1")), List.of(ledOn("l_ge")), List.of(ledOff("l_ge")))
                .build();

        var oracle = new ReferenceOracle(spec);
        int[][] pairsMv = {{1000, 3000}, {2500, 2500}, {4000, 1500}};
        String[] ledNames = {"l_eq", "l_ne", "l_lt", "l_le", "l_gt", "l_ge"};

        runBothBackends(spec, (sim, be) -> {
            for (int[] pair : pairsMv) {
                oracle.stepPass(ReferenceOracle.InputFrame.ofAnalogMv(Map.of("a0", pair[0], "a1", pair[1])));
                sim.setAnalogMv(0, pair[0]);
                sim.setAnalogMv(1, pair[1]);
                var st = sim.advanceMs(5);
                for (String ln : ledNames) {
                    assertEquals(oracle.isLedOn(ln), st.isLedOn(spec.led(ln).orElseThrow()),
                            be + ": " + ln + " para (" + pair[0] + "mV, " + pair[1] + "mV)");
                }
            }
        });
    }

    @Test
    void inRangeAndModuloWithAndWithoutZeroAcrossAllDigits() throws Exception {
        var spec = ProgramSpec.builder("cov_inrange_modulo")
                .button("btn", Pin.pd(2), Connection.GND)
                .led("l_range", Pin.pb(0), Connection.GND)
                .led("l_mod_inc0", Pin.pb(1), Connection.GND)
                .led("l_mod_no0", Pin.pb(2), Connection.GND)
                .var("c", 0, 9, 0, WRAP)
                .rule("Avanzar contador", onPress("btn"), inc("c"))
                .rule("InRange 3..6", always(), new InRange(var("c"), 3, 6),
                        List.of(ledOn("l_range")), List.of(ledOff("l_range")))
                .rule("Múltiplo de 3 con cero", always(), new Modulo("c", 3, 0, true),
                        List.of(ledOn("l_mod_inc0")), List.of(ledOff("l_mod_inc0")))
                .rule("Múltiplo de 3 sin cero", always(), new Modulo("c", 3, 0, false),
                        List.of(ledOn("l_mod_no0")), List.of(ledOff("l_mod_no0")))
                .build();

        var oracle = new ReferenceOracle(spec);
        var btn = spec.button("btn").orElseThrow();
        var lRange = spec.led("l_range").orElseThrow();
        var lModInc0 = spec.led("l_mod_inc0").orElseThrow();
        var lModNo0 = spec.led("l_mod_no0").orElseThrow();

        // Precalcular tabla esperada en Oráculo para c = 0..9
        boolean[][] expected = new boolean[10][3];
        oracle.stepPass(ReferenceOracle.InputFrame.empty());
        for (int v = 0; v < 10; v++) {
            expected[v] = new boolean[]{oracle.isLedOn("l_range"), oracle.isLedOn("l_mod_inc0"), oracle.isLedOn("l_mod_no0")};
            oraclePulse(oracle, "btn");
        }
        // Verificación directa en c = 0: includeZero=true -> ON, includeZero=false -> OFF
        assertTrue(expected[0][1]);
        assertFalse(expected[0][2]);

        runBothBackends(spec, (sim, be) -> {
            sim.advanceMs(2);
            for (int v = 0; v < 10; v++) {
                var st = sim.state();
                assertEquals(expected[v][0], st.isLedOn(lRange), be + ": InRange en c=" + v);
                assertEquals(expected[v][1], st.isLedOn(lModInc0), be + ": Mod(inc0) en c=" + v);
                assertEquals(expected[v][2], st.isLedOn(lModNo0), be + ": Mod(no0) en c=" + v);
                simPulse(sim, btn, 2, 2);
            }
        });
    }

    @Test
    void scaleAdc10BitWithOffsetAndBinaryUartByteTx() throws Exception {
        // Ejercita escalar_10b con outMin=2..outMax=8 (span=7) y SendUartByte
        var spec = ProgramSpec.builder("cov_scale10_uart_byte")
                .analog("pot", Pin.pc(0))
                .button("btn_leer", Pin.pd(2), Connection.GND)
                .uart("serie", UartBaud.B9600, UartMode.TX_ONLY)
                .rule("Enviar muestra escalada 2..8 al presionar", onPress("btn_leer"),
                        sendByte("serie", scaleAdc10To("pot", 2, 8)))
                .build();

        var oracle = new ReferenceOracle(spec);
        var btn = spec.button("btn_leer").orElseThrow();
        int[] mvPoints = {0, 2500, 4950};
        for (int mv : mvPoints) {
            var frameDown = new ReferenceOracle.InputFrame(Map.of("btn_leer", true), Map.of("pot", (mv * 1023) / 5000), OptionalInt.empty());
            var frameUp = new ReferenceOracle.InputFrame(Map.of("btn_leer", false), Map.of("pot", (mv * 1023) / 5000), OptionalInt.empty());
            oracle.stepPass(frameDown);
            oracle.stepPass(frameUp);
        }
        List<Integer> expected = oracle.uartTxBytes();
        assertEquals(List.of(2, 5, 8), expected);

        runBothBackends(spec, (sim, be) -> {
            sim.advanceMs(3);
            for (int mv : mvPoints) {
                sim.setAnalogMv(0, mv);
                simPulse(sim, btn, 5, 10);
            }
            assertEquals(expected, sim.state().txBytes(), be + ": bytes escalar_10b vs Oráculo");
        });
    }

    @Test
    void allSixHardwarePwmChannelsExtremesAndWavesAt1MHzAnd16MHz() throws Exception {
        for (ClockFreq freq : List.of(ClockFreq.MHZ_1, ClockFreq.MHZ_16)) {
            var spec = ProgramSpec.builder("cov_6_canales_pwm_" + freq.name())
                    .clock(freq)
                    .pwm("oc0a", Pin.pd(6), 0)    // extremo 0
                    .pwm("oc0b", Pin.pd(5), 255)  // extremo 255
                    .pwm("oc1a", Pin.pb(1), 128)  // onda 50% Timer1 A
                    .pwm("oc1b", Pin.pb(2), 64)   // onda 25% Timer1 B
                    .pwm("oc2a", Pin.pb(3), 128)  // onda 50% Timer2 A
                    .pwm("oc2b", Pin.pd(3), 192)  // onda 75% Timer2 B
                    .rule("Mantener configuración inicial", onBoot(), setPwm("oc0a", u8(0)))
                    .build();

            runBothBackends(spec, (sim, be) -> {
                sim.advanceMs(8);
                var st = sim.state();
                assertFalse(st.pinHigh(Pin.pd(6)), be + "@" + freq + ": OC0A=0 debe ser BAJO fijo");
                assertTrue(st.pinHigh(Pin.pd(5)), be + "@" + freq + ": OC0B=255 debe ser ALTO fijo");

                String vcd = sim.readVcd();
                for (Pin activePin : List.of(Pin.pb(1), Pin.pb(2), Pin.pb(3), Pin.pd(3))) {
                    char id = vcdId(activePin);
                    assertTrue(vcd.contains("1" + id) && vcd.contains("0" + id),
                            be + "@" + freq + ": pin " + activePin + " debe conmutar PWM real en VCD");
                }

                // Verificar también telemetría directa de ciclos de trabajo @PWM de simavr (usada por el osciloscopio UI)
                var duties = sim.readPwmDuties();
                assertEquals(0, duties.get(PwmChannel.OC0A), be + "@" + freq + ": duty OC0A");
                assertEquals(255, duties.get(PwmChannel.OC0B), be + "@" + freq + ": duty OC0B");
                assertEquals(128, duties.get(PwmChannel.OC1A), be + "@" + freq + ": duty OC1A");
                assertEquals(64, duties.get(PwmChannel.OC1B), be + "@" + freq + ": duty OC1B");
                assertEquals(128, duties.get(PwmChannel.OC2A), be + "@" + freq + ": duty OC2A");
                assertEquals(192, duties.get(PwmChannel.OC2B), be + "@" + freq + ": duty OC2B");
            });
        }
    }

    @Test
    void timerActiveConditionStopTimerAndBlockingDelayMs() throws Exception {
        for (ClockFreq freq : List.of(ClockFreq.MHZ_1, ClockFreq.MHZ_16)) {
            var spec = ProgramSpec.builder("cov_timer_active_delay_" + freq.name())
                    .clock(freq)
                    .button("btn_start", Pin.pd(2), Connection.GND)
                    .button("btn_stop", Pin.pd(3), Connection.GND)
                    .led("led_activo", Pin.pb(0), Connection.GND)
                    .led("led_boot", Pin.pb(1), Connection.GND)
                    .timer("t1")
                    .rule("Retardo bloqueante de 15 ms al arrancar y encender LED boot", onBoot(),
                            delayMs(15), ledOn("led_boot"))
                    .rule("Iniciar timer 80 ms", onPress("btn_start"), startTimer("t1", 80))
                    .rule("Detener timer", onPress("btn_stop"), stopTimer("t1"))
                    .rule("LED refleja TimerActive(t1)", always(), timerActive("t1"),
                            List.of(ledOn("led_activo")), List.of(ledOff("led_activo")))
                    .build();

            var bStart = spec.button("btn_start").orElseThrow();
            var bStop = spec.button("btn_stop").orElseThrow();
            var lAct = spec.led("led_activo").orElseThrow();
            var lBoot = spec.led("led_boot").orElseThrow();

            runBothBackends(spec, (sim, be) -> {
                // Antes de 15 ms, DelayMs(15) aún bloquea OnBoot -> led_boot sigue OFF
                assertFalse(sim.advanceMs(6).isLedOn(lBoot), be + "@" + freq + ": a 6ms aún en DelayMs(15)");
                // Después de 20 ms totales -> led_boot ON, timer aún inactivo
                var sBoot = sim.advanceMs(16);
                assertTrue(sBoot.isLedOn(lBoot), be + "@" + freq + ": tras DelayMs(15) led_boot ON");
                assertFalse(sBoot.isLedOn(lAct), be + "@" + freq + ": timer inactivo inicialmente");

                // Arrancar timer 80 ms -> activo
                simPulse(sim, bStart, 5, 5);
                assertTrue(sim.state().isLedOn(lAct), be + "@" + freq + ": TimerActive debe ser true");

                // StopTimer cancela inmediatamente -> inactivo
                simPulse(sim, bStop, 5, 5);
                assertFalse(sim.state().isLedOn(lAct), be + "@" + freq + ": tras StopTimer debe ser false");

                // Arrancar de nuevo y dejarlo vencer (> 90 ms) -> inactivo
                simPulse(sim, bStart, 5, 5);
                assertTrue(sim.state().isLedOn(lAct), be + "@" + freq + ": reactivado");
                sim.advanceMs(95);
                assertFalse(sim.state().isLedOn(lAct), be + "@" + freq + ": vencido -> false");
            });
        }
    }

    @Test
    void exhaustive16RowCompoundLogicTruthTableTripleParity() throws Exception {
        // Expresión anidada: NOT( OR( AND(a, b), XOR(c, d) ) ) sobre 4 botones -> 16 combinaciones
        var spec = ProgramSpec.builder("cov_tabla_verdad_16")
                .button("a", Pin.pd(2), Connection.GND)
                .button("b", Pin.pd(3), Connection.GND)
                .button("c", Pin.pd(4), Connection.GND)
                .button("d", Pin.pd(5), Connection.GND)
                .led("out", Pin.pb(0), Connection.GND)
                .rule("F = NOT((a AND b) OR (c XOR d))", always(),
                        not(or(and(pressed("a"), pressed("b")), xor(pressed("c"), pressed("d")))),
                        List.of(ledOn("out")), List.of(ledOff("out")))
                .build();

        var oracle = new ReferenceOracle(spec);
        var btns = List.of(
                spec.button("a").orElseThrow(),
                spec.button("b").orElseThrow(),
                spec.button("c").orElseThrow(),
                spec.button("d").orElseThrow());
        var outLed = spec.led("out").orElseThrow();

        boolean[] expected = new boolean[16];
        for (int mask = 0; mask < 16; mask++) {
            Map<String, Boolean> map = Map.of(
                    "a", (mask & 1) != 0,
                    "b", (mask & 2) != 0,
                    "c", (mask & 4) != 0,
                    "d", (mask & 8) != 0);
            oracle.stepPass(ReferenceOracle.InputFrame.ofButtons(map));
            expected[mask] = oracle.isLedOn("out");
        }

        runBothBackends(spec, (sim, be) -> {
            for (int mask = 0; mask < 16; mask++) {
                for (int bit = 0; bit < 4; bit++) {
                    sim.setButton(btns.get(bit), ((mask >> bit) & 1) != 0);
                }
                assertEquals(expected[mask], sim.advanceMs(1).isLedOn(outLed),
                        be + ": fila " + Integer.toBinaryString(mask) + " debe coincidir con Oráculo");
            }
        });
    }

    @FunctionalInterface
    private interface Scenario {
        void run(SimavrSession sim, String backend) throws Exception;
    }

    private record BackendHex(String label, Path hexFile) {}

    private void runBothBackends(ProgramSpec spec, Scenario scenario) throws Exception {
        Path cDir = WORK.resolve(spec.name()).resolve("c");
        Path asmElemDir = WORK.resolve(spec.name()).resolve("asm_elem");
        Path asmStdDir = WORK.resolve(spec.name()).resolve("asm_std");
        var cArt = tc.compileC(spec.name(), C_EMIT.emit(spec), cDir);
        var asmElemArt = tc.assembleAsm(spec.name() + "_elem",
                ASM_EMIT.emit(spec, AsmEmitter.InstructionProfile.ELEMENTARY, AsmEmitter.StorageMode.REGISTERS_FIRST), asmElemDir);
        var asmStdArt = tc.assembleAsm(spec.name() + "_std",
                ASM_EMIT.emit(spec, AsmEmitter.InstructionProfile.STANDARD, AsmEmitter.StorageMode.REGISTERS_FIRST), asmStdDir);
        runBackends(spec, List.of(
                new BackendHex("C99", cArt.hexFile()),
                new BackendHex("ASM-ELEMENTAL", asmElemArt.hexFile()),
                new BackendHex("ASM-ESTÁNDAR", asmStdArt.hexFile())), scenario);
    }

    private void runBackends(ProgramSpec spec, List<BackendHex> backends, Scenario scenario) throws Exception {
        for (var bh : backends) {
            try (SimavrSession sim = SimavrSession.open(bh.hexFile(), spec.fCpuHz(), tc)) {
                for (Hardware.Button b : spec.componentsOf(Hardware.Button.class)) {
                    sim.setButton(b, false);
                }
                scenario.run(sim, bh.label());
            }
        }
    }

    private static void oraclePulse(ReferenceOracle oracle, String buttonName) {
        oracle.stepPass(ReferenceOracle.InputFrame.ofButtons(Map.of(buttonName, true)));
        oracle.stepPass(ReferenceOracle.InputFrame.ofButtons(Map.of(buttonName, false)));
    }

    private static void simPulse(SimavrSession sim, Hardware.Button btn, int pressMs, int releaseMs) throws Exception {
        sim.setButton(btn, true);
        sim.advanceMs(pressMs);
        sim.setButton(btn, false);
        sim.advanceMs(releaseMs);
    }

    private static char vcdId(Pin pin) {
        int portIdx = switch (pin.port()) {
            case B -> 0;
            case C -> 1;
            case D -> 2;
        };
        return (char) ('!' + portIdx * 8 + pin.bit());
    }
}
