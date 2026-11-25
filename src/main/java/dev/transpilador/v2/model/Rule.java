package dev.transpilador.v2.model;

import java.util.List;
import java.util.Objects;

/**
 * Bloque 5 · Tarjeta / Regla Reactiva: CUÁNDO [Evento] · SI [Condición] → [HACER] (SI NO → [HACER]).
 */
public record Rule(
        String title,
        Trigger trigger,
        Expr.BoolExpr guard,
        List<Effect> thenEffects,
        List<Effect> elseEffects) {

    public sealed interface Trigger permits OnBoot, Always, OnPress, OnRelease, EveryMs, OnTimerExpired, OnUartRx {}

    /** Se ejecuta una sola vez al arrancar, antes del bucle principal. */
    public record OnBoot() implements Trigger {}

    /** Se evalúa en cada pasada del bucle principal. */
    public record Always() implements Trigger {}

    /** Flanco ascendente lógico del botón (liberado → presionado). */
    public record OnPress(String buttonName) implements Trigger {
        public OnPress { Hardware.requireIdentifier(buttonName, "Botón"); }
    }

    /** Flanco descendente lógico del botón (presionado → liberado). */
    public record OnRelease(String buttonName) implements Trigger {
        public OnRelease { Hardware.requireIdentifier(buttonName, "Botón"); }
    }

    /** Tarea periódica cooperativa cada N ms (sin bloquear otras tarjetas). */
    public record EveryMs(int periodMs) implements Trigger {
        public EveryMs {
            if (periodMs < 1 || periodMs > 65_000) {
                throw new IllegalArgumentException("Periodo fuera de rango (1..65000 ms): " + periodMs);
            }
        }
    }

    /**
     * Se dispara en la pasada exacta en que el temporizador llega a cero.
     * Contrato de difusión por pasada: el vencimiento se comparte entre todas las tarjetas
     * que escuchan este temporizador durante la misma pasada (aunque una tarjeta anterior
     * lo rearme con {@code StartTimer} o lo detenga con {@code StopTimer}), y se limpia
     * automáticamente en el siguiente tick cooperativo de 1 ms.
     */
    public record OnTimerExpired(String timerName) implements Trigger {
        public OnTimerExpired { Hardware.requireIdentifier(timerName, "Temporizador"); }
    }

    /** Se dispara cuando llega un byte nuevo en UART0. */
    public record OnUartRx(String uartName) implements Trigger {
        public OnUartRx { Hardware.requireIdentifier(uartName, "Puerto UART"); }
    }

    public Rule {
        Objects.requireNonNull(title, "title");
        if (title.isBlank()) throw new IllegalArgumentException("Toda tarjeta requiere un título descriptivo.");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(thenEffects, "thenEffects");
        Objects.requireNonNull(elseEffects, "elseEffects");
        if (thenEffects.isEmpty() && elseEffects.isEmpty()) {
            throw new IllegalArgumentException("La tarjeta '" + title + "' debe tener al menos un efecto.");
        }
        thenEffects = List.copyOf(thenEffects);
        elseEffects = List.copyOf(elseEffects);
    }

    public static Rule when(String title, Trigger trigger, Expr.BoolExpr guard, List<Effect> thenEffects, List<Effect> elseEffects) {
        return new Rule(title, trigger, guard, thenEffects, elseEffects);
    }

    public static Rule when(String title, Trigger trigger, Expr.BoolExpr guard, Effect... thenEffects) {
        return new Rule(title, trigger, guard, List.of(thenEffects), List.of());
    }

    public static Rule when(String title, Trigger trigger, Effect... thenEffects) {
        return new Rule(title, trigger, Expr.alwaysTrue(), List.of(thenEffects), List.of());
    }

    public static Trigger onBoot() { return new OnBoot(); }
    public static Trigger always() { return new Always(); }
    public static Trigger onPress(String btn) { return new OnPress(btn); }
    public static Trigger onRelease(String btn) { return new OnRelease(btn); }
    public static Trigger everyMs(int ms) { return new EveryMs(ms); }
    public static Trigger onTimer(String timer) { return new OnTimerExpired(timer); }
    public static Trigger onUartRx(String uart) { return new OnUartRx(uart); }
}
