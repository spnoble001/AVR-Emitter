package dev.transpilador.v2.emit;

import dev.transpilador.v2.model.*;

import java.util.*;

/**
 * Emisor AVRASM2 human-like para ATmega328P a partir del mismo núcleo ortogonal que CEmitter.
 *
 * Ofrece dos ejes configurables e independientes, ambos con paridad exacta verificada en simavr:
 *  - {@link InstructionProfile#ELEMENTARY} (por defecto): usa únicamente instrucciones elementales
 *    enseñadas en cursos introductorios (ldi, mov, clr, in, out, sbi, cbi, sbic, sbis, inc, dec,
 *    cpi, cp, breq, brne, rjmp, rcall, ret, nop…), evitando subi, sbci, cpc y tst.
 *  - {@link InstructionProfile#STANDARD} (opcional): aprovecha el set completo del ATmega328P
 *    (subi/sbci de 16 bits, cpc, tst) para máxima compacidad.
 *  - {@link StorageMode#REGISTERS_FIRST} (por defecto): asigna estado y contadores en registros
 *    libres sin emitir .dseg SRAM mientras quepan.
 *  - {@link StorageMode#SRAM_DATA_SEGMENT} (opcional): ubica estado y contadores en .dseg SRAM.
 */
public final class AsmEmitter {
    public enum InstructionProfile {
        ELEMENTARY("Elemental (curso)"),
        STANDARD("Estándar (ATmega328P)");

        private final String label;
        InstructionProfile(String label) { this.label = label; }
        public String label() { return label; }
        @Override public String toString() { return label; }
    }

    public enum StorageMode {
        REGISTERS_FIRST("Registros (sin SRAM si caben)"),
        SRAM_DATA_SEGMENT("SRAM (.dseg)");

        private final String label;
        StorageMode(String label) { this.label = label; }
        public String label() { return label; }
        @Override public String toString() { return label; }
    }

    // Registros persistentes nunca tocados por scratch (r0/r1 mul, r16..r21 temp, r30..r31 Z).
    // r22..r25 son registros altos sin .def previo en m328Pdef.inc; XL/XH y YL/YH ya vienen
    // predefinidos en m328Pdef.inc y se usan directo sin duplicar .def cuando hacen falta más pares.
    private static final List<String> NAMED_HIGH_REGS = List.of("r22", "r23", "r24", "r25");
    private static final List<String[]> POINTER_HIGH_PAIRS = List.of(
            new String[]{"XL", "XH"},
            new String[]{"YL", "YH"});
    private static final List<String> LOW_REGS = List.of(
            "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15");

    public record EmitOutput(
            String code,
            Map<Integer, TraceMap.LineRange> ruleLines,
            Map<String, String> registerAliases,
            Map<String, TraceMap.StorageLoc> varLocations,
            Map<String, TraceMap.StorageLoc> phaseLocations,
            Map<String, TraceMap.PairLoc> timerRemLocations) {
        public EmitOutput {
            ruleLines = Collections.unmodifiableMap(new LinkedHashMap<>(ruleLines));
            registerAliases = Collections.unmodifiableMap(new LinkedHashMap<>(registerAliases));
            varLocations = Collections.unmodifiableMap(new LinkedHashMap<>(varLocations));
            phaseLocations = Collections.unmodifiableMap(new LinkedHashMap<>(phaseLocations));
            timerRemLocations = Collections.unmodifiableMap(new LinkedHashMap<>(timerRemLocations));
        }
    }

    public String emit(ProgramSpec spec) {
        return emit(EmissionPlan.analyze(spec), InstructionProfile.ELEMENTARY, StorageMode.REGISTERS_FIRST);
    }

    public String emit(ProgramSpec spec, StorageMode mode) {
        return emit(EmissionPlan.analyze(spec), InstructionProfile.ELEMENTARY, mode);
    }

    public String emit(ProgramSpec spec, InstructionProfile profile, StorageMode mode) {
        return emit(EmissionPlan.analyze(spec), profile, mode);
    }

    public String emit(EmissionPlan plan) {
        return emit(plan, InstructionProfile.ELEMENTARY, StorageMode.REGISTERS_FIRST);
    }

    public String emit(EmissionPlan plan, StorageMode mode) {
        return emit(plan, InstructionProfile.ELEMENTARY, mode);
    }

    public String emit(EmissionPlan plan, InstructionProfile profile, StorageMode mode) {
        return emitWithTrace(plan, profile, mode).code();
    }

    public EmitOutput emitWithTrace(ProgramSpec spec, InstructionProfile profile, StorageMode mode) {
        return emitWithTrace(EmissionPlan.analyze(spec), profile, mode);
    }

    public EmitOutput emitWithTrace(EmissionPlan plan, InstructionProfile profile, StorageMode mode) {
        return new Writer(plan, Objects.requireNonNull(profile, "profile"), Objects.requireNonNull(mode, "mode")).generateWithTrace();
    }

    private record Slot(String symbol, String reg, boolean highReg, boolean emitDef, String comment) {
        boolean isReg() { return reg != null; }
    }

    private record PairSlot(Slot lo, Slot hi) {
        boolean bothHighRegs() { return lo.highReg() && hi.highReg(); }
        boolean bothRegs() { return lo.isReg() && hi.isReg(); }
    }

    private record PeriodicPlan(Kind kind, int a, int b) {
        enum Kind { SINGLE_8BIT, FACTORED_DEC, GENERIC_16BIT }
    }

    private static final class Writer {
        private final EmissionPlan plan;
        private final ProgramSpec spec;
        private final InstructionProfile profile;
        private final StorageMode mode;
        private final StringBuilder out = new StringBuilder();

        private final Deque<String> freeHigh = new ArrayDeque<>(NAMED_HIGH_REGS);
        private final Deque<String[]> freePtrPairs = new ArrayDeque<>(POINTER_HIGH_PAIRS);
        private final Deque<String> freeLow = new ArrayDeque<>(LOW_REGS);
        private final List<Slot> regSlots = new ArrayList<>();
        private final List<Slot> sramSlots = new ArrayList<>();

        private final Map<String, Slot> varSlots = new LinkedHashMap<>();
        private final Map<String, Slot> phaseCurSlots = new LinkedHashMap<>();
        private final Map<String, Slot> phasePastSlots = new LinkedHashMap<>();
        private final Map<String, Slot> prevBtnSlots = new LinkedHashMap<>();
        private final Map<String, Slot> riseBtnSlots = new LinkedHashMap<>();
        private final Map<String, Slot> fallBtnSlots = new LinkedHashMap<>();
        private final Map<String, PairSlot> timerRemSlots = new LinkedHashMap<>();
        private final Map<String, Slot> timerExpSlots = new LinkedHashMap<>();
        private final Map<Integer, PeriodicPlan> periodicPlans = new LinkedHashMap<>();
        private final Map<Integer, Slot> periodicSingleSlots = new LinkedHashMap<>();
        private final Map<Integer, PairSlot> periodicPairSlots = new LinkedHashMap<>();
        private final Map<Integer, Slot> periodicOkSlots = new LinkedHashMap<>();
        private final Map<String, Slot> adc8Slots = new LinkedHashMap<>();
        private final Map<String, PairSlot> adc10Slots = new LinkedHashMap<>();
        private Slot uartRxOkSlot;
        private Slot uartRxByteSlot;
        private Set<String> activeAdcSnapshots = Set.of();

        private int labelSeq = 0;

        Writer(EmissionPlan plan, InstructionProfile profile, StorageMode mode) {
            this.plan = plan;
            this.spec = plan.spec();
            this.profile = profile;
            this.mode = mode;
            allocateAllSlots();
        }

        private boolean elementary() {
            return profile == InstructionProfile.ELEMENTARY;
        }

        private void allocateAllSlots() {
            boolean purePeriodic = plan.isPurePeriodicLoop();

            // Planificar cada tarea periódica (8-bit si <=255 ms, factorizada A×B en elemental, o 16-bit)
            for (EmissionPlan.PeriodicTask pt : plan.periodicTasks()) {
                periodicPlans.put(pt.id(), planPeriodic(pt.periodMs()));
            }

            // 1. Pares de 16 bits (temporizadores y tareas periódicas > 255 ms)
            for (State.TimerDecl t : plan.activeTimers()) {
                timerRemSlots.put(t.name(), allocHighPair("tmr_" + t.name() + "_lo", "tmr_" + t.name() + "_hi"));
            }
            for (EmissionPlan.PeriodicTask pt : plan.periodicTasks()) {
                PeriodicPlan pp = periodicPlans.get(pt.id());
                if (pp.kind() == PeriodicPlan.Kind.SINGLE_8BIT) {
                    periodicSingleSlots.put(pt.id(), allocPreferHigh("per_" + pt.id()));
                } else if (pp.kind() == PeriodicPlan.Kind.FACTORED_DEC) {
                    periodicPairSlots.put(pt.id(), allocHighPair("per_" + pt.id() + "_int", "per_" + pt.id() + "_ext"));
                } else {
                    periodicPairSlots.put(pt.id(), allocHighPair("per_" + pt.id() + "_lo", "per_" + pt.id() + "_hi"));
                }
            }

            // 2. Variables de usuario activas
            for (State.VarDecl v : plan.activeVars()) {
                varSlots.put(v.name(), allocPreferHigh("var_" + v.name()));
            }

            // 3. Fases FSM activas
            for (State.PhaseGroup g : plan.activePhaseGroups()) {
                phaseCurSlots.put(g.name(), allocPreferHigh("fase_" + g.name()));
                if (plan.readsPhaseGroup(g.name())) {
                    phasePastSlots.put(g.name(), allocPreferHigh("fpas_" + g.name()));
                }
            }

            // 4. Banderas de 1 bit / estado auxiliar
            for (State.TimerDecl t : plan.activeTimers()) {
                if (plan.usesTimerExpired(t.name())) {
                    timerExpSlots.put(t.name(), allocPreferLow("exp_" + t.name()));
                }
            }
            if (!purePeriodic) {
                for (EmissionPlan.PeriodicTask pt : plan.periodicTasks()) {
                    periodicOkSlots.put(pt.id(), allocPreferLow("per_" + pt.id() + "_ok"));
                }
            }
            for (String btn : plan.edgeButtons()) {
                prevBtnSlots.put(btn, allocPreferLow("prev_" + btn));
                if (plan.usesPress(btn)) riseBtnSlots.put(btn, allocPreferLow("rise_" + btn));
                if (plan.usesRelease(btn)) fallBtnSlots.put(btn, allocPreferLow("fall_" + btn));
            }
            if (plan.emitsUartRx()) {
                uartRxOkSlot = allocPreferLow("uart_rx_ok");
                if (plan.readsUartRxByte()) {
                    uartRxByteSlot = allocPreferLow("uart_rx_byte");
                }
            }
            for (String analog : plan.cachedAnalogs()) {
                if (plan.isCachedAnalog10Bit(analog)) {
                    Slot lo = allocPreferLow("adc_" + analog + "_lo");
                    Slot hi = allocPreferLow("adc_" + analog + "_hi");
                    adc10Slots.put(analog, new PairSlot(lo, hi));
                } else {
                    adc8Slots.put(analog, allocPreferLow("adc_" + analog));
                }
            }
        }

        private PeriodicPlan planPeriodic(int periodMs) {
            if (periodMs <= 255) {
                return new PeriodicPlan(PeriodicPlan.Kind.SINGLE_8BIT, periodMs, 0);
            }
            if (elementary()) {
                // Buscar factorización limpia A x B (ej. 500 = 250 x 2, 1000 = 250 x 4)
                for (int pref : new int[]{250, 200, 100, 50, 25, 20, 10}) {
                    if (periodMs % pref == 0 && periodMs / pref >= 2 && periodMs / pref <= 255) {
                        return new PeriodicPlan(PeriodicPlan.Kind.FACTORED_DEC, pref, periodMs / pref);
                    }
                }
                for (int a = 255; a >= 2; a--) {
                    if (periodMs % a == 0 && periodMs / a >= 2 && periodMs / a <= 255) {
                        return new PeriodicPlan(PeriodicPlan.Kind.FACTORED_DEC, a, periodMs / a);
                    }
                }
            }
            return new PeriodicPlan(PeriodicPlan.Kind.GENERIC_16BIT, periodMs, 0);
        }

        private PairSlot allocHighPair(String loBase, String hiBase) {
            if (mode == StorageMode.REGISTERS_FIRST) {
                if (freeHigh.size() >= 4 || (freePtrPairs.isEmpty() && freeHigh.size() >= 2)) {
                    String hiReg = freeHigh.removeLast();
                    String loReg = freeHigh.removeLast();
                    Slot lo = new Slot(loBase, loReg, true, true, null);
                    Slot hi = new Slot(hiBase, hiReg, true, true, null);
                    regSlots.add(lo);
                    regSlots.add(hi);
                    return new PairSlot(lo, hi);
                }
                if (!freePtrPairs.isEmpty()) {
                    String[] pair = freePtrPairs.removeFirst();
                    Slot lo = new Slot(pair[0], pair[0], true, false, loBase + " en " + pair[0]);
                    Slot hi = new Slot(pair[1], pair[1], true, false, hiBase + " en " + pair[1]);
                    regSlots.add(lo);
                    regSlots.add(hi);
                    return new PairSlot(lo, hi);
                }
            }
            Slot lo = allocSram(loBase);
            Slot hi = allocSram(hiBase);
            return new PairSlot(lo, hi);
        }

        private Slot allocPreferHigh(String base) {
            if (mode == StorageMode.REGISTERS_FIRST) {
                if (!freeHigh.isEmpty()) {
                    Slot s = new Slot(base, freeHigh.removeFirst(), true, true, null);
                    regSlots.add(s);
                    return s;
                }
                if (!freeLow.isEmpty()) {
                    Slot s = new Slot(base, freeLow.removeFirst(), false, true, null);
                    regSlots.add(s);
                    return s;
                }
            }
            return allocSram(base);
        }

        private Slot allocPreferLow(String base) {
            if (mode == StorageMode.REGISTERS_FIRST) {
                if (!freeLow.isEmpty()) {
                    Slot s = new Slot(base, freeLow.removeFirst(), false, true, null);
                    regSlots.add(s);
                    return s;
                }
                if (!freeHigh.isEmpty()) {
                    Slot s = new Slot(base, freeHigh.removeFirst(), true, true, null);
                    regSlots.add(s);
                    return s;
                }
            }
            return allocSram(base);
        }

        private Slot allocSram(String base) {
            Slot s = new Slot("sram_" + base, null, false, false, null);
            sramSlots.add(s);
            return s;
        }

        private String fresh(String prefix) {
            return prefix + "_" + (labelSeq++);
        }

        EmitOutput generateWithTrace() {
            Map<Integer, TraceMap.LineRange> ruleLines = new LinkedHashMap<>();

            out.append("; ===================================================================\n");
            out.append("; Proyecto: ").append(sanitizeAsmComment(spec.name())).append('\n');
            out.append("; Microcontrolador: ATmega328P @ ").append(spec.fCpuHz()).append(" Hz (AVRASM2)\n");
            out.append("; Perfil: ").append(elementary() ? "Elemental (sin subi/sbci/cpc/tst)" : "Estándar ATmega328P")
                    .append(" · ").append(sramSlots.isEmpty() ? "sin SRAM" : "registros + SRAM").append('\n');
            out.append("; ===================================================================\n");
            out.append(".include \"m328Pdef.inc\"\n\n");

            emitDefinitions();
            emitDataSegment();

            out.append(".cseg\n");
            out.append(".org 0x0000\n");
            out.append("    rjmp inicio\n");
            out.append(".org INT_VECTORS_SIZE\n\n");

            out.append("inicio:\n");
            out.append("    cli\n");
            if (plan.needsAsmStack()) {
                out.append("    ; Inicializar puntero de pila para llamadas a subrutinas\n");
                out.append("    ldi  r16, high(RAMEND)\n");
                out.append("    out  SPH, r16\n");
                out.append("    ldi  r16, low(RAMEND)\n");
                out.append("    out  SPL, r16\n");
            }

            emitHardwareInit();
            emitStateInit();

            if (!plan.bootRules().isEmpty()) {
                out.append("\n    ; --- Reglas de arranque ---\n");
                emitAdcCaptures(plan.bootSnapshotAnalogs());
                for (int i = 0; i < plan.bootRules().size(); i++) {
                    int startLine = countNewlines(out) + 2;
                    emitRule(plan.bootRules().get(i), -1);
                    int endLine = Math.max(startLine, countNewlines(out));
                    ruleLines.put(plan.bootRuleOriginalIndex(i), new TraceMap.LineRange(startLine, endLine));
                }
            }

            out.append("\n; ===================================================================\n");
            out.append("; Bucle principal\n");
            out.append("; ===================================================================\n");
            out.append("bucle:\n");
            if (plan.isPurePeriodicLoop()) {
                out.append("    rcall retardo_1ms\n");
                for (int i = 0; i < plan.loopRules().size(); i++) {
                    int startLine = countNewlines(out) + 2;
                    emitPurePeriodicRule(plan.loopRules().get(i), i);
                    int endLine = Math.max(startLine, countNewlines(out));
                    ruleLines.put(plan.loopRuleOriginalIndex(i), new TraceMap.LineRange(startLine, endLine));
                }
            } else {
                emitPassCaptures();

                for (int i = 0; i < plan.loopRules().size(); i++) {
                    int startLine = countNewlines(out) + 2;
                    emitRule(plan.loopRules().get(i), i);
                    int endLine = Math.max(startLine, countNewlines(out));
                    ruleLines.put(plan.loopRuleOriginalIndex(i), new TraceMap.LineRange(startLine, endLine));
                }

                if (plan.usesClock()) {
                    emitCooperativeTick();
                } else if (plan.loopRules().isEmpty() && plan.edgeButtons().isEmpty() && !plan.emitsUartRx()) {
                    out.append("    nop ; mantener CPU activa para periféricos\n");
                }
            }

            out.append("    rjmp bucle\n");

            emitSubroutines();
            emitFlashStrings();
            String finalCode = upgradeLongRelativeBranches(out.toString());

            Map<String, String> aliases = new LinkedHashMap<>();
            for (Slot s : regSlots) {
                int rNum = registerNumber(s.reg());
                if (rNum >= 0) {
                    String label = s.emitDef()
                            ? s.symbol()
                            : (s.comment() != null ? s.comment() : s.symbol());
                    aliases.put("r" + rNum, label);
                }
            }
            Map<String, TraceMap.StorageLoc> varLocs = new LinkedHashMap<>();
            varSlots.forEach((k, s) -> varLocs.put(k, toStorageLoc(s)));

            Map<String, TraceMap.StorageLoc> phaseLocs = new LinkedHashMap<>();
            phaseCurSlots.forEach((k, s) -> phaseLocs.put(k, toStorageLoc(s)));

            Map<String, TraceMap.PairLoc> timerLocs = new LinkedHashMap<>();
            timerRemSlots.forEach((k, p) -> timerLocs.put(k, new TraceMap.PairLoc(toStorageLoc(p.lo()), toStorageLoc(p.hi()))));

            return new EmitOutput(finalCode, ruleLines, aliases, varLocs, phaseLocs, timerLocs);
        }

        private TraceMap.StorageLoc toStorageLoc(Slot s) {
            if (s.isReg()) {
                int idx = registerNumber(s.reg());
                return TraceMap.StorageLoc.reg(idx, s.reg() + " (" + s.symbol() + ")");
            }
            int sramIdx = sramSlots.indexOf(s);
            int addr = 0x0100 + Math.max(0, sramIdx);
            return TraceMap.StorageLoc.sram(addr, s.symbol() + " @ 0x" + String.format("%04X", addr));
        }

        private static int registerNumber(String reg) {
            if (reg == null) return -1;
            return switch (reg) {
                case "XL" -> 26;
                case "XH" -> 27;
                case "YL" -> 28;
                case "YH" -> 29;
                case "ZL" -> 30;
                case "ZH" -> 31;
                default -> reg.startsWith("r") ? Integer.parseInt(reg.substring(1)) : -1;
            };
        }

        private static int countNewlines(StringBuilder sb) {
            int n = 0;
            for (int i = 0; i < sb.length(); i++) {
                if (sb.charAt(i) == '\n') n++;
            }
            return n;
        }

        private void emitDefinitions() {
            boolean any = !regSlots.isEmpty() || !plan.activePhaseGroups().isEmpty();
            if (!any) return;

            out.append("; --- Registros y constantes simbólicas ---\n");
            for (Slot s : regSlots) {
                if (s.emitDef()) {
                    out.append(".def ").append(s.symbol()).append(" = ").append(s.reg()).append('\n');
                } else if (s.comment() != null) {
                    out.append("; ").append(s.comment()).append(" (predefinido en m328Pdef.inc)\n");
                }
            }
            for (State.PhaseGroup g : plan.activePhaseGroups()) {
                for (int i = 0; i < g.phases().size(); i++) {
                    out.append(".equ ").append(phaseEqu(g.name(), g.phases().get(i)))
                            .append(" = ").append(i).append('\n');
                }
            }
            out.append('\n');
        }

        private void emitDataSegment() {
            if (sramSlots.isEmpty()) return;

            out.append(".dseg\n");
            out.append(".org SRAM_START\n");
            for (Slot s : sramSlots) {
                out.append(s.symbol()).append(": .byte 1\n");
            }
            out.append('\n');
        }

        private void emitHardwareInit() {
            out.append("\n    ; --- Configuración de pines y periféricos ---\n");
            for (Hardware.Button b : spec.componentsOf(Hardware.Button.class)) {
                out.append("    ; Botón ").append(b.name()).append(" en ").append(b.pin())
                        .append(b.connection() == Hardware.Connection.GND ? " (pull-up interno)\n" : " (pull-down externo)\n");
                out.append("    cbi  ").append(b.pin().port().ddr()).append(", ").append(b.pin().bit()).append('\n');
                if (b.connection() == Hardware.Connection.GND) {
                    out.append("    sbi  ").append(b.pin().port().portReg()).append(", ").append(b.pin().bit()).append('\n');
                } else {
                    out.append("    cbi  ").append(b.pin().port().portReg()).append(", ").append(b.pin().bit()).append('\n');
                }
            }

            for (Hardware.Led l : spec.componentsOf(Hardware.Led.class)) {
                out.append("    ; LED ").append(l.name()).append(" en ").append(l.pin()).append('\n');
                boolean highInit = (l.initialOn() == l.activeHigh());
                out.append(highInit ? "    sbi  " : "    cbi  ")
                        .append(l.pin().port().portReg()).append(", ").append(l.pin().bit()).append('\n');
                out.append("    sbi  ").append(l.pin().port().ddr()).append(", ").append(l.pin().bit()).append('\n');
            }

            for (Hardware.BcdDisplay d : spec.componentsOf(Hardware.BcdDisplay.class)) {
                out.append("    ; Display BCD ").append(d.name()).append(" desde ").append(d.firstPin()).append('\n');
                out.append("    ldi  r16, ").append(d.initialDigit()).append('\n');
                out.append("    rcall mostrar_bcd_").append(d.name()).append('\n');
                for (int bit = d.firstPin().bit(); bit < d.firstPin().bit() + 4; bit++) {
                    out.append("    sbi  ").append(d.firstPin().port().ddr()).append(", ").append(bit).append('\n');
                }
            }

            if (plan.readsAnyAdc()) {
                var clk = spec.clockFreq();
                out.append("    ; ADC 10 bits: referencia AVcc (5 V), prescaler /").append(clk.adcPrescaler())
                        .append(" (").append(clk.adcClockKHz()).append(" kHz @ ").append(clk.label()).append(")\n");
                int didrMask = 0;
                for (Hardware.AnalogInput a : spec.componentsOf(Hardware.AnalogInput.class)) {
                    out.append("    cbi  DDRC, ").append(a.channel()).append('\n');
                    out.append("    cbi  PORTC, ").append(a.channel()).append('\n');
                    didrMask |= (1 << a.channel());
                }
                out.append("    lds  r16, DIDR0\n");
                out.append("    ori  r16, 0x").append(String.format("%02X", didrMask)).append('\n');
                out.append("    sts  DIDR0, r16\n");
                out.append("    ldi  r16, (1 << REFS0)\n");
                out.append("    sts  ADMUX, r16\n");
                out.append("    ldi  r16, (1 << ADEN) | ").append(clk.adcPrescalerExpr()).append('\n');
                out.append("    sts  ADCSRA, r16\n");
            }

            if (plan.usesPwm()) {
                var clk = spec.clockFreq();
                for (Hardware.PwmTimer timer : plan.usedPwmTimers()) {
                    String cs = clk.pwmClockSelectExpr(timer);
                    switch (timer) {
                        case TIMER0 -> {
                            out.append("    ; Timer0 Fast PWM 8-bit, prescaler /").append(clk.pwmPrescaler())
                                    .append(" (≈").append(clk.pwmFrequencyHz()).append(" Hz @ ").append(clk.label()).append(")\n");
                            out.append("    ldi  r16, (1 << WGM01) | (1 << WGM00)\n");
                            out.append("    out  TCCR0A, r16\n");
                            out.append("    ldi  r16, ").append(cs).append('\n');
                            out.append("    out  TCCR0B, r16\n");
                        }
                        case TIMER1 -> {
                            out.append("    ; Timer1 Fast PWM 8-bit, prescaler /").append(clk.pwmPrescaler())
                                    .append(" (≈").append(clk.pwmFrequencyHz()).append(" Hz @ ").append(clk.label()).append(")\n");
                            out.append("    ldi  r16, (1 << WGM10)\n");
                            out.append("    sts  TCCR1A, r16\n");
                            out.append("    ldi  r16, (1 << WGM12) | ").append(cs).append('\n');
                            out.append("    sts  TCCR1B, r16\n");
                        }
                        case TIMER2 -> {
                            out.append("    ; Timer2 Fast PWM 8-bit, prescaler /").append(clk.pwmPrescaler())
                                    .append(" (≈").append(clk.pwmFrequencyHz()).append(" Hz @ ").append(clk.label()).append(")\n");
                            out.append("    ldi  r16, (1 << WGM21) | (1 << WGM20)\n");
                            out.append("    sts  TCCR2A, r16\n");
                            out.append("    ldi  r16, ").append(cs).append('\n');
                            out.append("    sts  TCCR2B, r16\n");
                        }
                    }
                }
                for (Hardware.PwmOutput pwm : spec.componentsOf(Hardware.PwmOutput.class)) {
                    out.append("    ; Salida PWM ").append(pwm.name()).append(" en ").append(pwm.pin()).append('\n');
                    out.append("    sbi  ").append(pwm.pin().port().ddr()).append(", ").append(pwm.pin().bit()).append('\n');
                    out.append("    ldi  r16, ").append(pwm.initialDuty()).append('\n');
                    out.append("    rcall fijar_pwm_").append(pwm.name()).append('\n');
                }
            }

            plan.uart().ifPresent(u -> {
                int ubrr = u.baud().ubrrU2x(spec.fCpuHz());
                out.append("    ; USART0 ").append(u.baud().bps()).append(" bps 8N1 @ ")
                        .append(spec.clockFreq().label()).append(" (U2X0=1, UBRR0=").append(ubrr).append(")\n");
                out.append("    ldi  r16, (1 << U2X0)\n");
                out.append("    sts  UCSR0A, r16\n");
                if ((ubrr >> 8) == 0) {
                    out.append("    clr  r16\n");
                } else {
                    out.append("    ldi  r16, ").append((ubrr >> 8) & 0x0F).append('\n');
                }
                out.append("    sts  UBRR0H, r16\n");
                out.append("    ldi  r16, ").append(ubrr & 0xFF).append('\n');
                out.append("    sts  UBRR0L, r16\n");
                out.append("    ldi  r16, (1 << UCSZ01) | (1 << UCSZ00)\n");
                out.append("    sts  UCSR0C, r16\n");
                String bits = switch (u.mode()) {
                    case FULL_DUPLEX -> "(1 << RXEN0) | (1 << TXEN0)";
                    case HALF_DUPLEX, RX_ONLY -> "(1 << RXEN0)";
                    case TX_ONLY -> "(1 << TXEN0)";
                };
                out.append("    ldi  r16, ").append(bits).append('\n');
                out.append("    sts  UCSR0B, r16\n");
            });
        }

        private void emitStateInit() {
            boolean any = !plan.activeVars().isEmpty() || !plan.activePhaseGroups().isEmpty()
                    || !plan.edgeButtons().isEmpty() || !plan.activeTimers().isEmpty()
                    || !plan.periodicTasks().isEmpty() || plan.emitsUartRx();
            if (!any) return;

            out.append("\n    ; --- Inicialización de estado ---\n");
            for (State.VarDecl v : plan.activeVars()) {
                emitStoreImm(varSlots.get(v.name()), v.initial());
            }
            for (State.PhaseGroup g : plan.activePhaseGroups()) {
                String eq = phaseEqu(g.name(), g.initialPhase());
                emitStoreExpr(phaseCurSlots.get(g.name()), eq);
                Slot past = phasePastSlots.get(g.name());
                if (past != null) emitCopy(past, phaseCurSlots.get(g.name()));
            }
            for (String btn : plan.edgeButtons()) {
                readButtonToR16(spec.button(btn).orElseThrow());
                emitStoreFromR16(prevBtnSlots.get(btn));
            }
            for (State.TimerDecl t : plan.activeTimers()) {
                PairSlot rem = timerRemSlots.get(t.name());
                emitClear(rem.lo());
                emitClear(rem.hi());
                Slot exp = timerExpSlots.get(t.name());
                if (exp != null) emitClear(exp);
            }
            boolean purePeriodic = plan.isPurePeriodicLoop();
            for (EmissionPlan.PeriodicTask pt : plan.periodicTasks()) {
                PeriodicPlan pp = periodicPlans.get(pt.id());
                switch (pp.kind()) {
                    case SINGLE_8BIT -> emitStoreImm(periodicSingleSlots.get(pt.id()), pp.a());
                    case FACTORED_DEC -> {
                        PairSlot pair = periodicPairSlots.get(pt.id());
                        emitStoreImm(pair.lo(), pp.a());
                        emitStoreImm(pair.hi(), pp.b());
                    }
                    case GENERIC_16BIT -> {
                        PairSlot pair = periodicPairSlots.get(pt.id());
                        emitStoreExpr(pair.lo(), "low(" + pt.periodMs() + ")");
                        emitStoreExpr(pair.hi(), "high(" + pt.periodMs() + ")");
                    }
                }
                if (!purePeriodic) emitClear(periodicOkSlots.get(pt.id()));
            }
            if (plan.emitsUartRx()) {
                emitClear(uartRxOkSlot);
                if (uartRxByteSlot != null) emitClear(uartRxByteSlot);
            }
        }

        private void emitPassCaptures() {
            for (String btnName : plan.edgeButtons()) {
                Hardware.Button b = spec.button(btnName).orElseThrow();
                Slot prev = prevBtnSlots.get(btnName);
                out.append("    ; Detectar flancos de ").append(btnName).append('\n');
                readButtonToR16(b); // r16 = cur (0/1)
                emitLoadTo(prev, "r17");
                emitStoreFromR16(prev);
                out.append("    ldi  r19, 1\n");
                if (plan.usesPress(btnName)) {
                    out.append("    mov  r18, r17\n");
                    out.append("    eor  r18, r19\n");
                    out.append("    and  r18, r16\n");
                    emitStoreFrom("r18", riseBtnSlots.get(btnName));
                }
                if (plan.usesRelease(btnName)) {
                    out.append("    mov  r18, r16\n");
                    out.append("    eor  r18, r19\n");
                    out.append("    and  r18, r17\n");
                    emitStoreFrom("r18", fallBtnSlots.get(btnName));
                }
            }

            for (State.PhaseGroup g : plan.activePhaseGroups()) {
                Slot past = phasePastSlots.get(g.name());
                if (past != null) {
                    emitCopy(past, phaseCurSlots.get(g.name()));
                }
            }

            if (plan.emitsUartRx()) {
                String noRx = fresh("uart_sin_dato");
                out.append("    ; Consultar recepción UART0 sin bloquear\n");
                emitClear(uartRxOkSlot);
                out.append("    lds  r16, UCSR0A\n");
                out.append("    sbrs r16, RXC0\n");
                out.append("    rjmp ").append(noRx).append('\n');
                out.append("    lds  r16, UDR0\n");
                if (uartRxByteSlot != null) {
                    emitStoreFromR16(uartRxByteSlot);
                }
                emitSetOneAfterClear(uartRxOkSlot);
                out.append(noRx).append(":\n");
            }

            emitAdcCaptures(plan.passSnapshotAnalogs());
        }

        private void emitAdcCaptures(Set<String> analogs) {
            for (String analog : analogs) {
                int ch = spec.analog(analog).orElseThrow().channel();
                out.append("    ; Captura única ADC para ").append(analog).append('\n');
                out.append("    ldi  r16, ").append(ch).append('\n');
                if (plan.isCachedAnalog10Bit(analog)) {
                    out.append("    rcall leer_adc10\n");
                    PairSlot pair = adc10Slots.get(analog);
                    emitStoreFromR16(pair.lo());
                    emitStoreFrom("r17", pair.hi());
                } else {
                    out.append("    rcall leer_adc8\n");
                    emitStoreFromR16(adc8Slots.get(analog));
                }
            }
        }

        private void emitPurePeriodicRule(Rule rule, int loopRuleIndex) {
            int pid = plan.periodicIdForLoopRule(loopRuleIndex);
            PeriodicPlan pp = periodicPlans.get(pid);
            String endLabel = fresh("fin_tarjeta");
            String activeLabel = fresh("trig_ok");

            String detail = pp.kind() == PeriodicPlan.Kind.FACTORED_DEC
                    ? " (" + pp.a() + " ms x " + pp.b() + ")"
                    : "";
            out.append("\n    ; --- ").append(sanitizeAsmComment(rule.title())).append(detail).append(" ---\n");

            switch (pp.kind()) {
                case SINGLE_8BIT -> {
                    Slot s = periodicSingleSlots.get(pid);
                    emitDecSlotCheckZero(s);
                    out.append("    breq ").append(activeLabel).append('\n');
                    out.append("    rjmp ").append(endLabel).append('\n');
                    out.append(activeLabel).append(":\n");
                    emitStoreImm(s, pp.a());
                }
                case FACTORED_DEC -> {
                    PairSlot pair = periodicPairSlots.get(pid);
                    String reloadInner = fresh("per_recarga");
                    emitDecSlotCheckZero(pair.lo());
                    out.append("    breq ").append(reloadInner).append('\n');
                    out.append("    rjmp ").append(endLabel).append('\n');
                    out.append(reloadInner).append(":\n");
                    emitStoreImm(pair.lo(), pp.a());
                    emitDecSlotCheckZero(pair.hi());
                    out.append("    breq ").append(activeLabel).append('\n');
                    out.append("    rjmp ").append(endLabel).append('\n');
                    out.append(activeLabel).append(":\n");
                    emitStoreImm(pair.hi(), pp.b());
                }
                case GENERIC_16BIT -> {
                    PairSlot rem = periodicPairSlots.get(pid);
                    emitDecrement16CheckZero(rem);
                    out.append("    breq ").append(activeLabel).append('\n');
                    out.append("    rjmp ").append(endLabel).append('\n');
                    out.append(activeLabel).append(":\n");
                    emitStoreExpr(rem.lo(), "low(" + pp.a() + ")");
                    emitStoreExpr(rem.hi(), "high(" + pp.a() + ")");
                }
            }

            emitAdcCaptures(plan.ruleSnapshotAnalogs(loopRuleIndex));
            activeAdcSnapshots = plan.activeAdcSnapshotsForLoopRule(loopRuleIndex);
            emitRuleBody(rule, endLabel);
            activeAdcSnapshots = Set.of();
            out.append(endLabel).append(":\n");
        }

        private void emitRule(Rule rule, int loopRuleIndex) {
            String endLabel = fresh("fin_tarjeta");
            out.append("\n    ; --- ").append(sanitizeAsmComment(rule.title())).append(" ---\n");

            // 1. Trigger check
            Slot trigSlot = switch (rule.trigger()) {
                case Rule.OnBoot ignored -> null;
                case Rule.Always ignored -> null;
                case Rule.OnPress p -> riseBtnSlots.get(p.buttonName());
                case Rule.OnRelease r -> fallBtnSlots.get(r.buttonName());
                case Rule.EveryMs ignored -> periodicOkSlots.get(plan.periodicIdForLoopRule(loopRuleIndex));
                case Rule.OnTimerExpired t -> timerExpSlots.get(t.timerName());
                case Rule.OnUartRx ignored -> uartRxOkSlot;
            };
            if (trigSlot != null) {
                String activeLabel = fresh("trig_ok");
                emitTestZeroJump(trigSlot, activeLabel, endLabel);
            }

            if (loopRuleIndex >= 0) {
                emitAdcCaptures(plan.ruleSnapshotAnalogs(loopRuleIndex));
                activeAdcSnapshots = plan.activeAdcSnapshotsForLoopRule(loopRuleIndex);
            } else {
                activeAdcSnapshots = plan.bootSnapshotAnalogs();
            }
            emitRuleBody(rule, endLabel);
            activeAdcSnapshots = Set.of();
            out.append(endLabel).append(":\n");
        }

        private void emitRuleBody(Rule rule, String endLabel) {
            boolean trivialGuard = rule.guard() instanceof Expr.Truth t && t.value();
            if (trivialGuard) {
                emitEffects(rule.thenEffects());
            } else {
                String elseLabel = rule.elseEffects().isEmpty() ? endLabel : fresh("sino");
                if (!emitDirectGuardBranch(rule.guard(), elseLabel)) {
                    String thenLabel = fresh("entonces");
                    evalBoolToR16(rule.guard());
                    emitTestR16Zero();
                    out.append("    brne ").append(thenLabel).append('\n');
                    out.append("    rjmp ").append(elseLabel).append('\n');
                    out.append(thenLabel).append(":\n");
                }
                emitEffects(rule.thenEffects());
                if (!rule.elseEffects().isEmpty()) {
                    out.append("    rjmp ").append(endLabel).append('\n');
                    out.append(elseLabel).append(":\n");
                    emitEffects(rule.elseEffects());
                }
            }
        }

        private boolean emitDirectGuardBranch(Expr.BoolExpr guard, String falseTarget) {
            if (guard instanceof Expr.Pressed p) {
                Hardware.Button b = spec.button(p.buttonName()).orElseThrow();
                out.append(b.activeHigh() ? "    sbis " : "    sbic ")
                        .append(b.pin().port().pinReg()).append(", ").append(b.pin().bit()).append('\n');
                out.append("    rjmp ").append(falseTarget).append('\n');
                return true;
            }
            if (guard instanceof Expr.Not n && n.inner() instanceof Expr.Pressed p) {
                Hardware.Button b = spec.button(p.buttonName()).orElseThrow();
                out.append(b.activeHigh() ? "    sbic " : "    sbis ")
                        .append(b.pin().port().pinReg()).append(", ").append(b.pin().bit()).append('\n');
                out.append("    rjmp ").append(falseTarget).append('\n');
                return true;
            }
            if (guard instanceof Expr.InPhase ip) {
                String ok = fresh("fase_ok");
                emitCompareSlotImm(phasePastSlots.get(ip.groupName()), phaseEqu(ip.groupName(), ip.phaseName()));
                out.append("    breq ").append(ok).append('\n');
                out.append("    rjmp ").append(falseTarget).append('\n');
                out.append(ok).append(":\n");
                return true;
            }
            if (guard instanceof Expr.UartByteEquals u) {
                String ok = fresh("uart_ok");
                emitCompareSlotImm(uartRxByteSlot, String.valueOf(u.expectedByte()));
                out.append("    breq ").append(ok).append('\n');
                out.append("    rjmp ").append(falseTarget).append('\n');
                out.append(ok).append(":\n");
                return true;
            }
            if (guard instanceof Expr.Compare c
                    && c.left() instanceof Expr.ReadVar rv
                    && c.right() instanceof Expr.ConstU8 k
                    && (c.op() == Expr.CmpOp.EQ || c.op() == Expr.CmpOp.NE
                    || c.op() == Expr.CmpOp.LT || c.op() == Expr.CmpOp.GE)) {
                String ok = fresh("cmp_ok");
                emitCompareSlotImm(varSlots.get(rv.varName()), String.valueOf(k.value()));
                String br = switch (c.op()) {
                    case EQ -> "breq";
                    case NE -> "brne";
                    case LT -> "brlo";
                    case GE -> "brsh";
                    default -> throw new IllegalStateException();
                };
                out.append("    ").append(br).append(' ').append(ok).append('\n');
                out.append("    rjmp ").append(falseTarget).append('\n');
                out.append(ok).append(":\n");
                return true;
            }
            return false;
        }

        private void emitEffects(List<Effect> effects) {
            for (Effect eff : effects) {
                switch (eff) {
                    case Effect.WriteLed w -> {
                        Hardware.Led led = spec.led(w.ledName()).orElseThrow();
                        boolean high = (w.state() == Effect.LedState.ON) == led.activeHigh();
                        out.append(high ? "    sbi  " : "    cbi  ")
                                .append(led.pin().port().portReg()).append(", ").append(led.pin().bit()).append('\n');
                    }
                    case Effect.ToggleLed t -> {
                        Hardware.Led led = spec.led(t.ledName()).orElseThrow();
                        out.append("    in   r16, ").append(led.pin().port().portReg()).append('\n');
                        out.append("    ldi  r17, (1 << ").append(led.pin().bit()).append(")\n");
                        out.append("    eor  r16, r17\n");
                        out.append("    out  ").append(led.pin().port().portReg()).append(", r16\n");
                    }
                    case Effect.WriteBcd b -> {
                        evalValue8ToR16(b.value());
                        out.append("    rcall mostrar_bcd_").append(b.displayName()).append('\n');
                    }
                    case Effect.WriteDecimal2 d -> {
                        evalValue8ToR16(d.value());
                        out.append("    rcall divmod_10\n"); // r17 = decenas, r16 = unidades
                        out.append("    push r16\n");
                        out.append("    mov  r16, r17\n");
                        out.append("    rcall mostrar_bcd_").append(d.tensDisplay()).append('\n');
                        out.append("    pop  r16\n");
                        out.append("    rcall mostrar_bcd_").append(d.unitsDisplay()).append('\n');
                    }
                    case Effect.WritePwm p -> {
                        evalValue8ToR16(p.duty());
                        out.append("    rcall fijar_pwm_").append(p.pwmName()).append('\n');
                    }
                    case Effect.SendUartText s -> {
                        int id = plan.uartStringId(s.ascii());
                        out.append("    ldi  r30, low(uart_txt_").append(id).append(" << 1)\n");
                        out.append("    ldi  r31, high(uart_txt_").append(id).append(" << 1)\n");
                        out.append("    rcall uart_enviar_texto\n");
                    }
                    case Effect.SendUartByte b -> {
                        evalValue8ToR16(b.value());
                        out.append("    rcall uart_enviar_byte\n");
                    }
                    case Effect.SetVar s -> {
                        if (s.value() instanceof Expr.ConstU8 c) {
                            emitStoreImm(varSlots.get(s.varName()), c.value());
                        } else {
                            evalValue8ToR16(s.value());
                            emitStoreFromR16(varSlots.get(s.varName()));
                        }
                    }
                    case Effect.StepVar s -> emitStepVar(spec.var(s.varName()).orElseThrow(), s.direction());
                    case Effect.ResetVar r -> {
                        State.VarDecl v = spec.var(r.varName()).orElseThrow();
                        emitStoreImm(varSlots.get(v.name()), v.initial());
                    }
                    case Effect.SetPhase sp -> {
                        emitStoreExpr(phaseCurSlots.get(sp.groupName()), phaseEqu(sp.groupName(), sp.phaseName()));
                    }
                    case Effect.StartTimer st -> {
                        PairSlot rem = timerRemSlots.get(st.timerName());
                        emitStoreExpr(rem.lo(), "low(" + st.durationMs() + ")");
                        emitStoreExpr(rem.hi(), "high(" + st.durationMs() + ")");
                    }
                    case Effect.StopTimer st -> {
                        PairSlot rem = timerRemSlots.get(st.timerName());
                        emitClear(rem.lo());
                        emitClear(rem.hi());
                    }
                    case Effect.DelayMs d -> {
                        out.append("    ldi  r20, low(").append(d.durationMs()).append(")\n");
                        out.append("    ldi  r21, high(").append(d.durationMs()).append(")\n");
                        out.append("    rcall retardo_ms\n");
                    }
                }
            }
        }

        /** True si ninguna regla escribe valores fuera de [min..max] en esta variable mediante SetVar. */
        private boolean varGuaranteedInBounds(State.VarDecl v) {
            for (Rule r : spec.rules()) {
                for (Effect e : r.thenEffects()) if (!effectKeepsVarInBounds(v, e)) return false;
                for (Effect e : r.elseEffects()) if (!effectKeepsVarInBounds(v, e)) return false;
            }
            return true;
        }

        private static boolean effectKeepsVarInBounds(State.VarDecl v, Effect e) {
            if (!(e instanceof Effect.SetVar sv) || !sv.varName().equals(v.name())) return true;
            if (sv.value() instanceof Expr.ConstU8 c) return c.value() >= v.min() && c.value() <= v.max();
            if (sv.value() instanceof Expr.Scale sc) return sc.outMin() >= v.min() && sc.outMax() <= v.max();
            return false;
        }

        private void emitStepVar(State.VarDecl v, Effect.StepDir dir) {
            Slot slot = varSlots.get(v.name());
            String target = slot.highReg() ? slot.symbol() : "r16";
            if (!slot.highReg()) emitLoadToR16(slot);

            boolean inBounds = elementary() && varGuaranteedInBounds(v);
            String done = fresh("step_fin");
            if (dir == Effect.StepDir.INC) {
                if (v.policy() == State.VarPolicy.WRAP) {
                    if (v.min() == 0 && v.max() == 255) {
                        out.append("    inc  ").append(target).append('\n');
                    } else if (inBounds && v.max() < 255) {
                        // Forma elemental compacta de 4 instrucciones: inc / cpi max+1 / brne / ldi min
                        out.append("    inc  ").append(target).append('\n');
                        out.append("    cpi  ").append(target).append(", ").append(v.max() + 1).append('\n');
                        out.append("    brne ").append(done).append('\n');
                        out.append("    ldi  ").append(target).append(", ").append(v.min()).append('\n');
                        out.append(done).append(":\n");
                    } else {
                        String normal = fresh("inc_ok");
                        out.append("    cpi  ").append(target).append(", ").append(v.max()).append('\n');
                        out.append("    brlo ").append(normal).append('\n');
                        out.append("    ldi  ").append(target).append(", ").append(v.min()).append('\n');
                        out.append("    rjmp ").append(done).append('\n');
                        out.append(normal).append(":\n");
                        out.append("    inc  ").append(target).append('\n');
                        out.append(done).append(":\n");
                    }
                } else if (inBounds) {
                    out.append("    cpi  ").append(target).append(", ").append(v.max()).append('\n');
                    out.append("    breq ").append(done).append('\n');
                    out.append("    inc  ").append(target).append('\n');
                    out.append(done).append(":\n");
                } else {
                    out.append("    cpi  ").append(target).append(", ").append(v.max()).append('\n');
                    out.append("    brsh ").append(done).append('\n');
                    out.append("    inc  ").append(target).append('\n');
                    out.append(done).append(":\n");
                }
            } else {
                if (v.policy() == State.VarPolicy.WRAP) {
                    if (v.min() == 0 && v.max() == 255) {
                        out.append("    dec  ").append(target).append('\n');
                    } else if (inBounds && v.min() > 0) {
                        out.append("    dec  ").append(target).append('\n');
                        out.append("    cpi  ").append(target).append(", ").append(v.min() - 1).append('\n');
                        out.append("    brne ").append(done).append('\n');
                        out.append("    ldi  ").append(target).append(", ").append(v.max()).append('\n');
                        out.append(done).append(":\n");
                    } else if (inBounds) {
                        String normal = fresh("dec_wrap");
                        out.append("    cpi  ").append(target).append(", ").append(v.min()).append('\n');
                        out.append("    breq ").append(normal).append('\n');
                        out.append("    dec  ").append(target).append('\n');
                        out.append("    rjmp ").append(done).append('\n');
                        out.append(normal).append(":\n");
                        out.append("    ldi  ").append(target).append(", ").append(v.max()).append('\n');
                        out.append(done).append(":\n");
                    } else {
                        String normal = fresh("dec_ok");
                        out.append("    cpi  ").append(target).append(", ").append(v.min()).append('\n');
                        out.append("    breq ").append(normal).append('\n');
                        out.append("    brlo ").append(normal).append('\n');
                        out.append("    dec  ").append(target).append('\n');
                        out.append("    rjmp ").append(done).append('\n');
                        out.append(normal).append(":\n");
                        out.append("    ldi  ").append(target).append(", ").append(v.max()).append('\n');
                        out.append(done).append(":\n");
                    }
                } else if (inBounds) {
                    out.append("    cpi  ").append(target).append(", ").append(v.min()).append('\n');
                    out.append("    breq ").append(done).append('\n');
                    out.append("    dec  ").append(target).append('\n');
                    out.append(done).append(":\n");
                } else {
                    out.append("    cpi  ").append(target).append(", ").append(v.min()).append('\n');
                    out.append("    breq ").append(done).append('\n');
                    out.append("    brlo ").append(done).append('\n');
                    out.append("    dec  ").append(target).append('\n');
                    out.append(done).append(":\n");
                }
            }
            if (!slot.highReg()) emitStoreFromR16(slot);
        }

        private void emitCooperativeTick() {
            out.append("\n    ; --- Base de tiempo cooperativa (~1 ms, Timer0 queda libre) ---\n");
            out.append("    rcall retardo_1ms\n");
            for (State.TimerDecl t : plan.activeTimers()) {
                PairSlot rem = timerRemSlots.get(t.name());
                Slot exp = timerExpSlots.get(t.name());
                String skip = fresh("tmr_skip");
                if (exp != null) emitClear(exp);

                // Si rem == 0, el temporizador está inactivo
                if (rem.bothRegs()) {
                    out.append("    mov  r16, ").append(rem.lo().symbol()).append('\n');
                    out.append("    or   r16, ").append(rem.hi().symbol()).append('\n');
                } else {
                    emitLoadToR16(rem.lo());
                    emitLoadTo(rem.hi(), "r17");
                    out.append("    or   r16, r17\n");
                }
                out.append("    breq ").append(skip).append('\n');

                emitDecrement16CheckZero(rem);
                out.append("    brne ").append(skip).append('\n');
                if (exp != null) emitSetOneAfterClear(exp);
                out.append(skip).append(":\n");
            }

            for (EmissionPlan.PeriodicTask pt : plan.periodicTasks()) {
                PeriodicPlan pp = periodicPlans.get(pt.id());
                Slot ok = periodicOkSlots.get(pt.id());
                String skip = fresh("per_skip");
                emitClear(ok);

                switch (pp.kind()) {
                    case SINGLE_8BIT -> {
                        Slot s = periodicSingleSlots.get(pt.id());
                        emitDecSlotCheckZero(s);
                        out.append("    brne ").append(skip).append('\n');
                        emitStoreImm(s, pp.a());
                        emitSetOneAfterClear(ok);
                    }
                    case FACTORED_DEC -> {
                        PairSlot pair = periodicPairSlots.get(pt.id());
                        emitDecSlotCheckZero(pair.lo());
                        out.append("    brne ").append(skip).append('\n');
                        emitStoreImm(pair.lo(), pp.a());
                        emitDecSlotCheckZero(pair.hi());
                        out.append("    brne ").append(skip).append('\n');
                        emitStoreImm(pair.hi(), pp.b());
                        emitSetOneAfterClear(ok);
                    }
                    case GENERIC_16BIT -> {
                        PairSlot rem = periodicPairSlots.get(pt.id());
                        emitDecrement16CheckZero(rem);
                        out.append("    brne ").append(skip).append('\n');
                        emitStoreExpr(rem.lo(), "low(" + pp.a() + ")");
                        emitStoreExpr(rem.hi(), "high(" + pp.a() + ")");
                        emitSetOneAfterClear(ok);
                    }
                }
                out.append(skip).append(":\n");
            }
        }

        private void evalBoolToR16(Expr.BoolExpr expr) {
            switch (expr) {
                case Expr.Truth t -> out.append("    ldi  r16, ").append(t.value() ? 1 : 0).append('\n');
                case Expr.Pressed p -> readButtonToR16(spec.button(p.buttonName()).orElseThrow());
                case Expr.InPhase ip -> {
                    String yes = fresh("fase_ok"), end = fresh("fase_fin");
                    emitCompareSlotImm(phasePastSlots.get(ip.groupName()), phaseEqu(ip.groupName(), ip.phaseName()));
                    out.append("    breq ").append(yes).append('\n');
                    out.append("    clr  r16\n");
                    out.append("    rjmp ").append(end).append('\n');
                    out.append(yes).append(":\n");
                    out.append("    ldi  r16, 1\n");
                    out.append(end).append(":\n");
                }
                case Expr.TimerActive ta -> {
                    String end = fresh("tmr_act_fin");
                    PairSlot rem = timerRemSlots.get(ta.timerName());
                    if (rem.bothRegs()) {
                        out.append("    mov  r16, ").append(rem.lo().symbol()).append('\n');
                        out.append("    or   r16, ").append(rem.hi().symbol()).append('\n');
                    } else {
                        emitLoadToR16(rem.lo());
                        emitLoadTo(rem.hi(), "r17");
                        out.append("    or   r16, r17\n");
                    }
                    out.append("    breq ").append(end).append('\n');
                    out.append("    ldi  r16, 1\n");
                    out.append(end).append(":\n");
                }
                case Expr.UartByteEquals u -> {
                    String yes = fresh("uart_eq"), end = fresh("uart_eq_fin");
                    emitCompareSlotImm(uartRxByteSlot, String.valueOf(u.expectedByte()));
                    out.append("    breq ").append(yes).append('\n');
                    out.append("    clr  r16\n");
                    out.append("    rjmp ").append(end).append('\n');
                    out.append(yes).append(":\n");
                    out.append("    ldi  r16, 1\n");
                    out.append(end).append(":\n");
                }
                case Expr.Compare c -> emitCompare(c);
                case Expr.InRange r -> emitInRange(r);
                case Expr.Modulo m -> {
                    String no = fresh("mod_no"), end = fresh("mod_fin");
                    emitLoadToR16(varSlots.get(m.varName()));
                    if (!m.includeZero()) {
                        emitTestR16Zero();
                        out.append("    breq ").append(no).append('\n');
                    }
                    out.append("    ldi  r17, ").append(m.divisor()).append('\n');
                    out.append("    rcall mod_u8\n");
                    out.append("    cpi  r16, ").append(m.remainder()).append('\n');
                    out.append("    brne ").append(no).append('\n');
                    out.append("    ldi  r16, 1\n");
                    out.append("    rjmp ").append(end).append('\n');
                    out.append(no).append(":\n");
                    out.append("    clr  r16\n");
                    out.append(end).append(":\n");
                }
                case Expr.Not n -> {
                    evalBoolToR16(n.inner());
                    out.append("    ldi  r17, 1\n");
                    out.append("    eor  r16, r17\n");
                }
                case Expr.And a -> {
                    String fail = fresh("and_falso"), end = fresh("and_fin");
                    for (Expr.BoolExpr item : a.items()) {
                        String cont = fresh("and_sig");
                        evalBoolToR16(item);
                        emitTestR16Zero();
                        out.append("    brne ").append(cont).append('\n');
                        out.append("    rjmp ").append(fail).append('\n');
                        out.append(cont).append(":\n");
                    }
                    out.append("    ldi  r16, 1\n");
                    out.append("    rjmp ").append(end).append('\n');
                    out.append(fail).append(":\n");
                    out.append("    clr  r16\n");
                    out.append(end).append(":\n");
                }
                case Expr.Or o -> {
                    String ok = fresh("or_verdad"), end = fresh("or_fin");
                    for (Expr.BoolExpr item : o.items()) {
                        String cont = fresh("or_sig");
                        evalBoolToR16(item);
                        emitTestR16Zero();
                        out.append("    breq ").append(cont).append('\n');
                        out.append("    rjmp ").append(ok).append('\n');
                        out.append(cont).append(":\n");
                    }
                    out.append("    clr  r16\n");
                    out.append("    rjmp ").append(end).append('\n');
                    out.append(ok).append(":\n");
                    out.append("    ldi  r16, 1\n");
                    out.append(end).append(":\n");
                }
                case Expr.Xor x -> {
                    evalBoolToR16(x.left());
                    if (plan.needsAsmStack()) {
                        out.append("    push r16\n");
                        evalBoolToR16(x.right());
                        out.append("    pop  r17\n");
                        out.append("    eor  r16, r17\n");
                    } else {
                        out.append("    mov  r19, r16\n");
                        evalBoolToR16(x.right());
                        out.append("    eor  r16, r19\n");
                    }
                }
            }
        }

        private void emitCompare(Expr.Compare c) {
            boolean wide = c.left().is10Bit() || c.right().is10Bit();
            String yes = fresh("cmp_ok"), no = fresh("cmp_no"), end = fresh("cmp_fin");
            if (!wide && c.right() instanceof Expr.ConstU8 k
                    && (c.op() == Expr.CmpOp.EQ || c.op() == Expr.CmpOp.NE
                    || c.op() == Expr.CmpOp.LT || c.op() == Expr.CmpOp.GE)) {
                if (c.left() instanceof Expr.ReadVar rv && varSlots.get(rv.varName()).highReg()) {
                    out.append("    cpi  ").append(varSlots.get(rv.varName()).symbol()).append(", ").append(k.value()).append('\n');
                } else {
                    evalValue8ToR16(c.left());
                    out.append("    cpi  r16, ").append(k.value()).append('\n');
                }
                switch (c.op()) {
                    case EQ -> out.append("    breq ").append(yes).append('\n');
                    case NE -> out.append("    brne ").append(yes).append('\n');
                    case LT -> out.append("    brlo ").append(yes).append('\n');
                    case GE -> out.append("    brsh ").append(yes).append('\n');
                    default -> throw new IllegalStateException();
                }
            } else if (!wide) {
                evalValue8ToR16(c.left());
                if (plan.needsAsmStack()) {
                    out.append("    push r16\n");
                    evalValue8ToR16(c.right());
                    out.append("    mov  r20, r16\n");
                    out.append("    pop  r16\n");
                } else {
                    out.append("    mov  r20, r16\n");
                    evalValue8ToR16(c.right());
                    out.append("    mov  r21, r16\n");
                    out.append("    mov  r16, r20\n");
                    out.append("    mov  r20, r21\n");
                }
                switch (c.op()) {
                    case EQ -> { out.append("    cp   r16, r20\n    breq ").append(yes).append('\n'); }
                    case NE -> { out.append("    cp   r16, r20\n    brne ").append(yes).append('\n'); }
                    case LT -> { out.append("    cp   r16, r20\n    brlo ").append(yes).append('\n'); }
                    case GE -> { out.append("    cp   r16, r20\n    brsh ").append(yes).append('\n'); }
                    case GT -> { out.append("    cp   r20, r16\n    brlo ").append(yes).append('\n'); }
                    case LE -> { out.append("    cp   r20, r16\n    brsh ").append(yes).append('\n'); }
                }
            } else {
                evalValue16ToR17R16(c.left());
                out.append("    push r16\n");
                out.append("    push r17\n");
                evalValue16ToR17R16(c.right());
                out.append("    mov  r20, r16\n");
                out.append("    mov  r21, r17\n");
                out.append("    pop  r17\n");
                out.append("    pop  r16\n");
                if (elementary()) {
                    // Comparación 16-bit sin cpc: primero byte alto, luego byte bajo
                    switch (c.op()) {
                        case EQ -> {
                            out.append("    cp   r17, r21\n    brne ").append(no).append('\n');
                            out.append("    cp   r16, r20\n    breq ").append(yes).append('\n');
                        }
                        case NE -> {
                            out.append("    cp   r17, r21\n    brne ").append(yes).append('\n');
                            out.append("    cp   r16, r20\n    brne ").append(yes).append('\n');
                        }
                        case LT -> {
                            out.append("    cp   r17, r21\n    brlo ").append(yes).append("\n    brne ").append(no).append('\n');
                            out.append("    cp   r16, r20\n    brlo ").append(yes).append('\n');
                        }
                        case GE -> {
                            out.append("    cp   r17, r21\n    brlo ").append(no).append("\n    brne ").append(yes).append('\n');
                            out.append("    cp   r16, r20\n    brsh ").append(yes).append('\n');
                        }
                        case GT -> {
                            out.append("    cp   r21, r17\n    brlo ").append(yes).append("\n    brne ").append(no).append('\n');
                            out.append("    cp   r20, r16\n    brlo ").append(yes).append('\n');
                        }
                        case LE -> {
                            out.append("    cp   r21, r17\n    brlo ").append(no).append("\n    brne ").append(yes).append('\n');
                            out.append("    cp   r20, r16\n    brsh ").append(yes).append('\n');
                        }
                    }
                } else {
                    switch (c.op()) {
                        case EQ -> { out.append("    cp   r16, r20\n    cpc  r17, r21\n    breq ").append(yes).append('\n'); }
                        case NE -> { out.append("    cp   r16, r20\n    cpc  r17, r21\n    brne ").append(yes).append('\n'); }
                        case LT -> { out.append("    cp   r16, r20\n    cpc  r17, r21\n    brlo ").append(yes).append('\n'); }
                        case GE -> { out.append("    cp   r16, r20\n    cpc  r17, r21\n    brsh ").append(yes).append('\n'); }
                        case GT -> { out.append("    cp   r20, r16\n    cpc  r21, r17\n    brlo ").append(yes).append('\n'); }
                        case LE -> { out.append("    cp   r20, r16\n    cpc  r21, r17\n    brsh ").append(yes).append('\n'); }
                    }
                }
            }
            out.append(no).append(":\n");
            out.append("    clr  r16\n");
            out.append("    rjmp ").append(end).append('\n');
            out.append(yes).append(":\n");
            out.append("    ldi  r16, 1\n");
            out.append(end).append(":\n");
        }

        /** Evalúa InRange sobre UNA sola muestra (evita doble conversión ADC). */
        private void emitInRange(Expr.InRange r) {
            String no = fresh("rango_no"), end = fresh("rango_fin");
            if (!r.value().is10Bit()) {
                evalValue8ToR16(r.value());
                if (r.min() > 0) {
                    out.append("    cpi  r16, ").append(r.min()).append('\n');
                    out.append("    brlo ").append(no).append('\n');
                }
                if (r.max() < 255) {
                    out.append("    cpi  r16, ").append(r.max() + 1).append('\n');
                    out.append("    brsh ").append(no).append('\n');
                }
                out.append("    ldi  r16, 1\n");
                out.append("    rjmp ").append(end).append('\n');
                out.append(no).append(":\n");
                out.append("    clr  r16\n");
                out.append(end).append(":\n");
            } else {
                String chkMax = fresh("rango_max"), ok = fresh("rango_ok");
                evalValue16ToR17R16(r.value());
                if (r.min() > 0) {
                    out.append("    ldi  r20, low(").append(r.min()).append(")\n");
                    out.append("    ldi  r21, high(").append(r.min()).append(")\n");
                    if (elementary()) {
                        out.append("    cp   r17, r21\n");
                        out.append("    brlo ").append(no).append('\n');
                        out.append("    brne ").append(chkMax).append('\n');
                        out.append("    cp   r16, r20\n");
                        out.append("    brlo ").append(no).append('\n');
                    } else {
                        out.append("    cp   r16, r20\n");
                        out.append("    cpc  r17, r21\n");
                        out.append("    brlo ").append(no).append('\n');
                    }
                }
                out.append(chkMax).append(":\n");
                if (r.max() < 1023) {
                    out.append("    ldi  r20, low(").append(r.max()).append(")\n");
                    out.append("    ldi  r21, high(").append(r.max()).append(")\n");
                    if (elementary()) {
                        out.append("    cp   r21, r17\n");
                        out.append("    brlo ").append(no).append('\n');
                        out.append("    brne ").append(ok).append('\n');
                        out.append("    cp   r20, r16\n");
                        out.append("    brlo ").append(no).append('\n');
                    } else {
                        out.append("    cp   r20, r16\n");
                        out.append("    cpc  r21, r17\n");
                        out.append("    brlo ").append(no).append('\n');
                    }
                }
                out.append(ok).append(":\n");
                out.append("    ldi  r16, 1\n");
                out.append("    rjmp ").append(end).append('\n');
                out.append(no).append(":\n");
                out.append("    clr  r16\n");
                out.append(end).append(":\n");
            }
        }

        private void evalValue8ToR16(Expr.ValueExpr expr) {
            switch (expr) {
                case Expr.ConstU8 c -> out.append("    ldi  r16, ").append(c.value()).append('\n');
                case Expr.ConstU10 c -> out.append("    ldi  r16, ").append(c.value() & 0xFF).append('\n');
                case Expr.ReadVar rv -> emitLoadToR16(varSlots.get(rv.varName()));
                case Expr.ReadAdc8 a -> {
                    if (activeAdcSnapshots.contains(a.analogName())) {
                        if (plan.isCachedAnalog10Bit(a.analogName())) {
                            PairSlot pair = adc10Slots.get(a.analogName());
                            emitLoadToR16(pair.lo());
                            emitLoadTo(pair.hi(), "r17");
                            out.append("    lsr  r17\n");
                            out.append("    ror  r16\n");
                            out.append("    lsr  r17\n");
                            out.append("    ror  r16\n");
                        } else {
                            emitLoadToR16(adc8Slots.get(a.analogName()));
                        }
                    } else {
                        int ch = spec.analog(a.analogName()).orElseThrow().channel();
                        out.append("    ldi  r16, ").append(ch).append('\n');
                        out.append("    rcall leer_adc8\n");
                    }
                }
                case Expr.ReadAdc10 a -> {
                    if (activeAdcSnapshots.contains(a.analogName())) {
                        PairSlot pair = adc10Slots.get(a.analogName());
                        emitLoadToR16(pair.lo());
                        emitLoadTo(pair.hi(), "r17");
                    } else {
                        int ch = spec.analog(a.analogName()).orElseThrow().channel();
                        out.append("    ldi  r16, ").append(ch).append('\n');
                        out.append("    rcall leer_adc10\n");
                    }
                }
                case Expr.ReadUartByte ignored -> emitLoadToR16(uartRxByteSlot);
                case Expr.Scale s -> {
                    if (s.inMax() == 255) {
                        evalValue8ToR16(s.source());
                        if (s.span() < 256) {
                            out.append("    ldi  r17, ").append(s.span()).append('\n');
                            out.append("    mul  r16, r17\n");
                            out.append("    mov  r16, r1\n");
                            out.append("    clr  r1\n");
                        }
                    } else {
                        evalValue16ToR17R16(s.source());
                        if (s.span() == 256) {
                            out.append("    lsr  r17\n");
                            out.append("    ror  r16\n");
                            out.append("    lsr  r17\n");
                            out.append("    ror  r16\n");
                        } else {
                            out.append("    ldi  r18, ").append(s.span()).append('\n');
                            out.append("    rcall escalar_10b\n");
                        }
                    }
                    if (s.outMin() != 0) {
                        out.append("    ldi  r17, ").append(s.outMin()).append('\n');
                        out.append("    add  r16, r17\n");
                    }
                }
            }
        }

        private void evalValue16ToR17R16(Expr.ValueExpr expr) {
            switch (expr) {
                case Expr.ConstU10 c -> {
                    out.append("    ldi  r16, low(").append(c.value()).append(")\n");
                    out.append("    ldi  r17, high(").append(c.value()).append(")\n");
                }
                case Expr.ReadAdc10 a -> {
                    if (activeAdcSnapshots.contains(a.analogName())) {
                        PairSlot pair = adc10Slots.get(a.analogName());
                        emitLoadToR16(pair.lo());
                        emitLoadTo(pair.hi(), "r17");
                    } else {
                        int ch = spec.analog(a.analogName()).orElseThrow().channel();
                        out.append("    ldi  r16, ").append(ch).append('\n');
                        out.append("    rcall leer_adc10\n");
                    }
                }
                default -> {
                    evalValue8ToR16(expr);
                    out.append("    clr  r17\n");
                }
            }
        }

        private void readButtonToR16(Hardware.Button b) {
            out.append("    ldi  r16, 1\n");
            out.append(b.activeHigh() ? "    sbis " : "    sbic ")
                    .append(b.pin().port().pinReg()).append(", ").append(b.pin().bit()).append('\n');
            out.append("    clr  r16\n");
        }

        // ==================== Primitivas uniformes Registro / SRAM / Perfil ====================

        private void emitDecSlotCheckZero(Slot s) {
            if (s.isReg()) {
                out.append("    dec  ").append(s.symbol()).append('\n');
            } else {
                out.append("    lds  r16, ").append(s.symbol()).append('\n');
                out.append("    dec  r16\n");
                out.append("    sts  ").append(s.symbol()).append(", r16\n");
            }
        }

        /**
         * Decrementa un par de 16 bits (hi:lo > 0) y deja la bandera Z = 1 si llegó a 0.
         * En perfil ELEMENTAL usa cpi/brne/dec/or (cero subi/sbci); en ESTÁNDAR usa subi/sbci.
         */
        private void emitDecrement16CheckZero(PairSlot rem) {
            if (elementary()) {
                String noBorrow = fresh("sin_prestamo");
                if (rem.bothHighRegs()) {
                    out.append("    cpi  ").append(rem.lo().symbol()).append(", 0\n");
                    out.append("    brne ").append(noBorrow).append('\n');
                    out.append("    dec  ").append(rem.hi().symbol()).append('\n');
                    out.append(noBorrow).append(":\n");
                    out.append("    dec  ").append(rem.lo().symbol()).append('\n');
                    out.append("    mov  r16, ").append(rem.lo().symbol()).append('\n');
                    out.append("    or   r16, ").append(rem.hi().symbol()).append('\n');
                } else {
                    emitLoadToR16(rem.lo());
                    emitLoadTo(rem.hi(), "r17");
                    out.append("    cpi  r16, 0\n");
                    out.append("    brne ").append(noBorrow).append('\n');
                    out.append("    dec  r17\n");
                    out.append(noBorrow).append(":\n");
                    out.append("    dec  r16\n");
                    emitStoreFromR16(rem.lo());
                    emitStoreFrom("r17", rem.hi());
                    out.append("    or   r16, r17\n");
                }
            } else if (rem.bothHighRegs()) {
                out.append("    subi ").append(rem.lo().symbol()).append(", 1\n");
                out.append("    sbci ").append(rem.hi().symbol()).append(", 0\n");
            } else {
                emitLoadToR16(rem.lo());
                emitLoadTo(rem.hi(), "r17");
                out.append("    subi r16, 1\n");
                out.append("    sbci r17, 0\n");
                emitStoreFromR16(rem.lo());
                emitStoreFrom("r17", rem.hi());
                out.append("    or   r16, r17\n");
            }
        }

        private void emitTestR16Zero() {
            if (elementary()) {
                out.append("    cpi  r16, 0\n");
            } else {
                out.append("    tst  r16\n");
            }
        }

        private void emitLoadToR16(Slot s) {
            emitLoadTo(s, "r16");
        }

        private void emitLoadTo(Slot s, String destReg) {
            if (s.isReg()) {
                out.append("    mov  ").append(destReg).append(", ").append(s.symbol()).append('\n');
            } else {
                out.append("    lds  ").append(destReg).append(", ").append(s.symbol()).append('\n');
            }
        }

        private void emitStoreFromR16(Slot s) {
            emitStoreFrom("r16", s);
        }

        private void emitStoreFrom(String srcReg, Slot s) {
            if (s.isReg()) {
                out.append("    mov  ").append(s.symbol()).append(", ").append(srcReg).append('\n');
            } else {
                out.append("    sts  ").append(s.symbol()).append(", ").append(srcReg).append('\n');
            }
        }

        private void emitStoreImm(Slot s, int value) {
            if (value == 0) {
                emitClear(s);
            } else if (s.highReg()) {
                out.append("    ldi  ").append(s.symbol()).append(", ").append(value).append('\n');
            } else if (s.isReg()) {
                out.append("    ldi  r16, ").append(value).append('\n');
                out.append("    mov  ").append(s.symbol()).append(", r16\n");
            } else {
                out.append("    ldi  r16, ").append(value).append('\n');
                out.append("    sts  ").append(s.symbol()).append(", r16\n");
            }
        }

        private void emitStoreExpr(Slot s, String expr) {
            if (s.highReg()) {
                out.append("    ldi  ").append(s.symbol()).append(", ").append(expr).append('\n');
            } else if (s.isReg()) {
                out.append("    ldi  r16, ").append(expr).append('\n');
                out.append("    mov  ").append(s.symbol()).append(", r16\n");
            } else {
                out.append("    ldi  r16, ").append(expr).append('\n');
                out.append("    sts  ").append(s.symbol()).append(", r16\n");
            }
        }

        private void emitClear(Slot s) {
            if (s.isReg()) {
                out.append("    clr  ").append(s.symbol()).append('\n');
            } else {
                out.append("    clr  r16\n");
                out.append("    sts  ").append(s.symbol()).append(", r16\n");
            }
        }

        private void emitSetOneAfterClear(Slot s) {
            if (s.isReg()) {
                out.append("    inc  ").append(s.symbol()).append('\n');
            } else {
                out.append("    ldi  r16, 1\n");
                out.append("    sts  ").append(s.symbol()).append(", r16\n");
            }
        }

        private void emitCopy(Slot dst, Slot src) {
            if (dst.isReg() && src.isReg()) {
                out.append("    mov  ").append(dst.symbol()).append(", ").append(src.symbol()).append('\n');
            } else {
                emitLoadToR16(src);
                emitStoreFromR16(dst);
            }
        }

        private void emitCompareSlotImm(Slot s, String rhsExpr) {
            if (s.highReg()) {
                out.append("    cpi  ").append(s.symbol()).append(", ").append(rhsExpr).append('\n');
            } else {
                emitLoadToR16(s);
                out.append("    cpi  r16, ").append(rhsExpr).append('\n');
            }
        }

        private void emitTestZeroJump(Slot s, String activeLabel, String zeroTarget) {
            if (elementary()) {
                emitCompareSlotImm(s, "0");
            } else if (s.isReg()) {
                out.append("    tst  ").append(s.symbol()).append('\n');
            } else {
                out.append("    lds  r16, ").append(s.symbol()).append('\n');
                out.append("    tst  r16\n");
            }
            out.append("    brne ").append(activeLabel).append('\n');
            out.append("    rjmp ").append(zeroTarget).append('\n');
            out.append(activeLabel).append(":\n");
        }

        // ==================== Subrutinas auxiliares ====================

        private void emitSubroutines() {
            if (!plan.needsAsmStack()) return;

            out.append("\n; ===================================================================\n");
            out.append("; Subrutinas auxiliares (solo las utilizadas por este programa)\n");
            out.append("; ===================================================================\n");

            if (plan.usesClock() || plan.usesDelayMs()) {
                switch (spec.clockFreq()) {
                    case MHZ_1 -> {
                        out.append("\n; Retardo de 1 ms a 1 MHz (~1000 ciclos, bucle compacto)\n");
                        out.append("retardo_1ms:\n");
                        out.append("    ldi  r18, 250\n");
                        out.append("retardo_1ms_lazo:\n");
                        out.append("    nop\n");
                        out.append("    dec  r18\n");
                        out.append("    brne retardo_1ms_lazo\n");
                        out.append("    ret\n");
                    }
                    case MHZ_8 -> {
                        out.append("\n; Retardo de 1 ms a 8 MHz (~8000 ciclos, bucle anidado compacto)\n");
                        out.append("retardo_1ms:\n");
                        out.append("    ldi  r19, 10\n");
                        out.append("retardo_1ms_ext:\n");
                        out.append("    ldi  r18, 200\n");
                        out.append("retardo_1ms_int:\n");
                        out.append("    nop\n");
                        out.append("    dec  r18\n");
                        out.append("    brne retardo_1ms_int\n");
                        out.append("    dec  r19\n");
                        out.append("    brne retardo_1ms_ext\n");
                        out.append("    ret\n");
                    }
                    case MHZ_16 -> {
                        out.append("\n; Retardo de 1 ms a 16 MHz (~16000 ciclos, bucle anidado compacto)\n");
                        out.append("retardo_1ms:\n");
                        out.append("    ldi  r19, 20\n");
                        out.append("retardo_1ms_ext:\n");
                        out.append("    ldi  r18, 200\n");
                        out.append("retardo_1ms_int:\n");
                        out.append("    nop\n");
                        out.append("    dec  r18\n");
                        out.append("    brne retardo_1ms_int\n");
                        out.append("    dec  r19\n");
                        out.append("    brne retardo_1ms_ext\n");
                        out.append("    ret\n");
                    }
                }
            }

            if (plan.usesDelayMs()) {
                out.append("\n; Retardo bloqueante de r21:r20 ms\n");
                out.append("retardo_ms:\n");
                out.append("    rcall retardo_1ms\n");
                if (elementary()) {
                    out.append("    cpi  r20, 0\n");
                    out.append("    brne retardo_ms_dec\n");
                    out.append("    dec  r21\n");
                    out.append("retardo_ms_dec:\n");
                    out.append("    dec  r20\n");
                    out.append("    mov  r16, r20\n");
                    out.append("    or   r16, r21\n");
                    out.append("    brne retardo_ms\n");
                } else {
                    out.append("    subi r20, 1\n");
                    out.append("    sbci r21, 0\n");
                    out.append("    brne retardo_ms\n");
                }
                out.append("    ret\n");
            }

            for (Hardware.BcdDisplay d : spec.componentsOf(Hardware.BcdDisplay.class)) {
                out.append("\n; Escribe dígito 0..9 (r16) en display BCD ").append(d.name()).append('\n');
                out.append("mostrar_bcd_").append(d.name()).append(":\n");
                out.append("    andi r16, 0x0F\n");
                for (int s = 0; s < d.firstPin().bit(); s++) {
                    out.append("    lsl  r16\n");
                }
                out.append("    in   r17, ").append(d.firstPin().port().portReg()).append('\n');
                out.append("    andi r17, 0x").append(String.format("%02X", (~d.portMask()) & 0xFF)).append('\n');
                out.append("    or   r17, r16\n");
                out.append("    out  ").append(d.firstPin().port().portReg()).append(", r17\n");
                out.append("    ret\n");
            }

            if (plan.usesDecimal2()) {
                out.append("\n; Divide r16 entre 10 -> decenas en r17, unidades en r16\n");
                out.append("divmod_10:\n");
                out.append("    clr  r17\n");
                if (elementary()) out.append("    ldi  r18, 10\n");
                out.append("divmod_10_lazo:\n");
                out.append("    cpi  r16, 10\n");
                out.append("    brlo divmod_10_fin\n");
                out.append(elementary() ? "    sub  r16, r18\n" : "    subi r16, 10\n");
                out.append("    inc  r17\n");
                out.append("    rjmp divmod_10_lazo\n");
                out.append("divmod_10_fin:\n");
                out.append("    ret\n");
            }

            if (plan.usesModulo()) {
                out.append("\n; Calcula r16 % r17 -> resto en r16\n");
                out.append("mod_u8:\n");
                out.append("    cp   r16, r17\n");
                out.append("    brlo mod_u8_fin\n");
                out.append("    sub  r16, r17\n");
                out.append("    rjmp mod_u8\n");
                out.append("mod_u8_fin:\n");
                out.append("    ret\n");
            }

            if (plan.readsAnyAdc()) {
                out.append("\n; Lee canal ADC (r16) -> muestra de 10 bits en r17:r16\n");
                out.append("leer_adc10:\n");
                out.append("    andi r16, 0x07\n");
                out.append("    ori  r16, (1 << REFS0)\n");
                out.append("    sts  ADMUX, r16\n");
                out.append("    lds  r16, ADCSRA\n");
                out.append("    ori  r16, (1 << ADSC)\n");
                out.append("    sts  ADCSRA, r16\n");
                out.append("leer_adc10_esp:\n");
                out.append("    lds  r16, ADCSRA\n");
                out.append("    sbrc r16, ADSC\n");
                out.append("    rjmp leer_adc10_esp\n");
                out.append("    lds  r16, ADCL\n");
                out.append("    lds  r17, ADCH\n");
                out.append("    ret\n");

                if (plan.usesAdc8()) {
                    out.append("\n; Lee canal ADC (r16) -> equivalente 8 bits (muestra10 >> 2) en r16\n");
                    out.append("leer_adc8:\n");
                    out.append("    rcall leer_adc10\n");
                    out.append("    lsr  r17\n");
                    out.append("    ror  r16\n");
                    out.append("    lsr  r17\n");
                    out.append("    ror  r16\n");
                    out.append("    ret\n");
                }
            }

            if (plan.usesScale10()) {
                out.append("\n; Escala muestra 10 bits (r17:r16) por factor r18: (x * span) >> 10 -> r16\n");
                out.append("escalar_10b:\n");
                out.append("    mul  r16, r18\n");
                out.append("    mov  r19, r1\n");
                out.append("    mul  r17, r18\n");
                out.append("    add  r19, r0\n");
                out.append("    mov  r16, r1\n");
                out.append("    ldi  r17, 0\n");
                out.append("    adc  r16, r17\n");
                out.append("    clr  r1\n");
                out.append("    lsr  r16\n");
                out.append("    ror  r19\n");
                out.append("    lsr  r16\n");
                out.append("    ror  r19\n");
                out.append("    mov  r16, r19\n");
                out.append("    ret\n");
            }

            for (Hardware.PwmOutput pwm : spec.componentsOf(Hardware.PwmOutput.class)) {
                Hardware.PwmChannel ch = pwm.channel();
                Hardware.Pin pin = pwm.pin();
                String lbl = "fijar_pwm_" + pwm.name();
                String lblNo0 = fresh("pwm_no0");
                String lblAct = fresh("pwm_act");
                String clearMask = "~((1 << " + ch.com1Bit() + ") | (1 << " + ch.com0Bit() + "))";
                String ldTccr = ch.extendedIo() ? "lds" : "in ";
                String stTccr = ch.extendedIo() ? "sts" : "out";

                out.append("\n; Fija duty PWM (r16 = 0..255) en ").append(pwm.name())
                        .append(" (").append(pin).append(" / ").append(ch.name()).append(")\n");
                out.append(lbl).append(":\n");
                out.append("    cpi  r16, 0\n");
                out.append("    brne ").append(lblNo0).append('\n');
                out.append("    ").append(ldTccr).append("  r17, ").append(ch.tccrA()).append('\n');
                out.append("    andi r17, ").append(clearMask).append('\n');
                out.append("    ").append(stTccr).append("  ").append(ch.tccrA()).append(", r17\n");
                out.append("    cbi  ").append(pin.port().portReg()).append(", ").append(pin.bit()).append('\n');
                out.append("    ret\n");

                out.append(lblNo0).append(":\n");
                out.append("    cpi  r16, 255\n");
                out.append("    brne ").append(lblAct).append('\n');
                out.append("    ").append(ldTccr).append("  r17, ").append(ch.tccrA()).append('\n');
                out.append("    andi r17, ").append(clearMask).append('\n');
                out.append("    ").append(stTccr).append("  ").append(ch.tccrA()).append(", r17\n");
                out.append("    sbi  ").append(pin.port().portReg()).append(", ").append(pin.bit()).append('\n');
                out.append("    ret\n");

                out.append(lblAct).append(":\n");
                if (ch.timer() == Hardware.PwmTimer.TIMER1) {
                    String ocrH = ch == Hardware.PwmChannel.OC1A ? "OCR1AH" : "OCR1BH";
                    out.append("    clr  r17\n");
                    out.append("    sts  ").append(ocrH).append(", r17\n");
                    out.append("    sts  ").append(ch.ocrReg()).append(", r16\n");
                } else if (ch.extendedIo()) {
                    out.append("    sts  ").append(ch.ocrReg()).append(", r16\n");
                } else {
                    out.append("    out  ").append(ch.ocrReg()).append(", r16\n");
                }
                out.append("    ").append(ldTccr).append("  r17, ").append(ch.tccrA()).append('\n');
                out.append("    andi r17, ").append(clearMask).append('\n');
                out.append("    ori  r17, (1 << ").append(ch.com1Bit()).append(")\n");
                out.append("    ").append(stTccr).append("  ").append(ch.tccrA()).append(", r17\n");
                out.append("    ret\n");
            }

            if (plan.emitsUartTx()) {
                boolean halfDuplex = plan.uart().map(u -> u.mode() == Hardware.UartMode.HALF_DUPLEX).orElse(false);
                if (halfDuplex) {
                    out.append("\n; Envía un byte (r16) por USART0 en modo half-duplex (cambia turno RX -> TX -> RX)\n");
                    out.append("uart_enviar_byte:\n");
                    out.append("    ldi  r17, (1 << TXEN0)\n");
                    out.append("    sts  UCSR0B, r17\n");
                    out.append("uart_espera_udre:\n");
                    out.append("    lds  r17, UCSR0A\n");
                    out.append("    sbrs r17, UDRE0\n");
                    out.append("    rjmp uart_espera_udre\n");
                    out.append("    ldi  r17, (1 << U2X0) | (1 << TXC0)\n");
                    out.append("    sts  UCSR0A, r17\n");
                    out.append("    sts  UDR0, r16\n");
                    out.append("uart_espera_txc:\n");
                    out.append("    lds  r17, UCSR0A\n");
                    out.append("    sbrs r17, TXC0\n");
                    out.append("    rjmp uart_espera_txc\n");
                    out.append("    ldi  r17, (1 << RXEN0)\n");
                    out.append("    sts  UCSR0B, r17\n");
                    out.append("    ret\n");
                } else {
                    out.append("\n; Envía un byte (r16) por USART0\n");
                    out.append("uart_enviar_byte:\n");
                    out.append("    lds  r17, UCSR0A\n");
                    out.append("    sbrs r17, UDRE0\n");
                    out.append("    rjmp uart_enviar_byte\n");
                    out.append("    sts  UDR0, r16\n");
                    out.append("    ret\n");
                }

                if (!plan.uartStrings().isEmpty()) {
                    out.append("\n; Envía cadena ASCII terminada en 0 desde Flash (puntero Z)\n");
                    out.append("uart_enviar_texto:\n");
                    out.append("    lpm  r16, Z+\n");
                    emitTestR16Zero();
                    out.append("    breq uart_enviar_texto_fin\n");
                    out.append("    rcall uart_enviar_byte\n");
                    out.append("    rjmp uart_enviar_texto\n");
                    out.append("uart_enviar_texto_fin:\n");
                    out.append("    ret\n");
                }
            }
        }

        private void emitFlashStrings() {
            if (plan.uartStrings().isEmpty()) return;
            out.append("\n; --- Textos UART en memoria Flash ---\n");
            for (int i = 0; i < plan.uartStrings().size(); i++) {
                String text = plan.uartStrings().get(i);
                out.append("uart_txt_").append(i).append(": .db ");
                int byteCount = 0;
                for (int j = 0; j < text.length(); j++) {
                    if (j > 0) out.append(", ");
                    out.append((int) text.charAt(j));
                    byteCount++;
                }
                out.append(", 0");
                byteCount++;
                if ((byteCount & 1) == 1) {
                    out.append(", 0");
                }
                out.append(" ; \"").append(sanitizeAsmComment(text.replace("\r", "\\r").replace("\n", "\\n"))).append("\"\n");
            }
        }

        private static String phaseEqu(String group, String phase) {
            return ("EST_" + group + "_" + phase).toUpperCase(Locale.ROOT);
        }

        private static String sanitizeAsmComment(String text) {
            return text.replace("\r", " ").replace("\n", " ");
        }

        /**
         * Promueve únicamente las instrucciones {@code rjmp}/{@code rcall} que excedan el alcance
         * relativo de ±2048 palabras a {@code jmp}/{@code call} (soportadas por ATmega328P en los
         * 32 KB de Flash), preservando {@code rjmp}/{@code rcall} compactos en programas pequeños.
         */
        private static String upgradeLongRelativeBranches(String asm) {
            String[] lines = asm.split("\n", -1);
            int csegLines = 0;
            boolean inCseg = false;
            for (String line : lines) {
                String code = stripComment(line).trim();
                if (code.equalsIgnoreCase(".cseg")) { inCseg = true; continue; }
                if (code.equalsIgnoreCase(".dseg")) { inCseg = false; continue; }
                if (inCseg && !code.isEmpty()) csegLines++;
            }
            // Camino rápido O(N): con <= 1800 líneas en .cseg ningún rjmp/rcall puede exceder ±2048 palabras
            if (csegLines <= 1800) return asm;

            for (int iter = 0; iter < 5; iter++) {
                Map<String, Integer> labelAddr = new HashMap<>();
                inCseg = false;
                int pc = 0;
                for (String line : lines) {
                    String code = stripComment(line).trim();
                    if (code.isEmpty()) continue;
                    if (code.equalsIgnoreCase(".cseg")) { inCseg = true; continue; }
                    if (code.equalsIgnoreCase(".dseg")) { inCseg = false; continue; }
                    if (!inCseg) continue;
                    if (code.toLowerCase(Locale.ROOT).startsWith(".org")) {
                        pc = parseOrgWordAddress(code, pc);
                        continue;
                    }
                    int colon = code.indexOf(':');
                    if (colon > 0 && !containsWhitespace(code, colon)) {
                        String lbl = code.substring(0, colon).toLowerCase(Locale.ROOT);
                        labelAddr.put(lbl, pc);
                        code = code.substring(colon + 1).trim();
                        if (code.isEmpty()) continue;
                    }
                    pc += codeWordSize(code);
                }

                boolean changed = false;
                inCseg = false;
                pc = 0;
                for (int i = 0; i < lines.length; i++) {
                    String raw = lines[i];
                    String code = stripComment(raw).trim();
                    if (code.isEmpty()) continue;
                    if (code.equalsIgnoreCase(".cseg")) { inCseg = true; continue; }
                    if (code.equalsIgnoreCase(".dseg")) { inCseg = false; continue; }
                    if (!inCseg) continue;
                    if (code.toLowerCase(Locale.ROOT).startsWith(".org")) {
                        pc = parseOrgWordAddress(code, pc);
                        continue;
                    }
                    int colon = code.indexOf(':');
                    if (colon > 0 && !containsWhitespace(code, colon)) {
                        code = code.substring(colon + 1).trim();
                        if (code.isEmpty()) continue;
                    }
                    String[] parts = code.split("\\s+", 2);
                    String mnem = parts[0].toLowerCase(Locale.ROOT);
                    if ((mnem.equals("rjmp") || mnem.equals("rcall")) && parts.length == 2) {
                        String target = parts[1].trim().toLowerCase(Locale.ROOT);
                        Integer targetPc = labelAddr.get(target);
                        if (targetPc != null) {
                            int delta = targetPc - (pc + 1);
                            if (delta < -1950 || delta > 1950) {
                                lines[i] = mnem.equals("rjmp")
                                        ? raw.replaceFirst("(?i)\\brjmp\\b", "jmp ")
                                        : raw.replaceFirst("(?i)\\brcall\\b", "call");
                                changed = true;
                                pc += 2;
                                continue;
                            }
                        }
                    }
                    pc += codeWordSize(code);
                }
                if (!changed) break;
            }
            return String.join("\n", lines);
        }

        private static String stripComment(String line) {
            int semi = line.indexOf(';');
            return semi >= 0 ? line.substring(0, semi) : line;
        }

        private static boolean containsWhitespace(String s, int endExclusive) {
            for (int i = 0; i < endExclusive; i++) {
                if (Character.isWhitespace(s.charAt(i))) return true;
            }
            return false;
        }

        private static int parseOrgWordAddress(String orgLine, int fallback) {
            String arg = orgLine.substring(4).trim();
            if (arg.equalsIgnoreCase("INT_VECTORS_SIZE")) return 0x0034;
            try {
                if (arg.startsWith("0x") || arg.startsWith("0X")) {
                    return Integer.parseInt(arg.substring(2), 16);
                }
                return Integer.parseInt(arg);
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }

        private static int codeWordSize(String code) {
            String lower = code.toLowerCase(Locale.ROOT);
            if (lower.startsWith(".db")) {
                String args = code.substring(3).trim();
                if (args.isEmpty()) return 0;
                int items = 1;
                for (int i = 0; i < args.length(); i++) {
                    if (args.charAt(i) == ',') items++;
                }
                return (items + 1) / 2;
            }
            if (lower.startsWith(".")) return 0;
            int sp = 0;
            while (sp < lower.length() && !Character.isWhitespace(lower.charAt(sp))) sp++;
            String mnem = lower.substring(0, sp);
            return switch (mnem) {
                case "jmp", "call", "lds", "sts" -> 2;
                default -> 1;
            };
        }
    }
}
