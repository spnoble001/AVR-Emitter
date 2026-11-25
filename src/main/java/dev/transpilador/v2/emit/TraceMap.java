package dev.transpilador.v2.emit;

import dev.transpilador.v2.model.*;

import java.util.*;

/**
 * Mapa de trazabilidad bidireccional y ubicación física de estado entre:
 * Tarjetas Reactivas (ProgramSpec) ↔ Líneas C99 ↔ Líneas AVR ASM ↔ Pines/Componentes físicos.
 */
public final class TraceMap {

    /** Rango inclusivo de líneas (1-based) dentro de un archivo fuente emitido. */
    public record LineRange(int startLine, int endLine) {
        public LineRange {
            if (startLine < 1 || endLine < startLine) {
                throw new IllegalArgumentException("Rango de líneas inválido: " + startLine + ".." + endLine);
            }
        }

        public boolean contains(int line) {
            return line >= startLine && line <= endLine;
        }
    }

    /** Ubicación física de un byte de estado en el ATmega328P (registro r0..r31 o dirección SRAM 0x0100..0x08FF). */
    public record StorageLoc(boolean inRegister, int regIndex, int sramAddr, String displayName) {
        public static StorageLoc reg(int idx, String displayName) {
            return new StorageLoc(true, idx, -1, displayName);
        }

        public static StorageLoc sram(int addr, String displayName) {
            return new StorageLoc(false, -1, addr, displayName);
        }
    }

    /** Par de 16 bits (byte bajo y byte alto) para temporizadores u otros contadores de 16 bits. */
    public record PairLoc(StorageLoc lo, StorageLoc hi) {}

    /** Trazabilidad completa de una tarjeta (regla) por su índice original en {@code ProgramSpec.rules()}. */
    public record RuleTrace(
            int ruleIndex,
            String title,
            LineRange cRange,
            LineRange asmRange,
            Set<String> components,
            Set<String> pins,
            Set<String> vars,
            Set<String> phaseGroups,
            Set<String> timers) {

        public RuleTrace {
            components = Collections.unmodifiableSet(new LinkedHashSet<>(components));
            pins = Collections.unmodifiableSet(new LinkedHashSet<>(pins));
            vars = Collections.unmodifiableSet(new LinkedHashSet<>(vars));
            phaseGroups = Collections.unmodifiableSet(new LinkedHashSet<>(phaseGroups));
            timers = Collections.unmodifiableSet(new LinkedHashSet<>(timers));
        }
    }

    private final List<RuleTrace> rules;

    private TraceMap(List<RuleTrace> rules) {
        this.rules = List.copyOf(rules);
    }

    public static TraceMap build(
            ProgramSpec spec,
            Map<Integer, LineRange> cRanges,
            Map<Integer, LineRange> asmRanges) {
        List<RuleTrace> list = new ArrayList<>(spec.rules().size());
        for (int i = 0; i < spec.rules().size(); i++) {
            Rule r = spec.rules().get(i);
            var comps = new LinkedHashSet<String>();
            var pins = new LinkedHashSet<String>();
            var vars = new LinkedHashSet<String>();
            var groups = new LinkedHashSet<String>();
            var timers = new LinkedHashSet<String>();

            collectTrigger(r.trigger(), comps, timers);
            collectBool(r.guard(), comps, vars, groups, timers);
            for (Effect e : r.thenEffects()) collectEffect(e, comps, vars, groups, timers);
            for (Effect e : r.elseEffects()) collectEffect(e, comps, vars, groups, timers);

            for (Hardware.Component c : spec.components()) {
                if (comps.contains(c.name())) {
                    for (Hardware.Pin p : c.occupiedPins()) {
                        pins.add(p.label());
                    }
                }
            }

            list.add(new RuleTrace(
                    i,
                    r.title(),
                    cRanges.get(i),
                    asmRanges.get(i),
                    comps,
                    pins,
                    vars,
                    groups,
                    timers));
        }
        return new TraceMap(list);
    }

    public List<RuleTrace> rules() {
        return rules;
    }

    public Optional<RuleTrace> rule(int ruleIndex) {
        if (ruleIndex < 0 || ruleIndex >= rules.size()) return Optional.empty();
        return Optional.of(rules.get(ruleIndex));
    }

    public Optional<RuleTrace> ruleAtCLine(int lineNumber) {
        for (RuleTrace rt : rules) {
            if (rt.cRange() != null && rt.cRange().contains(lineNumber)) return Optional.of(rt);
        }
        return Optional.empty();
    }

    public Optional<RuleTrace> ruleAtAsmLine(int lineNumber) {
        for (RuleTrace rt : rules) {
            if (rt.asmRange() != null && rt.asmRange().contains(lineNumber)) return Optional.of(rt);
        }
        return Optional.empty();
    }

    private static void collectTrigger(Rule.Trigger t, Set<String> comps, Set<String> timers) {
        switch (t) {
            case Rule.OnPress p -> comps.add(p.buttonName());
            case Rule.OnRelease r -> comps.add(r.buttonName());
            case Rule.OnTimerExpired te -> timers.add(te.timerName());
            case Rule.OnUartRx u -> comps.add(u.uartName());
            default -> {}
        }
    }

    private static void collectBool(Expr.BoolExpr expr, Set<String> comps, Set<String> vars, Set<String> groups, Set<String> timers) {
        switch (expr) {
            case Expr.Pressed p -> comps.add(p.buttonName());
            case Expr.Compare c -> {
                collectValue(c.left(), comps, vars);
                collectValue(c.right(), comps, vars);
            }
            case Expr.InRange r -> collectValue(r.value(), comps, vars);
            case Expr.Modulo m -> vars.add(m.varName());
            case Expr.InPhase ip -> groups.add(ip.groupName());
            case Expr.TimerActive ta -> timers.add(ta.timerName());
            case Expr.UartByteEquals u -> comps.add(u.uartName());
            case Expr.Not n -> collectBool(n.inner(), comps, vars, groups, timers);
            case Expr.And a -> { for (var i : a.items()) collectBool(i, comps, vars, groups, timers); }
            case Expr.Or o -> { for (var i : o.items()) collectBool(i, comps, vars, groups, timers); }
            case Expr.Xor x -> {
                collectBool(x.left(), comps, vars, groups, timers);
                collectBool(x.right(), comps, vars, groups, timers);
            }
            default -> {}
        }
    }

    private static void collectValue(Expr.ValueExpr expr, Set<String> comps, Set<String> vars) {
        switch (expr) {
            case Expr.ReadVar rv -> vars.add(rv.varName());
            case Expr.ReadAdc8 a -> comps.add(a.analogName());
            case Expr.ReadAdc10 a -> comps.add(a.analogName());
            case Expr.ReadUartByte u -> comps.add(u.uartName());
            case Expr.Scale s -> collectValue(s.source(), comps, vars);
            default -> {}
        }
    }

    private static void collectEffect(Effect eff, Set<String> comps, Set<String> vars, Set<String> groups, Set<String> timers) {
        switch (eff) {
            case Effect.WriteLed w -> comps.add(w.ledName());
            case Effect.ToggleLed t -> comps.add(t.ledName());
            case Effect.WriteBcd b -> { comps.add(b.displayName()); collectValue(b.value(), comps, vars); }
            case Effect.WriteDecimal2 d -> {
                comps.add(d.unitsDisplay());
                comps.add(d.tensDisplay());
                collectValue(d.value(), comps, vars);
            }
            case Effect.WritePwm p -> { comps.add(p.pwmName()); collectValue(p.duty(), comps, vars); }
            case Effect.SendUartText s -> comps.add(s.uartName());
            case Effect.SendUartByte b -> { comps.add(b.uartName()); collectValue(b.value(), comps, vars); }
            case Effect.SetVar s -> { vars.add(s.varName()); collectValue(s.value(), comps, vars); }
            case Effect.StepVar s -> vars.add(s.varName());
            case Effect.ResetVar r -> vars.add(r.varName());
            case Effect.SetPhase sp -> groups.add(sp.groupName());
            case Effect.StartTimer st -> timers.add(st.timerName());
            case Effect.StopTimer st -> timers.add(st.timerName());
            default -> {}
        }
    }
}
