package dev.transpilador.v2;

import dev.transpilador.v2.emit.*;
import dev.transpilador.v2.model.ProgramSpec;
import dev.transpilador.v2.patterns.BenchmarkCatalog;
import dev.transpilador.v2.sim.SimEngineController;
import dev.transpilador.v2.sim.Toolchain;
import dev.transpilador.v2.sim.web.SimBridgeServer;
import dev.transpilador.v2.ui.StudioPanel;
import dev.transpilador.v2.ui.StudioTheme;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite integral para las 3 nuevas capacidades didácticas:
 *  1. Inspector Rayos X de CPU (r0..r31, SREG, PC, SP, puertos) + paso +1 instrucción.
 *  2. Trazabilidad interactiva (tooltips didácticos ASM en español + sincronización 4-vías).
 *  3. Grafo FSM en tiempo real sobre simavr en ambos motores (C99 ELF+MEM y AVR ASM).
 */
class XRayTraceabilityFsmTest {

    @Test
    void xrayCpuRegistersSregAndSingleInstructionSteppingWorkInSimavr() throws Exception {
        ProgramSpec spec = BenchmarkCatalog.b04ContadorBcdPausa();
        Toolchain tc = Toolchain.detect();

        try (SimEngineController engine = new SimEngineController(spec, tc)) {
            engine.setBackend(SimEngineController.Backend.ASM);
            engine.stepInstruction();
            SimEngineController.Snapshot s1 = waitIdleActive(engine);

            assertFalse(s1.running(), "+1 instr desde cero debe permanecer en pausa");
            assertNotNull(s1.cpu(), "Snapshot debe incluir CpuSnapshot real desde simavr");
            assertEquals(32, s1.cpu().regs().length);
            assertTrue(s1.cpu().ports().containsKey("B") && s1.cpu().ports().containsKey("D"));
            assertTrue(s1.cpu().registerAliases().values().stream().anyMatch(v -> v.contains("var_cuenta")),
                    "El mapa simbólico ASM debe exponer alias de var_cuenta: " + s1.cpu().registerAliases());

            long cycles1 = s1.cycles();
            engine.stepInstruction();
            SimEngineController.Snapshot s2 = waitCyclesAdvance(engine, cycles1);
            assertFalse(s2.running(), "Tras segundo +1 instr debe continuar en pausa");
            long delta = s2.cycles() - cycles1;
            assertTrue(delta >= 1 && delta <= 4,
                    "Una instrucción AVR debe consumir entre 1 y 4 ciclos, fueron: " + delta);
        }
    }

    @Test
    void liveFsmPhasesAndTransitionsAreTrackedInBothC99AndAsm() throws Exception {
        ProgramSpec spec = BenchmarkCatalog.b10SemaforoCancelable();
        Toolchain tc = Toolchain.detect();

        for (SimEngineController.Backend backend : SimEngineController.Backend.values()) {
            try (SimEngineController engine = new SimEngineController(spec, tc)) {
                engine.setBackend(backend);

                // 1. Arranque en VERDE (temporizador 300 ms)
                engine.stepMs(10);
                SimEngineController.Snapshot s1 = waitIdleActive(engine);
                assertNotNull(s1.cpu(), backend + ": falta telemetría CPU");
                assertEquals("VERDE", s1.cpu().livePhases().get("sem"),
                        backend + ": fase inicial debe ser VERDE");
                assertTrue(s1.cpu().liveTimersMs().getOrDefault("t_fase", 0) > 200,
                        backend + ": t_fase debe estar activo (>200 ms), fue: " + s1.cpu().liveTimersMs());

                // 2. Avanzar 310 ms -> VERDE -> AMARILLO
                long c1 = s1.cycles();
                engine.stepMs(310);
                SimEngineController.Snapshot s2 = waitCyclesAdvance(engine, c1);
                assertEquals("AMARILLO", s2.cpu().livePhases().get("sem"),
                        backend + ": tras 320 ms debe transicionar a AMARILLO");
                assertTrue(s2.recentFsmTransitions().stream()
                                .anyMatch(ev -> ev.group().equals("sem") && ev.from().equals("VERDE") && ev.to().equals("AMARILLO")),
                        backend + ": debe registrar evento VERDE -> AMARILLO: " + s2.recentFsmTransitions());

                // 3. Avanzar 160 ms -> AMARILLO -> ROJO
                long c2 = s2.cycles();
                engine.stepMs(160);
                SimEngineController.Snapshot s3 = waitCyclesAdvance(engine, c2);
                assertEquals("ROJO", s3.cpu().livePhases().get("sem"),
                        backend + ": tras +160 ms debe transicionar a ROJO");
                assertTrue(s3.recentFsmTransitions().stream()
                                .anyMatch(ev -> ev.group().equals("sem") && ev.from().equals("AMARILLO") && ev.to().equals("ROJO")),
                        backend + ": debe registrar evento AMARILLO -> ROJO: " + s3.recentFsmTransitions());
            }
        }
    }

    @Test
    void avrInstructionCatalogCoversAllBenchmarksAndNamedExamples() {
        // Casos explícitos pedidos por el usuario
        String tipSbic = AvrInstructionCatalog.tooltipHtmlForLine("    sbic PINB, 0");
        assertNotNull(tipSbic);
        assertTrue(tipSbic.contains("PINB") && tipSbic.contains("Ciclos:") && tipSbic.contains("SREG:"), tipSbic);

        String tipCpi = AvrInstructionCatalog.tooltipHtmlForLine("    cpi  r16, 10");
        assertNotNull(tipCpi);
        assertTrue(tipCpi.contains("r16") && tipCpi.contains("10") && tipCpi.contains("H, S, V, N, Z, C"), tipCpi);

        String tipRjmp = AvrInstructionCatalog.tooltipHtmlForLine("    rjmp bucle");
        assertNotNull(tipRjmp);
        assertTrue(tipRjmp.contains("bucle") && tipRjmp.contains("2 ciclos"), tipRjmp);

        // Cobertura 100 % de todas las instrucciones emitidas en los 15 benchmarks (Elemental y Estándar)
        AsmEmitter asm = new AsmEmitter();
        for (ProgramSpec spec : BenchmarkCatalog.all()) {
            for (AsmEmitter.InstructionProfile prof : AsmEmitter.InstructionProfile.values()) {
                String code = asm.emit(spec, prof, AsmEmitter.StorageMode.REGISTERS_FIRST);
                for (String rawLine : code.split("\\R")) {
                    String stripped = rawLine.replaceFirst(";.*", "").trim();
                    if (stripped.isEmpty()) continue;
                    String tip = AvrInstructionCatalog.tooltipHtmlForLine(rawLine);
                    assertNotNull(tip, () -> "Falta tooltip AVR para línea en " + spec.name() + " (" + prof + "): " + rawLine);
                }
            }
        }
    }

    @Test
    void traceMapAndFourWaySelectionSynchronizeAcrossStudioAndSimBridge() throws Exception {
        CEmitter ce = new CEmitter();
        AsmEmitter ae = new AsmEmitter();
        for (ProgramSpec spec : BenchmarkCatalog.all()) {
            EmissionPlan plan = EmissionPlan.analyze(spec);
            var cOut = ce.emitWithTrace(plan);
            var aOut = ae.emitWithTrace(plan, AsmEmitter.InstructionProfile.ELEMENTARY, AsmEmitter.StorageMode.REGISTERS_FIRST);
            TraceMap map = TraceMap.build(spec, cOut.ruleLines(), aOut.ruleLines());
            assertEquals(spec.rules().size(), map.rules().size());
            for (int i = 0; i < spec.rules().size(); i++) {
                var rt = map.rule(i).orElseThrow();
                assertNotNull(rt.cRange(), spec.name() + " regla #" + i + " sin rango C99");
                assertNotNull(rt.asmRange(), spec.name() + " regla #" + i + " sin rango ASM");
                assertEquals(i, map.ruleAtCLine(rt.cRange().startLine()).orElseThrow().ruleIndex());
                assertEquals(i, map.ruleAtAsmLine(rt.asmRange().startLine()).orElseThrow().ruleIndex());
            }
        }

        // Probar sincronización real en StudioPanel sobre el semáforo cancelable
        ProgramSpec sem = BenchmarkCatalog.b10SemaforoCancelable();
        SwingUtilities.invokeAndWait(() -> {
            StudioTheme.install();
            StudioPanel studio = new StudioPanel(sem);
            try {
                // Regla #4 (índice 4): "Emergencia cancela cualquier fase hacia Rojo" -> btn_emergencia + 3 LEDs
                studio.selectRule(4);
                assertEquals(4, studio.selectedRuleIndex());
                var rt = studio.traceMap().rule(4).orElseThrow();
                assertEquals(rt.cRange(), studio.cViewer().highlightedRange());
                assertEquals(rt.asmRange(), studio.asmViewer().highlightedRange());

                var hl = studio.simulatorTab().webEngine().highlight();
                assertNotNull(hl, "Seleccionar una tarjeta debe propagar HighlightState al simulador");
                assertEquals(4, hl.ruleIndex());
                assertEquals(Set.of("btn_emergencia", "led_verde", "led_amarillo", "led_rojo"), hl.components());
                assertEquals(Set.of("PD2", "PB0", "PB1", "PB2"), hl.pins());

                // Tooltip desde CodeViewerPane en la línea de rjmp bucle
                String asmText = studio.emittedAsm();
                String[] asmLines = asmText.split("\\R");
                int rjmpLine = -1;
                for (int i = 0; i < asmLines.length; i++) {
                    if (asmLines[i].contains("rjmp bucle")) { rjmpLine = i + 1; break; }
                }
                assertTrue(rjmpLine > 0);
                String paneTip = studio.asmViewer().tooltipForLine(rjmpLine);
                assertNotNull(paneTip);
                assertTrue(paneTip.contains("bucle") && paneTip.contains("2 ciclos"));

                // Limpiar selección
                studio.selectRule(-1);
                assertNull(studio.cViewer().highlightedRange());
                assertNull(studio.simulatorTab().webEngine().highlight());
            } finally {
                studio.shutdown();
            }
        });
    }

    @Test
    void simBridgeServerServesXrayFsmAssetsAndControlEndpoints() throws Exception {
        ProgramSpec spec = BenchmarkCatalog.b15MaquinaTresEstadosUartAdc();
        try (SimEngineController engine = new SimEngineController(spec, Toolchain.detect());
             SimBridgeServer server = new SimBridgeServer(engine)) {
            AtomicInteger webSelectedRule = new AtomicInteger(-1);
            engine.addRuleSelectListener(webSelectedRule::set);

            HttpClient http = HttpClient.newHttpClient();
            for (String path : new String[]{"/xray.js", "/fsm.js", "/styles.css", "/app.js"}) {
                var res = http.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + path.substring(1))).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, res.statusCode(), "Recurso web faltante: " + path);
            }

            var specRes = http.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "api/spec")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, specRes.statusCode());
            assertTrue(specRes.body().contains("\"fsmGraphs\""), "api/spec debe exportar fsmGraphs");
            assertTrue(specRes.body().contains("\"ADVERTENCIA\"") && specRes.body().contains("\"FALLO\""),
                    "fsmGraphs debe listar todas las fases del integrador");
            assertTrue(specRes.body().contains("\"global\":true"),
                    "Debe detectar transición global de reset UART 'r' -> NORMAL");

            // Clic en flecha del grafo FSM -> /api/ui/select-rule -> notifica al Estudio
            var selRes = http.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "api/ui/select-rule"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"ruleIndex\":2}"))
                    .header("Content-Type", "application/json")
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, selRes.statusCode());
            assertEquals(2, webSelectedRule.get());

            // Paso de 1 instrucción vía REST
            var stepRes = http.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "api/sim/step-instr"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("Content-Type", "application/json")
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, stepRes.statusCode());
            SimEngineController.Snapshot snap = waitIdleActive(engine);
            assertNotNull(snap.cpu());
            assertFalse(snap.running());
        }
    }

    private static SimEngineController.Snapshot waitIdleActive(SimEngineController engine) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 25_000;
        SimEngineController.Snapshot snap = engine.snapshot();
        while (System.currentTimeMillis() < deadline && (snap.busy() || !snap.sessionActive())) {
            Thread.sleep(40);
            snap = engine.snapshot();
        }
        assertTrue(snap.sessionActive(), "La sesión simavr no arrancó: " + snap.status());
        assertFalse(snap.busy(), "El controlador siguió ocupado: " + snap.status());
        return snap;
    }

    private static SimEngineController.Snapshot waitCyclesAdvance(SimEngineController engine, long prevCycles) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        SimEngineController.Snapshot snap = engine.snapshot();
        while (System.currentTimeMillis() < deadline && (snap.busy() || snap.cycles() <= prevCycles)) {
            Thread.sleep(30);
            snap = engine.snapshot();
        }
        assertTrue(snap.cycles() > prevCycles, "Los ciclos no avanzaron: " + snap.status());
        assertFalse(snap.busy());
        return snap;
    }
}
