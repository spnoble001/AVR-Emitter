package dev.transpilador.v2.ui;

import dev.transpilador.v2.model.*;

import java.util.*;

/**
 * Traducción clara y sin jerga innecesaria del núcleo de 5 bloques a español natural,
 * usada en tarjetas del estudio, vista previa en vivo del constructor y galería de intenciones.
 */
public final class NaturalLanguage {
    private NaturalLanguage() {}

    public static StudioTheme.Role triggerRole(Rule.Trigger t) {
        return switch (t) {
            case Rule.OnPress ignored -> StudioTheme.Role.INPUT;
            case Rule.OnRelease ignored -> StudioTheme.Role.INPUT;
            case Rule.EveryMs ignored -> StudioTheme.Role.LOGIC;
            case Rule.OnTimerExpired ignored -> StudioTheme.Role.LOGIC;
            case Rule.OnUartRx ignored -> StudioTheme.Role.ALT;
            case Rule.OnBoot ignored -> StudioTheme.Role.STATE;
            case Rule.Always ignored -> StudioTheme.Role.OUTPUT;
        };
    }

    public static String triggerBadge(Rule.Trigger t) {
        return switch (t) {
            case Rule.OnBoot ignored -> "Al iniciar";
            case Rule.Always ignored -> "Continuo";
            case Rule.OnPress ignored -> "Pulsación";
            case Rule.OnRelease ignored -> "Al soltar";
            case Rule.EveryMs ignored -> "Periódico";
            case Rule.OnTimerExpired ignored -> "Temporizador";
            case Rule.OnUartRx ignored -> "UART RX";
        };
    }

    public static String describeTrigger(Rule.Trigger t) {
        return switch (t) {
            case Rule.OnBoot ignored -> "Al encender el microcontrolador (una sola vez)";
            case Rule.Always ignored -> "Continuamente en cada revisión";
            case Rule.OnPress p -> "Al presionar «" + p.buttonName() + "»";
            case Rule.OnRelease r -> "Al soltar «" + r.buttonName() + "»";
            case Rule.EveryMs e -> "Cada " + e.periodMs() + " ms";
            case Rule.OnTimerExpired te -> "Al vencer el temporizador «" + te.timerName() + "»";
            case Rule.OnUartRx u -> "Al recibir un byte por UART «" + u.uartName() + "»";
        };
    }

    public static String describeBool(Expr.BoolExpr b) {
        return switch (b) {
            case Expr.Truth t -> t.value() ? "sin condición adicional" : "nunca";
            case Expr.Pressed p -> "«" + p.buttonName() + "» está presionado";
            case Expr.Not n -> switch (n.inner()) {
                case Expr.Pressed p -> "«" + p.buttonName() + "» está suelto";
                case Expr.TimerActive ta -> "«" + ta.timerName() + "» está detenido";
                case Expr.InPhase ip -> "«" + ip.groupName() + "» no está en " + ip.phaseName();
                default -> "NO (" + describeBool(n.inner()) + ")";
            };
            case Expr.Compare c -> describeValue(c.left()) + " " + describeOp(c.op()) + " " + describeValue(c.right());
            case Expr.InRange r -> describeValue(r.value()) + " está entre " + r.min() + " y " + r.max();
            case Expr.Modulo m -> "«" + m.varName() + "» % " + m.divisor() + " = " + m.remainder()
                    + (m.includeZero() ? "" : " (sin contar 0)");
            case Expr.InPhase ip -> "«" + ip.groupName() + "» está en fase " + ip.phaseName();
            case Expr.TimerActive ta -> "temporizador «" + ta.timerName() + "» está corriendo";
            case Expr.UartByteEquals u -> "byte recibido en «" + u.uartName() + "» es " + formatAscii(u.expectedByte());
            case Expr.And a -> joinBool(a.items(), "  Y  ");
            case Expr.Or o -> joinBool(o.items(), "  O  ");
            case Expr.Xor x -> "(" + describeBool(x.left()) + ")  XOR  (" + describeBool(x.right()) + ")";
        };
    }

    private static String joinBool(List<Expr.BoolExpr> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            Expr.BoolExpr item = items.get(i);
            boolean wrap = item instanceof Expr.And || item instanceof Expr.Or || item instanceof Expr.Xor;
            if (wrap) sb.append('(');
            sb.append(describeBool(item));
            if (wrap) sb.append(')');
        }
        return sb.toString();
    }

    public static String describeOp(Expr.CmpOp op) {
        return switch (op) {
            case EQ -> "=";
            case NE -> "≠";
            case LT -> "<";
            case LE -> "≤";
            case GT -> ">";
            case GE -> "≥";
        };
    }

    public static String describeValue(Expr.ValueExpr v) {
        return switch (v) {
            case Expr.ConstU8 c -> String.valueOf(c.value());
            case Expr.ConstU10 c -> String.valueOf(c.value());
            case Expr.ReadVar rv -> "«" + rv.varName() + "»";
            case Expr.ReadAdc8 a -> "ADC «" + a.analogName() + "» (0..255)";
            case Expr.ReadAdc10 a -> "ADC «" + a.analogName() + "» (0..1023)";
            case Expr.ReadUartByte u -> "byte recibido de «" + u.uartName() + "»";
            case Expr.Scale s -> "escalar " + describeValue(s.source()) + " a [" + s.outMin() + ".." + s.outMax() + "]";
        };
    }

    public static String describeEffect(Effect e) {
        return switch (e) {
            case Effect.WriteLed w -> (w.state() == Effect.LedState.ON ? "Encender LED «" : "Apagar LED «") + w.ledName() + "»";
            case Effect.ToggleLed t -> "Alternar LED «" + t.ledName() + "»";
            case Effect.WriteBcd b -> "Mostrar " + describeValue(b.value()) + " en display «" + b.displayName() + "»";
            case Effect.WriteDecimal2 d -> "Mostrar " + describeValue(d.value()) + " (00..99) en «" + d.tensDisplay() + "» y «" + d.unitsDisplay() + "»";
            case Effect.WritePwm p -> "Fijar PWM «" + p.pwmName() + "» = " + describeValue(p.duty());
            case Effect.SendUartText s -> "Enviar por «" + s.uartName() + "» texto \"" + escapeText(s.ascii()) + "\"";
            case Effect.SendUartByte b -> "Enviar por «" + b.uartName() + "» byte " + describeValue(b.value());
            case Effect.SetVar s -> "Fijar «" + s.varName() + "» = " + describeValue(s.value());
            case Effect.StepVar s -> (s.direction() == Effect.StepDir.INC ? "Avanzar (+1) «" : "Retroceder (−1) «") + s.varName() + "»";
            case Effect.ResetVar r -> "Reiniciar «" + r.varName() + "» a su valor inicial";
            case Effect.SetPhase sp -> "Cambiar «" + sp.groupName() + "» → fase " + sp.phaseName();
            case Effect.StartTimer st -> "Iniciar temporizador «" + st.timerName() + "» (" + st.durationMs() + " ms)";
            case Effect.StopTimer st -> "Detener temporizador «" + st.timerName() + "»";
            case Effect.DelayMs d -> "Esperar " + d.durationMs() + " ms (pausa bloqueante)";
        };
    }

    public static String describeEffectsList(List<Effect> effects) {
        if (effects.isEmpty()) return "—";
        if (effects.size() == 1) return describeEffect(effects.getFirst());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < effects.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(i + 1).append(". ").append(describeEffect(effects.get(i)));
        }
        return sb.toString();
    }

    public static String describeRuleNatural(Rule r) {
        StringBuilder sb = new StringBuilder();
        sb.append(describeTrigger(r.trigger()));
        if (!(r.guard() instanceof Expr.Truth t && t.value())) {
            sb.append(", si ").append(describeBool(r.guard()));
        }
        sb.append(" → ");
        sb.append(r.thenEffects().stream().map(NaturalLanguage::describeEffect)
                .reduce((a, b) -> a + "; luego " + b).orElse("(sin acciones)"));
        if (!r.elseEffects().isEmpty()) {
            sb.append(". Si no se cumple → ");
            sb.append(r.elseEffects().stream().map(NaturalLanguage::describeEffect)
                    .reduce((a, b) -> a + "; luego " + b).orElse(""));
        }
        sb.append('.');
        return sb.toString();
    }

    public static String suggestTitle(Rule.Trigger trigger, Expr.BoolExpr guard, List<Effect> thenEffects) {
        String trigShort = switch (trigger) {
            case Rule.OnBoot ignored -> "Al iniciar";
            case Rule.Always ignored -> "Control continuo";
            case Rule.OnPress p -> "Al presionar " + p.buttonName();
            case Rule.OnRelease r -> "Al soltar " + r.buttonName();
            case Rule.EveryMs e -> "Cada " + e.periodMs() + " ms";
            case Rule.OnTimerExpired te -> "Al vencer " + te.timerName();
            case Rule.OnUartRx u -> "Comando UART " + u.uartName();
        };
        if (thenEffects.isEmpty()) return trigShort;
        String firstAct = switch (thenEffects.getFirst()) {
            case Effect.WriteLed w -> (w.state() == Effect.LedState.ON ? "encender " : "apagar ") + w.ledName();
            case Effect.ToggleLed t -> "alternar " + t.ledName();
            case Effect.WriteBcd b -> "actualizar " + b.displayName();
            case Effect.WriteDecimal2 d -> "mostrar en " + d.tensDisplay() + "/" + d.unitsDisplay();
            case Effect.WritePwm p -> "ajustar PWM " + p.pwmName();
            case Effect.SendUartText s -> "enviar texto por " + s.uartName();
            case Effect.SendUartByte b -> "enviar byte por " + b.uartName();
            case Effect.SetVar s -> "fijar " + s.varName();
            case Effect.StepVar s -> (s.direction() == Effect.StepDir.INC ? "avanzar " : "retroceder ") + s.varName();
            case Effect.ResetVar r -> "reiniciar " + r.varName();
            case Effect.SetPhase sp -> sp.groupName() + " → " + sp.phaseName();
            case Effect.StartTimer st -> "iniciar " + st.timerName();
            case Effect.StopTimer st -> "detener " + st.timerName();
            case Effect.DelayMs d -> "esperar " + d.durationMs() + " ms";
        };
        String suffix = (guard instanceof Expr.Truth t && t.value()) ? "" : " (con condición)";
        return trigShort + ": " + firstAct + suffix;
    }

    /** Extrae los nombres de componentes y estados mencionados en la regla para chips visuales. */
    public static List<String> referencedNames(Rule r) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        switch (r.trigger()) {
            case Rule.OnPress p -> names.add(p.buttonName());
            case Rule.OnRelease rel -> names.add(rel.buttonName());
            case Rule.OnTimerExpired te -> names.add(te.timerName());
            case Rule.OnUartRx u -> names.add(u.uartName());
            case Rule.OnBoot ignored -> {}
            case Rule.Always ignored -> {}
            case Rule.EveryMs ignored -> {}
        }
        collectBool(r.guard(), names);
        r.thenEffects().forEach(e -> collectEffect(e, names));
        r.elseEffects().forEach(e -> collectEffect(e, names));
        return List.copyOf(names);
    }

    private static void collectBool(Expr.BoolExpr b, Set<String> out) {
        switch (b) {
            case Expr.Truth ignored -> {}
            case Expr.Pressed p -> out.add(p.buttonName());
            case Expr.Compare c -> { collectVal(c.left(), out); collectVal(c.right(), out); }
            case Expr.InRange r -> collectVal(r.value(), out);
            case Expr.Modulo m -> out.add(m.varName());
            case Expr.InPhase ip -> out.add(ip.groupName());
            case Expr.TimerActive ta -> out.add(ta.timerName());
            case Expr.UartByteEquals u -> out.add(u.uartName());
            case Expr.Not n -> collectBool(n.inner(), out);
            case Expr.And a -> a.items().forEach(i -> collectBool(i, out));
            case Expr.Or o -> o.items().forEach(i -> collectBool(i, out));
            case Expr.Xor x -> { collectBool(x.left(), out); collectBool(x.right(), out); }
        }
    }

    private static void collectVal(Expr.ValueExpr v, Set<String> out) {
        switch (v) {
            case Expr.ConstU8 ignored -> {}
            case Expr.ConstU10 ignored -> {}
            case Expr.ReadVar rv -> out.add(rv.varName());
            case Expr.ReadAdc8 a -> out.add(a.analogName());
            case Expr.ReadAdc10 a -> out.add(a.analogName());
            case Expr.ReadUartByte u -> out.add(u.uartName());
            case Expr.Scale s -> collectVal(s.source(), out);
        }
    }

    private static void collectEffect(Effect e, Set<String> out) {
        switch (e) {
            case Effect.WriteLed w -> out.add(w.ledName());
            case Effect.ToggleLed t -> out.add(t.ledName());
            case Effect.WriteBcd b -> { out.add(b.displayName()); collectVal(b.value(), out); }
            case Effect.WriteDecimal2 d -> { out.add(d.tensDisplay()); out.add(d.unitsDisplay()); collectVal(d.value(), out); }
            case Effect.WritePwm p -> { out.add(p.pwmName()); collectVal(p.duty(), out); }
            case Effect.SendUartText s -> out.add(s.uartName());
            case Effect.SendUartByte b -> { out.add(b.uartName()); collectVal(b.value(), out); }
            case Effect.SetVar s -> { out.add(s.varName()); collectVal(s.value(), out); }
            case Effect.StepVar s -> out.add(s.varName());
            case Effect.ResetVar r -> out.add(r.varName());
            case Effect.SetPhase sp -> out.add(sp.groupName());
            case Effect.StartTimer st -> out.add(st.timerName());
            case Effect.StopTimer st -> out.add(st.timerName());
            case Effect.DelayMs ignored -> {}
        }
    }

    public static String prettyProjectName(String raw) {
        if (raw == null || raw.isBlank()) return "Proyecto sin título";
        String spaced = raw.replace('_', ' ').trim();
        if (spaced.length() > 3 && Character.isDigit(spaced.charAt(0)) && Character.isDigit(spaced.charAt(1)) && spaced.charAt(2) == ' ') {
            String rest = spaced.substring(3).trim();
            if (!rest.isEmpty()) {
                return spaced.substring(0, 2) + " · " + Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String formatAscii(int code) {
        if (code >= 32 && code <= 126) return "'" + (char) code + "' (" + code + ")";
        return "0x" + Integer.toHexString(code).toUpperCase(Locale.ROOT);
    }

    private static String escapeText(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }
}
