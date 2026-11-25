package dev.transpilador.v2;

import dev.transpilador.v2.emit.AsmEmitter;
import dev.transpilador.v2.emit.CEmitter;
import dev.transpilador.v2.model.Effect;
import dev.transpilador.v2.model.Expr;
import dev.transpilador.v2.model.Hardware;
import dev.transpilador.v2.model.ProgramSpec;
import dev.transpilador.v2.model.Rule;
import dev.transpilador.v2.model.State;
import dev.transpilador.v2.patterns.BenchmarkCatalog;
import dev.transpilador.v2.sim.Toolchain;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica calidad didáctica (human-like) y compilación/ensamblado real sin advertencias
 * de los 15 programas benchmark en C99 (avr-gcc -Wall -Wextra -Werror) y AVRASM2.
 */
class HumanLikeCodeTest {
    private static final Pattern NOP_INSTRUCTION = Pattern.compile("(?m)^\\s+nop\\b");
    private static final Pattern NON_ELEMENTARY = Pattern.compile("(?i)^\\s+(subi|sbci|cpc|tst)\\b");

    @Test
    void allFifteenBenchmarksEmitCleanHumanLikeCAndAsmAndBuildWithZeroWarnings() throws Exception {
        Toolchain tc = Toolchain.detect();
        assertTrue(tc.hasAvrGcc(), "Se requiere avr-gcc para verificar compilación C99.");
        assertTrue(tc.hasAvrasm2(), "Se requiere avrasm2.exe para verificar ensamblado ASM.");

        CEmitter cEmitter = new CEmitter();
        AsmEmitter asmEmitter = new AsmEmitter();
        Path outDir = Path.of("target/generated-benchmarks").toAbsolutePath();
        Files.createDirectories(outDir);

        for (ProgramSpec spec : BenchmarkCatalog.all()) {
            String cCode = cEmitter.emit(spec);
            String asmCode = asmEmitter.emit(spec);

            // 1. Regla anti-4000-NOPs: a lo sumo 1 instrucción 'nop' en todo el ASM (dentro de retardo_1ms)
            int nopCount = countMatches(NOP_INSTRUCTION, asmCode);
            assertTrue(nopCount <= 1,
                    () -> spec.name() + " emitió " + nopCount + " instrucciones NOP (debe ser <= 1).");

            // 1b. Política REGISTERS_FIRST por defecto: ninguno de los 15 benchmarks desborda los 22 registros libres -> cero .dseg!
            assertFalse(asmCode.contains(".dseg"),
                    () -> spec.name() + " debe caber íntegramente en registros de CPU sin emitir .dseg SRAM.");

            // 1c. Perfil ELEMENTARY por defecto: cero subi, sbci, cpc ni tst en todo el programa
            assertNoNonElementaryOps(spec.name(), asmCode);

            // 2. Proporcionalidad en programas combinacionales puros
            if (spec.name().equals("01_led_boton") || spec.name().equals("02_compuertas_and_xor")) {
                assertFalse(asmCode.contains("retardo_1ms"), spec.name() + " no debe incluir reloj/retardo.");
                assertFalse(asmCode.contains("SPH"), spec.name() + " no debe inicializar pila si no usa rcall.");
                assertFalse(asmCode.contains(".dseg"), spec.name() + " no debe usar SRAM.");
                assertFalse(cCode.contains("<util/delay.h>"), spec.name() + " C no debe incluir util/delay.h.");
                long asmInstructions = asmCode.lines()
                        .map(String::trim)
                        .filter(l -> !l.isEmpty() && !l.startsWith(";") && !l.startsWith(".") && !l.endsWith(":"))
                        .count();
                assertTrue(asmInstructions <= 50,
                        () -> spec.name() + " demasiadas instrucciones ASM: " + asmInstructions);
            }

            // 3. Compilación real C99 con -Wall -Wextra -Werror
            Path cDir = outDir.resolve("c").resolve(spec.name());
            var cArt = tc.compileC(spec.name(), cCode, cDir);
            assertTrue(Files.isRegularFile(cArt.hexFile()), "Falta .hex generado desde C para " + spec.name());

            // 4. Ensamblado real AVRASM2 sin errores ni advertencias (Elemental, Estándar y SRAM opcional)
            Path asmDir = outDir.resolve("asm").resolve(spec.name());
            var asmArt = tc.assembleAsm(spec.name(), asmCode, asmDir);
            assertTrue(Files.isRegularFile(asmArt.hexFile()), "Falta .hex generado desde ASM para " + spec.name());

            String asmStd = asmEmitter.emit(spec, AsmEmitter.InstructionProfile.STANDARD, AsmEmitter.StorageMode.REGISTERS_FIRST);
            tc.assembleAsm(spec.name() + "_std", asmStd, outDir.resolve("asm_std").resolve(spec.name()));

            String asmSram = asmEmitter.emit(spec, AsmEmitter.InstructionProfile.ELEMENTARY, AsmEmitter.StorageMode.SRAM_DATA_SEGMENT);
            assertNoNonElementaryOps(spec.name() + "_sram", asmSram);
            tc.assembleAsm(spec.name() + "_sram", asmSram, outDir.resolve("asm_sram").resolve(spec.name()));
        }
    }

    @Test
    void contadorCircular4511ConPausaA8MHzEmiteAsmSinRamYCompacto() throws Exception {
        var spec = ProgramSpec.builder("Prueba")
                .clock(Hardware.ClockFreq.MHZ_8)
                .button("btn", Hardware.Pin.pb(0), Hardware.Connection.GND)
                .bcd("display", Hardware.Pin.pb(1), 1)
                .var("cuenta", 1, 9, 1, State.VarPolicy.WRAP)
                .rule("Cada 500 ms avanzar cuenta salvo pausa en btn",
                        Rule.everyMs(500), Expr.not(Expr.pressed("btn")),
                        Effect.inc("cuenta"), Effect.showBcd("display", Expr.var("cuenta")))
                .build();

        AsmEmitter emitter = new AsmEmitter();
        CEmitter cEmitter = new CEmitter();
        String cCode = cEmitter.emit(spec);
        String asmElem = emitter.emit(spec, AsmEmitter.InstructionProfile.ELEMENTARY, AsmEmitter.StorageMode.REGISTERS_FIRST);
        String asmStd = emitter.emit(spec, AsmEmitter.InstructionProfile.STANDARD, AsmEmitter.StorageMode.REGISTERS_FIRST);
        String asmSram = emitter.emit(spec, AsmEmitter.InstructionProfile.ELEMENTARY, AsmEmitter.StorageMode.SRAM_DATA_SEGMENT);

        assertFalse(cCode.contains("volatile"), "C99 no debe usar volatile innecesario:\n" + cCode);
        assertFalse(cCode.contains("per_0_ok"), "Bucle periódico puro C99 no necesita bandera per_0_ok:\n" + cCode);
        assertFalse(asmElem.contains(".dseg"), "El modo por defecto (registros) no debe emitir .dseg:\n" + asmElem);
        assertFalse(asmElem.contains("per_0_ok"), "Bucle periódico puro ASM no necesita bandera per_0_ok:\n" + asmElem);
        assertFalse(asmElem.contains("lds"), "No debe haber lds en modo registros:\n" + asmElem);
        assertFalse(asmElem.contains("sts"), "No debe haber sts en modo registros:\n" + asmElem);
        assertNoNonElementaryOps("Prueba(elemental)", asmElem);
        assertFalse(asmElem.contains("brlo"), "Contador circular 1..9 elemental no necesita brlo:\n" + asmElem);
        assertTrue(asmStd.contains("subi"), "El perfil Estándar debe usar subi/sbci para 16 bits.");
        assertTrue(asmSram.contains(".dseg"), "El modo opcional SRAM sí debe emitir .dseg.");

        long cLines = cCode.lines().count();
        assertTrue(cLines <= 42, "C99 KISS esperado <= 42 líneas, fueron: " + cLines + "\n" + cCode);

        long instrCount = asmElem.lines()
                .map(l -> l.replaceFirst(";.*", "").trim())
                .filter(l -> !l.isEmpty() && !l.startsWith(".") && !l.endsWith(":"))
                .count();
        assertTrue(instrCount <= 52, "ASM elemental esperado <= 52 instrucciones, fueron: " + instrCount + "\n" + asmElem);

        Toolchain tc = Toolchain.detect();
        Path dir = Path.of("target/generated-benchmarks/prueba_sin_ram").toAbsolutePath();
        if (tc.hasAvrGcc()) {
            tc.compileC("prueba_c", cCode, dir.resolve("c"));
        }
        if (tc.hasAvrasm2()) {
            tc.assembleAsm("prueba_elem", asmElem, dir.resolve("elem"));
            tc.assembleAsm("prueba_std", asmStd, dir.resolve("std"));
            tc.assembleAsm("prueba_sram", asmSram, dir.resolve("sram"));
        }
    }

    private static void assertNoNonElementaryOps(String label, String asm) {
        for (String line : asm.split("\\R")) {
            String codeOnly = line.replaceFirst(";.*", "");
            assertFalse(NON_ELEMENTARY.matcher(codeOnly).find(),
                    () -> label + " emitió instrucción no elemental en perfil ELEMENTARY: " + line);
        }
    }

    private static int countMatches(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count;
    }
}
