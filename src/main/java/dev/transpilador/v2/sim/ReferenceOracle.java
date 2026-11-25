package dev.transpilador.v2.sim;

import dev.transpilador.v2.emit.EmissionPlan;
import dev.transpilador.v2.model.*;

import java.util.*;

/**
 * Oráculo semántico independiente para el núcleo ortogonal de 5 bloques.
 * Evalúa ProgramSpec directamente desde su especificación matemática (sin pasar
 * por C ni por AVR ASM) para contrastar triple paridad: Oráculo ≡ C99 ≡ AVRASM2.
 */
public final class ReferenceOracle {

    public record InputFrame(
            Map<String, Boolean> buttonsPressed,
            Map<String, Integer> analogSample10,
            OptionalInt uartRxByte) {

        public InputFrame {
            buttonsPressed = Map.copyOf(buttonsPressed);
            analogSample10 = Map.copyOf(analogSample10);
            Objects.requireNonNull(uartRxByte, "uartRxByte");
        }

        public static InputFrame empty() {
            return new InputFrame(Map.of(), Map.of(), OptionalInt.empty());
        }

        public static InputFrame ofButtons(Map<String, Boolean> buttons) {
            return new InputFrame(buttons, Map.of(), OptionalInt.empty());
        }

        public static InputFrame ofAnalogMv(Map<String, Integer> mvByName) {
            Map<String, Integer> samples = new LinkedHashMap<>();
            mvByName.forEach((k, mv) -> samples.put(k, (mv * 1023) / 5000));
            return new InputFrame(Map.of(), samples, OptionalInt.empty());
        }
    }

    private final ProgramSpec spec;
    private final EmissionPlan plan;
    private final Map<String, Boolean> ledOn = new LinkedHashMap<>();
    private final Map<String, Integer> bcdDigits = new LinkedHashMap<>();
    private final Map<String, Integer> pwmDuties = new LinkedHashMap<>();
    private final Map<String, Integer> vars = new LinkedHashMap<>();
    private final Map<String, String> phases = new LinkedHashMap<>();
    private final Map<String, Integer> timerRem = new LinkedHashMap<>();
    private final Map<String, Boolean> timerExp = new LinkedHashMap<>();
    private final int[] perRem;
    private final boolean[] perFired;
    private final Map<String, Boolean> prevButtons = new LinkedHashMap<>();
    private final List<Integer> uartTx = new ArrayList<>();
    private long blockingDelayMs;

    public ReferenceOracle(ProgramSpec spec) {
        this.plan = EmissionPlan.analyze(spec);
        this.spec = spec;
        this.perRem = new int[plan.periodicTasks().size()];
        this.perFired = new boolean[plan.periodicTasks().size()];
        reset(InputFrame.empty());
    }

    public void reset(InputFrame initialInputs) {
        ledOn.clear();
        bcdDigits.clear();
        pwmDuties.clear();
        vars.clear();
        phases.clear();
        timerRem.clear();
        timerExp.clear();
        prevButtons.clear();
        uartTx.clear();
        blockingDelayMs = 0;

        for (Hardware.Led l : spec.componentsOf(Hardware.Led.class)) ledOn.put(l.name(), l.initialOn());
        for (Hardware.BcdDisplay d : spec.componentsOf(Hardware.BcdDisplay.class)) bcdDigits.put(d.name(), d.initialDigit());
        for (Hardware.PwmOutput p : spec.componentsOf(Hardware.PwmOutput.class)) pwmDuties.put(p.name(), p.initialDuty());
        for (State.VarDecl v : spec.vars()) vars.put(v.name(), v.initial());
        for (State.PhaseGroup g : spec.phaseGroups()) phases.put(g.name(), g.initialPhase());
        for (State.TimerDecl t : spec.timers()) {
            timerRem.put(t.name(), 0);
            timerExp.put(t.name(), false);
        }
        for (EmissionPlan.PeriodicTask pt : plan.periodicTasks()) {
            perRem[pt.id()] = pt.periodMs();
            perFired[pt.id()] = false;
        }
        for (String btn : plan.edgeButtons()) {
            prevButtons.put(btn, initialInputs.buttonsPressed().getOrDefault(btn, false));
        }

        // Ejecutar reglas de arranque (OnBoot)
        Map<String, String> phaseSnap = Map.copyOf(phases);
        for (Rule r : plan.bootRules()) {
            executeRule(r, initialInputs, Map.of(), Map.of(), phaseSnap, false, 0);
        }
    }

    /** Ejecuta una pasada completa del bucle principal (y avanza 1 tick cooperativo si usesClock). */
    public void stepPass(InputFrame inputs) {
        Map<String, Boolean> rising = new HashMap<>();
        Map<String, Boolean> falling = new HashMap<>();
        for (String btn : plan.edgeButtons()) {
            boolean cur = inputs.buttonsPressed().getOrDefault(btn, false);
            boolean prev = prevButtons.getOrDefault(btn, false);
            rising.put(btn, cur && !prev);
            falling.put(btn, !cur && prev);
            prevButtons.put(btn, cur);
        }

        Map<String, String> phaseSnapshot = Map.copyOf(phases);
        boolean uartRxOk = plan.emitsUartRx() && inputs.uartRxByte().isPresent();
        int uartRxByte = uartRxOk ? (inputs.uartRxByte().getAsInt() & 0xFF) : 0;

        for (int i = 0; i < plan.loopRules().size(); i++) {
            Rule rule = plan.loopRules().get(i);
            boolean active = switch (rule.trigger()) {
                case Rule.OnBoot ignored -> false;
                case Rule.Always ignored -> true;
                case Rule.OnPress p -> rising.getOrDefault(p.buttonName(), false);
                case Rule.OnRelease rel -> falling.getOrDefault(rel.buttonName(), false);
                case Rule.EveryMs ignored -> perFired[plan.periodicIdForLoopRule(i)];
                case Rule.OnTimerExpired te -> timerExp.getOrDefault(te.timerName(), false);
                case Rule.OnUartRx ignored -> uartRxOk;
            };
            if (active) {
                executeRule(rule, inputs, rising, falling, phaseSnapshot, uartRxOk, uartRxByte);
            }
        }

        if (plan.usesClock()) {
            for (State.TimerDecl t : spec.timers()) {
                timerExp.put(t.name(), false);
                int rem = timerRem.getOrDefault(t.name(), 0);
                if (rem > 0) {
                    rem--;
                    timerRem.put(t.name(), rem);
                    if (rem == 0) timerExp.put(t.name(), true);
                }
            }
            for (EmissionPlan.PeriodicTask pt : plan.periodicTasks()) {
                perFired[pt.id()] = false;
                perRem[pt.id()]--;
                if (perRem[pt.id()] == 0) {
                    perRem[pt.id()] = pt.periodMs();
                    perFired[pt.id()] = true;
                }
            }
        }
    }

    public void stepPasses(int count, InputFrame inputs) {
        for (int i = 0; i < count; i++) stepPass(inputs);
    }

    private void executeRule(Rule rule, InputFrame inputs, Map<String, Boolean> rising, Map<String, Boolean> falling,
                             Map<String, String> phaseSnapshot, boolean uartRxOk, int uartRxByte) {
        boolean guardOk = evalBool(rule.guard(), inputs, phaseSnapshot, uartRxByte);
        List<Effect> effects = guardOk ? rule.thenEffects() : rule.elseEffects();
        for (Effect eff : effects) {
            applyEffect(eff, inputs, uartRxByte);
        }
    }

    private void applyEffect(Effect eff, InputFrame inputs, int uartRxByte) {
        switch (eff) {
            case Effect.WriteLed w -> ledOn.put(w.ledName(), w.state() == Effect.LedState.ON);
            case Effect.ToggleLed t -> ledOn.put(t.ledName(), !ledOn.getOrDefault(t.ledName(), false));
            case Effect.WriteBcd b -> bcdDigits.put(b.displayName(), evalValue(b.value(), inputs, uartRxByte) & 0x0F);
            case Effect.WriteDecimal2 d -> {
                int v = evalValue(d.value(), inputs, uartRxByte) & 0xFF;
                bcdDigits.put(d.tensDisplay(), (v / 10) & 0x0F);
                bcdDigits.put(d.unitsDisplay(), (v % 10) & 0x0F);
            }
            case Effect.WritePwm p -> pwmDuties.put(p.pwmName(), evalValue(p.duty(), inputs, uartRxByte) & 0xFF);
            case Effect.SendUartText s -> {
                for (int i = 0; i < s.ascii().length(); i++) uartTx.add(s.ascii().charAt(i) & 0x7F);
            }
            case Effect.SendUartByte b -> uartTx.add(evalValue(b.value(), inputs, uartRxByte) & 0xFF);
            case Effect.SetVar s -> vars.put(s.varName(), evalValue(s.value(), inputs, uartRxByte) & 0xFF);
            case Effect.StepVar s -> {
                State.VarDecl decl = spec.var(s.varName()).orElseThrow();
                int cur = vars.get(decl.name());
                int next;
                if (s.direction() == Effect.StepDir.INC) {
                    if (decl.policy() == State.VarPolicy.WRAP) next = (cur >= decl.max()) ? decl.min() : cur + 1;
                    else next = (cur < decl.max()) ? cur + 1 : cur;
                } else {
                    if (decl.policy() == State.VarPolicy.WRAP) next = (cur <= decl.min()) ? decl.max() : cur - 1;
                    else next = (cur > decl.min()) ? cur - 1 : cur;
                }
                vars.put(decl.name(), next);
            }
            case Effect.ResetVar r -> vars.put(r.varName(), spec.var(r.varName()).orElseThrow().initial());
            case Effect.SetPhase sp -> phases.put(sp.groupName(), sp.phaseName());
            case Effect.StartTimer st -> timerRem.put(st.timerName(), st.durationMs());
            case Effect.StopTimer st -> timerRem.put(st.timerName(), 0);
            case Effect.DelayMs d -> blockingDelayMs += d.durationMs();
        }
    }

    private boolean evalBool(Expr.BoolExpr expr, InputFrame inputs, Map<String, String> phaseSnap, int uartRxByte) {
        return switch (expr) {
            case Expr.Truth t -> t.value();
            case Expr.Pressed p -> inputs.buttonsPressed().getOrDefault(p.buttonName(), false);
            case Expr.Compare c -> {
                int l = evalValue(c.left(), inputs, uartRxByte);
                int r = evalValue(c.right(), inputs, uartRxByte);
                yield switch (c.op()) {
                    case EQ -> l == r;
                    case NE -> l != r;
                    case LT -> l < r;
                    case LE -> l <= r;
                    case GT -> l > r;
                    case GE -> l >= r;
                };
            }
            case Expr.InRange r -> {
                int v = evalValue(r.value(), inputs, uartRxByte);
                yield v >= r.min() && v <= r.max();
            }
            case Expr.Modulo m -> {
                int v = vars.get(m.varName());
                yield (m.includeZero() || v != 0) && (v % m.divisor() == m.remainder());
            }
            case Expr.InPhase ip -> phaseSnap.get(ip.groupName()).equals(ip.phaseName());
            case Expr.TimerActive ta -> timerRem.getOrDefault(ta.timerName(), 0) > 0;
            case Expr.UartByteEquals u -> uartRxByte == u.expectedByte();
            case Expr.Not n -> !evalBool(n.inner(), inputs, phaseSnap, uartRxByte);
            case Expr.And a -> a.items().stream().allMatch(i -> evalBool(i, inputs, phaseSnap, uartRxByte));
            case Expr.Or o -> o.items().stream().anyMatch(i -> evalBool(i, inputs, phaseSnap, uartRxByte));
            case Expr.Xor x -> evalBool(x.left(), inputs, phaseSnap, uartRxByte)
                    ^ evalBool(x.right(), inputs, phaseSnap, uartRxByte);
        };
    }

    private int evalValue(Expr.ValueExpr expr, InputFrame inputs, int uartRxByte) {
        return switch (expr) {
            case Expr.ConstU8 c -> c.value();
            case Expr.ConstU10 c -> c.value();
            case Expr.ReadVar rv -> vars.get(rv.varName());
            case Expr.ReadAdc8 a -> (inputs.analogSample10().getOrDefault(a.analogName(), 0) >> 2) & 0xFF;
            case Expr.ReadAdc10 a -> inputs.analogSample10().getOrDefault(a.analogName(), 0) & 0x3FF;
            case Expr.ReadUartByte ignored -> uartRxByte & 0xFF;
            case Expr.Scale s -> {
                long raw = evalValue(s.source(), inputs, uartRxByte);
                int scaled = (int) ((raw * s.span()) >> s.shiftBits());
                yield (s.outMin() + scaled) & 0xFF;
            }
        };
    }

    public boolean isLedOn(String name) { return ledOn.getOrDefault(name, false); }
    public int bcd(String name) { return bcdDigits.getOrDefault(name, 0); }
    public int pwmDuty(String name) { return pwmDuties.getOrDefault(name, 0); }
    public int varValue(String name) { return vars.getOrDefault(name, 0); }
    public String phase(String group) { return phases.get(group); }
    public List<Integer> uartTxBytes() { return List.copyOf(uartTx); }
    public long blockingDelayMs() { return blockingDelayMs; }
    public String uartTxAscii() {
        StringBuilder sb = new StringBuilder(uartTx.size());
        for (int b : uartTx) sb.append((char) (b & 0xFF));
        return sb.toString();
    }
}
