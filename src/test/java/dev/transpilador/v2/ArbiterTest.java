package dev.transpilador.v2;

import dev.transpilador.v2.arbiter.ResourceArbiter;
import dev.transpilador.v2.model.*;
import dev.transpilador.v2.patterns.BenchmarkCatalog;
import org.junit.jupiter.api.Test;

import static dev.transpilador.v2.model.Effect.*;
import static dev.transpilador.v2.model.Expr.*;
import static dev.transpilador.v2.model.Hardware.*;
import static dev.transpilador.v2.model.Rule.*;
import static dev.transpilador.v2.model.State.VarPolicy.WRAP;
import static org.junit.jupiter.api.Assertions.*;

class ArbiterTest {
    private final ResourceArbiter arbiter = new ResourceArbiter();

    @Test
    void allFifteenBenchmarksPassArbitrationCleanly() {
        var benchmarks = BenchmarkCatalog.all();
        assertEquals(15, benchmarks.size());
        for (ProgramSpec spec : benchmarks) {
            var res = arbiter.analyze(spec);
            assertTrue(res.valid(), () -> spec.name() + " falló arbitraje: " + res.errors());
            assertTrue(res.warnings().isEmpty(), () -> spec.name() + " tiene advertencias: " + res.warnings());
            assertFalse(res.allocations().isEmpty(), () -> spec.name() + " debe reportar asignaciones hardware");
        }
    }

    @Test
    void detectsPinCollisionBetweenLedAndBcdDisplay() {
        var spec = ProgramSpec.builder("colision_pin")
                .bcd("disp", Pin.pb(0), 0) // ocupa PB0..PB3
                .led("led", Pin.pb(2), Connection.GND)
                .rule("Encender", onBoot(), ledOn("led"))
                .build();
        var res = arbiter.analyze(spec);
        assertFalse(res.valid());
        assertTrue(res.errors().stream().anyMatch(d -> d.code().equals("PIN_CONFLICT")));
    }

    @Test
    void allowsTwoPwmChannelsSharingSameTimerWithoutFalseConflict() {
        var spec = ProgramSpec.builder("pwm_compartido")
                .pwm("motor_a", Pin.pb(1), 100) // OC1A · Timer1
                .pwm("motor_b", Pin.pb(2), 200) // OC1B · Timer1
                .rule("Fijar ambos", onBoot(), setPwm("motor_a", u8(128)), setPwm("motor_b", u8(64)))
                .build();
        var res = arbiter.analyze(spec);
        assertTrue(res.valid(), () -> "No debe haber conflicto entre OC1A y OC1B: " + res.errors());
    }

    @Test
    void rejectsRaw10BitAdcInto8BitPwm() {
        var spec = ProgramSpec.builder("adc10_a_pwm")
                .analog("pot", Pin.pc(0))
                .pwm("led_pwm", Pin.pd(6), 0)
                .rule("Mal ancho", always(), setPwm("led_pwm", adc10("pot")))
                .build();
        var res = arbiter.analyze(spec);
        assertFalse(res.valid());
        assertTrue(res.errors().stream().anyMatch(d -> d.code().equals("WIDTH_MISMATCH")));
    }

    @Test
    void rejectsUartRxTriggerWhenPortIsTxOnly() {
        var spec = ProgramSpec.builder("uart_tx_only")
                .uart("serie", UartBaud.B9600, UartMode.TX_ONLY)
                .led("led", Pin.pb(0), Connection.GND)
                .rule("RX inválido", onUartRx("serie"), ledOn("led"))
                .build();
        var res = arbiter.analyze(spec);
        assertFalse(res.valid());
        assertTrue(res.errors().stream().anyMatch(d -> d.code().equals("UART_NO_RX")));
    }

    @Test
    void rejectsUnknownPhaseAndPinCollisionWithUartPd0() {
        var spec = ProgramSpec.builder("uart_y_fase")
                .uart("serie", UartBaud.B9600, UartMode.FULL_DUPLEX) // ocupa PD0 y PD1
                .button("btn", Pin.pd(0), Connection.GND)
                .phases("modo", "A", "A", "B")
                .rule("Fase inexistente", onPress("btn"), inPhase("modo", "C"), setPhase("modo", "A"))
                .build();
        var res = arbiter.analyze(spec);
        assertFalse(res.valid());
        assertTrue(res.errors().stream().anyMatch(d -> d.code().equals("PIN_CONFLICT")));
        assertTrue(res.errors().stream().anyMatch(d -> d.code().equals("UNKNOWN_PHASE")));
    }

    @Test
    void rejectsReadingUartByteOutsideOnUartRxRule() {
        var spec = ProgramSpec.builder("uart_fuera_rx")
                .uart("serie", UartBaud.B9600, UartMode.FULL_DUPLEX)
                .var("ultimo", 0, 255, 0, WRAP)
                .rule("Leer byte en Always", always(), setVar("ultimo", uartByte("serie")))
                .build();
        var res = arbiter.analyze(spec);
        assertFalse(res.valid());
        assertTrue(res.errors().stream().anyMatch(d -> d.code().equals("UART_BYTE_OUTSIDE_RX")));
    }
}
