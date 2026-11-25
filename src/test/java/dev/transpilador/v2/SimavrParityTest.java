package dev.transpilador.v2;

import dev.transpilador.v2.emit.AsmEmitter;
import dev.transpilador.v2.emit.CEmitter;
import dev.transpilador.v2.model.Hardware;
import dev.transpilador.v2.model.ProgramSpec;
import dev.transpilador.v2.patterns.BenchmarkCatalog;
import dev.transpilador.v2.sim.SimavrSession;
import dev.transpilador.v2.sim.Toolchain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de paridad real en silicio simulado (simavr · ATmega328P @ 1 MHz):
 * para cada uno de los 15 programas benchmark, compila el C99 con avr-gcc,
 * ensambla el ASM con AVRASM2 y ejecuta exactamente los mismos estímulos físicos
 * sobre ambos binarios .hex verificando comportamiento idéntico.
 */
class SimavrParityTest {
    private static Toolchain toolchain;
    private static final CEmitter C_EMITTER = new CEmitter();
    private static final AsmEmitter ASM_EMITTER = new AsmEmitter();
    private static final Path WORK_ROOT = Path.of("target/parity-simavr").toAbsolutePath();

    @FunctionalInterface
    interface Scenario {
        void run(SimavrSession sim, String backend) throws Exception;
    }

    @BeforeAll
    static void checkToolchain() {
        toolchain = Toolchain.detect();
        assertTrue(toolchain.hasAvrGcc(), "Falta avr-gcc");
        assertTrue(toolchain.hasAvrasm2(), "Falta avrasm2");
        assertTrue(toolchain.hasSimavr(), "Falta firmware-runner de simavr");
    }

    private void verifyBothBackends(ProgramSpec spec, Scenario scenario) throws Exception {
        Path cDir = WORK_ROOT.resolve(spec.name()).resolve("c");
        Path asmElemDir = WORK_ROOT.resolve(spec.name()).resolve("asm_elem");
        Path asmStdDir = WORK_ROOT.resolve(spec.name()).resolve("asm_std");
        var cArt = toolchain.compileC(spec.name(), C_EMITTER.emit(spec), cDir);
        var asmElemArt = toolchain.assembleAsm(spec.name() + "_elem",
                ASM_EMITTER.emit(spec, AsmEmitter.InstructionProfile.ELEMENTARY, AsmEmitter.StorageMode.REGISTERS_FIRST), asmElemDir);
        var asmStdArt = toolchain.assembleAsm(spec.name() + "_std",
                ASM_EMITTER.emit(spec, AsmEmitter.InstructionProfile.STANDARD, AsmEmitter.StorageMode.REGISTERS_FIRST), asmStdDir);

        for (var entry : List.of(
                new Object[]{"C99", cArt.hexFile()},
                new Object[]{"ASM-ELEMENTAL", asmElemArt.hexFile()},
                new Object[]{"ASM-ESTÁNDAR", asmStdArt.hexFile()})) {
            String backend = (String) entry[0];
            Path hex = (Path) entry[1];
            try (SimavrSession sim = SimavrSession.open(hex, spec.fCpuHz(), toolchain)) {
                // Liberar todos los botones a su estado de reposo eléctrico inicial
                for (Hardware.Button b : spec.componentsOf(Hardware.Button.class)) {
                    sim.setButton(b, false);
                }
                scenario.run(sim, backend);
            }
        }
    }

    @Test
    void parity01LedBoton() throws Exception {
        var spec = BenchmarkCatalog.b01LedBoton();
        var btn = spec.button("boton").orElseThrow();
        var led = spec.led("led").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            assertFalse(sim.advanceMs(2).isLedOn(led), be + ": LED debe iniciar apagado");
            sim.setButton(btn, true);
            assertTrue(sim.advanceMs(2).isLedOn(led), be + ": LED debe encender al presionar");
            sim.setButton(btn, false);
            assertFalse(sim.advanceMs(2).isLedOn(led), be + ": LED debe apagar al soltar");
        });
    }

    @Test
    void parity02CompuertasAndXor() throws Exception {
        var spec = BenchmarkCatalog.b02CompuertasAndXor();
        var a = spec.button("btn_a").orElseThrow();
        var b = spec.button("btn_b").orElseThrow();
        var ledAnd = spec.led("led_and").orElseThrow();
        var ledXor = spec.led("led_xor").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            var s00 = sim.advanceMs(2);
            assertFalse(s00.isLedOn(ledAnd), be + " 00 AND");
            assertFalse(s00.isLedOn(ledXor), be + " 00 XOR");

            sim.setButton(a, true);
            var s10 = sim.advanceMs(2);
            assertFalse(s10.isLedOn(ledAnd), be + " 10 AND");
            assertTrue(s10.isLedOn(ledXor), be + " 10 XOR");

            sim.setButton(b, true);
            var s11 = sim.advanceMs(2);
            assertTrue(s11.isLedOn(ledAnd), be + " 11 AND");
            assertFalse(s11.isLedOn(ledXor), be + " 11 XOR");

            sim.setButton(a, false);
            var s01 = sim.advanceMs(2);
            assertFalse(s01.isLedOn(ledAnd), be + " 01 AND");
            assertTrue(s01.isLedOn(ledXor), be + " 01 XOR");
        });
    }

    @Test
    void parity03CadaTresPulsaciones() throws Exception {
        var spec = BenchmarkCatalog.b03CadaTresPulsaciones();
        var btn = spec.button("btn_a").orElseThrow();
        var led = spec.led("led_a").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            assertFalse(sim.advanceMs(2).isLedOn(led), be + ": inicial OFF");
            // Pulsación 1 (presionar y soltar) -> pulsaciones=1 -> LED OFF
            pulse(sim, btn, 2, 2);
            assertFalse(sim.state().isLedOn(led), be + ": tras 1ª pulsación OFF");
            // Pulsación 2 -> pulsaciones=2 -> LED OFF
            pulse(sim, btn, 2, 2);
            assertFalse(sim.state().isLedOn(led), be + ": tras 2ª pulsación OFF");
            // Pulsación 3 -> pulsaciones=0 (wrap 0..2) -> LED ON!
            pulse(sim, btn, 2, 2);
            assertTrue(sim.state().isLedOn(led), be + ": tras 3ª pulsación ON");
            // Pulsación 4 -> pulsaciones=1 -> LED OFF
            pulse(sim, btn, 2, 2);
            assertFalse(sim.state().isLedOn(led), be + ": tras 4ª pulsación OFF");
            // Pulsación 5 y 6 -> LED ON otra vez
            pulse(sim, btn, 2, 2);
            assertFalse(sim.state().isLedOn(led), be + ": tras 5ª pulsación OFF");
            pulse(sim, btn, 2, 2);
            assertTrue(sim.state().isLedOn(led), be + ": tras 6ª pulsación ON");
        });
    }

    @Test
    void parity04ContadorBcdPausa() throws Exception {
        var spec = BenchmarkCatalog.b04ContadorBcdPausa();
        var adv = spec.button("btn_avanzar").orElseThrow();
        var pause = spec.button("btn_pausa").orElseThrow();
        var rst = spec.button("btn_reset").orElseThrow();
        var disp = spec.bcd("display").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            assertEquals(0, sim.advanceMs(2).readBcd(disp), be + ": inicial 0");
            pulse(sim, adv, 2, 2);
            pulse(sim, adv, 2, 2);
            assertEquals(2, sim.state().readBcd(disp), be + ": tras 2 avances = 2");
            // Con pausa presionada no debe avanzar
            sim.setButton(pause, true);
            sim.advanceMs(2);
            pulse(sim, adv, 2, 2);
            assertEquals(2, sim.state().readBcd(disp), be + ": en pausa permanece en 2");
            sim.setButton(pause, false);
            sim.advanceMs(2);
            // Reset vuelve a 0
            pulse(sim, rst, 2, 2);
            assertEquals(0, sim.state().readBcd(disp), be + ": tras reset = 0");
        });
    }

    @Test
    void parity05ContadorDecimal00a99() throws Exception {
        var spec = BenchmarkCatalog.b05ContadorDecimal00a99();
        var mas = spec.button("btn_mas").orElseThrow();
        var menos = spec.button("btn_menos").orElseThrow();
        var uni = spec.bcd("unidades").orElseThrow();
        var dec = spec.bcd("decenas").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            sim.advanceMs(2);
            // Decrementar en 0 con CLAMP se queda en 00
            pulse(sim, menos, 1, 1);
            assertEquals(0, sim.state().readBcd(dec), be + ": clamp 0 decenas");
            assertEquals(0, sim.state().readBcd(uni), be + ": clamp 0 unidades");
            // Avanzar 12 veces -> decenas=1, unidades=2
            for (int i = 0; i < 12; i++) pulse(sim, mas, 1, 1);
            assertEquals(1, sim.state().readBcd(dec), be + ": 12 -> decenas 1");
            assertEquals(2, sim.state().readBcd(uni), be + ": 12 -> unidades 2");
            // Retroceder 3 veces -> 09
            for (int i = 0; i < 3; i++) pulse(sim, menos, 1, 1);
            assertEquals(0, sim.state().readBcd(dec), be + ": 09 -> decenas 0");
            assertEquals(9, sim.state().readBcd(uni), be + ": 09 -> unidades 9");
        });
    }

    @Test
    void parity06MultiprocesoDosLeds() throws Exception {
        var spec = BenchmarkCatalog.b06MultiprocesoDosLeds();
        var lento = spec.led("led_lento").orElseThrow();
        var rapido = spec.led("led_rapido").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            // Al inicio ambos OFF
            var s0 = sim.advanceMs(10);
            assertFalse(s0.isLedOn(lento), be + ": lento 10ms OFF");
            assertFalse(s0.isLedOn(rapido), be + ": rapido 10ms OFF");
            // A ~320 ms: rápido (250 ms) ya alternó a ON, lento (500 ms) sigue OFF
            var s1 = sim.advanceMs(310);
            assertFalse(s1.isLedOn(lento), be + ": lento ~320ms OFF");
            assertTrue(s1.isLedOn(rapido), be + ": rapido ~320ms ON");
            // A ~600 ms: lento (500 ms) ya alternó a ON, rápido (500 ms = 2x250) volvió a OFF
            var s2 = sim.advanceMs(280);
            assertTrue(s2.isLedOn(lento), be + ": lento ~600ms ON");
            assertFalse(s2.isLedOn(rapido), be + ": rapido ~600ms OFF");
        });
    }

    @Test
    void parity07DobleClic() throws Exception {
        var spec = BenchmarkCatalog.b07DobleClic();
        var btn = spec.button("btn").orElseThrow();
        var led = spec.led("led").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            sim.advanceMs(5);
            // Un solo clic y esperar > 450 ms -> vence ventana, LED sigue OFF
            pulse(sim, btn, 10, 480);
            assertFalse(sim.state().isLedOn(led), be + ": clic simple no debe encender LED");
            // Dos clics con 100 ms entre ellos (< 400 ms) -> alterna LED a ON
            pulse(sim, btn, 10, 80);
            assertFalse(sim.state().isLedOn(led), be + ": tras primer clic del doble clic aún OFF");
            pulse(sim, btn, 10, 20);
            assertTrue(sim.state().isLedOn(led), be + ": segundo clic dentro de 400 ms debe encender LED");
        });
    }

    @Test
    void parity08PulsacionCortaLarga() throws Exception {
        var spec = BenchmarkCatalog.b08PulsacionCortaLarga();
        var btn = spec.button("btn").orElseThrow();
        var disp = spec.bcd("display").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            sim.advanceMs(5);
            // Dos pulsaciones cortas (~80 ms) -> cuenta = 2
            pulse(sim, btn, 80, 20);
            pulse(sim, btn, 80, 20);
            assertEquals(2, sim.state().readBcd(disp), be + ": dos cortas -> 2");
            // Pulsación larga (> 1050 ms) -> reinicia a 0 mientras sigue presionado
            sim.setButton(btn, true);
            sim.advanceMs(1150);
            assertEquals(0, sim.state().readBcd(disp), be + ": pulsación larga -> 0");
            // Al soltar tras larga NO debe contar como corta
            sim.setButton(btn, false);
            sim.advanceMs(20);
            assertEquals(0, sim.state().readBcd(disp), be + ": soltar tras larga sigue en 0");
        });
    }

    @Test
    void parity09SecuenciaALuegoB() throws Exception {
        var spec = BenchmarkCatalog.b09SecuenciaALuegoB();
        var a = spec.button("btn_a").orElseThrow();
        var b = spec.button("btn_b").orElseThrow();
        var led = spec.led("led_ok").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            sim.advanceMs(5);
            // Presionar B solo no enciende
            pulse(sim, b, 10, 10);
            assertFalse(sim.state().isLedOn(led), be + ": B solo no enciende");
            // Presionar A, esperar 600 ms (vence ventana 500 ms), luego B -> no enciende
            pulse(sim, a, 10, 580);
            pulse(sim, b, 10, 10);
            assertFalse(sim.state().isLedOn(led), be + ": B fuera de ventana no enciende");
            // Presionar A, esperar 100 ms, luego B -> enciende
            pulse(sim, a, 10, 100);
            pulse(sim, b, 10, 10);
            assertTrue(sim.state().isLedOn(led), be + ": B dentro de 500 ms enciende LED");
        });
    }

    @Test
    void parity10SemaforoCancelable() throws Exception {
        var spec = BenchmarkCatalog.b10SemaforoCancelable();
        var emerg = spec.button("btn_emergencia").orElseThrow();
        var verde = spec.led("led_verde").orElseThrow();
        var amarillo = spec.led("led_amarillo").orElseThrow();
        var rojo = spec.led("led_rojo").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            // Al arrancar (OnBoot): Verde ON
            var s0 = sim.advanceMs(20);
            assertTrue(s0.isLedOn(verde), be + ": inicio VERDE");
            assertFalse(s0.isLedOn(amarillo), be + ": inicio AMARILLO off");
            assertFalse(s0.isLedOn(rojo), be + ": inicio ROJO off");
            // A ~360 ms: pasa a Amarillo (300 ms verde)
            var s1 = sim.advanceMs(340);
            assertFalse(s1.isLedOn(verde), be + ": 360ms VERDE off");
            assertTrue(s1.isLedOn(amarillo), be + ": 360ms AMARILLO on");
            // Botón emergencia cancela inmediatamente hacia Rojo
            pulse(sim, emerg, 10, 10);
            var s2 = sim.state();
            assertFalse(s2.isLedOn(verde), be + ": emergencia VERDE off");
            assertFalse(s2.isLedOn(amarillo), be + ": emergencia AMARILLO off");
            assertTrue(s2.isLedOn(rojo), be + ": emergencia ROJO on");
        });
    }

    @Test
    void parity11AdcUmbral() throws Exception {
        var spec = BenchmarkCatalog.b11AdcUmbral();
        var led = spec.led("led").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            sim.setAnalogMv(0, 2000); // ~409 <= 614
            assertFalse(sim.advanceMs(5).isLedOn(led), be + ": 2.0V debe estar OFF");
            sim.setAnalogMv(0, 3500); // ~716 > 614
            assertTrue(sim.advanceMs(5).isLedOn(led), be + ": 3.5V debe estar ON");
            sim.setAnalogMv(0, 1000); // ~204 <= 614
            assertFalse(sim.advanceMs(5).isLedOn(led), be + ": 1.0V vuelve a OFF");
        });
    }

    @Test
    void parity12AdcABcd() throws Exception {
        var spec = BenchmarkCatalog.b12AdcABcd();
        var disp = spec.bcd("display").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            sim.setAnalogMv(0, 0);
            assertEquals(0, sim.advanceMs(5).readBcd(disp), be + ": 0V -> 0");
            sim.setAnalogMv(0, 2500); // simavr: (2500*1023)/5000 = 511 -> byte 127 -> (127*10)>>8 = 4
            assertEquals(4, sim.advanceMs(5).readBcd(disp), be + ": 2.50V -> 4");
            sim.setAnalogMv(0, 2600); // simavr: 531 -> byte 132 -> (132*10)>>8 = 5
            assertEquals(5, sim.advanceMs(5).readBcd(disp), be + ": 2.60V -> 5");
            sim.setAnalogMv(0, 4950); // ~1012 -> byte 253 -> (253*10)>>8 = 9
            assertEquals(9, sim.advanceMs(5).readBcd(disp), be + ": 4.95V -> 9");
        });
    }

    @Test
    void parity13PwmFijoYRampa() throws Exception {
        var spec = BenchmarkCatalog.b13PwmFijoYRampa();
        var pwmFijo = spec.pwm("pwm_fijo").orElseThrow();
        var pwmRampa = spec.pwm("pwm_rampa").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            // Al inicio: PB1/OC1A al 50% (128) debe conmutar en 5 ms (periodo ≈ 2.05 ms), PD6/OC0A en 0 está bajo fijo
            sim.advanceMs(1);
            assertFalse(sim.state().pinHigh(pwmRampa.pin()), be + ": rampa inicia en 0 (bajo constante)");
            sim.advanceMs(25); // tras ~20 ms, brillo >= 2 -> OC0A también genera onda PWM
            String vcd = sim.readVcd();
            // Verificar que ambos pines PWM han tenido transiciones reales 0 y 1 en la traza VCD
            char idFijo = vcdId(pwmFijo.pin());
            char idRampa = vcdId(pwmRampa.pin());
            assertTrue(vcd.contains("1" + idFijo) && vcd.contains("0" + idFijo),
                    be + ": OC1A (PB1) debe generar onda PWM real al 50%");
            assertTrue(vcd.contains("1" + idRampa) && vcd.contains("0" + idRampa),
                    be + ": OC0A (PD6) debe generar onda PWM tras incrementar rampa");
        });
    }

    @Test
    void parity14AdcAPwm() throws Exception {
        var spec = BenchmarkCatalog.b14AdcAPwm();
        var pwm = spec.pwm("led_pwm").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            // 0 V -> duty 0 -> salida baja constante
            sim.setAnalogMv(0, 0);
            sim.advanceMs(5);
            assertFalse(sim.state().pinHigh(pwm.pin()), be + ": 0V -> duty 0 (bajo constante)");
            // 5 V -> duty 255 -> salida alta constante (desconectada de OC0A)
            sim.setAnalogMv(0, 5000);
            sim.advanceMs(5);
            assertTrue(sim.state().pinHigh(pwm.pin()), be + ": 5V -> duty 255 (alto constante)");
            // 2.5 V -> duty ~128 -> onda PWM conmutando
            sim.setAnalogMv(0, 2500);
            sim.advanceMs(10);
            String vcd = sim.readVcd();
            char id = vcdId(pwm.pin());
            assertTrue(vcd.contains("1" + id) && vcd.contains("0" + id),
                    be + ": 2.5V debe generar conmutación PWM real");
        });
    }

    @Test
    void parity15MaquinaTresEstadosUartAdc() throws Exception {
        var spec = BenchmarkCatalog.b15MaquinaTresEstadosUartAdc();
        var alerta = spec.button("btn_alerta").orElseThrow();
        var fallo = spec.led("led_fallo").orElseThrow();
        verifyBothBackends(spec, (sim, be) -> {
            sim.setAnalogMv(0, 4000); // ~819 > 512
            var sInit = sim.advanceMs(10);
            assertTrue(sInit.uartRxReady(), be + ": UART RX debe estar habilitada");
            assertFalse(sInit.isLedOn(fallo), be + ": fallo inicial OFF");

            // Primera pulsación -> ADVERTENCIA
            pulse(sim, alerta, 10, 40);
            assertTrue(sim.state().txAscii().contains("ADVERTENCIA\r\n"),
                    be + ": debe enviar ADVERTENCIA, recibido: " + sim.state().txAscii());
            assertFalse(sim.state().isLedOn(fallo), be + ": en ADVERTENCIA fallo sigue OFF");

            // Segunda pulsación dentro de 3000 ms -> FALLO + LED fallo ON
            pulse(sim, alerta, 10, 40);
            assertTrue(sim.state().txAscii().contains("FALLO\r\n"),
                    be + ": debe enviar FALLO, recibido: " + sim.state().txAscii());
            assertTrue(sim.state().isLedOn(fallo), be + ": en FALLO el LED fallo debe encender");

            // Enviar comando UART 'r' -> RESET + LED fallo OFF
            assertTrue(sim.injectUartRx('r'), be + ": RX debe aceptar 'r'");
            sim.advanceMs(40);
            assertTrue(sim.state().txAscii().contains("RESET\r\n"),
                    be + ": debe enviar RESET, recibido: " + sim.state().txAscii());
            assertFalse(sim.state().isLedOn(fallo), be + ": tras RESET el LED fallo debe apagar");

            // Enviar comando UART 's' con sensor en 4.0 V (> 512) -> ALTO
            assertTrue(sim.injectUartRx('s'), be + ": RX debe aceptar 's'");
            sim.advanceMs(40);
            assertTrue(sim.state().txAscii().contains("ALTO\r\n"),
                    be + ": debe enviar ALTO, recibido: " + sim.state().txAscii());
        });
    }

    @Test
    void pruebaDeFuegoDisplay4511Circular1a9ConPausa() throws Exception {
        // 1 a 9 cada 500 ms en CD4511, pausa mientras botón presionado, circular 9 -> 1
        var spec = dev.transpilador.v2.model.ProgramSpec.builder("prueba_fuego_4511_1a9_pausa")
                .bcd("display", Hardware.Pin.pb(0), 1)
                .button("btn_pausa", Hardware.Pin.pd(2), Hardware.Connection.GND)
                .var("cuenta", 1, 9, 1, dev.transpilador.v2.model.State.VarPolicy.WRAP)
                .rule("Contar 1..9 circular cada 500 ms si no está en pausa",
                        dev.transpilador.v2.model.Rule.everyMs(500),
                        dev.transpilador.v2.model.Expr.not(dev.transpilador.v2.model.Expr.pressed("btn_pausa")),
                        dev.transpilador.v2.model.Effect.inc("cuenta"),
                        dev.transpilador.v2.model.Effect.showBcd("display", dev.transpilador.v2.model.Expr.var("cuenta")))
                .build();

        var disp = spec.bcd("display").orElseThrow();
        var pausa = spec.button("btn_pausa").orElseThrow();

        verifyBothBackends(spec, (sim, be) -> {
            // Al arrancar debe mostrar 1
            assertEquals(1, sim.advanceMs(20).readBcd(disp), be + ": debe iniciar en 1");

            // Tras ~550 ms -> 2; tras otro ~550 ms -> 3
            assertEquals(2, sim.advanceMs(540).readBcd(disp), be + ": primer tick 500ms -> 2");
            assertEquals(3, sim.advanceMs(520).readBcd(disp), be + ": segundo tick 500ms -> 3");

            // Presionar pausa durante > 1100 ms (más de 2 periodos) -> debe quedarse congelado en 3
            sim.setButton(pausa, true);
            sim.advanceMs(1150);
            assertEquals(3, sim.state().readBcd(disp), be + ": en pausa debe seguir en 3");

            // Liberar botón -> retoma conteo desde 3 hasta 9 y envuelve 9 -> 1
            sim.setButton(pausa, false);
            int prev = 3;
            boolean sawNine = false;
            boolean wrappedToOne = false;
            for (int step = 0; step < 10; step++) {
                int cur = sim.advanceMs(520).readBcd(disp);
                assertTrue(cur >= 1 && cur <= 9, be + ": dígito fuera de 1..9: " + cur);
                if (prev == 9 && cur == 1) wrappedToOne = true;
                if (cur == 9) sawNine = true;
                prev = cur;
            }
            assertTrue(sawNine, be + ": debe pasar por el dígito 9");
            assertTrue(wrappedToOne, be + ": debe reiniciar circularmente de 9 a 1 (sin pasar por 0)");
        });
    }

    private static void pulse(SimavrSession sim, Hardware.Button btn, int pressMs, int releaseMs) throws Exception {
        sim.setButton(btn, true);
        sim.advanceMs(pressMs);
        sim.setButton(btn, false);
        sim.advanceMs(releaseMs);
    }

    /** ID de señal VCD asignado por firmware_runner.c: '!' + (portIndex * 8 + bit). */
    private static char vcdId(Hardware.Pin pin) {
        int portIdx = switch (pin.port()) {
            case B -> 0;
            case C -> 1;
            case D -> 2;
        };
        return (char) ('!' + portIdx * 8 + pin.bit());
    }
}
