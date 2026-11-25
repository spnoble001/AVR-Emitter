package dev.transpilador.v2;

import dev.transpilador.v2.arbiter.ResourceArbiter;
import dev.transpilador.v2.emit.AsmEmitter;
import dev.transpilador.v2.emit.CEmitter;
import dev.transpilador.v2.model.*;
import dev.transpilador.v2.patterns.BenchmarkCatalog;
import dev.transpilador.v2.patterns.PedagogicalPatterns;
import dev.transpilador.v2.persistence.ProjectJson;
import dev.transpilador.v2.sim.Toolchain;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.transpilador.v2.model.Hardware.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica fidelidad exacta de persistencia JSON (.avr2.json), generación de los 8
 * patrones pedagógicos No-Code (en C99 + AVRASM2 sin advertencias) y flujo CLI.
 */
class PersistenceAndPatternsTest {

    @Test
    void jsonRoundTripPreservesAllFifteenBenchmarksExactly() {
        for (ProgramSpec original : BenchmarkCatalog.all()) {
            String json = ProjectJson.toJson(original);
            ProgramSpec restored = ProjectJson.parse(json);
            assertEquals(original, restored, () -> "Diferencia en round-trip JSON para " + original.name());
        }
    }

    @Test
    void allEightPedagogicalPatternsProduceValidC99AndAsmWithZeroWarnings() throws Exception {
        var arbiter = new ResourceArbiter();
        var cEmitter = new CEmitter();
        var asmEmitter = new AsmEmitter();
        var tc = Toolchain.detect();
        Path outDir = Path.of("target/patterns-check").toAbsolutePath();

        // Circuito base con todos los tipos para probar los 10 patrones
        ProgramSpec base = ProgramSpec.builder("base_patrones")
                .button("btn_a", Pin.pd(2), Connection.GND)
                .button("btn_b", Pin.pd(3), Connection.GND)
                .led("led_1", Pin.pb(0), Connection.GND)
                .bcd("disp", Pin.pb(1), 0)
                .analog("pot", Pin.pc(0))
                .pwm("motor", Pin.pd(6), 0)
                .uart("serie", UartBaud.B9600, UartMode.FULL_DUPLEX)
                .var("cuenta", 0, 9, 0, State.VarPolicy.WRAP)
                .build();

        List<ProgramSpec> generated = List.of(
                PedagogicalPatterns.addEveryNPresses(base, "btn_a", "led_1", 3, true),
                PedagogicalPatterns.addDoubleClick(base, "btn_a", "led_1", 400),
                PedagogicalPatterns.addShortLongPress(base, "btn_a", "cuenta", "disp", 1000),
                PedagogicalPatterns.addSequenceAThenB(base, "btn_a", "btn_b", "led_1", 500),
                PedagogicalPatterns.addBcdCircularWithPause(base, "disp", "btn_a", 500),
                PedagogicalPatterns.addPeriodicBlink(base, "led_1", 250),
                PedagogicalPatterns.addAdcThreshold(base, "pot", "led_1", 614),
                PedagogicalPatterns.addAdcToPwm(base, "pot", "motor"),
                PedagogicalPatterns.addPwmRamp(base, "motor", 15),
                PedagogicalPatterns.addUartLedCommand(base, "serie", "led_1", '1', '0')
        );

        for (int i = 0; i < generated.size(); i++) {
            final int idx = i;
            ProgramSpec spec = generated.get(i);
            var res = arbiter.analyze(spec);
            assertTrue(res.valid(), () -> "Patrón #" + idx + " falló arbitraje: " + res.errors());

            String cCode = cEmitter.emit(spec);
            String asmCode = asmEmitter.emit(spec);
            assertEquals(spec, ProjectJson.parse(ProjectJson.toJson(spec)), "Patrón #" + i + " debe sobrevivir JSON");

            if (tc.hasAvrGcc()) {
                tc.compileC("patron_" + i, cCode, outDir.resolve("c_" + i));
            }
            if (tc.hasAvrasm2()) {
                tc.assembleAsm("patron_" + i, asmCode, outDir.resolve("asm_" + i));
            }
        }
    }

    @Test
    void cliTranspilesJsonProjectToBothCAndAsm() throws Exception {
        Path dir = Files.createTempDirectory("cli-v2-test-");
        Path proj = dir.resolve("cada3.avr2.json");
        Path outC = dir.resolve("cada3.c");
        Path outAsm = dir.resolve("cada3.asm");
        Files.writeString(proj, ProjectJson.toJson(BenchmarkCatalog.b03CadaTresPulsaciones()), StandardCharsets.UTF_8);

        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        assertEquals(0, Main.runCli(new String[]{proj.toString(), outC.toString()}, new PrintStream(stdout), new PrintStream(stderr)));
        assertEquals(0, Main.runCli(new String[]{proj.toString(), outAsm.toString()}, new PrintStream(stdout), new PrintStream(stderr)));
        assertTrue(Files.readString(outC).contains("int main(void)"));
        assertTrue(Files.readString(outAsm).contains(".include \"m328Pdef.inc\""));
    }
}
