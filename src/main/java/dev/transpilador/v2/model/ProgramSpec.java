package dev.transpilador.v2.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Documento raíz del núcleo ortogonal v2 para ATmega328P.
 * Es inmutable, completamente semántico y sirve como única fuente de verdad para C99, AVR ASM, simavr y UI.
 */
public record ProgramSpec(
        String name,
        long fCpuHz,
        List<Hardware.Component> components,
        List<State.VarDecl> vars,
        List<State.PhaseGroup> phaseGroups,
        List<State.TimerDecl> timers,
        List<Rule> rules) {

    public static final long DEFAULT_F_CPU_HZ = 1_000_000L;

    public ProgramSpec {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("El proyecto requiere un nombre.");
        Hardware.ClockFreq.fromHz(fCpuHz);
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        vars = List.copyOf(Objects.requireNonNull(vars, "vars"));
        phaseGroups = List.copyOf(Objects.requireNonNull(phaseGroups, "phaseGroups"));
        timers = List.copyOf(Objects.requireNonNull(timers, "timers"));
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    public Hardware.ClockFreq clockFreq() {
        return Hardware.ClockFreq.fromHz(fCpuHz);
    }

    public ProgramSpec withClock(Hardware.ClockFreq freq) {
        return new ProgramSpec(name, freq.hz(), components, vars, phaseGroups, timers, rules);
    }

    public Optional<Hardware.Component> component(String name) {
        return components.stream().filter(c -> c.name().equals(name)).findFirst();
    }

    public <T extends Hardware.Component> List<T> componentsOf(Class<T> type) {
        return components.stream().filter(type::isInstance).map(type::cast).toList();
    }

    public Optional<Hardware.Button> button(String name) {
        return componentsOf(Hardware.Button.class).stream().filter(c -> c.name().equals(name)).findFirst();
    }

    public Optional<Hardware.Led> led(String name) {
        return componentsOf(Hardware.Led.class).stream().filter(c -> c.name().equals(name)).findFirst();
    }

    public Optional<Hardware.BcdDisplay> bcd(String name) {
        return componentsOf(Hardware.BcdDisplay.class).stream().filter(c -> c.name().equals(name)).findFirst();
    }

    public Optional<Hardware.AnalogInput> analog(String name) {
        return componentsOf(Hardware.AnalogInput.class).stream().filter(c -> c.name().equals(name)).findFirst();
    }

    public Optional<Hardware.PwmOutput> pwm(String name) {
        return componentsOf(Hardware.PwmOutput.class).stream().filter(c -> c.name().equals(name)).findFirst();
    }

    public Optional<Hardware.UartPort> uart(String name) {
        return componentsOf(Hardware.UartPort.class).stream().filter(c -> c.name().equals(name)).findFirst();
    }

    public Optional<State.VarDecl> var(String name) {
        return vars.stream().filter(v -> v.name().equals(name)).findFirst();
    }

    public Optional<State.PhaseGroup> phaseGroup(String name) {
        return phaseGroups.stream().filter(g -> g.name().equals(name)).findFirst();
    }

    public Optional<State.TimerDecl> timer(String name) {
        return timers.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private long fCpuHz = DEFAULT_F_CPU_HZ;
        private final List<Hardware.Component> components = new ArrayList<>();
        private final List<State.VarDecl> vars = new ArrayList<>();
        private final List<State.PhaseGroup> phaseGroups = new ArrayList<>();
        private final List<State.TimerDecl> timers = new ArrayList<>();
        private final List<Rule> rules = new ArrayList<>();

        private Builder(String name) { this.name = name; }

        public Builder fCpuHz(long hz) { this.fCpuHz = Hardware.ClockFreq.fromHz(hz).hz(); return this; }
        public Builder clock(Hardware.ClockFreq freq) { this.fCpuHz = freq.hz(); return this; }
        public Builder add(Hardware.Component c) { components.add(c); return this; }
        public Builder button(String name, Hardware.Pin pin, Hardware.Connection conn) {
            return add(new Hardware.Button(name, pin, conn));
        }
        public Builder led(String name, Hardware.Pin pin, Hardware.Connection conn) {
            return add(new Hardware.Led(name, pin, conn, false));
        }
        public Builder led(String name, Hardware.Pin pin, Hardware.Connection conn, boolean initialOn) {
            return add(new Hardware.Led(name, pin, conn, initialOn));
        }
        public Builder bcd(String name, Hardware.Pin firstPin, int initialDigit) {
            return add(new Hardware.BcdDisplay(name, firstPin, initialDigit));
        }
        public Builder analog(String name, Hardware.Pin pin) {
            return add(new Hardware.AnalogInput(name, pin));
        }
        public Builder pwm(String name, Hardware.Pin pin, int initialDuty) {
            return add(new Hardware.PwmOutput(name, pin, initialDuty));
        }
        public Builder uart(String name, Hardware.UartBaud baud, Hardware.UartMode mode) {
            return add(new Hardware.UartPort(name, baud, mode));
        }
        public Builder var(String name, int min, int max, int initial, State.VarPolicy policy) {
            vars.add(new State.VarDecl(name, min, max, initial, policy));
            return this;
        }
        public Builder phases(String groupName, String initialPhase, String... phases) {
            phaseGroups.add(new State.PhaseGroup(groupName, List.of(phases), initialPhase));
            return this;
        }
        public Builder timer(String name) {
            timers.add(new State.TimerDecl(name));
            return this;
        }
        public Builder rule(Rule rule) {
            rules.add(rule);
            return this;
        }
        public Builder rule(String title, Rule.Trigger trigger, Expr.BoolExpr guard, List<Effect> thenEffects, List<Effect> elseEffects) {
            return rule(new Rule(title, trigger, guard, thenEffects, elseEffects));
        }
        public Builder rule(String title, Rule.Trigger trigger, Expr.BoolExpr guard, Effect... thenEffects) {
            return rule(Rule.when(title, trigger, guard, thenEffects));
        }
        public Builder rule(String title, Rule.Trigger trigger, Effect... thenEffects) {
            return rule(Rule.when(title, trigger, thenEffects));
        }

        public ProgramSpec build() {
            return new ProgramSpec(name, fCpuHz, components, vars, phaseGroups, timers, rules);
        }
    }
}
