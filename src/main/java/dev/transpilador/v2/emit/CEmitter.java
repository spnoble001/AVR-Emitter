package dev.transpilador.v2.emit;

import dev.transpilador.v2.model.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Emisor C99 bare-metal human-like para ATmega328P (avr-gcc).
 * Sigue la filosofía KISS ("Keep It As Simple As It Can"): variables locales limpias,
 * sin dependencias ni variables muertas, condiciones sin paréntesis redundantes y
 * bucle periódico directo sin banderas intermedias cuando es posible.
 */
public final class CEmitter {

    public record EmitOutput(String code, Map<Integer, TraceMap.LineRange> ruleLines) {
        public EmitOutput {
            ruleLines = Collections.unmodifiableMap(new LinkedHashMap<>(ruleLines));
        }
    }

    public String emit(ProgramSpec spec) {
        return emitWithTrace(EmissionPlan.analyze(spec), false).code();
    }

    public String emit(EmissionPlan plan) {
        return emitWithTrace(plan, false).code();
    }

    public EmitOutput emitWithTrace(ProgramSpec spec) {
        return emitWithTrace(EmissionPlan.analyze(spec), false);
    }

    public EmitOutput emitWithTrace(EmissionPlan plan) {
        return emitWithTrace(plan, false);
    }

    /**
     * Emite una variante interna solo para la simulación interactiva en {@code simavr}:
     * ubica variables, fases FSM y temporizadores en símbolos SRAM fijos ({@code volatile})
     * para poder leerlos en vivo desde ELF + {@code MEM} sin alterar la salida didáctica de {@link #emit}.
     */
    public String emitForSimulation(ProgramSpec spec) {
        return emitWithTrace(EmissionPlan.analyze(spec), true).code();
    }

    public String emitForSimulation(EmissionPlan plan) {
        return emitWithTrace(plan, true).code();
    }

    private EmitOutput emitWithTrace(EmissionPlan plan, boolean simTelemetry) {
        ProgramSpec spec = plan.spec();
        StringBuilder out = new StringBuilder();
        Map<Integer, TraceMap.LineRange> ruleLines = new LinkedHashMap<>();

        out.append("/*\n");
        out.append(" * Proyecto: ").append(sanitizeCComment(spec.name())).append('\n');
        out.append(" * Microcontrolador: ATmega328P @ ").append(spec.fCpuHz()).append(" Hz\n");
        out.append(" * Generado por Transpilador AVR v2 (C99 bare-metal)\n");
        out.append(" */\n");
        out.append("#define F_CPU ").append(spec.fCpuHz()).append("UL\n");
        out.append("#include <avr/io.h>\n");
        out.append("#include <stdint.h>\n");
        if (plan.usesClock() || plan.usesDelayMs()) {
            out.append("#include <util/delay.h>\n");
        }
        out.append('\n');

        emitPhaseEnums(out, plan);
        if (simTelemetry) {
            emitSimulationGlobals(out, plan);
        }
        emitPeripheralHelpers(out, plan);

        out.append("int main(void) {\n");
        emitHardwareInit(out, plan);
        emitLocalStateDecls(out, plan, simTelemetry);

        // Reglas de arranque (OnBoot)
        if (!plan.bootRules().isEmpty()) {
            out.append("\n    /* Arranque */\n");
            emitAdcCaptures(out, plan, plan.bootSnapshotAnalogs(), "    ");
            for (int i = 0; i < plan.bootRules().size(); i++) {
                int startLine = countNewlines(out) + 2;
                emitRule(out, plan, plan.bootRules().get(i), -1, "    ");
                int endLine = Math.max(startLine, countNewlines(out));
                ruleLines.put(plan.bootRuleOriginalIndex(i), new TraceMap.LineRange(startLine, endLine));
            }
        }

        // Bucle principal
        out.append("\n    while (1) {\n");
        if (plan.isPurePeriodicLoop()) {
            out.append("        _delay_ms(1);\n");
            for (int i = 0; i < plan.loopRules().size(); i++) {
                int startLine = countNewlines(out) + 2;
                emitPurePeriodicRule(out, plan, plan.loopRules().get(i), i);
                int endLine = Math.max(startLine, countNewlines(out));
                ruleLines.put(plan.loopRuleOriginalIndex(i), new TraceMap.LineRange(startLine, endLine));
            }
        } else {
            emitPassCaptures(out, plan);

            for (int i = 0; i < plan.loopRules().size(); i++) {
                int startLine = countNewlines(out) + 2;
                emitRule(out, plan, plan.loopRules().get(i), i, "        ");
                int endLine = Math.max(startLine, countNewlines(out));
                ruleLines.put(plan.loopRuleOriginalIndex(i), new TraceMap.LineRange(startLine, endLine));
            }

            if (plan.usesClock()) {
                emitCooperativeTick(out, plan);
            } else if (plan.loopRules().isEmpty() && plan.edgeButtons().isEmpty() && !plan.emitsUartRx()) {
                out.append("        __asm__ __volatile__(\"nop\"); /* mantener CPU activa para periféricos */\n");
            }
        }

        out.append("    }\n");
        out.append("}\n");
        return new EmitOutput(out.toString(), ruleLines);
    }

    private static int countNewlines(StringBuilder sb) {
        int n = 0;
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') n++;
        }
        return n;
    }

    private void emitSimulationGlobals(StringBuilder out, EmissionPlan plan) {
        boolean any = !plan.activeVars().isEmpty() || !plan.activePhaseGroups().isEmpty() || !plan.activeTimers().isEmpty();
        if (!any) return;
        out.append("/* Símbolos de telemetría SRAM para inspección en vivo (simavr) */\n");
        for (State.VarDecl v : plan.activeVars()) {
            out.append("volatile uint8_t var_").append(v.name()).append(";\n");
        }
        for (State.PhaseGroup g : plan.activePhaseGroups()) {
            out.append("volatile uint8_t fase_").append(g.name()).append(";\n");
        }
        for (State.TimerDecl t : plan.activeTimers()) {
            out.append("volatile uint16_t rem_").append(t.name()).append(";\n");
        }
        out.append('\n');
    }

    private void emitPhaseEnums(StringBuilder out, EmissionPlan plan) {
        if (plan.activePhaseGroups().isEmpty()) return;
        for (State.PhaseGroup g : plan.activePhaseGroups()) {
            out.append("enum { ");
            for (int i = 0; i < g.phases().size(); i++) {
                if (i > 0) out.append(", ");
                out.append(phaseConst(g.name(), g.phases().get(i))).append(" = ").append(i);
            }
            out.append(" };\n");
        }
        out.append('\n');
    }

    private void emitPeripheralHelpers(StringBuilder out, EmissionPlan plan) {
        ProgramSpec spec = plan.spec();

        for (Hardware.Button b : spec.componentsOf(Hardware.Button.class)) {
            if (!plan.readsButton(b.name())) continue;
            out.append("static uint8_t boton_").append(b.name()).append("_presionado(void) {\n");
            String bit = "(" + b.pin().port().pinReg() + " & (1 << " + b.pin().bit() + "))";
            out.append("    return ").append(b.activeHigh() ? bit + " != 0" : bit + " == 0").append(";\n");
            out.append("}\n\n");
        }

        for (Hardware.BcdDisplay d : spec.componentsOf(Hardware.BcdDisplay.class)) {
            out.append("static void mostrar_bcd_").append(d.name()).append("(uint8_t digito) {\n");
            String port = d.firstPin().port().portReg();
            int keepMask = (~d.portMask()) & 0xFF;
            String val = d.firstPin().bit() == 0
                    ? "(digito & 0x0F)"
                    : "((digito & 0x0F) << " + d.firstPin().bit() + ")";
            out.append("    ").append(port).append(" = (").append(port)
                    .append(" & 0x").append(String.format("%02X", keepMask))
                    .append(") | ").append(val).append(";\n");
            out.append("}\n\n");
        }

        if (plan.readsAnyAdc()) {
            out.append("static uint16_t leer_adc10(uint8_t canal) {\n");
            out.append("    ADMUX = (1 << REFS0) | (canal & 0x07);\n");
            out.append("    ADCSRA |= (1 << ADSC);\n");
            out.append("    while (ADCSRA & (1 << ADSC)) {}\n");
            out.append("    return ADC;\n");
            out.append("}\n\n");
            if (plan.usesAdc8()) {
                out.append("static uint8_t leer_adc8(uint8_t canal) {\n");
                out.append("    return (uint8_t)(leer_adc10(canal) >> 2);\n");
                out.append("}\n\n");
            }
        }

        for (Hardware.PwmOutput pwm : spec.componentsOf(Hardware.PwmOutput.class)) {
            Hardware.PwmChannel ch = pwm.channel();
            Hardware.Pin pin = pwm.pin();
            String clearCom = "(uint8_t)~((1 << " + ch.com1Bit() + ") | (1 << " + ch.com0Bit() + "))";
            out.append("static void fijar_pwm_").append(pwm.name()).append("(uint8_t duty) {\n");
            out.append("    if (duty == 0) {\n");
            out.append("        ").append(ch.tccrA()).append(" &= ").append(clearCom).append(";\n");
            out.append("        ").append(pin.port().portReg()).append(" &= ~(1 << ").append(pin.bit()).append(");\n");
            out.append("    } else if (duty == 255) {\n");
            out.append("        ").append(ch.tccrA()).append(" &= ").append(clearCom).append(";\n");
            out.append("        ").append(pin.port().portReg()).append(" |= (1 << ").append(pin.bit()).append(");\n");
            out.append("    } else {\n");
            if (ch.timer() == Hardware.PwmTimer.TIMER1) {
                String ocr16 = ch == Hardware.PwmChannel.OC1A ? "OCR1A" : "OCR1B";
                out.append("        ").append(ocr16).append(" = duty;\n");
            } else {
                out.append("        ").append(ch.ocrReg()).append(" = duty;\n");
            }
            out.append("        ").append(ch.tccrA()).append(" = (").append(ch.tccrA())
                    .append(" & ").append(clearCom).append(") | (1 << ").append(ch.com1Bit()).append(");\n");
            out.append("    }\n");
            out.append("}\n\n");
        }

        if (plan.usesAdcInRange8()) {
            out.append("static uint8_t en_rango_u8(uint8_t v, uint8_t lo, uint8_t hi) {\n");
            out.append("    return v >= lo && v <= hi;\n");
            out.append("}\n\n");
        }
        if (plan.usesAdcInRange10()) {
            out.append("static uint8_t en_rango_u10(uint16_t v, uint16_t lo, uint16_t hi) {\n");
            out.append("    return v >= lo && v <= hi;\n");
            out.append("}\n\n");
        }

        if (plan.emitsUartTx()) {
            boolean halfDuplex = plan.uart().map(u -> u.mode() == Hardware.UartMode.HALF_DUPLEX).orElse(false);
            out.append("static void uart_enviar_byte(uint8_t dato) {\n");
            if (halfDuplex) {
                out.append("    /* Turno half-duplex: deshabilita RX, transmite byte completo y vuelve a RX */\n");
                out.append("    UCSR0B = (1 << TXEN0);\n");
                out.append("    while (!(UCSR0A & (1 << UDRE0))) {}\n");
                out.append("    UCSR0A = (1 << U2X0) | (1 << TXC0);\n");
                out.append("    UDR0 = dato;\n");
                out.append("    while (!(UCSR0A & (1 << TXC0))) {}\n");
                out.append("    UCSR0B = (1 << RXEN0);\n");
            } else {
                out.append("    while (!(UCSR0A & (1 << UDRE0))) {}\n");
                out.append("    UDR0 = dato;\n");
            }
            out.append("}\n\n");
            if (!plan.uartStrings().isEmpty()) {
                out.append("static void uart_enviar_texto(const char *texto) {\n");
                out.append("    while (*texto) uart_enviar_byte((uint8_t)*texto++);\n");
                out.append("}\n\n");
            }
        }
    }

    private void emitHardwareInit(StringBuilder out, EmissionPlan plan) {
        ProgramSpec spec = plan.spec();
        out.append("    /* Pines y periféricos */\n");

        for (Hardware.Button b : spec.componentsOf(Hardware.Button.class)) {
            out.append("    ").append(b.pin().port().ddr()).append(" &= ~(1 << ").append(b.pin().bit()).append(");");
            if (b.connection() == Hardware.Connection.GND) {
                out.append(" ").append(b.pin().port().portReg()).append(" |= (1 << ").append(b.pin().bit()).append(");");
                out.append(" /* ").append(b.name()).append(" en ").append(b.pin()).append(" (pull-up) */\n");
            } else {
                out.append(" ").append(b.pin().port().portReg()).append(" &= ~(1 << ").append(b.pin().bit()).append(");");
                out.append(" /* ").append(b.name()).append(" en ").append(b.pin()).append(" (pull-down) */\n");
            }
        }

        for (Hardware.Led l : spec.componentsOf(Hardware.Led.class)) {
            boolean highInit = (l.initialOn() == l.activeHigh());
            out.append("    ");
            if (highInit) {
                out.append(l.pin().port().portReg()).append(" |= (1 << ").append(l.pin().bit()).append("); ");
            } else {
                out.append(l.pin().port().portReg()).append(" &= ~(1 << ").append(l.pin().bit()).append("); ");
            }
            out.append(l.pin().port().ddr()).append(" |= (1 << ").append(l.pin().bit()).append(");");
            out.append(" /* ").append(l.name()).append(" en ").append(l.pin()).append(" */\n");
        }

        for (Hardware.BcdDisplay d : spec.componentsOf(Hardware.BcdDisplay.class)) {
            out.append("    mostrar_bcd_").append(d.name()).append("(").append(d.initialDigit()).append("); ");
            out.append(d.firstPin().port().ddr()).append(" |= 0x").append(String.format("%02X", d.portMask()))
                    .append("; /* ").append(d.name()).append(" */\n");
        }

        if (plan.readsAnyAdc()) {
            var clk = spec.clockFreq();
            int didrMask = 0;
            for (Hardware.AnalogInput a : spec.componentsOf(Hardware.AnalogInput.class)) {
                out.append("    DDRC &= ~(1 << ").append(a.channel()).append("); ")
                        .append("PORTC &= ~(1 << ").append(a.channel()).append("); /* ")
                        .append(a.name()).append(" en ").append(a.pin()).append(" */\n");
                didrMask |= (1 << a.channel());
            }
            out.append("    DIDR0 |= 0x").append(String.format("%02X", didrMask)).append(";\n");
            out.append("    ADMUX = (1 << REFS0);\n");
            out.append("    ADCSRA = (1 << ADEN) | ").append(clk.adcPrescalerExpr())
                    .append("; /* ADC ").append(clk.adcClockKHz()).append(" kHz @ ").append(clk.label()).append(" */\n");
        }

        if (plan.usesPwm()) {
            var clk = spec.clockFreq();
            for (Hardware.PwmTimer timer : plan.usedPwmTimers()) {
                String cs = clk.pwmClockSelectExpr(timer);
                switch (timer) {
                    case TIMER0 -> {
                        out.append("    TCCR0A = (1 << WGM01) | (1 << WGM00);\n");
                        out.append("    TCCR0B = ").append(cs).append("; /* Timer0 Fast PWM /")
                                .append(clk.pwmPrescaler()).append(" ≈").append(clk.pwmFrequencyHz()).append(" Hz */\n");
                    }
                    case TIMER1 -> {
                        out.append("    TCCR1A = (1 << WGM10);\n");
                        out.append("    TCCR1B = (1 << WGM12) | ").append(cs).append("; /* Timer1 Fast PWM /")
                                .append(clk.pwmPrescaler()).append(" ≈").append(clk.pwmFrequencyHz()).append(" Hz */\n");
                    }
                    case TIMER2 -> {
                        out.append("    TCCR2A = (1 << WGM21) | (1 << WGM20);\n");
                        out.append("    TCCR2B = ").append(cs).append("; /* Timer2 Fast PWM /")
                                .append(clk.pwmPrescaler()).append(" ≈").append(clk.pwmFrequencyHz()).append(" Hz */\n");
                    }
                }
            }
            for (Hardware.PwmOutput pwm : spec.componentsOf(Hardware.PwmOutput.class)) {
                out.append("    ").append(pwm.pin().port().ddr()).append(" |= (1 << ").append(pwm.pin().bit()).append("); ");
                out.append("fijar_pwm_").append(pwm.name()).append("(").append(pwm.initialDuty()).append(");\n");
            }
        }

        plan.uart().ifPresent(u -> {
            int ubrr = u.baud().ubrrU2x(spec.fCpuHz());
            out.append("    UCSR0A = (1 << U2X0);\n");
            out.append("    UBRR0H = ").append((ubrr >> 8) & 0x0F)
                    .append("; UBRR0L = ").append(ubrr & 0xFF).append("; /* ")
                    .append(u.baud().bps()).append(" bps 8N1 @ ").append(spec.clockFreq().label())
                    .append(" */\n");
            out.append("    UCSR0C = (1 << UCSZ01) | (1 << UCSZ00);\n");
            String enableBits = switch (u.mode()) {
                case FULL_DUPLEX -> "(1 << RXEN0) | (1 << TXEN0)";
                case HALF_DUPLEX, RX_ONLY -> "(1 << RXEN0)";
                case TX_ONLY -> "(1 << TXEN0)";
            };
            out.append("    UCSR0B = ").append(enableBits).append(";\n");
        });
    }

    private void emitLocalStateDecls(StringBuilder out, EmissionPlan plan, boolean simTelemetry) {
        boolean any = !plan.activeVars().isEmpty() || !plan.activePhaseGroups().isEmpty()
                || !plan.edgeButtons().isEmpty() || !plan.activeTimers().isEmpty()
                || !plan.periodicTasks().isEmpty() || plan.emitsUartRx()
                || !plan.cachedAnalogs().isEmpty();
        if (!any) return;

        out.append("\n    /* Estado */\n");
        for (State.VarDecl v : plan.activeVars()) {
            if (simTelemetry) {
                out.append("    var_").append(v.name()).append(" = ").append(v.initial()).append(";\n");
            } else {
                out.append("    uint8_t var_").append(v.name())
                        .append(" = ").append(v.initial()).append(";\n");
                if (!plan.readsVar(v.name())) {
                    out.append("    (void)var_").append(v.name()).append(";\n");
                }
            }
        }
        for (State.PhaseGroup g : plan.activePhaseGroups()) {
            boolean read = plan.readsPhaseGroup(g.name());
            if (simTelemetry) {
                out.append("    fase_").append(g.name())
                        .append(" = ").append(phaseConst(g.name(), g.initialPhase())).append(";\n");
                if (read) {
                    out.append("    uint8_t fase_pasada_").append(g.name()).append(" = fase_").append(g.name()).append(";\n");
                }
            } else {
                out.append("    uint8_t fase_").append(g.name())
                        .append(" = ").append(phaseConst(g.name(), g.initialPhase())).append(";\n");
                if (read) {
                    out.append("    uint8_t fase_pasada_").append(g.name()).append(" = fase_").append(g.name()).append(";\n");
                } else {
                    out.append("    (void)fase_").append(g.name()).append(";\n");
                }
            }
        }
        for (String btn : plan.edgeButtons()) {
            out.append("    uint8_t prev_").append(btn).append(" = boton_").append(btn).append("_presionado();\n");
        }
        for (State.TimerDecl t : plan.activeTimers()) {
            if (simTelemetry) {
                out.append("    rem_").append(t.name()).append(" = 0;\n");
            } else {
                out.append("    uint16_t rem_").append(t.name()).append(" = 0;\n");
            }
            if (plan.usesTimerExpired(t.name())) {
                out.append("    uint8_t exp_").append(t.name()).append(" = 0;\n");
            }
        }
        boolean purePeriodic = plan.isPurePeriodicLoop();
        for (EmissionPlan.PeriodicTask pt : plan.periodicTasks()) {
            out.append("    uint16_t per_").append(pt.id()).append(" = ").append(pt.periodMs()).append(";\n");
            if (!purePeriodic) {
                out.append("    uint8_t per_").append(pt.id()).append("_ok = 0;\n");
            }
        }
        if (plan.emitsUartRx()) {
            out.append("    uint8_t uart_rx_ok = 0;\n");
            if (plan.readsUartRxByte()) {
                out.append("    uint8_t uart_rx_byte = 0;\n");
            }
        }
        for (String analog : plan.cachedAnalogs()) {
            String type = plan.isCachedAnalog10Bit(analog) ? "uint16_t" : "uint8_t";
            out.append("    ").append(type).append(" adc_").append(analog).append(" = 0;\n");
        }
    }

    private void emitAdcCaptures(StringBuilder out, EmissionPlan plan, Set<String> analogs, String indent) {
        for (String analog : analogs) {
            int ch = plan.spec().analog(analog).orElseThrow().channel();
            String fn = plan.isCachedAnalog10Bit(analog) ? "leer_adc10" : "leer_adc8";
            out.append(indent).append("adc_").append(analog).append(" = ")
                    .append(fn).append("(").append(ch).append(");\n");
        }
    }

    private void emitPassCaptures(StringBuilder out, EmissionPlan plan) {
        for (String btn : plan.edgeButtons()) {
            out.append("        uint8_t cur_").append(btn).append(" = boton_").append(btn).append("_presionado();\n");
            if (plan.usesPress(btn)) {
                out.append("        uint8_t rise_").append(btn).append(" = cur_").append(btn).append(" && !prev_").append(btn).append(";\n");
            }
            if (plan.usesRelease(btn)) {
                out.append("        uint8_t fall_").append(btn).append(" = !cur_").append(btn).append(" && prev_").append(btn).append(";\n");
            }
            out.append("        prev_").append(btn).append(" = cur_").append(btn).append(";\n");
        }
        for (State.PhaseGroup g : plan.activePhaseGroups()) {
            if (plan.readsPhaseGroup(g.name())) {
                out.append("        fase_pasada_").append(g.name()).append(" = fase_").append(g.name()).append(";\n");
            }
        }
        if (plan.emitsUartRx()) {
            out.append("        uart_rx_ok = 0;\n");
            out.append("        if (UCSR0A & (1 << RXC0)) {\n");
            if (plan.readsUartRxByte()) {
                out.append("            uart_rx_byte = UDR0;\n");
            } else {
                out.append("            (void)UDR0;\n");
            }
            out.append("            uart_rx_ok = 1;\n");
            out.append("        }\n");
        }
        emitAdcCaptures(out, plan, plan.passSnapshotAnalogs(), "        ");
    }

    private void emitPurePeriodicRule(StringBuilder out, EmissionPlan plan, Rule rule, int loopRuleIndex) {
        int pid = plan.periodicIdForLoopRule(loopRuleIndex);
        int periodMs = ((Rule.EveryMs) rule.trigger()).periodMs();
        Set<String> ruleCaptures = plan.ruleSnapshotAnalogs(loopRuleIndex);
        Set<String> activeAdc = plan.activeAdcSnapshotsForLoopRule(loopRuleIndex);
        out.append("\n        /* ").append(sanitizeCComment(rule.title())).append(" */\n");
        out.append("        if (--per_").append(pid).append(" == 0) {\n");
        out.append("            per_").append(pid).append(" = ").append(periodMs).append(";\n");
        emitAdcCaptures(out, plan, ruleCaptures, "            ");

        boolean trivialGuard = rule.guard() instanceof Expr.Truth t && t.value();
        if (trivialGuard) {
            emitEffects(out, plan, rule.thenEffects(), activeAdc, "            ");
        } else {
            out.append("            if (").append(topBoolExpr(plan, rule.guard(), activeAdc)).append(") {\n");
            emitEffects(out, plan, rule.thenEffects(), activeAdc, "                ");
            if (!rule.elseEffects().isEmpty()) {
                out.append("            } else {\n");
                emitEffects(out, plan, rule.elseEffects(), activeAdc, "                ");
            }
            out.append("            }\n");
        }
        out.append("        }\n");
    }

    private void emitRule(StringBuilder out, EmissionPlan plan, Rule rule, int loopRuleIndex, String indent) {
        out.append('\n').append(indent).append("/* ").append(sanitizeCComment(rule.title())).append(" */\n");
        String trigCond = triggerCondition(plan, rule.trigger(), loopRuleIndex);
        boolean hasTrigger = trigCond != null;
        boolean trivialGuard = rule.guard() instanceof Expr.Truth t && t.value();
        Set<String> ruleCaptures = loopRuleIndex >= 0 ? plan.ruleSnapshotAnalogs(loopRuleIndex) : Set.of();
        Set<String> activeAdc = loopRuleIndex >= 0
                ? plan.activeAdcSnapshotsForLoopRule(loopRuleIndex)
                : plan.bootSnapshotAnalogs();

        // Combinar trigger && guard en un solo if cuando no hay rama else ni capturas ADC locales (KISS)
        if (hasTrigger && !trivialGuard && rule.elseEffects().isEmpty() && ruleCaptures.isEmpty()) {
            boolean needsParens = rule.guard() instanceof Expr.Or || rule.guard() instanceof Expr.Xor;
            String guardStr = needsParens
                    ? "(" + topBoolExpr(plan, rule.guard(), activeAdc) + ")"
                    : topBoolExpr(plan, rule.guard(), activeAdc);
            out.append(indent).append("if (").append(trigCond).append(" && ").append(guardStr).append(") {\n");
            emitEffects(out, plan, rule.thenEffects(), activeAdc, indent + "    ");
            out.append(indent).append("}\n");
            return;
        }

        String bodyIndent = indent;
        if (hasTrigger) {
            out.append(indent).append("if (").append(trigCond).append(") {\n");
            bodyIndent = indent + "    ";
        }
        emitAdcCaptures(out, plan, ruleCaptures, bodyIndent);

        if (trivialGuard) {
            emitEffects(out, plan, rule.thenEffects(), activeAdc, bodyIndent);
        } else {
            out.append(bodyIndent).append("if (").append(topBoolExpr(plan, rule.guard(), activeAdc)).append(") {\n");
            emitEffects(out, plan, rule.thenEffects(), activeAdc, bodyIndent + "    ");
            if (!rule.elseEffects().isEmpty()) {
                out.append(bodyIndent).append("} else {\n");
                emitEffects(out, plan, rule.elseEffects(), activeAdc, bodyIndent + "    ");
            }
            out.append(bodyIndent).append("}\n");
        }

        if (hasTrigger) {
            out.append(indent).append("}\n");
        }
    }

    private String triggerCondition(EmissionPlan plan, Rule.Trigger trigger, int loopRuleIndex) {
        return switch (trigger) {
            case Rule.OnBoot ignored -> null;
            case Rule.Always ignored -> null;
            case Rule.OnPress p -> "rise_" + p.buttonName();
            case Rule.OnRelease r -> "fall_" + r.buttonName();
            case Rule.EveryMs ignored -> "per_" + plan.periodicIdForLoopRule(loopRuleIndex) + "_ok";
            case Rule.OnTimerExpired t -> "exp_" + t.timerName();
            case Rule.OnUartRx ignored -> "uart_rx_ok";
        };
    }

    private void emitEffects(StringBuilder out, EmissionPlan plan, List<Effect> effects, Set<String> activeAdc, String indent) {
        ProgramSpec spec = plan.spec();
        for (Effect eff : effects) {
            switch (eff) {
                case Effect.WriteLed w -> {
                    Hardware.Led led = spec.led(w.ledName()).orElseThrow();
                    boolean high = (w.state() == Effect.LedState.ON) == led.activeHigh();
                    if (high) {
                        out.append(indent).append(led.pin().port().portReg())
                                .append(" |= (1 << ").append(led.pin().bit()).append(");\n");
                    } else {
                        out.append(indent).append(led.pin().port().portReg())
                                .append(" &= ~(1 << ").append(led.pin().bit()).append(");\n");
                    }
                }
                case Effect.ToggleLed t -> {
                    Hardware.Led led = spec.led(t.ledName()).orElseThrow();
                    out.append(indent).append(led.pin().port().portReg())
                            .append(" ^= (1 << ").append(led.pin().bit()).append(");\n");
                }
                case Effect.WriteBcd b -> out.append(indent).append("mostrar_bcd_").append(b.displayName())
                        .append("(").append(valueExpr(plan, b.value(), activeAdc)).append(");\n");
                case Effect.WriteDecimal2 d -> {
                    out.append(indent).append("{\n");
                    out.append(indent).append("    uint8_t val = ").append(valueExpr(plan, d.value(), activeAdc)).append(";\n");
                    out.append(indent).append("    mostrar_bcd_").append(d.tensDisplay()).append("(val / 10);\n");
                    out.append(indent).append("    mostrar_bcd_").append(d.unitsDisplay()).append("(val % 10);\n");
                    out.append(indent).append("}\n");
                }
                case Effect.WritePwm p -> out.append(indent).append("fijar_pwm_").append(p.pwmName())
                        .append("(").append(valueExpr(plan, p.duty(), activeAdc)).append(");\n");
                case Effect.SendUartText s -> out.append(indent).append("uart_enviar_texto(\"")
                        .append(escapeCString(s.ascii())).append("\");\n");
                case Effect.SendUartByte b -> out.append(indent).append("uart_enviar_byte(")
                        .append(valueExpr(plan, b.value(), activeAdc)).append(");\n");
                case Effect.SetVar s -> out.append(indent).append("var_").append(s.varName())
                        .append(" = ").append(valueExpr(plan, s.value(), activeAdc)).append(";\n");
                case Effect.StepVar s -> emitStepVar(out, spec.var(s.varName()).orElseThrow(), s.direction(), indent);
                case Effect.ResetVar r -> {
                    State.VarDecl v = spec.var(r.varName()).orElseThrow();
                    out.append(indent).append("var_").append(v.name())
                            .append(" = ").append(v.initial()).append(";\n");
                }
                case Effect.SetPhase sp -> out.append(indent).append("fase_").append(sp.groupName())
                        .append(" = ").append(phaseConst(sp.groupName(), sp.phaseName())).append(";\n");
                case Effect.StartTimer st -> out.append(indent).append("rem_").append(st.timerName())
                        .append(" = ").append(st.durationMs()).append(";\n");
                case Effect.StopTimer st -> out.append(indent).append("rem_").append(st.timerName())
                        .append(" = 0;\n");
                case Effect.DelayMs d -> out.append(indent).append("_delay_ms(").append(d.durationMs()).append(");\n");
            }
        }
    }

    private void emitStepVar(StringBuilder out, State.VarDecl v, Effect.StepDir dir, String indent) {
        String name = "var_" + v.name();
        if (dir == Effect.StepDir.INC) {
            if (v.policy() == State.VarPolicy.WRAP) {
                if (v.min() == 0 && v.max() == 255) {
                    out.append(indent).append(name).append("++;\n");
                } else {
                    out.append(indent).append("if (").append(name).append(" >= ").append(v.max())
                            .append(") ").append(name).append(" = ").append(v.min())
                            .append("; else ").append(name).append("++;\n");
                }
            } else {
                out.append(indent).append("if (").append(name).append(" < ").append(v.max())
                        .append(") ").append(name).append("++;\n");
            }
        } else {
            if (v.policy() == State.VarPolicy.WRAP) {
                if (v.min() == 0 && v.max() == 255) {
                    out.append(indent).append(name).append("--;\n");
                } else {
                    out.append(indent).append("if (").append(name).append(" <= ").append(v.min())
                            .append(") ").append(name).append(" = ").append(v.max())
                            .append("; else ").append(name).append("--;\n");
                }
            } else {
                out.append(indent).append("if (").append(name).append(" > ").append(v.min())
                        .append(") ").append(name).append("--;\n");
            }
        }
    }

    private void emitCooperativeTick(StringBuilder out, EmissionPlan plan) {
        out.append("\n        /* Base de tiempo cooperativa (1 ms) */\n");
        out.append("        _delay_ms(1);\n");
        for (State.TimerDecl t : plan.activeTimers()) {
            boolean trackExp = plan.usesTimerExpired(t.name());
            if (trackExp) out.append("        exp_").append(t.name()).append(" = 0;\n");
            out.append("        if (rem_").append(t.name()).append(" > 0) {\n");
            out.append("            rem_").append(t.name()).append("--;\n");
            if (trackExp) {
                out.append("            if (rem_").append(t.name()).append(" == 0) exp_").append(t.name()).append(" = 1;\n");
            }
            out.append("        }\n");
        }
        for (EmissionPlan.PeriodicTask pt : plan.periodicTasks()) {
            out.append("        per_").append(pt.id()).append("_ok = 0;\n");
            out.append("        if (--per_").append(pt.id()).append(" == 0) {\n");
            out.append("            per_").append(pt.id()).append(" = ").append(pt.periodMs()).append(";\n");
            out.append("            per_").append(pt.id()).append("_ok = 1;\n");
            out.append("        }\n");
        }
    }

    /** Expresión booleana a nivel raíz sin paréntesis exteriores redundantes. */
    private String topBoolExpr(EmissionPlan plan, Expr.BoolExpr expr, Set<String> activeAdc) {
        return switch (expr) {
            case Expr.Truth t -> t.value() ? "1" : "0";
            case Expr.Pressed p -> "boton_" + p.buttonName() + "_presionado()";
            case Expr.Compare c -> emitCompareExpr(plan, c, activeAdc);
            case Expr.InRange r -> emitInRangeExpr(plan, r, activeAdc);
            case Expr.Modulo m -> {
                String v = "var_" + m.varName();
                String mod = "(" + v + " % " + m.divisor() + ") == " + m.remainder();
                yield m.includeZero() ? mod : v + " != 0 && " + mod;
            }
            case Expr.InPhase ip -> "fase_pasada_" + ip.groupName() + " == " + phaseConst(ip.groupName(), ip.phaseName());
            case Expr.TimerActive ta -> "rem_" + ta.timerName() + " > 0";
            case Expr.UartByteEquals u -> "uart_rx_byte == " + formatCByte(u.expectedByte());
            case Expr.Not n -> "!" + nestedBoolExpr(plan, n.inner(), activeAdc);
            case Expr.And a -> a.items().stream().map(i -> nestedBoolExpr(plan, i, activeAdc)).collect(Collectors.joining(" && "));
            case Expr.Or o -> o.items().stream().map(i -> nestedBoolExpr(plan, i, activeAdc)).collect(Collectors.joining(" || "));
            case Expr.Xor x -> "(" + nestedBoolExpr(plan, x.left(), activeAdc) + " != 0) ^ (" + nestedBoolExpr(plan, x.right(), activeAdc) + " != 0)";
        };
    }

    private String emitInRangeExpr(EmissionPlan plan, Expr.InRange r, Set<String> activeAdc) {
        String v = valueExpr(plan, r.value(), activeAdc);
        if (EmissionPlan.containsUncachedAdc(r.value(), activeAdc)) {
            String fn = r.value().is10Bit() ? "en_rango_u10" : "en_rango_u8";
            return fn + "(" + v + ", " + r.min() + ", " + r.max() + ")";
        }
        int typeMax = r.value().is10Bit() ? 65535 : 255;
        boolean needMin = r.min() > 0;
        boolean needMax = r.max() < typeMax;
        if (!needMin && !needMax) {
            return safeConstantBool(r.value(), v, true);
        }
        if (!needMin) return v + " <= " + r.max();
        if (!needMax) return v + " >= " + r.min();
        return v + " >= " + r.min() + " && " + v + " <= " + r.max();
    }

    private String emitCompareExpr(EmissionPlan plan, Expr.Compare c, Set<String> activeAdc) {
        String ls = valueExpr(plan, c.left(), activeAdc);
        String rs = valueExpr(plan, c.right(), activeAdc);
        // Evitar -Werror=tautological-compare en autocomparaciones idénticas (ej. adc_pot == adc_pot, var_x == var_x)
        if (ls.equals(rs) && !EmissionPlan.containsUncachedAdc(c.left(), activeAdc)) {
            boolean always = (c.op() == Expr.CmpOp.EQ || c.op() == Expr.CmpOp.LE || c.op() == Expr.CmpOp.GE);
            return safeConstantBool(c.left(), ls, always);
        }
        // Evitar -Werror=type-limits en comparaciones de enteros sin signo contra 0 o >= 255/65535
        Integer leftK = constIntValue(c.left());
        Integer rightK = constIntValue(c.right());
        if (rightK != null && leftK == null) {
            Boolean constant = evaluateUnsignedTypeLimit(c.left().is10Bit() ? 65535 : 255, c.op(), rightK);
            if (constant != null) return safeConstantBool(c.left(), ls, constant);
        } else if (leftK != null && rightK == null) {
            Boolean constant = evaluateUnsignedTypeLimit(c.right().is10Bit() ? 65535 : 255, flipOp(c.op()), leftK);
            if (constant != null) return safeConstantBool(c.right(), rs, constant);
        }
        return ls + " " + c.op().symbol() + " " + rs;
    }

    private static Integer constIntValue(Expr.ValueExpr expr) {
        return switch (expr) {
            case Expr.ConstU8 c -> c.value();
            case Expr.ConstU10 c -> c.value();
            default -> null;
        };
    }

    private static Expr.CmpOp flipOp(Expr.CmpOp op) {
        return switch (op) {
            case LT -> Expr.CmpOp.GT;
            case LE -> Expr.CmpOp.GE;
            case GT -> Expr.CmpOp.LT;
            case GE -> Expr.CmpOp.LE;
            case EQ -> Expr.CmpOp.EQ;
            case NE -> Expr.CmpOp.NE;
        };
    }

    /** Devuelve Boolean si {@code x <op> k} es siempre verdadero o falso para todo {@code x in [0..typeMax]}. */
    private static Boolean evaluateUnsignedTypeLimit(int typeMax, Expr.CmpOp op, int k) {
        return switch (op) {
            case LT -> k <= 0 ? Boolean.FALSE : (k > typeMax ? Boolean.TRUE : null);
            case GE -> k <= 0 ? Boolean.TRUE : (k > typeMax ? Boolean.FALSE : null);
            case GT -> k >= typeMax ? Boolean.FALSE : null;
            case LE -> k >= typeMax ? Boolean.TRUE : null;
            case EQ -> (k < 0 || k > typeMax) ? Boolean.FALSE : null;
            case NE -> (k < 0 || k > typeMax) ? Boolean.TRUE : null;
        };
    }

    private static String safeConstantBool(Expr.ValueExpr expr, String rendered, boolean result) {
        if (constIntValue(expr) != null) return result ? "1" : "0";
        return "((void)(" + rendered + "), " + (result ? "1" : "0") + ")";
    }

    private String nestedBoolExpr(EmissionPlan plan, Expr.BoolExpr expr, Set<String> activeAdc) {
        return switch (expr) {
            case Expr.Truth ignored -> topBoolExpr(plan, expr, activeAdc);
            case Expr.Pressed ignored -> topBoolExpr(plan, expr, activeAdc);
            default -> "(" + topBoolExpr(plan, expr, activeAdc) + ")";
        };
    }

    private String valueExpr(EmissionPlan plan, Expr.ValueExpr expr, Set<String> activeAdc) {
        ProgramSpec spec = plan.spec();
        return switch (expr) {
            case Expr.ConstU8 c -> String.valueOf(c.value());
            case Expr.ConstU10 c -> String.valueOf(c.value());
            case Expr.ReadVar rv -> "var_" + rv.varName();
            case Expr.ReadAdc8 a -> {
                if (activeAdc.contains(a.analogName())) {
                    yield plan.isCachedAnalog10Bit(a.analogName())
                            ? "(uint8_t)(adc_" + a.analogName() + " >> 2)"
                            : "adc_" + a.analogName();
                }
                yield "leer_adc8(" + spec.analog(a.analogName()).orElseThrow().channel() + ")";
            }
            case Expr.ReadAdc10 a -> {
                if (activeAdc.contains(a.analogName())) {
                    yield "adc_" + a.analogName();
                }
                yield "leer_adc10(" + spec.analog(a.analogName()).orElseThrow().channel() + ")";
            }
            case Expr.ReadUartByte ignored -> "uart_rx_byte";
            case Expr.Scale s -> {
                String cast = s.inMax() == 255 ? "(uint16_t)" : "(uint32_t)";
                String base = "((uint8_t)((" + cast + valueExpr(plan, s.source(), activeAdc) + " * " + s.span() + ") >> " + s.shiftBits() + "))";
                yield s.outMin() == 0 ? base : "(uint8_t)(" + s.outMin() + " + " + base + ")";
            }
        };
    }

    private static String formatCByte(int code) {
        if (code == '\'') return "'\\''";
        if (code == '\\') return "'\\\\'";
        if (code >= 32 && code <= 126) return "'" + (char) code + "'";
        return String.valueOf(code);
    }

    private static String phaseConst(String group, String phase) {
        return ("EST_" + group + "_" + phase).toUpperCase(java.util.Locale.ROOT);
    }

    private static String sanitizeCComment(String text) {
        String s = text.replace("\r", " ")
                .replace("\n", " ")
                .replace("*/", "* /")
                .replace("/*", "/ *");
        while (s.contains("??")) {
            s = s.replace("??", "? ?");
        }
        return s;
    }

    private static String escapeCString(String ascii) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ascii.length(); i++) {
            char c = ascii.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '?' -> {
                    if (i > 0 && ascii.charAt(i - 1) == '?') {
                        sb.append("\\?");
                    } else {
                        sb.append('?');
                    }
                }
                default -> {
                    if (c < 32 || c == 127) {
                        sb.append(String.format("\\x%02X", (int) c));
                        if (i + 1 < ascii.length() && isHexDigit(ascii.charAt(i + 1))) {
                            sb.append("\"\"");
                        }
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
