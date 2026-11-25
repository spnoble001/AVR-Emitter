package dev.transpilador.v2;

import dev.transpilador.v2.arbiter.ResourceArbiter;
import dev.transpilador.v2.emit.AsmEmitter;
import dev.transpilador.v2.emit.CEmitter;
import dev.transpilador.v2.model.*;
import dev.transpilador.v2.persistence.ProjectJson;
import dev.transpilador.v2.sim.Toolchain;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.transpilador.v2.model.Effect.*;
import static dev.transpilador.v2.model.Expr.*;
import static dev.transpilador.v2.model.Hardware.*;
import static dev.transpilador.v2.model.Rule.*;
import static dev.transpilador.v2.model.State.VarPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Nivel 1 · Matriz Cartesiana Ortogonal (Trigger × BoolExpr × Effect).
 * Evalúa las 1,820 combinaciones (7 × 13 × 20) del catálogo completo contra ResourceArbiter,
 * verifica rechazos exactos vs. admisiones válidas + round-trip JSON + emisión C99/AVRASM2,
 * y compila/ensambla con 0 advertencias un subconjunto de cobertura 100 % por pares
 * repartido entre 1 MHz, 8 MHz y 16 MHz.
 */
class DomainMatrixTest {
    private static final Pattern NOP_INSTRUCTION = Pattern.compile("(?m)^\\s+nop\\b");
    private static final Pattern NON_ELEMENTARY = Pattern.compile("(?i)^\\s+(subi|sbci|cpc|tst)\\b");

    private record Named<T>(String id, T value, boolean requiresUartRx) {
        Named(String id, T value) { this(id, value, false); }
    }

    @Test
    void fullCartesianMatrixArbitrationEmissionAndPairwiseRealBuilds() throws Exception {
        var arbiter = new ResourceArbiter();
        var cEmitter = new CEmitter();
        var asmEmitter = new AsmEmitter();
        var tc = Toolchain.detect();
        assertTrue(tc.hasAvrGcc() && tc.hasAvrasm2(), "Se requieren avr-gcc y avrasm2");

        // 7 Disparadores (todos los permisos de Rule.Trigger)
        List<Named<Rule.Trigger>> triggers = List.of(
                new Named<>("onBoot", onBoot()),
                new Named<>("always", always()),
                new Named<>("onPress", onPress("btn_gnd")),
                new Named<>("onRelease", onRelease("btn_vcc")),
                new Named<>("everyMs", everyMs(25)),
                new Named<>("onTimer", onTimer("tmr_a")),
                new Named<>("onUartRx", onUartRx("serie"))
        );

        // 13 Condiciones (cubre todos los nodos de Expr.BoolExpr + SRAM vars + ADC vs ADC)
        List<Named<Expr.BoolExpr>> guards = List.of(
                new Named<>("truth", alwaysTrue()),
                new Named<>("pressedGnd", pressed("btn_gnd")),
                new Named<>("pressedVcc", pressed("btn_vcc")),
                new Named<>("cmp8RegSram", cmp(var("v0"), CmpOp.GT, var("v5"))),
                new Named<>("cmp10AdcAdc", cmp(adc10("pot0"), CmpOp.LE, adc10("pot1"))),
                new Named<>("cmp10Const", cmp(adc10("pot0"), CmpOp.GT, u10(600))),
                new Named<>("inRangeSram", new InRange(var("v4"), 2, 7)),
                new Named<>("moduloNoZero", new Modulo("v5", 3, 1, false)),
                new Named<>("inPhase", inPhase("fsm", "ACTIVO")),
                new Named<>("timerActive", timerActive("tmr_a")),
                new Named<>("uartEq", uartChar("serie", 'k'), true),
                new Named<>("notOr", not(or(pressed("btn_gnd"), inPhase("fsm", "ACTIVO")))),
                new Named<>("xorCompound", xor(pressed("btn_vcc"), cmp(var("v1"), CmpOp.EQ, u8(3))))
        );

        // 20 Efectos (cubre todos los nodos de Effect + desplazamientos BCD + Scale 8/10 + SRAM vars + 3 timers PWM)
        List<Named<Effect>> effects = List.of(
                new Named<>("ledGndOn", ledOn("led_gnd")),
                new Named<>("ledVccOff", ledOff("led_vcc")),
                new Named<>("toggleLed", toggleLed("led_vcc")),
                new Named<>("bcdLow", showBcd("disp_pb", var("v0"))),
                new Named<>("bcdShiftAdc10", showBcd("disp_pd", scaleAdc10To("pot0", 0, 9))),
                new Named<>("decimal2Sram", showDecimal2("disp_pb", "disp_pd", var("v5"))),
                new Named<>("pwmOc0Const", setPwm("pwm0", u8(128))),
                new Named<>("pwmOc1Sram", setPwm("pwm1", var("v4"))),
                new Named<>("pwmOc2Adc8", setPwm("pwm2", adc8("pot0"))),
                new Named<>("uartText", sendText("serie", "OK\r\n")),
                new Named<>("uartByteSram", sendByte("serie", var("v5"))),
                new Named<>("uartEchoRx", sendByte("serie", uartByte("serie")), true),
                new Named<>("setVarScale8", setVar("v5", scaleAdc8To("pot1", 2, 8))),
                new Named<>("incSramClamp", inc("v5")),
                new Named<>("decSramWrap", dec("v4")),
                new Named<>("resetSram", resetVar("v5")),
                new Named<>("setPhase", setPhase("fsm", "ACTIVO")),
                new Named<>("startTimer", startTimer("tmr_a", 50)),
                new Named<>("stopTimer", stopTimer("tmr_a")),
                new Named<>("delayMs", delayMs(5))
        );

        int total = 0;
        int accepted = 0;
        int expectedRejected = 0;

        // Seguimiento de cobertura 100 % por pares para compilar/ensamblar realmente
        Set<String> coveredTG = new HashSet<>();
        Set<String> coveredGE = new HashSet<>();
        Set<String> coveredTE = new HashSet<>();
        record BuildCase(String id, ProgramSpec spec, String cCode, String asmCode) {}
        List<BuildCase> toBuild = new ArrayList<>();
        ClockFreq[] clocks = ClockFreq.values();

        for (int ti = 0; ti < triggers.size(); ti++) {
            var trig = triggers.get(ti);
            for (int gi = 0; gi < guards.size(); gi++) {
                var guard = guards.get(gi);
                for (int ei = 0; ei < effects.size(); ei++) {
                    var eff = effects.get(ei);
                    total++;

                    boolean needsRx = guard.requiresUartRx() || eff.requiresUartRx();
                    boolean isRxTrigger = trig.value() instanceof Rule.OnUartRx;
                    boolean shouldBeValid = !needsRx || isRxTrigger;

                    ClockFreq freq = clocks[(ti + gi + ei) % clocks.length];
                    String caseId = "m_" + ti + "_" + gi + "_" + ei;
                    ProgramSpec spec = buildMatrixSpec(caseId, freq, trig.value(), guard.value(), eff.value());

                    var res = arbiter.analyze(spec);
                    if (!shouldBeValid) {
                        assertFalse(res.valid(), () -> caseId + " debía rechazarse (UART RX fuera de OnUartRx)");
                        assertTrue(res.errors().stream().anyMatch(d -> d.code().equals("UART_BYTE_OUTSIDE_RX")),
                                () -> caseId + " código esperado UART_BYTE_OUTSIDE_RX, obtenido: " + res.errors());
                        expectedRejected++;
                        continue;
                    }

                    assertTrue(res.valid(), () -> caseId + " falló arbitraje: " + res.errors());
                    accepted++;

                    // Emitir C99 y AVRASM2 (Elemental por defecto) en las 100 % combinaciones válidas
                    String cCode = cEmitter.emit(spec);
                    String asmCode = asmEmitter.emit(spec);
                    assertTrue(countNops(asmCode) <= 1, () -> caseId + " emitió >1 NOP en ASM");
                    for (String line : asmCode.split("\\R")) {
                        String codeOnly = line.replaceFirst(";.*", "");
                        assertFalse(NON_ELEMENTARY.matcher(codeOnly).find(),
                                () -> caseId + " emitió instrucción no elemental en perfil ELEMENTARY: " + line);
                    }

                    // Seleccionar cobertura por pares (100 % de pares válidos TG, GE y TE) para compilar realmente
                    String kTG = ti + ":" + gi;
                    String kGE = gi + ":" + ei;
                    String kTE = ti + ":" + ei;
                    if (!coveredTG.contains(kTG) || !coveredGE.contains(kGE) || !coveredTE.contains(kTE)) {
                        coveredTG.add(kTG);
                        coveredGE.add(kGE);
                        coveredTE.add(kTE);
                        // Verificar JSON round-trip en cada caso representativo
                        assertEquals(spec, ProjectJson.parse(ProjectJson.toJson(spec)), caseId + " JSON round-trip");
                        toBuild.add(new BuildCase(caseId, spec, cCode, asmCode));
                    }
                }
            }
        }

        assertEquals(triggers.size() * guards.size() * effects.size(), total);
        assertTrue(expectedRejected > 0, "Debe haber rechazos negativos verificados");
        assertTrue(accepted > 1400, "Deben validarse y emitirse >1400 combinaciones válidas, fueron: " + accepted);
        assertTrue(toBuild.size() >= 100, "La cobertura por pares debe compilar >= 100 programas distintos, fueron: " + toBuild.size());
        System.out.printf("DomainMatrixTest: total=%d, valid=%d, expectedRejected=%d, pairwiseBuilds=%d (C99+ASM = %d builds)%n",
                total, accepted, expectedRejected, toBuild.size(), toBuild.size() * 2);

        // Compilar en paralelo con avr-gcc (-Wall -Wextra -Werror) y ensamblar con avrasm2 (0 warnings)
        Path workDir = Path.of("target/domain-matrix-builds").toAbsolutePath();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (BuildCase bc : toBuild) {
                futures.add(pool.submit(() -> {
                    tc.compileC(bc.id(), bc.cCode(), workDir.resolve("c").resolve(bc.id()));
                    tc.assembleAsm(bc.id(), bc.asmCode(), workDir.resolve("asm").resolve(bc.id()));
                    return null;
                }));
            }
            for (Future<Void> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static ProgramSpec buildMatrixSpec(String name, ClockFreq freq, Rule.Trigger trigger,
                                               Expr.BoolExpr guard, Effect effect) {
        var builder = ProgramSpec.builder(name)
                .clock(freq)
                .analog("pot0", Pin.pc(0))
                .analog("pot1", Pin.pc(1))
                .uart("serie", UartBaud.B9600, UartMode.FULL_DUPLEX)
                .var("v0", 0, 9, 1, WRAP)
                .var("v1", 0, 9, 3, WRAP)
                .var("v2", 0, 99, 10, WRAP)
                .var("v3", 0, 99, 20, CLAMP)
                .var("v4", 0, 99, 5, WRAP)   // SRAM #1 en ASM
                .var("v5", 0, 99, 7, CLAMP)  // SRAM #2 en ASM
                .phases("fsm", "REPOSO", "REPOSO", "ACTIVO")
                .timer("tmr_a");

        boolean needsSecondDisplay = effect instanceof Effect.WriteDecimal2
                || (effect instanceof Effect.WriteBcd wb && wb.displayName().equals("disp_pd"));
        if (needsSecondDisplay) {
            // Dos displays (PB0..PB3 y PD4..PD7) + botones/LEDs en PC2..PC5
            builder.bcd("disp_pb", Pin.pb(0), 0)
                    .bcd("disp_pd", Pin.pd(4), 0)
                    .button("btn_gnd", Pin.pc(2), Connection.GND)
                    .button("btn_vcc", Pin.pc(3), Connection.VCC)
                    .led("led_gnd", Pin.pc(4), Connection.GND)
                    .led("led_vcc", Pin.pc(5), Connection.VCC, true);
        } else {
            // Todos los 3 timers PWM (PD6/OC0A, PB1/OC1A, PB3/OC2A) + 1 display BCD (PC2..PC5) + botones/LEDs en PB/PD
            builder.pwm("pwm0", Pin.pd(6), 0)
                    .pwm("pwm1", Pin.pb(1), 0)
                    .pwm("pwm2", Pin.pb(3), 0)
                    .bcd("disp_pb", Pin.pc(2), 0)
                    .button("btn_gnd", Pin.pd(2), Connection.GND)
                    .button("btn_vcc", Pin.pd(4), Connection.VCC)
                    .led("led_gnd", Pin.pb(0), Connection.GND)
                    .led("led_vcc", Pin.pb(4), Connection.VCC, true);
        }

        // Asegurar que tmr_a se inicie para no disparar advertencias si el trigger no es StartTimer
        if (!(effect instanceof Effect.StartTimer)) {
            builder.rule("Init timer", onBoot(), startTimer("tmr_a", 20));
        }
        builder.rule("Regla matriz " + name, trigger, guard, effect);
        return builder.build();
    }

    private static int countNops(String asm) {
        Matcher m = NOP_INSTRUCTION.matcher(asm);
        int count = 0;
        while (m.find()) count++;
        return count;
    }
}
