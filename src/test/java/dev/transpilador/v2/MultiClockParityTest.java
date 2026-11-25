package dev.transpilador.v2;

import dev.transpilador.v2.arbiter.ResourceArbiter;
import dev.transpilador.v2.emit.AsmEmitter;
import dev.transpilador.v2.emit.CEmitter;
import dev.transpilador.v2.model.*;
import dev.transpilador.v2.patterns.BenchmarkCatalog;
import dev.transpilador.v2.persistence.ProjectJson;
import dev.transpilador.v2.sim.SimavrSession;
import dev.transpilador.v2.sim.Toolchain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static dev.transpilador.v2.model.Effect.*;
import static dev.transpilador.v2.model.Expr.*;
import static dev.transpilador.v2.model.Hardware.*;
import static dev.transpilador.v2.model.Rule.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica soporte real de reloj ATmega328P en 1 MHz, 8 MHz y 16 MHz:
 * retardos calibrados en C99 y AVRASM2, prescalers ADC (125 kHz), Fast PWM (Timer0/1/2),
 * UART0 con UBRR0 de 12 bits (UBRR0H/UBRR0L) y paridad binaria en simavr a 8 y 16 MHz.
 */
class MultiClockParityTest {
    private static Toolchain tc;
    private static final CEmitter C_EMIT = new CEmitter();
    private static final AsmEmitter ASM_EMIT = new AsmEmitter();
    private static final Path WORK = Path.of("target/multiclock-simavr").toAbsolutePath();

    @BeforeAll
    static void checkTools() {
        tc = Toolchain.detect();
        assertTrue(tc.hasAvrGcc() && tc.hasAvrasm2() && tc.hasSimavr());
    }

    @Test
    void allFifteenBenchmarksBuildCleanlyAt8MHzAnd16MHz() throws Exception {
        for (ClockFreq freq : List.of(ClockFreq.MHZ_8, ClockFreq.MHZ_16)) {
            for (ProgramSpec base : BenchmarkCatalog.all()) {
                ProgramSpec spec = base.withClock(freq);
                assertEquals(spec, ProjectJson.parse(ProjectJson.toJson(spec)), "JSON debe preservar F_CPU " + freq);
                String c = C_EMIT.emit(spec);
                String asm = ASM_EMIT.emit(spec);
                assertTrue(c.contains("#define F_CPU " + freq.hz() + "UL"));
                tc.compileC(spec.name(), c, WORK.resolve("build_" + freq.name()).resolve("c").resolve(spec.name()));
                tc.assembleAsm(spec.name(), asm, WORK.resolve("build_" + freq.name()).resolve("asm").resolve(spec.name()));
            }
        }
    }

    @Test
    void rejectsHighBaudRateAt1MHzButAcceptsAt16MHz() {
        var arbiter = new ResourceArbiter();
        var at1 = ProgramSpec.builder("uart_115k_1mhz")
                .clock(ClockFreq.MHZ_1)
                .uart("serie", UartBaud.B115200, UartMode.FULL_DUPLEX)
                .rule("Saludo", onBoot(), sendText("serie", "HI\r\n"))
                .build();
        var res1 = arbiter.analyze(at1);
        assertFalse(res1.valid(), "115200 bps a 1 MHz debe rechazarse por error de reloj");
        assertTrue(res1.errors().stream().anyMatch(d -> d.code().equals("UART_BAUD_UNSUPPORTED")));

        var res16 = arbiter.analyze(at1.withClock(ClockFreq.MHZ_16));
        assertTrue(res16.valid(), () -> "115200 bps a 16 MHz debe ser válido: " + res16.errors());
    }

    @Test
    void cooperativeTimingParityAt8MHzAnd16MHz() throws Exception {
        for (ClockFreq freq : List.of(ClockFreq.MHZ_8, ClockFreq.MHZ_16)) {
            ProgramSpec spec = BenchmarkCatalog.b06MultiprocesoDosLeds().withClock(freq);
            var lento = spec.led("led_lento").orElseThrow();
            var rapido = spec.led("led_rapido").orElseThrow();

            verifyBoth(spec, (sim, be) -> {
                var s0 = sim.advanceMs(10);
                assertFalse(s0.isLedOn(lento), be + "@" + freq + " 10ms lento OFF");
                assertFalse(s0.isLedOn(rapido), be + "@" + freq + " 10ms rapido OFF");

                var s1 = sim.advanceMs(310); // ~320 ms: rapido (250) ON, lento (500) OFF
                assertFalse(s1.isLedOn(lento), be + "@" + freq + " 320ms lento OFF");
                assertTrue(s1.isLedOn(rapido), be + "@" + freq + " 320ms rapido ON");

                var s2 = sim.advanceMs(260); // ~580 ms: lento (500) ON, rapido (500) OFF
                assertTrue(s2.isLedOn(lento), be + "@" + freq + " 580ms lento ON");
                assertFalse(s2.isLedOn(rapido), be + "@" + freq + " 580ms rapido OFF");
            });
        }
    }

    @Test
    void adcAndAllThreePwmTimersIncludingTimer2CS22At8MHzAnd16MHz() throws Exception {
        for (ClockFreq freq : List.of(ClockFreq.MHZ_8, ClockFreq.MHZ_16)) {
            ProgramSpec spec = ProgramSpec.builder("adc_pwm_3timers_" + freq.name())
                    .clock(freq)
                    .analog("pot", Pin.pc(0))
                    .led("led_umbral", Pin.pb(5), Connection.GND)
                    .pwm("pwm0", Pin.pd(6), 128) // Timer0 OC0A
                    .pwm("pwm1", Pin.pb(1), 128) // Timer1 OC1A
                    .pwm("pwm2", Pin.pb(3), 128) // Timer2 OC2A (usa CS22 para /64!)
                    .rule("Umbral ADC 3V", always(), cmp(adc10("pot"), CmpOp.GT, u10(614)),
                            List.of(ledOn("led_umbral")), List.of(ledOff("led_umbral")))
                    .build();

            var led = spec.led("led_umbral").orElseThrow();
            var p0 = spec.pwm("pwm0").orElseThrow();
            var p1 = spec.pwm("pwm1").orElseThrow();
            var p2 = spec.pwm("pwm2").orElseThrow();

            verifyBoth(spec, (sim, be) -> {
                sim.setAnalogMv(0, 1500);
                assertFalse(sim.advanceMs(5).isLedOn(led), be + "@" + freq + " 1.5V OFF");
                sim.setAnalogMv(0, 4000);
                assertTrue(sim.advanceMs(5).isLedOn(led), be + "@" + freq + " 4.0V ON");

                String vcd = sim.readVcd();
                for (var p : List.of(p0, p1, p2)) {
                    char id = vcdId(p.pin());
                    assertTrue(vcd.contains("1" + id) && vcd.contains("0" + id),
                            be + "@" + freq + " " + p.channel() + " debe conmutar PWM real");
                }
            });
        }
    }

    @Test
    void uart12BitUbrrAndHighSpeedParityAt8MHzAnd16MHz() throws Exception {
        // 1) 2400 bps @ 8 MHz -> UBRR0 = 416 (UBRR0H = 1, UBRR0L = 160)
        // 2) 2400 bps @ 16 MHz -> UBRR0 = 832 (UBRR0H = 3, UBRR0L = 64)
        // 3) 115200 bps @ 16 MHz -> UBRR0 = 16
        List<Object[]> cases = List.of(
                new Object[]{ClockFreq.MHZ_8, UartBaud.B2400},
                new Object[]{ClockFreq.MHZ_16, UartBaud.B2400},
                new Object[]{ClockFreq.MHZ_16, UartBaud.B115200}
        );
        for (Object[] c : cases) {
            ClockFreq freq = (ClockFreq) c[0];
            UartBaud baud = (UartBaud) c[1];
            ProgramSpec spec = ProgramSpec.builder("uart_" + baud.bps() + "_" + freq.name())
                    .clock(freq)
                    .uart("serie", baud, UartMode.FULL_DUPLEX)
                    .led("led_rx", Pin.pb(0), Connection.GND)
                    .rule("Saludo arranque", onBoot(), sendText("serie", "OK\r\n"))
                    .rule("Comando 'x' enciende LED y responde", onUartRx("serie"), uartChar("serie", 'x'),
                            ledOn("led_rx"), sendText("serie", "ACK\r\n"))
                    .build();

            var led = spec.led("led_rx").orElseThrow();
            verifyBoth(spec, (sim, be) -> {
                sim.advanceMs(25);
                assertTrue(sim.state().txAscii().contains("OK\r\n"),
                        be + "@" + freq + "/" + baud + ": falta saludo OK, recibido: " + sim.state().txAscii());
                assertTrue(sim.injectUartRx('x'), be + "@" + freq + "/" + baud + ": RX debe aceptar 'x'");
                sim.advanceMs(35);
                assertTrue(sim.state().isLedOn(led), be + "@" + freq + "/" + baud + ": LED debe encender");
                assertTrue(sim.state().txAscii().contains("ACK\r\n"),
                        be + "@" + freq + "/" + baud + ": falta ACK, recibido: " + sim.state().txAscii());
            });
        }
    }

    @FunctionalInterface
    private interface Scenario {
        void run(SimavrSession sim, String backend) throws Exception;
    }

    private void verifyBoth(ProgramSpec spec, Scenario scenario) throws Exception {
        Path cDir = WORK.resolve(spec.name()).resolve("c");
        Path asmDir = WORK.resolve(spec.name()).resolve("asm");
        var cArt = tc.compileC(spec.name(), C_EMIT.emit(spec), cDir);
        var asmArt = tc.assembleAsm(spec.name(), ASM_EMIT.emit(spec), asmDir);

        for (var entry : List.of(new Object[]{"C99", cArt.hexFile()}, new Object[]{"ASM", asmArt.hexFile()})) {
            String be = (String) entry[0];
            Path hex = (Path) entry[1];
            try (SimavrSession sim = SimavrSession.open(hex, spec.fCpuHz(), tc)) {
                scenario.run(sim, be);
            }
        }
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
