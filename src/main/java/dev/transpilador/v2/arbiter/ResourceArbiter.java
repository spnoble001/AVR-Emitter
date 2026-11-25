package dev.transpilador.v2.arbiter;

import dev.transpilador.v2.model.*;

import java.util.*;

/**
 * Árbitro de recursos físicos y validador semántico para ATmega328P.
 * Verifica pines, periféricos (ADC, Timer0/1/2 Fast PWM, UART0), nombres,
 * referencias y compatibilidad de anchura (U8 vs U10).
 */
public final class ResourceArbiter {

    public enum Severity { ERROR, WARNING }

    public record Diagnostic(Severity severity, String code, String message) {
        public boolean isError() { return severity == Severity.ERROR; }
    }

    public record ResourceAllocation(String owner, String resource, String detail) {}

    public record ArbitrationResult(
            List<Diagnostic> diagnostics,
            List<ResourceAllocation> allocations) {

        public ArbitrationResult {
            diagnostics = List.copyOf(diagnostics);
            allocations = List.copyOf(allocations);
        }

        public boolean valid() {
            return diagnostics.stream().noneMatch(Diagnostic::isError);
        }

        public List<Diagnostic> errors() {
            return diagnostics.stream().filter(Diagnostic::isError).toList();
        }

        public List<Diagnostic> warnings() {
            return diagnostics.stream().filter(d -> d.severity() == Severity.WARNING).toList();
        }

        public void requireValid() {
            if (!valid()) {
                String summary = errors().stream()
                        .map(d -> "[" + d.code() + "] " + d.message())
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("Errores de validación");
                throw new IllegalArgumentException(summary);
            }
        }
    }

    private static final Set<String> RESERVED_NAMES = Set.of(
            "main", "inicio", "bucle", "setup", "loop", "true", "false",
            "DDRB", "DDRC", "DDRD", "PORTB", "PORTC", "PORTD", "PINB", "PINC", "PIND",
            "ADMUX", "ADCSRA", "ADCH", "ADCL", "ADC", "DIDR0",
            "UBRR0", "UCSR0A", "UCSR0B", "UCSR0C", "UDR0",
            "TCCR0A", "TCCR0B", "OCR0A", "OCR0B", "TCCR1A", "TCCR1B", "OCR1A", "OCR1B",
            "TCCR2A", "TCCR2B", "OCR2A", "OCR2B"
    );

    public ArbitrationResult analyze(ProgramSpec spec) {
        Objects.requireNonNull(spec, "spec");
        var diagnostics = new ArrayList<Diagnostic>();
        var allocations = new ArrayList<ResourceAllocation>();

        var symbolOwners = new LinkedHashMap<String, String>();
        var pinOwners = new LinkedHashMap<Hardware.Pin, String>();
        int uartCount = 0;

        // 1. Componentes físicos y pines
        for (Hardware.Component comp : spec.components()) {
            registerSymbol(symbolOwners, comp.name(), "componente", diagnostics);

            for (Hardware.Pin pin : comp.occupiedPins()) {
                String prev = pinOwners.putIfAbsent(pin, comp.name());
                if (prev != null) {
                    diagnostics.add(new Diagnostic(
                            Severity.ERROR,
                            "PIN_CONFLICT",
                            "Conflicto en pin " + pin.label() + " (DIP-" + pin.dip28Pin() + "): '"
                                    + prev + "' y '" + comp.name() + "' reclaman el mismo pin."));
                }
            }

            switch (comp) {
                case Hardware.Button b -> allocations.add(new ResourceAllocation(
                        b.name(), b.pin().label() + " (DIP-" + b.pin().dip28Pin() + ")",
                        b.connection() == Hardware.Connection.GND
                                ? "Entrada digital · pull-up interno (activo en bajo)"
                                : "Entrada digital · pull-down externo 10 kΩ (activo en alto)"));
                case Hardware.Led l -> allocations.add(new ResourceAllocation(
                        l.name(), l.pin().label() + " (DIP-" + l.pin().dip28Pin() + ")",
                        (l.activeHigh() ? "Salida digital (cátodo a GND)" : "Salida digital (ánodo a VCC)")
                                + ", inicial=" + (l.initialOn() ? "ON" : "OFF")));
                case Hardware.BcdDisplay d -> {
                    var pins = d.occupiedPins();
                    allocations.add(new ResourceAllocation(
                            d.name(),
                            pins.getFirst().label() + ".." + pins.getLast().label(),
                            "Display BCD CD4511 (4 bits consecutivos), inicial=" + d.initialDigit()));
                }
                case Hardware.AnalogInput a -> {
                    var clk = spec.clockFreq();
                    allocations.add(new ResourceAllocation(
                            a.name(), a.pin().label() + " / ADC" + a.channel() + " (DIP-" + a.pin().dip28Pin() + ")",
                            "ADC 10 bits · AVcc 5V · prescaler /" + clk.adcPrescaler()
                                    + " (" + clk.adcClockKHz() + " kHz @ " + clk.label() + ") · DIDR0 activo"));
                }
                case Hardware.PwmOutput p -> {
                    var ch = p.channel();
                    var clk = spec.clockFreq();
                    allocations.add(new ResourceAllocation(
                            p.name(),
                            p.pin().label() + " / " + ch.name() + " (DIP-" + p.pin().dip28Pin() + ")",
                            ch.timer().displayName() + " · Fast PWM 8 bits /" + clk.pwmPrescaler()
                                    + " (≈" + clk.pwmFrequencyHz() + " Hz @ " + clk.label() + "), inicial=" + p.initialDuty()));
                }
                case Hardware.UartPort u -> {
                    uartCount++;
                    if (uartCount > 1) {
                        diagnostics.add(new Diagnostic(
                                Severity.ERROR,
                                "UART_SINGLETON",
                                "El ATmega328P solo dispone de un periférico USART0; '" + u.name() + "' duplica el puerto serie."));
                    }
                    double errPct = u.baud().errorPercentU2x(spec.fCpuHz());
                    if (errPct > 2.5) {
                        diagnostics.add(new Diagnostic(
                                Severity.ERROR,
                                "UART_BAUD_UNSUPPORTED",
                                String.format(Locale.ROOT,
                                        "Velocidad %d bps tiene error de reloj de %.1f%% a %s (máx. admitido 2.5%%). Usa 8 MHz / 16 MHz o reduce la velocidad.",
                                        u.baud().bps(), errPct, spec.clockFreq().label())));
                    }
                    allocations.add(new ResourceAllocation(
                            u.name(),
                            u.occupiedPins().stream().map(Hardware.Pin::label).reduce((x, y) -> x + ", " + y).orElse(""),
                            String.format(Locale.ROOT, "USART0 · %d bps 8N1 · %s (UBRR0=%d, U2X0=1, error %.2f%% @ %s)",
                                    u.baud().bps(), u.mode(), u.baud().ubrrU2x(spec.fCpuHz()), errPct, spec.clockFreq().label())));
                }
            }
        }

        // 2. Estado mutable: variables, grupos de fases y temporizadores
        for (State.VarDecl v : spec.vars()) {
            registerSymbol(symbolOwners, v.name(), "variable", diagnostics);
            allocations.add(new ResourceAllocation(
                    v.name(), "Estado U8",
                    "Rango [" + v.min() + ".." + v.max() + "], inicial=" + v.initial() + ", modo=" + v.policy()));
        }

        var phaseSymbols = new LinkedHashMap<String, String>();
        for (State.PhaseGroup g : spec.phaseGroups()) {
            registerSymbol(symbolOwners, g.name(), "grupo de fases", diagnostics);
            for (String phase : g.phases()) {
                String sym = ("EST_" + g.name() + "_" + phase).toUpperCase(Locale.ROOT);
                String prev = phaseSymbols.putIfAbsent(sym, g.name() + "." + phase);
                if (prev != null) {
                    diagnostics.add(new Diagnostic(
                            Severity.ERROR,
                            "DUPLICATE_PHASE_SYMBOL",
                            "Colisión de símbolo emitido '" + sym + "' entre fases '" + prev + "' y '" + g.name() + "." + phase + "'."));
                }
            }
            allocations.add(new ResourceAllocation(
                    g.name(), "FSM (" + g.phases().size() + " fases)",
                    "Fases " + g.phases() + ", inicial=" + g.initialPhase()));
        }

        for (State.TimerDecl t : spec.timers()) {
            registerSymbol(symbolOwners, t.name(), "temporizador", diagnostics);
            allocations.add(new ResourceAllocation(
                    t.name(), "Temporizador cooperativo (16 bits)",
                    "Cuenta descendente por tick de 1 ms (no reserva Timer0 hardware)"));
        }

        // 3. Reglas (tarjetas)
        if (spec.rules().isEmpty()) {
            diagnostics.add(new Diagnostic(
                    Severity.WARNING,
                    "NO_RULES",
                    "El programa no tiene ninguna tarjeta de comportamiento."));
        }

        for (int i = 0; i < spec.rules().size(); i++) {
            Rule rule = spec.rules().get(i);
            String ctx = "Tarjeta #" + (i + 1) + " ('" + rule.title() + "')";
            validateTrigger(spec, rule.trigger(), ctx, diagnostics);
            validateBool(spec, rule.guard(), rule.trigger(), ctx, diagnostics);
            for (Effect eff : rule.thenEffects()) {
                validateEffect(spec, eff, rule.trigger(), ctx, diagnostics);
            }
            for (Effect eff : rule.elseEffects()) {
                validateEffect(spec, eff, rule.trigger(), ctx, diagnostics);
            }
            if (rule.trigger() instanceof Rule.OnBoot && !rule.elseEffects().isEmpty()) {
                diagnostics.add(new Diagnostic(
                        Severity.WARNING,
                        "BOOT_ELSE_UNUSUAL",
                        ctx + ": las tarjetas de arranque (OnBoot) suelen tener solo acciones principales."));
            }
        }

        // 4. Comprobación de uso de temporizadores sin iniciar
        Set<String> startedTimers = new HashSet<>();
        for (Rule r : spec.rules()) {
            collectStartedTimers(r.thenEffects(), startedTimers);
            collectStartedTimers(r.elseEffects(), startedTimers);
        }
        for (State.TimerDecl t : spec.timers()) {
            if (!startedTimers.contains(t.name())) {
                diagnostics.add(new Diagnostic(
                        Severity.WARNING,
                        "TIMER_NEVER_STARTED",
                        "El temporizador '" + t.name() + "' nunca se inicia con StartTimer."));
            }
        }

        return new ArbitrationResult(diagnostics, allocations);
    }

    private void registerSymbol(Map<String, String> owners, String name, String kind, List<Diagnostic> diagnostics) {
        String key = name.toUpperCase(Locale.ROOT);
        if (RESERVED_NAMES.stream().anyMatch(r -> r.equalsIgnoreCase(name))) {
            diagnostics.add(new Diagnostic(
                    Severity.ERROR,
                    "RESERVED_NAME",
                    "El nombre '" + name + "' (" + kind + ") está reservado por registros o símbolos AVR/C."));
            return;
        }
        String prev = owners.putIfAbsent(key, kind + " '" + name + "'");
        if (prev != null) {
            diagnostics.add(new Diagnostic(
                    Severity.ERROR,
                    "DUPLICATE_NAME",
                    "Nombre duplicado (sin distinguir mayúsculas) '" + name + "': colisiona con " + prev + " al emitir símbolos AVRASM2/C."));
        }
    }

    private void validateTrigger(ProgramSpec spec, Rule.Trigger trigger, String ctx, List<Diagnostic> diagnostics) {
        switch (trigger) {
            case Rule.OnBoot ignored -> {}
            case Rule.Always ignored -> {}
            case Rule.EveryMs ignored -> {}
            case Rule.OnPress p -> {
                if (spec.button(p.buttonName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_BUTTON",
                            ctx + ": botón desconocido '" + p.buttonName() + "' en OnPress."));
                }
            }
            case Rule.OnRelease r -> {
                if (spec.button(r.buttonName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_BUTTON",
                            ctx + ": botón desconocido '" + r.buttonName() + "' en OnRelease."));
                }
            }
            case Rule.OnTimerExpired t -> {
                if (spec.timer(t.timerName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_TIMER",
                            ctx + ": temporizador desconocido '" + t.timerName() + "' en OnTimerExpired."));
                }
            }
            case Rule.OnUartRx u -> {
                var port = spec.uart(u.uartName());
                if (port.isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_UART",
                            ctx + ": puerto UART desconocido '" + u.uartName() + "' en OnUartRx."));
                } else if (!port.get().mode().usesRx()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UART_NO_RX",
                            ctx + ": el puerto UART '" + u.uartName() + "' está configurado en modo "
                                    + port.get().mode() + " y no habilita recepción RX."));
                }
            }
        }
    }

    private void validateBool(ProgramSpec spec, Expr.BoolExpr expr, Rule.Trigger trigger, String ctx, List<Diagnostic> diagnostics) {
        switch (expr) {
            case Expr.Truth ignored -> {}
            case Expr.Pressed p -> {
                if (spec.button(p.buttonName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_BUTTON",
                            ctx + ": botón desconocido '" + p.buttonName() + "'."));
                }
            }
            case Expr.Compare c -> {
                validateValue(spec, c.left(), trigger, ctx, diagnostics);
                validateValue(spec, c.right(), trigger, ctx, diagnostics);
            }
            case Expr.InRange r -> validateValue(spec, r.value(), trigger, ctx, diagnostics);
            case Expr.Modulo m -> {
                if (spec.var(m.varName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_VAR",
                            ctx + ": variable desconocida '" + m.varName() + "' en Modulo."));
                }
            }
            case Expr.InPhase ip -> {
                var g = spec.phaseGroup(ip.groupName());
                if (g.isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_PHASE_GROUP",
                            ctx + ": grupo de fases desconocido '" + ip.groupName() + "'."));
                } else if (!g.get().phases().contains(ip.phaseName())) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_PHASE",
                            ctx + ": la fase '" + ip.phaseName() + "' no existe en el grupo '" + ip.groupName() + "'."));
                }
            }
            case Expr.TimerActive ta -> {
                if (spec.timer(ta.timerName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_TIMER",
                            ctx + ": temporizador desconocido '" + ta.timerName() + "'."));
                }
            }
            case Expr.UartByteEquals u -> {
                validateUartRxReference(spec, u.uartName(), trigger, ctx, diagnostics);
            }
            case Expr.Not n -> validateBool(spec, n.inner(), trigger, ctx, diagnostics);
            case Expr.And a -> {
                for (Expr.BoolExpr item : a.items()) validateBool(spec, item, trigger, ctx, diagnostics);
            }
            case Expr.Or o -> {
                for (Expr.BoolExpr item : o.items()) validateBool(spec, item, trigger, ctx, diagnostics);
            }
            case Expr.Xor x -> {
                validateBool(spec, x.left(), trigger, ctx, diagnostics);
                validateBool(spec, x.right(), trigger, ctx, diagnostics);
            }
        }
    }

    private void validateValue(ProgramSpec spec, Expr.ValueExpr expr, Rule.Trigger trigger, String ctx, List<Diagnostic> diagnostics) {
        switch (expr) {
            case Expr.ConstU8 ignored -> {}
            case Expr.ConstU10 ignored -> {}
            case Expr.ReadVar rv -> {
                if (spec.var(rv.varName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_VAR",
                            ctx + ": variable desconocida '" + rv.varName() + "'."));
                }
            }
            case Expr.ReadAdc8 a -> {
                if (spec.analog(a.analogName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_ANALOG",
                            ctx + ": entrada analógica desconocida '" + a.analogName() + "'."));
                }
            }
            case Expr.ReadAdc10 a -> {
                if (spec.analog(a.analogName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_ANALOG",
                            ctx + ": entrada analógica desconocida '" + a.analogName() + "'."));
                }
            }
            case Expr.ReadUartByte u -> validateUartRxReference(spec, u.uartName(), trigger, ctx, diagnostics);
            case Expr.Scale s -> {
                validateValue(spec, s.source(), trigger, ctx, diagnostics);
                if (s.inMax() == 255 && s.source().is10Bit()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "WIDTH_MISMATCH",
                            ctx + ": Scale con inMax=255 no acepta una fuente de 10 bits; usa inMax=1023 o ReadAdc8."));
                }
                if (s.inMax() == 1023 && !s.source().is10Bit()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "WIDTH_MISMATCH",
                            ctx + ": Scale con inMax=1023 requiere una fuente de 10 bits (ReadAdc10)."));
                }
            }
        }
    }

    private void validateUartRxReference(ProgramSpec spec, String uartName, Rule.Trigger trigger, String ctx, List<Diagnostic> diagnostics) {
        var port = spec.uart(uartName);
        if (port.isEmpty()) {
            diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_UART",
                    ctx + ": puerto UART desconocido '" + uartName + "'."));
            return;
        }
        if (!port.get().mode().usesRx()) {
            diagnostics.add(new Diagnostic(Severity.ERROR, "UART_NO_RX",
                    ctx + ": '" + uartName + "' no tiene recepción RX habilitada."));
            return;
        }
        if (!(trigger instanceof Rule.OnUartRx rx && rx.uartName().equals(uartName))) {
            diagnostics.add(new Diagnostic(Severity.ERROR, "UART_BYTE_OUTSIDE_RX",
                    ctx + ": leer el byte recibido de '" + uartName + "' solo es válido en tarjetas disparadas por OnUartRx('" + uartName + "')."));
        }
    }

    private void validateEffect(ProgramSpec spec, Effect eff, Rule.Trigger trigger, String ctx, List<Diagnostic> diagnostics) {
        switch (eff) {
            case Effect.WriteLed w -> {
                if (spec.led(w.ledName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_LED",
                            ctx + ": LED desconocido '" + w.ledName() + "'."));
                }
            }
            case Effect.ToggleLed t -> {
                if (spec.led(t.ledName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_LED",
                            ctx + ": LED desconocido '" + t.ledName() + "'."));
                }
            }
            case Effect.WriteBcd b -> {
                if (spec.bcd(b.displayName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_BCD",
                            ctx + ": display BCD desconocido '" + b.displayName() + "'."));
                }
                validateValue(spec, b.value(), trigger, ctx, diagnostics);
                require8Bit(b.value(), "WriteBcd", ctx, diagnostics);
                int[] bounds = valueBounds(spec, b.value());
                if (bounds != null && bounds[1] > 9) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "BCD_RANGE",
                            ctx + ": WriteBcd requiere dígito 0..9, pero el valor puede alcanzar " + bounds[1]
                                    + " (usa una constante/variable 0..9, Scale(..., 0, 9) o WriteDecimal2)."));
                }
            }
            case Effect.WriteDecimal2 d -> {
                if (spec.bcd(d.unitsDisplay()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_BCD",
                            ctx + ": display de unidades desconocido '" + d.unitsDisplay() + "'."));
                }
                if (spec.bcd(d.tensDisplay()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_BCD",
                            ctx + ": display de decenas desconocido '" + d.tensDisplay() + "'."));
                }
                validateValue(spec, d.value(), trigger, ctx, diagnostics);
                require8Bit(d.value(), "WriteDecimal2", ctx, diagnostics);
                int[] bounds = valueBounds(spec, d.value());
                if (bounds != null && bounds[1] > 99) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "DECIMAL_RANGE",
                            ctx + ": WriteDecimal2 requiere valor 0..99, pero la expresión puede alcanzar " + bounds[1]
                                    + " (usa Scale(..., 0, 99) o una variable con máx <= 99)."));
                }
            }
            case Effect.WritePwm p -> {
                if (spec.pwm(p.pwmName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_PWM",
                            ctx + ": salida PWM desconocida '" + p.pwmName() + "'."));
                }
                validateValue(spec, p.duty(), trigger, ctx, diagnostics);
                require8Bit(p.duty(), "WritePwm", ctx, diagnostics);
            }
            case Effect.SendUartText s -> validateUartTx(spec, s.uartName(), ctx, diagnostics);
            case Effect.SendUartByte s -> {
                validateUartTx(spec, s.uartName(), ctx, diagnostics);
                validateValue(spec, s.value(), trigger, ctx, diagnostics);
                require8Bit(s.value(), "SendUartByte", ctx, diagnostics);
            }
            case Effect.SetVar s -> {
                var declOpt = spec.var(s.varName());
                if (declOpt.isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_VAR",
                            ctx + ": variable desconocida '" + s.varName() + "'."));
                }
                validateValue(spec, s.value(), trigger, ctx, diagnostics);
                require8Bit(s.value(), "SetVar", ctx, diagnostics);
                if (declOpt.isPresent()) {
                    State.VarDecl v = declOpt.get();
                    int[] bounds = valueBounds(spec, s.value());
                    if (bounds != null && (bounds[0] < v.min() || bounds[1] > v.max())) {
                        diagnostics.add(new Diagnostic(Severity.ERROR, "VAR_RANGE",
                                ctx + ": SetVar('" + v.name() + "') puede escribir [" + bounds[0] + ".." + bounds[1]
                                        + "], fuera del rango declarado [" + v.min() + ".." + v.max() + "]."));
                    }
                }
            }
            case Effect.StepVar s -> {
                if (spec.var(s.varName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_VAR",
                            ctx + ": variable desconocida '" + s.varName() + "'."));
                }
            }
            case Effect.ResetVar r -> {
                if (spec.var(r.varName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_VAR",
                            ctx + ": variable desconocida '" + r.varName() + "'."));
                }
            }
            case Effect.SetPhase sp -> {
                var g = spec.phaseGroup(sp.groupName());
                if (g.isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_PHASE_GROUP",
                            ctx + ": grupo de fases desconocido '" + sp.groupName() + "'."));
                } else if (!g.get().phases().contains(sp.phaseName())) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_PHASE",
                            ctx + ": fase desconocida '" + sp.phaseName() + "' en grupo '" + sp.groupName() + "'."));
                }
            }
            case Effect.StartTimer st -> {
                if (spec.timer(st.timerName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_TIMER",
                            ctx + ": temporizador desconocido '" + st.timerName() + "'."));
                }
            }
            case Effect.StopTimer st -> {
                if (spec.timer(st.timerName()).isEmpty()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_TIMER",
                            ctx + ": temporizador desconocido '" + st.timerName() + "'."));
                }
            }
            case Effect.DelayMs ignored -> {}
        }
    }

    private void validateUartTx(ProgramSpec spec, String uartName, String ctx, List<Diagnostic> diagnostics) {
        var port = spec.uart(uartName);
        if (port.isEmpty()) {
            diagnostics.add(new Diagnostic(Severity.ERROR, "UNKNOWN_UART",
                    ctx + ": puerto UART desconocido '" + uartName + "'."));
        } else if (!port.get().mode().usesTx()) {
            diagnostics.add(new Diagnostic(Severity.ERROR, "UART_NO_TX",
                    ctx + ": el puerto UART '" + uartName + "' está en modo " + port.get().mode() + " y no habilita transmisión TX."));
        }
    }

    private void require8Bit(Expr.ValueExpr expr, String opName, String ctx, List<Diagnostic> diagnostics) {
        if (expr.is10Bit()) {
            diagnostics.add(new Diagnostic(Severity.ERROR, "WIDTH_MISMATCH",
                    ctx + ": " + opName + " espera un valor de 8 bits (0..255); usa ReadAdc8 o Scale(...) en lugar de una lectura de 10 bits directa."));
        }
    }

    /** Cotas estáticas [min, max] garantizadas para una expresión de valor, o null si depende de variable desconocida. */
    private static int[] valueBounds(ProgramSpec spec, Expr.ValueExpr expr) {
        return switch (expr) {
            case Expr.ConstU8 c -> new int[]{c.value(), c.value()};
            case Expr.ConstU10 c -> new int[]{c.value(), c.value()};
            case Expr.ReadVar rv -> spec.var(rv.varName()).map(v -> new int[]{v.min(), v.max()}).orElse(null);
            case Expr.ReadAdc8 ignored -> new int[]{0, 255};
            case Expr.ReadAdc10 ignored -> new int[]{0, 1023};
            case Expr.ReadUartByte ignored -> new int[]{0, 255};
            case Expr.Scale s -> new int[]{s.outMin(), s.outMax()};
        };
    }

    private void collectStartedTimers(List<Effect> effects, Set<String> out) {
        for (Effect e : effects) {
            if (e instanceof Effect.StartTimer st) out.add(st.timerName());
        }
    }
}
