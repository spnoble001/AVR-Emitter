package dev.transpilador.v2.persistence;

import dev.transpilador.v2.model.*;

import java.util.*;

/**
 * Serialización y deserialización JSON autocontenida (sin dependencias externas)
 * para el formato único v2 (.avr2.json) de ProgramSpec.
 */
public final class ProjectJson {
    public static final int FORMAT_VERSION = 2;

    private ProjectJson() {}

    public static String toJson(ProgramSpec spec) {
        Objects.requireNonNull(spec, "spec");
        var out = new StringBuilder();
        out.append("{\n");
        out.append("  \"formatVersion\": ").append(FORMAT_VERSION).append(",\n");
        out.append("  \"name\": ").append(quote(spec.name())).append(",\n");
        out.append("  \"fCpuHz\": ").append(spec.fCpuHz()).append(",\n");

        out.append("  \"components\": [");
        for (int i = 0; i < spec.components().size(); i++) {
            out.append(i == 0 ? "\n" : ",\n").append("    ").append(componentJson(spec.components().get(i)));
        }
        out.append(spec.components().isEmpty() ? "],\n" : "\n  ],\n");

        out.append("  \"vars\": [");
        for (int i = 0; i < spec.vars().size(); i++) {
            State.VarDecl v = spec.vars().get(i);
            out.append(i == 0 ? "\n" : ",\n").append("    {\"name\": ").append(quote(v.name()))
                    .append(", \"min\": ").append(v.min())
                    .append(", \"max\": ").append(v.max())
                    .append(", \"initial\": ").append(v.initial())
                    .append(", \"policy\": ").append(quote(v.policy().name())).append("}");
        }
        out.append(spec.vars().isEmpty() ? "],\n" : "\n  ],\n");

        out.append("  \"phaseGroups\": [");
        for (int i = 0; i < spec.phaseGroups().size(); i++) {
            State.PhaseGroup g = spec.phaseGroups().get(i);
            out.append(i == 0 ? "\n" : ",\n").append("    {\"name\": ").append(quote(g.name()))
                    .append(", \"initialPhase\": ").append(quote(g.initialPhase()))
                    .append(", \"phases\": [");
            for (int j = 0; j < g.phases().size(); j++) {
                if (j > 0) out.append(", ");
                out.append(quote(g.phases().get(j)));
            }
            out.append("]}");
        }
        out.append(spec.phaseGroups().isEmpty() ? "],\n" : "\n  ],\n");

        out.append("  \"timers\": [");
        for (int i = 0; i < spec.timers().size(); i++) {
            if (i > 0) out.append(", ");
            out.append(quote(spec.timers().get(i).name()));
        }
        out.append("],\n");

        out.append("  \"rules\": [");
        for (int i = 0; i < spec.rules().size(); i++) {
            out.append(i == 0 ? "\n" : ",\n").append("    ").append(ruleJson(spec.rules().get(i)));
        }
        out.append(spec.rules().isEmpty() ? "]\n" : "\n  ]\n");
        out.append("}\n");
        return out.toString();
    }

    private static String componentJson(Hardware.Component c) {
        return switch (c) {
            case Hardware.Button b -> "{\"kind\": \"button\", \"name\": " + quote(b.name())
                    + ", \"pin\": " + quote(b.pin().label()) + ", \"connection\": " + quote(b.connection().name()) + "}";
            case Hardware.Led l -> "{\"kind\": \"led\", \"name\": " + quote(l.name())
                    + ", \"pin\": " + quote(l.pin().label()) + ", \"connection\": " + quote(l.connection().name())
                    + ", \"initialOn\": " + l.initialOn() + "}";
            case Hardware.BcdDisplay d -> "{\"kind\": \"bcd\", \"name\": " + quote(d.name())
                    + ", \"firstPin\": " + quote(d.firstPin().label()) + ", \"initialDigit\": " + d.initialDigit() + "}";
            case Hardware.AnalogInput a -> "{\"kind\": \"analog\", \"name\": " + quote(a.name())
                    + ", \"pin\": " + quote(a.pin().label()) + "}";
            case Hardware.PwmOutput p -> "{\"kind\": \"pwm\", \"name\": " + quote(p.name())
                    + ", \"pin\": " + quote(p.pin().label()) + ", \"initialDuty\": " + p.initialDuty() + "}";
            case Hardware.UartPort u -> "{\"kind\": \"uart\", \"name\": " + quote(u.name())
                    + ", \"baud\": " + quote(u.baud().name()) + ", \"mode\": " + quote(u.mode().name()) + "}";
        };
    }

    private static String ruleJson(Rule r) {
        var sb = new StringBuilder();
        sb.append("{\"title\": ").append(quote(r.title()))
                .append(", \"trigger\": ").append(triggerJson(r.trigger()))
                .append(", \"guard\": ").append(boolJson(r.guard()))
                .append(", \"then\": [");
        for (int i = 0; i < r.thenEffects().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(effectJson(r.thenEffects().get(i)));
        }
        sb.append("], \"else\": [");
        for (int i = 0; i < r.elseEffects().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(effectJson(r.elseEffects().get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String triggerJson(Rule.Trigger t) {
        return switch (t) {
            case Rule.OnBoot ignored -> "{\"kind\": \"onBoot\"}";
            case Rule.Always ignored -> "{\"kind\": \"always\"}";
            case Rule.OnPress p -> "{\"kind\": \"onPress\", \"button\": " + quote(p.buttonName()) + "}";
            case Rule.OnRelease r -> "{\"kind\": \"onRelease\", \"button\": " + quote(r.buttonName()) + "}";
            case Rule.EveryMs e -> "{\"kind\": \"everyMs\", \"ms\": " + e.periodMs() + "}";
            case Rule.OnTimerExpired te -> "{\"kind\": \"onTimer\", \"timer\": " + quote(te.timerName()) + "}";
            case Rule.OnUartRx u -> "{\"kind\": \"onUartRx\", \"uart\": " + quote(u.uartName()) + "}";
        };
    }

    private static String valueJson(Expr.ValueExpr v) {
        return switch (v) {
            case Expr.ConstU8 c -> "{\"kind\": \"u8\", \"value\": " + c.value() + "}";
            case Expr.ConstU10 c -> "{\"kind\": \"u10\", \"value\": " + c.value() + "}";
            case Expr.ReadVar rv -> "{\"kind\": \"var\", \"name\": " + quote(rv.varName()) + "}";
            case Expr.ReadAdc8 a -> "{\"kind\": \"adc8\", \"name\": " + quote(a.analogName()) + "}";
            case Expr.ReadAdc10 a -> "{\"kind\": \"adc10\", \"name\": " + quote(a.analogName()) + "}";
            case Expr.ReadUartByte u -> "{\"kind\": \"uartByte\", \"name\": " + quote(u.uartName()) + "}";
            case Expr.Scale s -> "{\"kind\": \"scale\", \"source\": " + valueJson(s.source())
                    + ", \"inMax\": " + s.inMax() + ", \"outMin\": " + s.outMin() + ", \"outMax\": " + s.outMax() + "}";
        };
    }

    private static String boolJson(Expr.BoolExpr b) {
        return switch (b) {
            case Expr.Truth t -> "{\"kind\": \"truth\", \"value\": " + t.value() + "}";
            case Expr.Pressed p -> "{\"kind\": \"pressed\", \"button\": " + quote(p.buttonName()) + "}";
            case Expr.Compare c -> "{\"kind\": \"compare\", \"left\": " + valueJson(c.left())
                    + ", \"op\": " + quote(c.op().name()) + ", \"right\": " + valueJson(c.right()) + "}";
            case Expr.InRange r -> "{\"kind\": \"inRange\", \"value\": " + valueJson(r.value())
                    + ", \"min\": " + r.min() + ", \"max\": " + r.max() + "}";
            case Expr.Modulo m -> "{\"kind\": \"modulo\", \"var\": " + quote(m.varName())
                    + ", \"divisor\": " + m.divisor() + ", \"remainder\": " + m.remainder()
                    + ", \"includeZero\": " + m.includeZero() + "}";
            case Expr.InPhase ip -> "{\"kind\": \"inPhase\", \"group\": " + quote(ip.groupName())
                    + ", \"phase\": " + quote(ip.phaseName()) + "}";
            case Expr.TimerActive ta -> "{\"kind\": \"timerActive\", \"timer\": " + quote(ta.timerName()) + "}";
            case Expr.UartByteEquals u -> "{\"kind\": \"uartEq\", \"uart\": " + quote(u.uartName())
                    + ", \"byte\": " + u.expectedByte() + "}";
            case Expr.Not n -> "{\"kind\": \"not\", \"inner\": " + boolJson(n.inner()) + "}";
            case Expr.And a -> "{\"kind\": \"and\", \"items\": [" + joinBool(a.items()) + "]}";
            case Expr.Or o -> "{\"kind\": \"or\", \"items\": [" + joinBool(o.items()) + "]}";
            case Expr.Xor x -> "{\"kind\": \"xor\", \"left\": " + boolJson(x.left())
                    + ", \"right\": " + boolJson(x.right()) + "}";
        };
    }

    private static String joinBool(List<Expr.BoolExpr> items) {
        var sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(boolJson(items.get(i)));
        }
        return sb.toString();
    }

    private static String effectJson(Effect e) {
        return switch (e) {
            case Effect.WriteLed w -> "{\"kind\": \"writeLed\", \"led\": " + quote(w.ledName())
                    + ", \"state\": " + quote(w.state().name()) + "}";
            case Effect.ToggleLed t -> "{\"kind\": \"toggleLed\", \"led\": " + quote(t.ledName()) + "}";
            case Effect.WriteBcd b -> "{\"kind\": \"writeBcd\", \"display\": " + quote(b.displayName())
                    + ", \"value\": " + valueJson(b.value()) + "}";
            case Effect.WriteDecimal2 d -> "{\"kind\": \"writeDecimal2\", \"units\": " + quote(d.unitsDisplay())
                    + ", \"tens\": " + quote(d.tensDisplay()) + ", \"value\": " + valueJson(d.value()) + "}";
            case Effect.WritePwm p -> "{\"kind\": \"writePwm\", \"pwm\": " + quote(p.pwmName())
                    + ", \"duty\": " + valueJson(p.duty()) + "}";
            case Effect.SendUartText s -> "{\"kind\": \"sendUartText\", \"uart\": " + quote(s.uartName())
                    + ", \"ascii\": " + quote(s.ascii()) + "}";
            case Effect.SendUartByte b -> "{\"kind\": \"sendUartByte\", \"uart\": " + quote(b.uartName())
                    + ", \"value\": " + valueJson(b.value()) + "}";
            case Effect.SetVar s -> "{\"kind\": \"setVar\", \"var\": " + quote(s.varName())
                    + ", \"value\": " + valueJson(s.value()) + "}";
            case Effect.StepVar s -> "{\"kind\": \"stepVar\", \"var\": " + quote(s.varName())
                    + ", \"dir\": " + quote(s.direction().name()) + "}";
            case Effect.ResetVar r -> "{\"kind\": \"resetVar\", \"var\": " + quote(r.varName()) + "}";
            case Effect.SetPhase sp -> "{\"kind\": \"setPhase\", \"group\": " + quote(sp.groupName())
                    + ", \"phase\": " + quote(sp.phaseName()) + "}";
            case Effect.StartTimer st -> "{\"kind\": \"startTimer\", \"timer\": " + quote(st.timerName())
                    + ", \"ms\": " + st.durationMs() + "}";
            case Effect.StopTimer st -> "{\"kind\": \"stopTimer\", \"timer\": " + quote(st.timerName()) + "}";
            case Effect.DelayMs d -> "{\"kind\": \"delayMs\", \"ms\": " + d.durationMs() + "}";
        };
    }

    public static ProgramSpec parse(String json) {
        Objects.requireNonNull(json, "json");
        Parser parser = new Parser(json);
        Map<String, Object> root = asObj(parser.parseValue());
        parser.expectEof();
        int version = asInt(root.get("formatVersion"));
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Versión de formato no soportada: " + version + " (esperada " + FORMAT_VERSION + ")");
        }
        String name = asStr(root.get("name"));
        long fCpu = asLong(root.get("fCpuHz"));

        var components = new ArrayList<Hardware.Component>();
        for (Object item : asList(root.get("components"))) {
            components.add(parseComponent(asObj(item)));
        }
        var vars = new ArrayList<State.VarDecl>();
        for (Object item : asList(root.get("vars"))) {
            Map<String, Object> o = asObj(item);
            vars.add(new State.VarDecl(asStr(o.get("name")), asInt(o.get("min")), asInt(o.get("max")),
                    asInt(o.get("initial")), State.VarPolicy.valueOf(asStr(o.get("policy")))));
        }
        var phaseGroups = new ArrayList<State.PhaseGroup>();
        for (Object item : asList(root.get("phaseGroups"))) {
            Map<String, Object> o = asObj(item);
            List<String> phases = asList(o.get("phases")).stream().map(ProjectJson::asStr).toList();
            phaseGroups.add(new State.PhaseGroup(asStr(o.get("name")), phases, asStr(o.get("initialPhase"))));
        }
        var timers = new ArrayList<State.TimerDecl>();
        for (Object item : asList(root.get("timers"))) {
            timers.add(new State.TimerDecl(asStr(item)));
        }
        var rules = new ArrayList<Rule>();
        for (Object item : asList(root.get("rules"))) {
            rules.add(parseRule(asObj(item)));
        }
        return new ProgramSpec(name, fCpu, components, vars, phaseGroups, timers, rules);
    }

    private static Hardware.Component parseComponent(Map<String, Object> o) {
        return switch (asStr(o.get("kind"))) {
            case "button" -> new Hardware.Button(asStr(o.get("name")), Hardware.Pin.parse(asStr(o.get("pin"))),
                    Hardware.Connection.valueOf(asStr(o.get("connection"))));
            case "led" -> new Hardware.Led(asStr(o.get("name")), Hardware.Pin.parse(asStr(o.get("pin"))),
                    Hardware.Connection.valueOf(asStr(o.get("connection"))), asBool(o.get("initialOn")));
            case "bcd" -> new Hardware.BcdDisplay(asStr(o.get("name")), Hardware.Pin.parse(asStr(o.get("firstPin"))),
                    asInt(o.get("initialDigit")));
            case "analog" -> new Hardware.AnalogInput(asStr(o.get("name")), Hardware.Pin.parse(asStr(o.get("pin"))));
            case "pwm" -> new Hardware.PwmOutput(asStr(o.get("name")), Hardware.Pin.parse(asStr(o.get("pin"))),
                    asInt(o.get("initialDuty")));
            case "uart" -> new Hardware.UartPort(asStr(o.get("name")), Hardware.UartBaud.valueOf(asStr(o.get("baud"))),
                    Hardware.UartMode.valueOf(asStr(o.get("mode"))));
            default -> throw new IllegalArgumentException("Componente desconocido: " + o.get("kind"));
        };
    }

    private static Rule parseRule(Map<String, Object> o) {
        String title = asStr(o.get("title"));
        Rule.Trigger trigger = parseTrigger(asObj(o.get("trigger")));
        Expr.BoolExpr guard = parseBool(asObj(o.get("guard")));
        List<Effect> thenList = asList(o.get("then")).stream().map(x -> parseEffect(asObj(x))).toList();
        List<Effect> elseList = asList(o.get("else")).stream().map(x -> parseEffect(asObj(x))).toList();
        return new Rule(title, trigger, guard, thenList, elseList);
    }

    private static Rule.Trigger parseTrigger(Map<String, Object> o) {
        return switch (asStr(o.get("kind"))) {
            case "onBoot" -> new Rule.OnBoot();
            case "always" -> new Rule.Always();
            case "onPress" -> new Rule.OnPress(asStr(o.get("button")));
            case "onRelease" -> new Rule.OnRelease(asStr(o.get("button")));
            case "everyMs" -> new Rule.EveryMs(asInt(o.get("ms")));
            case "onTimer" -> new Rule.OnTimerExpired(asStr(o.get("timer")));
            case "onUartRx" -> new Rule.OnUartRx(asStr(o.get("uart")));
            default -> throw new IllegalArgumentException("Disparador desconocido: " + o.get("kind"));
        };
    }

    private static Expr.ValueExpr parseValue(Map<String, Object> o) {
        return switch (asStr(o.get("kind"))) {
            case "u8" -> new Expr.ConstU8(asInt(o.get("value")));
            case "u10" -> new Expr.ConstU10(asInt(o.get("value")));
            case "var" -> new Expr.ReadVar(asStr(o.get("name")));
            case "adc8" -> new Expr.ReadAdc8(asStr(o.get("name")));
            case "adc10" -> new Expr.ReadAdc10(asStr(o.get("name")));
            case "uartByte" -> new Expr.ReadUartByte(asStr(o.get("name")));
            case "scale" -> new Expr.Scale(parseValue(asObj(o.get("source"))),
                    asInt(o.get("inMax")), asInt(o.get("outMin")), asInt(o.get("outMax")));
            default -> throw new IllegalArgumentException("Expresión de valor desconocida: " + o.get("kind"));
        };
    }

    private static Expr.BoolExpr parseBool(Map<String, Object> o) {
        return switch (asStr(o.get("kind"))) {
            case "truth" -> new Expr.Truth(asBool(o.get("value")));
            case "pressed" -> new Expr.Pressed(asStr(o.get("button")));
            case "compare" -> new Expr.Compare(parseValue(asObj(o.get("left"))),
                    Expr.CmpOp.valueOf(asStr(o.get("op"))), parseValue(asObj(o.get("right"))));
            case "inRange" -> new Expr.InRange(parseValue(asObj(o.get("value"))), asInt(o.get("min")), asInt(o.get("max")));
            case "modulo" -> new Expr.Modulo(asStr(o.get("var")), asInt(o.get("divisor")),
                    asInt(o.get("remainder")), asBool(o.get("includeZero")));
            case "inPhase" -> new Expr.InPhase(asStr(o.get("group")), asStr(o.get("phase")));
            case "timerActive" -> new Expr.TimerActive(asStr(o.get("timer")));
            case "uartEq" -> new Expr.UartByteEquals(asStr(o.get("uart")), asInt(o.get("byte")));
            case "not" -> new Expr.Not(parseBool(asObj(o.get("inner"))));
            case "and" -> new Expr.And(asList(o.get("items")).stream().map(x -> parseBool(asObj(x))).toList());
            case "or" -> new Expr.Or(asList(o.get("items")).stream().map(x -> parseBool(asObj(x))).toList());
            case "xor" -> new Expr.Xor(parseBool(asObj(o.get("left"))), parseBool(asObj(o.get("right"))));
            default -> throw new IllegalArgumentException("Condición booleana desconocida: " + o.get("kind"));
        };
    }

    private static Effect parseEffect(Map<String, Object> o) {
        return switch (asStr(o.get("kind"))) {
            case "writeLed" -> new Effect.WriteLed(asStr(o.get("led")), Effect.LedState.valueOf(asStr(o.get("state"))));
            case "toggleLed" -> new Effect.ToggleLed(asStr(o.get("led")));
            case "writeBcd" -> new Effect.WriteBcd(asStr(o.get("display")), parseValue(asObj(o.get("value"))));
            case "writeDecimal2" -> new Effect.WriteDecimal2(asStr(o.get("units")), asStr(o.get("tens")),
                    parseValue(asObj(o.get("value"))));
            case "writePwm" -> new Effect.WritePwm(asStr(o.get("pwm")), parseValue(asObj(o.get("duty"))));
            case "sendUartText" -> new Effect.SendUartText(asStr(o.get("uart")), asStr(o.get("ascii")));
            case "sendUartByte" -> new Effect.SendUartByte(asStr(o.get("uart")), parseValue(asObj(o.get("value"))));
            case "setVar" -> new Effect.SetVar(asStr(o.get("var")), parseValue(asObj(o.get("value"))));
            case "stepVar" -> new Effect.StepVar(asStr(o.get("var")), Effect.StepDir.valueOf(asStr(o.get("dir"))));
            case "resetVar" -> new Effect.ResetVar(asStr(o.get("var")));
            case "setPhase" -> new Effect.SetPhase(asStr(o.get("group")), asStr(o.get("phase")));
            case "startTimer" -> new Effect.StartTimer(asStr(o.get("timer")), asInt(o.get("ms")));
            case "stopTimer" -> new Effect.StopTimer(asStr(o.get("timer")));
            case "delayMs" -> new Effect.DelayMs(asInt(o.get("ms")));
            default -> throw new IllegalArgumentException("Efecto desconocido: " + o.get("kind"));
        };
    }

    private static String quote(String s) {
        var sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObj(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("Se esperaba objeto JSON");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        if (o instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException("Se esperaba arreglo JSON");
    }

    private static String asStr(Object o) {
        if (o instanceof String s) return s;
        throw new IllegalArgumentException("Se esperaba cadena JSON");
    }

    private static int asInt(Object o) {
        if (o instanceof Long l) {
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Número entero fuera de rango de 32 bits: " + l);
            }
            return l.intValue();
        }
        if (o instanceof Integer i) return i;
        throw new IllegalArgumentException("Se esperaba número entero JSON");
    }

    private static long asLong(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Integer i) return i.longValue();
        throw new IllegalArgumentException("Se esperaba número entero JSON");
    }

    private static boolean asBool(Object o) {
        if (o instanceof Boolean b) return b;
        throw new IllegalArgumentException("Se esperaba booleano JSON");
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        Object parseValue() {
            skipWs();
            if (pos >= s.length()) throw new IllegalArgumentException("JSON incompleto");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        void expectEof() {
            skipWs();
            if (pos != s.length()) {
                throw new IllegalArgumentException("Contenido inesperado tras el documento JSON en posición " + pos);
            }
        }

        private Map<String, Object> parseObject() {
            expect('{');
            var map = new LinkedHashMap<String, Object>();
            skipWs();
            if (peek('}')) { pos++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                if (map.containsKey(key)) {
                    throw new IllegalArgumentException("Clave JSON duplicada en objeto: '" + key + "'");
                }
                map.put(key, parseValue());
                skipWs();
                if (peek(',')) { pos++; continue; }
                expect('}');
                return map;
            }
        }

        private List<Object> parseArray() {
            expect('[');
            var list = new ArrayList<Object>();
            skipWs();
            if (peek(']')) { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (peek(',')) { pos++; continue; }
                expect(']');
                return list;
            }
        }

        private String parseString() {
            expect('"');
            var sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw new IllegalArgumentException("Escape JSON incompleto");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'r' -> sb.append('\r');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            int code = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            sb.append((char) code);
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Escape JSON inválido: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Cadena JSON sin cerrar");
        }

        private Boolean parseBoolean() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("Booleano JSON inválido en posición " + pos);
        }

        private Object parseNull() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw new IllegalArgumentException("Literal JSON inválido en posición " + pos);
        }

        private Long parseNumber() {
            int start = pos;
            if (peek('-')) pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            return Long.parseLong(s.substring(start, pos));
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private boolean peek(char c) { return pos < s.length() && s.charAt(pos) == c; }

        private void expect(char c) {
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw new IllegalArgumentException("Se esperaba '" + c + "' en posición " + pos);
            }
            pos++;
        }
    }
}
