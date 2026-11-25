package dev.transpilador.v2.sim.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.transpilador.v2.model.Effect;
import dev.transpilador.v2.model.Expr;
import dev.transpilador.v2.model.Hardware;
import dev.transpilador.v2.model.ProgramSpec;
import dev.transpilador.v2.model.Rule;
import dev.transpilador.v2.model.State;
import dev.transpilador.v2.sim.SimEngineController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Puente local HTTP + Server-Sent Events (SSE) sobre loopback (127.0.0.1:puerto efímero)
 * que desacopla el motor de simulación {@link SimEngineController} ({@code simavr})
 * de cualquier frontend web (JCEF Chromium embebido o navegador externo).
 */
public final class SimBridgeServer implements AutoCloseable {
    private static final String WEB_ROOT = "/sim-web/";
    private static final Gson GSON = new Gson();

    private final SimEngineController engine;
    private final HttpServer server;
    private final ExecutorService httpPool;
    private final ScheduledExecutorService heartbeatPool;
    private final Set<SseClient> sseClients = ConcurrentHashMap.newKeySet();
    private final Consumer<SimEngineController.Snapshot> stateListener;
    private final Consumer<ProgramSpec> specListener;
    private final Consumer<SimEngineController.HighlightState> highlightListener;
    private volatile boolean closed;

    public SimBridgeServer(SimEngineController engine) throws IOException {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.httpPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sim-bridge-http");
            t.setDaemon(true);
            return t;
        });
        this.heartbeatPool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-bridge-sse-ping");
            t.setDaemon(true);
            return t;
        });
        this.server.setExecutor(httpPool);

        // API REST + SSE
        server.createContext("/api/spec", this::handleGetSpec);
        server.createContext("/api/state", this::handleGetState);
        server.createContext("/api/events", this::handleSse);
        server.createContext("/api/sim/", this::handleSimControl);
        server.createContext("/api/input/", this::handleInput);
        server.createContext("/api/ui/", this::handleUiAction);
        // Recursos estáticos del simulador web
        server.createContext("/", this::handleStatic);

        this.stateListener = snap -> broadcastSse("state", GSON.toJson(snap));
        this.specListener = spec -> broadcastSse("spec", serializeSpec(spec));
        this.highlightListener = hl -> broadcastSse("highlight", hl == null ? "null" : GSON.toJson(hl));
        engine.addStateListener(stateListener);
        engine.addSpecListener(specListener);
        engine.addHighlightListener(highlightListener);

        heartbeatPool.scheduleAtFixedRate(this::pingClients, 15, 15, TimeUnit.SECONDS);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port() + "/";
    }

    public SimEngineController engine() {
        return engine;
    }

    private void handleGetSpec(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        sendText(ex, 200, "application/json; charset=utf-8", serializeSpec(engine.spec()));
    }

    private void handleGetState(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        sendText(ex, 200, "application/json; charset=utf-8", GSON.toJson(engine.snapshot()));
    }

    private void handleSse(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        var headers = ex.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-store, no-cache");
        headers.set("Connection", "keep-alive");
        headers.set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        SseClient client = new SseClient(ex);
        sseClients.add(client);
        try {
            // Enviar spec + snapshot + highlight inicial inmediatamente al conectar
            client.sendEvent("spec", serializeSpec(engine.spec()));
            client.sendEvent("state", GSON.toJson(engine.snapshot()));
            var hl = engine.highlight();
            client.sendEvent("highlight", hl == null ? "null" : GSON.toJson(hl));
            client.awaitClose();
        } finally {
            sseClients.remove(client);
            client.close();
        }
    }

    private void handleSimControl(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String action = ex.getRequestURI().getPath().substring("/api/sim/".length());
        JsonObject body = readJsonBody(ex);
        switch (action) {
            case "start" -> engine.start();
            case "pause" -> engine.pause();
            case "step" -> {
                int ms = body.has("ms") ? body.get("ms").getAsInt() : 10;
                engine.stepMs(ms);
            }
            case "step-instr" -> engine.stepInstruction();
            case "reset" -> engine.reset();
            case "backend" -> {
                String b = body.has("backend") ? body.get("backend").getAsString() : "C99";
                engine.setBackend("ASM".equalsIgnoreCase(b)
                        ? SimEngineController.Backend.ASM
                        : SimEngineController.Backend.C99);
            }
            case "clear-custom" -> engine.clearCustomFirmware();
            default -> {
                sendText(ex, 404, "application/json; charset=utf-8", "{\"error\":\"unknown sim action\"}");
                return;
            }
        }
        sendText(ex, 200, "application/json; charset=utf-8", GSON.toJson(engine.snapshot()));
    }

    private void handleInput(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String target = ex.getRequestURI().getPath().substring("/api/input/".length());
        JsonObject body = readJsonBody(ex);
        switch (target) {
            case "button" -> {
                String name = body.has("name") ? body.get("name").getAsString() : "";
                if (body.has("togglePin") && body.get("togglePin").getAsBoolean()) {
                    engine.toggleButtonPinned(name);
                } else if (body.has("pinned")) {
                    engine.setButtonPinned(name, body.get("pinned").getAsBoolean());
                } else if (body.has("pressed")) {
                    engine.setButtonPressed(name, body.get("pressed").getAsBoolean());
                }
            }
            case "analog" -> {
                String name = body.has("name") ? body.get("name").getAsString() : "";
                int mv = body.has("mv") ? body.get("mv").getAsInt() : 0;
                engine.setAnalogMv(name, mv);
            }
            case "uart" -> {
                String text = body.has("text") ? body.get("text").getAsString() : "";
                engine.sendUartRx(text);
            }
            default -> {
                sendText(ex, 404, "application/json; charset=utf-8", "{\"error\":\"unknown input target\"}");
                return;
            }
        }
        sendText(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private void handleUiAction(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String action = ex.getRequestURI().getPath().substring("/api/ui/".length());
        JsonObject body = readJsonBody(ex);
        switch (action) {
            case "select-rule" -> {
                int idx = body.has("ruleIndex") ? body.get("ruleIndex").getAsInt() : -1;
                engine.requestSelectRuleFromWeb(idx);
            }
            case "clear-highlight" -> {
                engine.setHighlight(null);
                engine.requestSelectRuleFromWeb(-1);
            }
            default -> {
                sendText(ex, 404, "application/json; charset=utf-8", "{\"error\":\"unknown ui action\"}");
                return;
            }
        }
        sendText(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String rawPath = ex.getRequestURI().getPath();
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
            rawPath = "/index.html";
        }
        if (rawPath.contains("..") || rawPath.contains("\\")) {
            sendText(ex, 400, "text/plain", "Bad Request");
            return;
        }
        String resource = WEB_ROOT + (rawPath.startsWith("/") ? rawPath.substring(1) : rawPath);
        try (InputStream is = SimBridgeServer.class.getResourceAsStream(resource)) {
            if (is == null) {
                sendText(ex, 404, "text/plain; charset=utf-8", "Recurso no encontrado: " + rawPath);
                return;
            }
            byte[] bytes = is.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentTypeFor(rawPath));
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static String serializeSpec(ProgramSpec spec) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", spec.name());
        root.put("clockLabel", spec.clockFreq().label());
        root.put("clockEnum", spec.clockFreq().name());
        root.put("fCpuHz", spec.fCpuHz());
        root.put("pwmFrequencyHz", spec.clockFreq().pwmFrequencyHz());
        root.put("rulesCount", spec.rules().size());

        List<Map<String, Object>> comps = new ArrayList<>();
        for (Hardware.Component c : spec.components()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            List<Map<String, Object>> pins = new ArrayList<>();
            for (Hardware.Pin p : c.occupiedPins()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("label", p.label());
                pm.put("dip28", p.dip28Pin());
                pins.add(pm);
            }
            m.put("pins", pins);

            switch (c) {
                case Hardware.Button b -> {
                    m.put("type", "BUTTON");
                    m.put("pin", b.pin().label());
                    m.put("dip28", b.pin().dip28Pin());
                    m.put("connection", b.connection().name());
                    m.put("activeHigh", b.activeHigh());
                }
                case Hardware.Led l -> {
                    m.put("type", "LED");
                    m.put("pin", l.pin().label());
                    m.put("dip28", l.pin().dip28Pin());
                    m.put("connection", l.connection().name());
                    m.put("activeHigh", l.activeHigh());
                    m.put("initialOn", l.initialOn());
                }
                case Hardware.BcdDisplay d -> {
                    m.put("type", "BCD");
                    m.put("firstPin", d.firstPin().label());
                    m.put("initialDigit", d.initialDigit());
                }
                case Hardware.AnalogInput a -> {
                    m.put("type", "ANALOG");
                    m.put("pin", a.pin().label());
                    m.put("dip28", a.pin().dip28Pin());
                    m.put("channel", a.channel());
                }
                case Hardware.PwmOutput p -> {
                    m.put("type", "PWM");
                    m.put("pin", p.pin().label());
                    m.put("dip28", p.pin().dip28Pin());
                    m.put("channel", p.channel().name());
                    m.put("timer", p.channel().timer().displayName());
                    m.put("initialDuty", p.initialDuty());
                }
                case Hardware.UartPort u -> {
                    m.put("type", "UART");
                    m.put("baud", u.baud().bps());
                    m.put("mode", u.mode().name());
                    m.put("usesTx", u.mode().usesTx());
                    m.put("usesRx", u.mode().usesRx());
                }
            }
            comps.add(m);
        }
        root.put("components", comps);

        // Metadatos de estado para Rayos X CPU
        List<Map<String, Object>> vars = new ArrayList<>();
        for (State.VarDecl v : spec.vars()) {
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("name", v.name());
            vm.put("min", v.min());
            vm.put("max", v.max());
            vm.put("initial", v.initial());
            vm.put("policy", v.policy().name());
            vars.add(vm);
        }
        root.put("vars", vars);

        List<String> timers = new ArrayList<>();
        for (State.TimerDecl t : spec.timers()) timers.add(t.name());
        root.put("timers", timers);

        // Grafos FSM extraídos automáticamente de PhaseGroups y reglas
        List<Map<String, Object>> fsmGraphs = new ArrayList<>();
        for (State.PhaseGroup g : spec.phaseGroups()) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("group", g.name());
            gm.put("initialPhase", g.initialPhase());
            gm.put("states", g.phases());
            List<Map<String, Object>> transitions = new ArrayList<>();
            for (int rIdx = 0; rIdx < spec.rules().size(); rIdx++) {
                Rule r = spec.rules().get(rIdx);
                collectFsmTransitions(g, r, rIdx, r.thenEffects(), false, transitions);
                collectFsmTransitions(g, r, rIdx, r.elseEffects(), true, transitions);
            }
            gm.put("transitions", transitions);
            fsmGraphs.add(gm);
        }
        root.put("fsmGraphs", fsmGraphs);

        return GSON.toJson(root);
    }

    private static void collectFsmTransitions(
            State.PhaseGroup group,
            Rule rule,
            int ruleIndex,
            List<Effect> effects,
            boolean isElseBranch,
            List<Map<String, Object>> out) {
        List<String> targets = new ArrayList<>();
        for (Effect e : effects) {
            if (e instanceof Effect.SetPhase sp && sp.groupName().equals(group.name())) {
                targets.add(sp.phaseName());
            }
        }
        if (targets.isEmpty()) return;

        Set<String> sources = new LinkedHashSet<>();
        if (!isElseBranch) {
            extractGuardPhases(rule.guard(), group.name(), sources);
        }
        if (sources.isEmpty()) {
            sources.add("*");
        }
        String label = compactTransitionLabel(rule.trigger(), rule.guard(), isElseBranch);
        for (String from : sources) {
            for (String to : targets) {
                Map<String, Object> tr = new LinkedHashMap<>();
                tr.put("ruleIndex", ruleIndex);
                tr.put("from", from);
                tr.put("to", to);
                tr.put("global", "*".equals(from));
                tr.put("label", label);
                tr.put("title", rule.title());
                out.add(tr);
            }
        }
    }

    private static void extractGuardPhases(Expr.BoolExpr expr, String groupName, Set<String> out) {
        switch (expr) {
            case Expr.InPhase ip -> {
                if (ip.groupName().equals(groupName)) out.add(ip.phaseName());
            }
            case Expr.And a -> { for (var item : a.items()) extractGuardPhases(item, groupName, out); }
            case Expr.Or o -> { for (var item : o.items()) extractGuardPhases(item, groupName, out); }
            default -> {}
        }
    }

    private static String compactTransitionLabel(Rule.Trigger trigger, Expr.BoolExpr guard, boolean isElse) {
        String trig = switch (trigger) {
            case Rule.OnBoot ignored -> "arranque";
            case Rule.Always ignored -> "siempre";
            case Rule.OnPress p -> "↓ " + p.buttonName();
            case Rule.OnRelease r -> "↑ " + r.buttonName();
            case Rule.EveryMs e -> "cada " + e.periodMs() + "ms";
            case Rule.OnTimerExpired t -> "⏱ " + t.timerName();
            case Rule.OnUartRx u -> {
                String ch = findUartExpectedChar(guard);
                yield ch != null ? "UART " + ch : "UART RX";
            }
        };
        return isElse ? trig + " (si no)" : trig;
    }

    private static String findUartExpectedChar(Expr.BoolExpr expr) {
        return switch (expr) {
            case Expr.UartByteEquals u -> (u.expectedByte() >= 32 && u.expectedByte() <= 126)
                    ? "'" + (char) u.expectedByte() + "'"
                    : String.valueOf(u.expectedByte());
            case Expr.And a -> {
                for (var i : a.items()) {
                    String found = findUartExpectedChar(i);
                    if (found != null) yield found;
                }
                yield null;
            }
            default -> null;
        };
    }

    private void broadcastSse(String event, String json) {
        if (closed || sseClients.isEmpty()) return;
        for (SseClient client : sseClients) {
            if (!client.sendEvent(event, json)) {
                sseClients.remove(client);
                client.close();
            }
        }
    }

    private void pingClients() {
        if (closed || sseClients.isEmpty()) return;
        for (SseClient client : sseClients) {
            if (!client.sendRaw(": ping\n\n")) {
                sseClients.remove(client);
                client.close();
            }
        }
    }

    private static JsonObject readJsonBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) return new JsonObject();
            var el = JsonParser.parseString(raw);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static void sendText(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String contentTypeFor(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    @Override
    public void close() {
        closed = true;
        engine.removeStateListener(stateListener);
        engine.removeSpecListener(specListener);
        engine.removeHighlightListener(highlightListener);
        heartbeatPool.shutdownNow();
        for (SseClient client : sseClients) {
            client.close();
        }
        sseClients.clear();
        server.stop(0);
        httpPool.shutdownNow();
    }

    private static final class SseClient {
        private final HttpExchange exchange;
        private final OutputStream out;
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private volatile boolean dead;

        SseClient(HttpExchange exchange) {
            this.exchange = exchange;
            this.out = exchange.getResponseBody();
        }

        synchronized boolean sendEvent(String event, String data) {
            return sendRaw("event: " + event + "\ndata: " + data.replace("\n", "") + "\n\n");
        }

        synchronized boolean sendRaw(String frame) {
            if (dead) return false;
            try {
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException ex) {
                dead = true;
                closedLatch.countDown();
                return false;
            }
        }

        void awaitClose() {
            try {
                closedLatch.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        void close() {
            dead = true;
            closedLatch.countDown();
            try {
                out.close();
            } catch (IOException ignored) {
            }
            exchange.close();
        }
    }
}
