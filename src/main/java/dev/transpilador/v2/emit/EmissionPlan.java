package dev.transpilador.v2.emit;

import dev.transpilador.v2.arbiter.ResourceArbiter;
import dev.transpilador.v2.model.*;

import java.util.*;

/**
 * Análisis compartido previo a la emisión de C99 y AVR ASM.
 * Garantiza que ambos emisores solo incluyan los periféricos, variables, relojes y rutinas
 * estrictamente necesarios para el programa (proporcionalidad y simplicidad KISS).
 */
public final class EmissionPlan {
    public record PeriodicTask(int id, int ruleIndex, int periodMs) {}

    private final ProgramSpec spec;
    private final ResourceArbiter.ArbitrationResult arbitration;
    private final List<Rule> bootRules;
    private final List<Integer> bootRuleIndices;
    private final List<Rule> loopRules;
    private final List<Integer> loopRuleIndices;
    private final List<PeriodicTask> periodicTasks;
    private final Map<Integer, Integer> loopRuleToPeriodicId;
    private final LinkedHashSet<String> edgeButtons;
    private final LinkedHashSet<String> readButtons;
    private final LinkedHashSet<String> pressButtons;
    private final LinkedHashSet<String> releaseButtons;
    private final LinkedHashSet<String> usedAnalogInputs;
    private final LinkedHashSet<String> bootSnapshotAnalogs;
    private final LinkedHashSet<String> passSnapshotAnalogs;
    private final Map<Integer, Set<String>> ruleSnapshotAnalogs;
    private final LinkedHashSet<String> cachedAnalogs;
    private final LinkedHashSet<String> cachedAnalogs10Bit;
    private final LinkedHashSet<String> usedVars;
    private final LinkedHashSet<String> readVars;
    private final LinkedHashSet<String> usedPhaseGroups;
    private final LinkedHashSet<String> readPhaseGroups;
    private final LinkedHashSet<String> usedTimers;
    private final LinkedHashSet<String> expiredTimers;
    private final boolean usesAdc8;
    private final boolean usesAdc10;
    private final boolean emitsUartTx;
    private final boolean emitsUartRx;
    private final boolean readsUartRxByte;
    private final boolean usesDelayMs;
    private final boolean usesDecimal2;
    private final boolean usesModulo;
    private final boolean usesScale10;
    private final boolean usesStackExpr;
    private final boolean usesAdcInRange8;
    private final boolean usesAdcInRange10;
    private final EnumSet<Hardware.PwmTimer> usedPwmTimers;
    private final List<String> uartStrings;
    private final Map<String, Integer> uartStringIndex;

    private EmissionPlan(
            ProgramSpec spec,
            ResourceArbiter.ArbitrationResult arbitration,
            List<Rule> bootRules,
            List<Integer> bootRuleIndices,
            List<Rule> loopRules,
            List<Integer> loopRuleIndices,
            List<PeriodicTask> periodicTasks,
            Map<Integer, Integer> loopRuleToPeriodicId,
            LinkedHashSet<String> edgeButtons,
            LinkedHashSet<String> readButtons,
            LinkedHashSet<String> pressButtons,
            LinkedHashSet<String> releaseButtons,
            LinkedHashSet<String> usedAnalogInputs,
            LinkedHashSet<String> bootSnapshotAnalogs,
            LinkedHashSet<String> passSnapshotAnalogs,
            Map<Integer, Set<String>> ruleSnapshotAnalogs,
            LinkedHashSet<String> cachedAnalogs,
            LinkedHashSet<String> cachedAnalogs10Bit,
            LinkedHashSet<String> usedVars,
            LinkedHashSet<String> readVars,
            LinkedHashSet<String> usedPhaseGroups,
            LinkedHashSet<String> readPhaseGroups,
            LinkedHashSet<String> usedTimers,
            LinkedHashSet<String> expiredTimers,
            boolean usesAdc8,
            boolean usesAdc10,
            boolean emitsUartTx,
            boolean emitsUartRx,
            boolean readsUartRxByte,
            boolean usesDelayMs,
            boolean usesDecimal2,
            boolean usesModulo,
            boolean usesScale10,
            boolean usesStackExpr,
            boolean usesAdcInRange8,
            boolean usesAdcInRange10,
            EnumSet<Hardware.PwmTimer> usedPwmTimers,
            List<String> uartStrings,
            Map<String, Integer> uartStringIndex) {
        this.spec = spec;
        this.arbitration = arbitration;
        this.bootRules = List.copyOf(bootRules);
        this.bootRuleIndices = List.copyOf(bootRuleIndices);
        this.loopRules = List.copyOf(loopRules);
        this.loopRuleIndices = List.copyOf(loopRuleIndices);
        this.periodicTasks = List.copyOf(periodicTasks);
        this.loopRuleToPeriodicId = Map.copyOf(loopRuleToPeriodicId);
        this.edgeButtons = edgeButtons;
        this.readButtons = readButtons;
        this.pressButtons = pressButtons;
        this.releaseButtons = releaseButtons;
        this.usedAnalogInputs = usedAnalogInputs;
        this.bootSnapshotAnalogs = bootSnapshotAnalogs;
        this.passSnapshotAnalogs = passSnapshotAnalogs;
        this.ruleSnapshotAnalogs = Map.copyOf(ruleSnapshotAnalogs);
        this.cachedAnalogs = cachedAnalogs;
        this.cachedAnalogs10Bit = cachedAnalogs10Bit;
        this.usedVars = usedVars;
        this.readVars = readVars;
        this.usedPhaseGroups = usedPhaseGroups;
        this.readPhaseGroups = readPhaseGroups;
        this.usedTimers = usedTimers;
        this.expiredTimers = expiredTimers;
        this.usesAdc8 = usesAdc8;
        this.usesAdc10 = usesAdc10;
        this.emitsUartTx = emitsUartTx;
        this.emitsUartRx = emitsUartRx;
        this.readsUartRxByte = readsUartRxByte;
        this.usesDelayMs = usesDelayMs;
        this.usesDecimal2 = usesDecimal2;
        this.usesModulo = usesModulo;
        this.usesScale10 = usesScale10;
        this.usesStackExpr = usesStackExpr;
        this.usesAdcInRange8 = usesAdcInRange8;
        this.usesAdcInRange10 = usesAdcInRange10;
        this.usedPwmTimers = usedPwmTimers;
        this.uartStrings = List.copyOf(uartStrings);
        this.uartStringIndex = Map.copyOf(uartStringIndex);
    }

    public static EmissionPlan analyze(ProgramSpec spec) {
        var arb = new ResourceArbiter().analyze(spec);
        arb.requireValid();

        var bootRules = new ArrayList<Rule>();
        var bootRuleIndices = new ArrayList<Integer>();
        var loopRules = new ArrayList<Rule>();
        var loopRuleIndices = new ArrayList<Integer>();
        var periodicTasks = new ArrayList<PeriodicTask>();
        var loopRuleToPeriodicId = new LinkedHashMap<Integer, Integer>();
        var edgeButtons = new LinkedHashSet<String>();
        var readButtons = new LinkedHashSet<String>();
        var pressButtons = new LinkedHashSet<String>();
        var releaseButtons = new LinkedHashSet<String>();
        var usedAnalogs = new LinkedHashSet<String>();
        var usedVars = new LinkedHashSet<String>();
        var readVars = new LinkedHashSet<String>();
        var usedPhaseGroups = new LinkedHashSet<String>();
        var readPhaseGroups = new LinkedHashSet<String>();
        var usedTimers = new LinkedHashSet<String>();
        var expiredTimers = new LinkedHashSet<String>();
        var uartStrings = new ArrayList<String>();
        var uartStringIndex = new LinkedHashMap<String, Integer>();
        var bootAdcCounts = new LinkedHashMap<String, Integer>();
        var loopRuleAdcCounts = new ArrayList<Map<String, Integer>>();
        var loopRuleAdcReaders = new LinkedHashMap<String, Integer>();
        var adcUses10Bit = new LinkedHashSet<String>();
        boolean[] flags = new boolean[12]; // 0:adc10, 1:delayMs, 2:decimal2, 3:modulo, 4:scale10, 5:adc8, 6:uartTx, 7:uartRx, 8:stackExpr, 9:readsUartRxByte, 10:adcInRange8, 11:adcInRange10

        for (int origIdx = 0; origIdx < spec.rules().size(); origIdx++) {
            Rule r = spec.rules().get(origIdx);
            var ruleAdc = new LinkedHashMap<String, Integer>();
            boolean isBoot = r.trigger() instanceof Rule.OnBoot;
            if (isBoot) {
                bootRules.add(r);
                bootRuleIndices.add(origIdx);
            } else {
                int loopIdx = loopRules.size();
                loopRules.add(r);
                loopRuleIndices.add(origIdx);
                switch (r.trigger()) {
                    case Rule.OnPress p -> { edgeButtons.add(p.buttonName()); readButtons.add(p.buttonName()); pressButtons.add(p.buttonName()); }
                    case Rule.OnRelease rel -> { edgeButtons.add(rel.buttonName()); readButtons.add(rel.buttonName()); releaseButtons.add(rel.buttonName()); }
                    case Rule.OnTimerExpired te -> { usedTimers.add(te.timerName()); expiredTimers.add(te.timerName()); }
                    case Rule.OnUartRx ignored -> flags[7] = true;
                    case Rule.EveryMs e -> {
                        int pid = periodicTasks.size();
                        periodicTasks.add(new PeriodicTask(pid, loopIdx, e.periodMs()));
                        loopRuleToPeriodicId.put(loopIdx, pid);
                    }
                    default -> {}
                }
            }
            scanBool(r.guard(), readButtons, usedAnalogs, ruleAdc, adcUses10Bit, usedVars, readVars, usedPhaseGroups, readPhaseGroups, usedTimers, flags);
            for (Effect e : r.thenEffects()) scanEffect(e, usedAnalogs, ruleAdc, adcUses10Bit, usedVars, readVars, usedPhaseGroups, usedTimers, uartStrings, uartStringIndex, flags);
            for (Effect e : r.elseEffects()) scanEffect(e, usedAnalogs, ruleAdc, adcUses10Bit, usedVars, readVars, usedPhaseGroups, usedTimers, uartStrings, uartStringIndex, flags);

            if (isBoot) {
                ruleAdc.forEach((k, v) -> bootAdcCounts.merge(k, v, Integer::sum));
            } else {
                loopRuleAdcCounts.add(ruleAdc);
                for (String ch : ruleAdc.keySet()) {
                    loopRuleAdcReaders.merge(ch, 1, Integer::sum);
                }
            }
        }

        // Planificar capturas únicas de ADC para garantizar paridad exacta con ReferenceOracle
        var bootSnapshotAnalogs = new LinkedHashSet<String>();
        bootAdcCounts.forEach((ch, count) -> { if (count >= 2) bootSnapshotAnalogs.add(ch); });

        var passSnapshotAnalogs = new LinkedHashSet<String>();
        loopRuleAdcReaders.forEach((ch, readers) -> { if (readers >= 2) passSnapshotAnalogs.add(ch); });

        var ruleSnapshotAnalogs = new LinkedHashMap<Integer, Set<String>>();
        for (int i = 0; i < loopRuleAdcCounts.size(); i++) {
            var perRule = new LinkedHashSet<String>();
            for (var entry : loopRuleAdcCounts.get(i).entrySet()) {
                if (entry.getValue() >= 2 && !passSnapshotAnalogs.contains(entry.getKey())) {
                    perRule.add(entry.getKey());
                }
            }
            if (!perRule.isEmpty()) {
                ruleSnapshotAnalogs.put(i, Collections.unmodifiableSet(perRule));
            }
        }

        var cachedAnalogs = new LinkedHashSet<String>();
        cachedAnalogs.addAll(bootSnapshotAnalogs);
        cachedAnalogs.addAll(passSnapshotAnalogs);
        for (Set<String> s : ruleSnapshotAnalogs.values()) cachedAnalogs.addAll(s);

        var cachedAnalogs10Bit = new LinkedHashSet<String>();
        for (String ch : cachedAnalogs) {
            if (adcUses10Bit.contains(ch)) cachedAnalogs10Bit.add(ch);
        }

        // Recalcular flags exactos de helpers (leer_adc8, en_rango_u8, en_rango_u10) sin funciones muertas
        boolean[] helperFlags = new boolean[3]; // 0:callsLeerAdc8, 1:usesAdcInRange8, 2:usesAdcInRange10
        for (String ch : cachedAnalogs) {
            if (!cachedAnalogs10Bit.contains(ch)) helperFlags[0] = true;
        }
        for (Rule r : bootRules) {
            scanRuleAdcHelpers(r, bootSnapshotAnalogs, helperFlags);
        }
        for (int i = 0; i < loopRules.size(); i++) {
            Set<String> active = combineAdcSnapshots(passSnapshotAnalogs, ruleSnapshotAnalogs.get(i));
            scanRuleAdcHelpers(loopRules.get(i), active, helperFlags);
        }

        var usedPwmTimers = EnumSet.noneOf(Hardware.PwmTimer.class);
        for (Hardware.PwmOutput pwm : spec.componentsOf(Hardware.PwmOutput.class)) {
            usedPwmTimers.add(pwm.channel().timer());
        }

        return new EmissionPlan(
                spec, arb, bootRules, bootRuleIndices, loopRules, loopRuleIndices, periodicTasks, loopRuleToPeriodicId,
                edgeButtons, readButtons, pressButtons, releaseButtons, usedAnalogs,
                bootSnapshotAnalogs, passSnapshotAnalogs, ruleSnapshotAnalogs, cachedAnalogs, cachedAnalogs10Bit,
                usedVars, readVars, usedPhaseGroups, readPhaseGroups, usedTimers, expiredTimers,
                helperFlags[0], flags[0], flags[6], flags[7], flags[9], flags[1], flags[2], flags[3], flags[4], flags[8],
                helperFlags[1], helperFlags[2],
                usedPwmTimers, uartStrings, uartStringIndex);
    }

    private static Set<String> combineAdcSnapshots(Set<String> passSnap, Set<String> ruleSnap) {
        if (ruleSnap == null || ruleSnap.isEmpty()) return passSnap;
        if (passSnap.isEmpty()) return ruleSnap;
        var out = new LinkedHashSet<>(passSnap);
        out.addAll(ruleSnap);
        return out;
    }

    private static void scanRuleAdcHelpers(Rule r, Set<String> activeSnapshots, boolean[] helperFlags) {
        scanBoolAdcHelpers(r.guard(), activeSnapshots, helperFlags);
        for (Effect e : r.thenEffects()) scanEffectAdcHelpers(e, activeSnapshots, helperFlags);
        for (Effect e : r.elseEffects()) scanEffectAdcHelpers(e, activeSnapshots, helperFlags);
    }

    private static void scanBoolAdcHelpers(Expr.BoolExpr expr, Set<String> activeSnapshots, boolean[] helperFlags) {
        switch (expr) {
            case Expr.Compare c -> {
                scanValueAdcHelpers(c.left(), activeSnapshots, helperFlags);
                scanValueAdcHelpers(c.right(), activeSnapshots, helperFlags);
            }
            case Expr.InRange r -> {
                if (containsUncachedAdc(r.value(), activeSnapshots)) {
                    if (r.value().is10Bit()) helperFlags[2] = true;
                    else helperFlags[1] = true;
                }
                scanValueAdcHelpers(r.value(), activeSnapshots, helperFlags);
            }
            case Expr.Not n -> scanBoolAdcHelpers(n.inner(), activeSnapshots, helperFlags);
            case Expr.And a -> { for (var i : a.items()) scanBoolAdcHelpers(i, activeSnapshots, helperFlags); }
            case Expr.Or o -> { for (var i : o.items()) scanBoolAdcHelpers(i, activeSnapshots, helperFlags); }
            case Expr.Xor x -> {
                scanBoolAdcHelpers(x.left(), activeSnapshots, helperFlags);
                scanBoolAdcHelpers(x.right(), activeSnapshots, helperFlags);
            }
            default -> {}
        }
    }

    private static void scanValueAdcHelpers(Expr.ValueExpr expr, Set<String> activeSnapshots, boolean[] helperFlags) {
        switch (expr) {
            case Expr.ReadAdc8 a -> { if (!activeSnapshots.contains(a.analogName())) helperFlags[0] = true; }
            case Expr.Scale s -> scanValueAdcHelpers(s.source(), activeSnapshots, helperFlags);
            default -> {}
        }
    }

    private static void scanEffectAdcHelpers(Effect eff, Set<String> activeSnapshots, boolean[] helperFlags) {
        switch (eff) {
            case Effect.WriteBcd b -> scanValueAdcHelpers(b.value(), activeSnapshots, helperFlags);
            case Effect.WriteDecimal2 d -> scanValueAdcHelpers(d.value(), activeSnapshots, helperFlags);
            case Effect.WritePwm p -> scanValueAdcHelpers(p.duty(), activeSnapshots, helperFlags);
            case Effect.SendUartByte b -> scanValueAdcHelpers(b.value(), activeSnapshots, helperFlags);
            case Effect.SetVar s -> scanValueAdcHelpers(s.value(), activeSnapshots, helperFlags);
            default -> {}
        }
    }

    private static void scanBool(Expr.BoolExpr expr, Set<String> readButtons, Set<String> analogs,
                                 Map<String, Integer> ruleAdc, Set<String> adcUses10Bit,
                                 Set<String> usedVars, Set<String> readVars,
                                 Set<String> usedPhaseGroups, Set<String> readPhaseGroups,
                                 Set<String> usedTimers, boolean[] flags) {
        switch (expr) {
            case Expr.Pressed p -> readButtons.add(p.buttonName());
            case Expr.InPhase ip -> { usedPhaseGroups.add(ip.groupName()); readPhaseGroups.add(ip.groupName()); }
            case Expr.TimerActive ta -> usedTimers.add(ta.timerName());
            case Expr.UartByteEquals ignored -> flags[9] = true;
            case Expr.Compare c -> {
                if (c.left().is10Bit() || c.right().is10Bit()) flags[8] = true;
                scanValue(c.left(), analogs, ruleAdc, adcUses10Bit, usedVars, readVars, flags);
                scanValue(c.right(), analogs, ruleAdc, adcUses10Bit, usedVars, readVars, flags);
            }
            case Expr.InRange r -> {
                if (r.value().is10Bit()) flags[8] = true;
                scanValue(r.value(), analogs, ruleAdc, adcUses10Bit, usedVars, readVars, flags);
            }
            case Expr.Modulo m -> { usedVars.add(m.varName()); readVars.add(m.varName()); flags[3] = true; }
            case Expr.Not n -> scanBool(n.inner(), readButtons, analogs, ruleAdc, adcUses10Bit, usedVars, readVars, usedPhaseGroups, readPhaseGroups, usedTimers, flags);
            case Expr.And a -> { for (var i : a.items()) scanBool(i, readButtons, analogs, ruleAdc, adcUses10Bit, usedVars, readVars, usedPhaseGroups, readPhaseGroups, usedTimers, flags); }
            case Expr.Or o -> { for (var i : o.items()) scanBool(i, readButtons, analogs, ruleAdc, adcUses10Bit, usedVars, readVars, usedPhaseGroups, readPhaseGroups, usedTimers, flags); }
            case Expr.Xor x -> {
                if (!(x.right() instanceof Expr.Pressed || x.right() instanceof Expr.Truth)) flags[8] = true;
                scanBool(x.left(), readButtons, analogs, ruleAdc, adcUses10Bit, usedVars, readVars, usedPhaseGroups, readPhaseGroups, usedTimers, flags);
                scanBool(x.right(), readButtons, analogs, ruleAdc, adcUses10Bit, usedVars, readVars, usedPhaseGroups, readPhaseGroups, usedTimers, flags);
            }
            default -> {}
        }
    }

    private static void scanValue(Expr.ValueExpr expr, Set<String> analogs,
                                  Map<String, Integer> ruleAdc, Set<String> adcUses10Bit,
                                  Set<String> usedVars, Set<String> readVars, boolean[] flags) {
        switch (expr) {
            case Expr.ReadVar rv -> { usedVars.add(rv.varName()); readVars.add(rv.varName()); }
            case Expr.ReadAdc8 a -> {
                analogs.add(a.analogName());
                ruleAdc.merge(a.analogName(), 1, Integer::sum);
                flags[5] = true;
            }
            case Expr.ReadAdc10 a -> {
                analogs.add(a.analogName());
                ruleAdc.merge(a.analogName(), 1, Integer::sum);
                adcUses10Bit.add(a.analogName());
                flags[0] = true;
            }
            case Expr.ReadUartByte ignored -> flags[9] = true;
            case Expr.Scale s -> {
                scanValue(s.source(), analogs, ruleAdc, adcUses10Bit, usedVars, readVars, flags);
                if (s.inMax() == 1023) flags[4] = true;
            }
            default -> {}
        }
    }

    private static void scanEffect(Effect eff, Set<String> analogs,
                                   Map<String, Integer> ruleAdc, Set<String> adcUses10Bit,
                                   Set<String> usedVars, Set<String> readVars,
                                   Set<String> usedPhaseGroups, Set<String> usedTimers,
                                   List<String> uartStrings, Map<String, Integer> uartStringIndex, boolean[] flags) {
        switch (eff) {
            case Effect.WriteBcd b -> scanValue(b.value(), analogs, ruleAdc, adcUses10Bit, usedVars, readVars, flags);
            case Effect.WriteDecimal2 d -> { scanValue(d.value(), analogs, ruleAdc, adcUses10Bit, usedVars, readVars, flags); flags[2] = true; }
            case Effect.WritePwm p -> scanValue(p.duty(), analogs, ruleAdc, adcUses10Bit, usedVars, readVars, flags);
            case Effect.SendUartText s -> {
                flags[6] = true;
                uartStringIndex.computeIfAbsent(s.ascii(), k -> {
                    int idx = uartStrings.size();
                    uartStrings.add(k);
                    return idx;
                });
            }
            case Effect.SendUartByte b -> { flags[6] = true; scanValue(b.value(), analogs, ruleAdc, adcUses10Bit, usedVars, readVars, flags); }
            case Effect.SetVar s -> { usedVars.add(s.varName()); scanValue(s.value(), analogs, ruleAdc, adcUses10Bit, usedVars, readVars, flags); }
            case Effect.StepVar s -> { usedVars.add(s.varName()); readVars.add(s.varName()); }
            case Effect.ResetVar r -> usedVars.add(r.varName());
            case Effect.SetPhase sp -> usedPhaseGroups.add(sp.groupName());
            case Effect.StartTimer st -> usedTimers.add(st.timerName());
            case Effect.StopTimer st -> usedTimers.add(st.timerName());
            case Effect.DelayMs ignored -> flags[1] = true;
            default -> {}
        }
    }

    public ProgramSpec spec() { return spec; }
    public ResourceArbiter.ArbitrationResult arbitration() { return arbitration; }
    public List<Rule> bootRules() { return bootRules; }
    public int bootRuleOriginalIndex(int bootIndex) { return bootRuleIndices.get(bootIndex); }
    public List<Rule> loopRules() { return loopRules; }
    public int loopRuleOriginalIndex(int loopIndex) { return loopRuleIndices.get(loopIndex); }
    public List<PeriodicTask> periodicTasks() { return periodicTasks; }
    public Integer periodicIdForLoopRule(int loopRuleIndex) { return loopRuleToPeriodicId.get(loopRuleIndex); }
    public Set<String> edgeButtons() { return Collections.unmodifiableSet(edgeButtons); }
    public boolean readsButton(String buttonName) { return readButtons.contains(buttonName); }
    public boolean usesPress(String buttonName) { return pressButtons.contains(buttonName); }
    public boolean usesRelease(String buttonName) { return releaseButtons.contains(buttonName); }
    public Set<String> usedAnalogInputs() { return Collections.unmodifiableSet(usedAnalogInputs); }
    public Set<String> bootSnapshotAnalogs() { return Collections.unmodifiableSet(bootSnapshotAnalogs); }
    public Set<String> passSnapshotAnalogs() { return Collections.unmodifiableSet(passSnapshotAnalogs); }
    public Set<String> ruleSnapshotAnalogs(int loopRuleIndex) { return ruleSnapshotAnalogs.getOrDefault(loopRuleIndex, Set.of()); }
    public Set<String> activeAdcSnapshotsForLoopRule(int loopRuleIndex) {
        return combineAdcSnapshots(passSnapshotAnalogs, ruleSnapshotAnalogs.get(loopRuleIndex));
    }
    public Set<String> cachedAnalogs() { return Collections.unmodifiableSet(cachedAnalogs); }
    public boolean isCachedAnalog10Bit(String analogName) { return cachedAnalogs10Bit.contains(analogName); }
    public boolean readsAnyAdc() { return !usedAnalogInputs.isEmpty(); }
    public List<State.VarDecl> activeVars() { return spec.vars().stream().filter(v -> usedVars.contains(v.name())).toList(); }
    public boolean readsVar(String varName) { return readVars.contains(varName); }
    public List<State.PhaseGroup> activePhaseGroups() { return spec.phaseGroups().stream().filter(g -> usedPhaseGroups.contains(g.name())).toList(); }
    public List<State.TimerDecl> activeTimers() { return spec.timers().stream().filter(t -> usedTimers.contains(t.name())).toList(); }
    public boolean usesClock() { return !periodicTasks.isEmpty() || !usedTimers.isEmpty(); }

    /**
     * True cuando el bucle principal consiste exclusivamente en reglas periódicas (EveryMs)
     * sin flancos, temporizadores ni FSM: permite poner el tick de 1 ms al inicio del bucle
     * y descontar cada tarea periódica inline sin bandera per_X_ok adicional.
     */
    public boolean isPurePeriodicLoop() {
        return !periodicTasks.isEmpty()
                && periodicTasks.size() == loopRules.size()
                && edgeButtons.isEmpty()
                && usedTimers.isEmpty()
                && usedPhaseGroups.isEmpty()
                && passSnapshotAnalogs.isEmpty()
                && !emitsUartRx;
    }

    public boolean usesAdc() { return !usedAnalogInputs.isEmpty() || !spec.componentsOf(Hardware.AnalogInput.class).isEmpty(); }
    public boolean usesAdc8() { return usesAdc8; }
    public boolean usesAdc10() { return usesAdc10; }
    public boolean emitsUartTx() { return emitsUartTx; }
    public boolean emitsUartRx() { return emitsUartRx; }
    public boolean readsUartRxByte() { return readsUartRxByte; }
    public boolean usesTimerExpired(String timerName) { return expiredTimers.contains(timerName); }
    public boolean readsPhaseGroup(String groupName) { return readPhaseGroups.contains(groupName); }
    public boolean usesPwm() { return !usedPwmTimers.isEmpty(); }
    public Set<Hardware.PwmTimer> usedPwmTimers() { return Collections.unmodifiableSet(usedPwmTimers); }
    public Optional<Hardware.UartPort> uart() {
        var list = spec.componentsOf(Hardware.UartPort.class);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }
    public boolean usesUartTx() { return uart().map(u -> u.mode().usesTx()).orElse(false); }
    public boolean usesUartRx() { return uart().map(u -> u.mode().usesRx()).orElse(false); }
    public boolean usesDelayMs() { return usesDelayMs; }
    public boolean usesDecimal2() { return usesDecimal2; }
    public boolean usesModulo() { return usesModulo; }
    public boolean usesScale10() { return usesScale10; }
    public boolean usesAdcInRange8() { return usesAdcInRange8; }
    public boolean usesAdcInRange10() { return usesAdcInRange10; }
    public List<String> uartStrings() { return uartStrings; }

    /** True si la expresión realiza al menos una conversión ADC por hardware no almacenada en captura previa. */
    public static boolean containsUncachedAdc(Expr.ValueExpr expr, Set<String> activeSnapshots) {
        return switch (expr) {
            case Expr.ReadAdc8 a -> !activeSnapshots.contains(a.analogName());
            case Expr.ReadAdc10 a -> !activeSnapshots.contains(a.analogName());
            case Expr.Scale s -> containsUncachedAdc(s.source(), activeSnapshots);
            default -> false;
        };
    }

    /** True si la expresión realiza al menos una conversión ADC por hardware. */
    public static boolean containsAdc(Expr.ValueExpr expr) {
        return containsUncachedAdc(expr, Set.of());
    }
    public int uartStringId(String ascii) { return uartStringIndex.get(ascii); }

    /** True si el emisor ASM emitirá al menos una subrutina (rcall/ret) o push/pop y necesita pila. */
    public boolean needsAsmStack() {
        return usesClock() || usesDelayMs || readsAnyAdc() || usesPwm()
                || emitsUartTx || usesDecimal2 || usesModulo || usesScale10 || usesStackExpr
                || !spec.componentsOf(Hardware.BcdDisplay.class).isEmpty();
    }

    /** True si el emisor ASM necesita segmento .dseg en modo SRAM. */
    public boolean needsAsmSram() {
        return usesClock() || !edgeButtons.isEmpty() || emitsUartRx
                || !usedPhaseGroups.isEmpty()
                || !cachedAnalogs.isEmpty()
                || usedVars.size() > 4;
    }
}
