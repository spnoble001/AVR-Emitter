package dev.transpilador.v2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.transpilador.v2.patterns.BenchmarkCatalog;
import dev.transpilador.v2.sim.SimEngineController;
import dev.transpilador.v2.sim.web.SimBridgeServer;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba integral de Fase A: puente HTTP + SSE ({@link SimBridgeServer}) conectado
 * en vivo a {@link SimEngineController} y {@code simavr} real (tanto en C99 como en AVR ASM).
 */
class SimBridgeServerTest {

    @Test
    void bridgeServesWebAssetsStreamsSseAndDrivesRealSimavrInC99AndAsm() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try (SimEngineController engine = new SimEngineController(BenchmarkCatalog.b01LedBoton());
             SimBridgeServer bridge = new SimBridgeServer(engine)) {

            String base = bridge.baseUrl();

            // 1. Recursos estáticos y módulos ES de sim-web/
            var htmlRes = http.send(HttpRequest.newBuilder(URI.create(base)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, htmlRes.statusCode());
            assertTrue(htmlRes.body().contains("cadCanvas"), "index.html debe montar el lienzo CAD");
            assertTrue(htmlRes.body().contains("modeBreadboard"), "index.html debe incluir selector Protoboard");

            for (String module : java.util.List.of("app.js", "shared.js", "schematic.js", "breadboard.js", "scope.js")) {
                var modRes = http.send(HttpRequest.newBuilder(URI.create(base + module)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, modRes.statusCode(), "Debe servir módulo ES " + module);
                assertTrue(modRes.headers().firstValue("Content-Type").orElse("").contains("javascript"),
                        module + " debe servirse como application/javascript");
            }

            var cssRes = http.send(HttpRequest.newBuilder(URI.create(base + "styles.css")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, cssRes.statusCode());

            // 2. Protección contra path traversal
            var badRes = http.send(HttpRequest.newBuilder(URI.create(base + "..%2Fpom.xml")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(badRes.statusCode() == 400 || badRes.statusCode() == 404);

            // 3. GET /api/spec
            var specRes = http.send(HttpRequest.newBuilder(URI.create(base + "api/spec")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, specRes.statusCode());
            JsonObject specJson = JsonParser.parseString(specRes.body()).getAsJsonObject();
            assertEquals("01_led_boton", specJson.get("name").getAsString());
            assertEquals(2, specJson.getAsJsonArray("components").size());

            // 4. Stream SSE entrega spec y state inicial inmediatamente
            var sseReq = HttpRequest.newBuilder(URI.create(base + "api/events")).GET().timeout(Duration.ofSeconds(5)).build();
            var sseRes = http.send(sseReq, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, sseRes.statusCode());
            try (BufferedReader br = new BufferedReader(new InputStreamReader(sseRes.body(), StandardCharsets.UTF_8))) {
                StringBuilder firstLines = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    String line = br.readLine();
                    if (line == null) break;
                    firstLines.append(line).append('\n');
                }
                String sseText = firstLines.toString();
                assertTrue(sseText.contains("event: spec"), "SSE debe emitir spec inicial: " + sseText);
                assertTrue(sseText.contains("event: state"), "SSE debe emitir state inicial: " + sseText);
            }

            // 5. Simulación real en C99 vía HTTP POST -> simavr
            verifyLiveButtonLedCycle(http, base, "C99");

            // 6. Cambiar motor a AVR ASM vía HTTP POST y verificar paridad idéntica en vivo
            verifyLiveButtonLedCycle(http, base, "ASM");
        }
    }

    private static void verifyLiveButtonLedCycle(HttpClient http, String base, String backend) throws Exception {
        postJson(http, base + "api/sim/backend", "{\"backend\":\"" + backend + "\"}");
        postJson(http, base + "api/sim/start", "{}");

        // Esperar arranque de simavr (máx 25 s para cubrir arranque en frío de WSL)
        JsonObject st = waitForCondition(http, base,
                j -> j.get("sessionActive").getAsBoolean() && j.get("running").getAsBoolean(), 25_000);
        assertFalse(st.getAsJsonObject("ledOn").get("led").getAsBoolean(), backend + ": LED debe iniciar apagado");

        // Presionar botón vía puente HTTP -> LED se enciende en simavr
        postJson(http, base + "api/input/button", "{\"name\":\"boton\",\"pressed\":true}");
        st = waitForCondition(http, base,
                j -> j.getAsJsonObject("ledOn").get("led").getAsBoolean(), 4_000);
        assertTrue(st.getAsJsonObject("ledOn").get("led").getAsBoolean(), backend + ": al pulsar boton, LED debe encender");

        // Soltar botón vía puente HTTP -> LED se apaga en simavr
        postJson(http, base + "api/input/button", "{\"name\":\"boton\",\"pressed\":false}");
        st = waitForCondition(http, base,
                j -> !j.getAsJsonObject("ledOn").get("led").getAsBoolean(), 4_000);
        assertFalse(st.getAsJsonObject("ledOn").get("led").getAsBoolean(), backend + ": al soltar boton, LED debe apagar");

        postJson(http, base + "api/sim/reset", "{}");
    }

    private static JsonObject postJson(HttpClient http, String url, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), "POST " + url + " -> " + res.body());
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private static JsonObject waitForCondition(HttpClient http, String base,
                                               java.util.function.Predicate<JsonObject> pred, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonObject last = null;
        while (System.currentTimeMillis() < deadline) {
            var res = http.send(HttpRequest.newBuilder(URI.create(base + "api/state")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            last = JsonParser.parseString(res.body()).getAsJsonObject();
            if (pred.test(last)) return last;
            Thread.sleep(60);
        }
        fail("Tiempo agotado esperando condición en /api/state. Último estado: " + last);
        return last;
    }
}
