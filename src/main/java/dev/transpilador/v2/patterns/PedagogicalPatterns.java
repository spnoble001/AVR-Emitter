package dev.transpilador.v2.patterns;

import dev.transpilador.v2.model.*;

import java.util.*;

import static dev.transpilador.v2.model.Effect.*;
import static dev.transpilador.v2.model.Expr.*;
import static dev.transpilador.v2.model.Rule.*;

/**
 * Capa de Patrones / Intenciones Pedagógicas (No-Code UX).
 * Traduce intenciones humanas de alto nivel ("Cada N pulsaciones", "Doble clic",
 * "Pulsación corta vs. larga", "Contador circular en 4511 con pausa", "Brillo PWM", etc.)
 * exclusivamente a tarjetas y estado estándar de los 5 bloques ortogonales,
 * sin añadir casos especiales al compilador ni a los emisores C/ASM.
 */
public final class PedagogicalPatterns {
    private PedagogicalPatterns() {}

    public enum Category {
        BUTTONS("Botones y Conteo"),
        DISPLAYS_TIME("Displays y Tiempo"),
        SENSORS_PWM("Sensores ADC y PWM"),
        SERIAL("Comunicación UART");

        private final String label;
        Category(String label) { this.label = label; }
        public String label() { return label; }
        @Override public String toString() { return label; }
    }

    public enum Kind {
        EVERY_N_PRESSES(Category.BUTTONS,
                "Cada N pulsaciones encender un LED",
                "Cuenta pulsaciones en un contador circular 0..N-1 y enciende el LED en cada N-ésima liberación.",
                "Requiere: 1 botón y 1 LED"),
        DOUBLE_CLICK(Category.BUTTONS,
                "Doble clic dentro de una ventana de tiempo",
                "Crea una máquina de 2 fases y un temporizador para alternar un LED solo con doble pulsación rápida.",
                "Requiere: 1 botón y 1 LED"),
        SHORT_LONG_PRESS(Category.BUTTONS,
                "Pulsación corta vs. pulsación larga",
                "Pulsación corta avanza un contador (y display si existe); pulsación larga mantenida lo reinicia.",
                "Requiere: 1 botón (crea contador automático si falta)"),
        SEQUENCE_A_THEN_B(Category.BUTTONS,
                "Secuencia ordenada: A y luego B en N ms",
                "Enciende un LED únicamente si se presiona el botón B dentro de la ventana tras presionar A.",
                "Requiere: 2 botones y 1 LED"),
        BCD_CIRCULAR_PAUSE(Category.DISPLAYS_TIME,
                "Contador circular 1..9 en display 4511 con pausa",
                "Cuenta automáticamente de 1 a 9 cada N ms en un display CD4511, se pausa mientras se presiona el botón y reinicia de 9 a 1.",
                "Requiere: 1 display BCD y 1 botón"),
        PERIODIC_BLINK(Category.DISPLAYS_TIME,
                "Parpadeo cooperativo de LED cada N ms",
                "Alterna un LED periódicamente sin bloquear otros botones, displays ni temporizadores.",
                "Requiere: 1 LED"),
        ADC_THRESHOLD(Category.SENSORS_PWM,
                "Encender LED cuando potenciómetro supera umbral",
                "Compara la lectura analógica ADC de 10 bits contra un umbral configurable en cuentas/voltios.",
                "Requiere: 1 potenciómetro ADC y 1 LED"),
        ADC_TO_PWM(Category.SENSORS_PWM,
                "Controlar brillo PWM con potenciómetro",
                "Conecta una entrada analógica (8 bits) directamente al ciclo de trabajo de una salida Fast PWM.",
                "Requiere: 1 potenciómetro ADC y 1 salida PWM"),
        PWM_RAMP(Category.SENSORS_PWM,
                "Rampa automática de brillo PWM",
                "Incrementa un contador 0..255 cada N ms y actualiza de forma fluida la salida Fast PWM.",
                "Requiere: 1 salida PWM"),
        UART_LED_COMMAND(Category.SERIAL,
                "Controlar LED con comandos por puerto serie",
                "Al recibir '1' por UART enciende el LED y responde \"ON\"; al recibir '0' lo apaga y responde \"OFF\".",
                "Requiere: 1 puerto UART (con RX) y 1 LED");

        private final Category category;
        private final String label;
        private final String description;
        private final String requirementsHint;

        Kind(Category category, String label, String description, String requirementsHint) {
            this.category = category;
            this.label = label;
            this.description = description;
            this.requirementsHint = requirementsHint;
        }

        public Category category() { return category; }
        public String label() { return label; }
        public String description() { return description; }
        public String requirementsHint() { return requirementsHint; }
        @Override public String toString() { return label; }
    }

    /** Agrega el patrón "Cada N pulsaciones al soltar botón -> encender LED". */
    public static ProgramSpec addEveryNPresses(ProgramSpec base, String button, String led, int n, boolean onRelease) {
        if (n < 2 || n > 255) throw new IllegalArgumentException("N debe estar entre 2 y 255.");
        String varName = uniqueName(base, "pulsaciones_" + button);
        var vars = new ArrayList<>(base.vars());
        vars.add(new State.VarDecl(varName, 0, n - 1, 0, State.VarPolicy.WRAP));

        Rule.Trigger trig = onRelease ? onRelease(button) : onPress(button);
        var rules = new ArrayList<>(base.rules());
        rules.add(Rule.when("Contar pulsaciones de " + button + " (cada " + n + ")", trig, inc(varName)));
        rules.add(Rule.when("Encender " + led + " al completar " + n + " pulsaciones", trig,
                cmp(var(varName), CmpOp.EQ, u8(0)),
                List.of(ledOn(led)), List.of(ledOff(led))));

        return new ProgramSpec(base.name(), base.fCpuHz(), base.components(), vars, base.phaseGroups(), base.timers(), rules);
    }

    /** Agrega el patrón "Doble clic en ventanaMs -> alternar LED". */
    public static ProgramSpec addDoubleClick(ProgramSpec base, String button, String led, int windowMs) {
        String group = uniqueName(base, "clic_" + button);
        String timer = uniqueName(base, "t_doble_" + button);
        var phases = new ArrayList<>(base.phaseGroups());
        phases.add(new State.PhaseGroup(group, List.of("REPOSO", "ESPERANDO_2DO"), "REPOSO"));
        var timers = new ArrayList<>(base.timers());
        timers.add(new State.TimerDecl(timer));

        var rules = new ArrayList<>(base.rules());
        rules.add(Rule.when("Primer clic en " + button + " abre ventana de " + windowMs + " ms",
                onPress(button), inPhase(group, "REPOSO"),
                setPhase(group, "ESPERANDO_2DO"), startTimer(timer, windowMs)));
        rules.add(Rule.when("Segundo clic en " + button + " alterna " + led,
                onPress(button), inPhase(group, "ESPERANDO_2DO"),
                toggleLed(led), stopTimer(timer), setPhase(group, "REPOSO")));
        rules.add(Rule.when("Ventana de doble clic vencida vuelve a reposo",
                onTimer(timer), inPhase(group, "ESPERANDO_2DO"),
                setPhase(group, "REPOSO")));

        return new ProgramSpec(base.name(), base.fCpuHz(), base.components(), base.vars(), phases, timers, rules);
    }

    /** Agrega el patrón "Pulsación corta avanza contador, larga (>= holdMs) reinicia contador". */
    public static ProgramSpec addShortLongPress(ProgramSpec base, String button, String varName, String optionalBcd, int holdMs) {
        var vars = new ArrayList<>(base.vars());
        String effectiveVar = varName;
        if (effectiveVar == null || effectiveVar.isBlank()) {
            effectiveVar = uniqueName(base, "cuenta");
            vars.add(new State.VarDecl(effectiveVar, 0, 9, 0, State.VarPolicy.WRAP));
        }

        String group = uniqueName(base, "pulso_" + button);
        String timer = uniqueName(base, "t_larga_" + button);
        var phases = new ArrayList<>(base.phaseGroups());
        phases.add(new State.PhaseGroup(group, List.of("SUELTO", "MIDIENDO", "LARGA_HECHA"), "SUELTO"));
        var timers = new ArrayList<>(base.timers());
        timers.add(new State.TimerDecl(timer));

        List<Effect> onLong = new ArrayList<>();
        onLong.add(resetVar(effectiveVar));
        if (optionalBcd != null) onLong.add(showBcd(optionalBcd, var(effectiveVar)));
        onLong.add(setPhase(group, "LARGA_HECHA"));

        List<Effect> onShort = new ArrayList<>();
        onShort.add(stopTimer(timer));
        onShort.add(inc(effectiveVar));
        if (optionalBcd != null) onShort.add(showBcd(optionalBcd, var(effectiveVar)));
        onShort.add(setPhase(group, "SUELTO"));

        var rules = new ArrayList<>(base.rules());
        rules.add(Rule.when("Iniciar medición de pulso en " + button,
                onPress(button), inPhase(group, "SUELTO"),
                setPhase(group, "MIDIENDO"), startTimer(timer, holdMs)));
        rules.add(Rule.when("Pulsación larga (" + holdMs + " ms) reinicia " + effectiveVar,
                onTimer(timer), inPhase(group, "MIDIENDO"), onLong, List.of()));
        rules.add(Rule.when("Pulsación corta avanza " + effectiveVar,
                onRelease(button), inPhase(group, "MIDIENDO"), onShort, List.of()));
        rules.add(Rule.when("Liberar tras pulsación larga vuelve a reposo",
                onRelease(button), inPhase(group, "LARGA_HECHA"),
                setPhase(group, "SUELTO")));

        return new ProgramSpec(base.name(), base.fCpuHz(), base.components(), vars, phases, timers, rules);
    }

    /** Agrega el patrón "A y luego B dentro de windowMs -> encender LED". */
    public static ProgramSpec addSequenceAThenB(ProgramSpec base, String btnA, String btnB, String led, int windowMs) {
        String group = uniqueName(base, "sec_" + btnA);
        String timer = uniqueName(base, "t_sec_" + btnA);
        var phases = new ArrayList<>(base.phaseGroups());
        phases.add(new State.PhaseGroup(group, List.of("INICIO", "ARMADO"), "INICIO"));
        var timers = new ArrayList<>(base.timers());
        timers.add(new State.TimerDecl(timer));

        var rules = new ArrayList<>(base.rules());
        rules.add(Rule.when(btnA + " arma ventana de " + windowMs + " ms",
                onPress(btnA), inPhase(group, "INICIO"),
                setPhase(group, "ARMADO"), startTimer(timer, windowMs)));
        rules.add(Rule.when(btnB + " dentro de ventana enciende " + led,
                onPress(btnB), inPhase(group, "ARMADO"),
                ledOn(led), stopTimer(timer), setPhase(group, "INICIO")));
        rules.add(Rule.when("Ventana vencida desarma secuencia",
                onTimer(timer), inPhase(group, "ARMADO"),
                setPhase(group, "INICIO")));

        return new ProgramSpec(base.name(), base.fCpuHz(), base.components(), base.vars(), phases, timers, rules);
    }

    /** Agrega el patrón "Contador circular 1..9 en display CD4511 cada periodMs, pausado mientras botón esté presionado". */
    public static ProgramSpec addBcdCircularWithPause(ProgramSpec base, String bcdDisplay, String pauseButton, int periodMs) {
        String varName = uniqueName(base, "digito_" + bcdDisplay);
        var vars = new ArrayList<>(base.vars());
        vars.add(new State.VarDecl(varName, 1, 9, 1, State.VarPolicy.WRAP));

        var rules = new ArrayList<>(base.rules());
        rules.add(Rule.when("Mostrar dígito inicial 1 en " + bcdDisplay,
                onBoot(), showBcd(bcdDisplay, var(varName))));
        rules.add(Rule.when("Contar circularmente 1..9 cada " + periodMs + " ms salvo pausa en " + pauseButton,
                everyMs(periodMs), not(pressed(pauseButton)),
                inc(varName), showBcd(bcdDisplay, var(varName))));

        return new ProgramSpec(base.name(), base.fCpuHz(), base.components(), vars, base.phaseGroups(), base.timers(), rules);
    }

    /** Agrega el patrón "Parpadeo cooperativo de LED cada periodMs". */
    public static ProgramSpec addPeriodicBlink(ProgramSpec base, String led, int periodMs) {
        var rules = new ArrayList<>(base.rules());
        rules.add(Rule.when("Parpadear " + led + " cada " + periodMs + " ms", everyMs(periodMs), toggleLed(led)));
        return new ProgramSpec(base.name(), base.fCpuHz(), base.components(), base.vars(), base.phaseGroups(), base.timers(), rules);
    }

    /** Agrega el patrón "Encender LED si potenciómetro supera umbral 10-bit (0..1023)". */
    public static ProgramSpec addAdcThreshold(ProgramSpec base, String analog, String led, int threshold10Bit) {
        var rules = new ArrayList<>(base.rules());
        double volts = threshold10Bit * 5.0 / 1024.0;
        rules.add(Rule.when(String.format(Locale.ROOT, "Encender %s si %s > %d (%.2f V)", led, analog, threshold10Bit, volts),
                always(), cmp(adc10(analog), CmpOp.GT, u10(threshold10Bit)),
                List.of(ledOn(led)), List.of(ledOff(led))));
        return new ProgramSpec(base.name(), base.fCpuHz(), base.components(), base.vars(), base.phaseGroups(), base.timers(), rules);
    }

    /** Agrega el patrón "Controlar PWM directamente desde entrada analógica 8-bit". */
    public static ProgramSpec addAdcToPwm(ProgramSpec base, String analog, String pwm) {
        var rules = new ArrayList<>(base.rules());
        rules.add(Rule.when("Ajustar PWM " + pwm + " según " + analog, always(), setPwm(pwm, adc8(analog))));
        return new ProgramSpec(base.name(), base.fCpuHz(), base.components(), base.vars(), base.phaseGroups(), base.timers(), rules);
    }

    /** Agrega el patrón "Rampa automática de brillo PWM cada stepMs". */
    public static ProgramSpec addPwmRamp(ProgramSpec base, String pwm, int stepMs) {
        String varName = uniqueName(base, "brillo_" + pwm);
        var vars = new ArrayList<>(base.vars());
        vars.add(new State.VarDecl(varName, 0, 255, 0, State.VarPolicy.WRAP));
        var rules = new ArrayList<>(base.rules());
        rules.add(Rule.when("Rampa PWM en " + pwm + " cada " + stepMs + " ms",
                everyMs(stepMs), inc(varName), setPwm(pwm, var(varName))));
        return new ProgramSpec(base.name(), base.fCpuHz(), base.components(), vars, base.phaseGroups(), base.timers(), rules);
    }

    /** Agrega el patrón "Controlar LED por comandos UART ('1' enciende, '0' apaga)". */
    public static ProgramSpec addUartLedCommand(ProgramSpec base, String uart, String led, char onChar, char offChar) {
        var rules = new ArrayList<>(base.rules());
        boolean canTx = base.uart(uart).map(u -> u.mode().usesTx()).orElse(false);
        List<Effect> onEffects = canTx ? List.of(ledOn(led), sendText(uart, "ON\r\n")) : List.of(ledOn(led));
        List<Effect> offEffects = canTx ? List.of(ledOff(led), sendText(uart, "OFF\r\n")) : List.of(ledOff(led));

        rules.add(Rule.when("Comando UART '" + onChar + "' enciende " + led,
                onUartRx(uart), uartChar(uart, onChar), onEffects, List.of()));
        rules.add(Rule.when("Comando UART '" + offChar + "' apaga " + led,
                onUartRx(uart), uartChar(uart, offChar), offEffects, List.of()));

        return new ProgramSpec(base.name(), base.fCpuHz(), base.components(), base.vars(), base.phaseGroups(), base.timers(), rules);
    }

    private static String uniqueName(ProgramSpec spec, String proposed) {
        String base = proposed.length() > 26 ? proposed.substring(0, 26) : proposed;
        Set<String> used = new HashSet<>();
        spec.components().forEach(c -> used.add(c.name()));
        spec.vars().forEach(v -> used.add(v.name()));
        spec.phaseGroups().forEach(g -> used.add(g.name()));
        spec.timers().forEach(t -> used.add(t.name()));
        if (!used.contains(base)) return base;
        int i = 2;
        while (used.contains(base + "_" + i)) i++;
        return base + "_" + i;
    }
}
