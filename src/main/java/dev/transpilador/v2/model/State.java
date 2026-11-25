package dev.transpilador.v2.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Bloque 2 · Estado mutable del programa (Variables U8, Fases FSM y Temporizadores).
 */
public final class State {
    private State() {}

    public enum VarPolicy {
        /** Al superar max vuelve a min (y al bajar de min vuelve a max). */
        WRAP,
        /** Se detiene en min o max sin envolver. */
        CLAMP
    }

    public record VarDecl(String name, int min, int max, int initial, VarPolicy policy) {
        public VarDecl {
            Hardware.requireIdentifier(name, "Nombre de variable");
            Objects.requireNonNull(policy, "policy");
            if (min < 0 || max > 255 || min > max) {
                throw new IllegalArgumentException("Rango inválido para variable '" + name + "': [" + min + ".." + max + "]");
            }
            if (initial < min || initial > max) {
                throw new IllegalArgumentException("Valor inicial " + initial + " fuera de [" + min + ".." + max + "] para '" + name + "'");
            }
        }

        public static VarDecl counter(String name, int min, int max, int initial, VarPolicy policy) {
            return new VarDecl(name, min, max, initial, policy);
        }

        public static VarDecl u8(String name, int initial) {
            return new VarDecl(name, 0, 255, initial, VarPolicy.WRAP);
        }
    }

    /** Grupo de fases nombradas de una máquina de estados finita (FSM). */
    public record PhaseGroup(String name, List<String> phases, String initialPhase) {
        public PhaseGroup {
            Hardware.requireIdentifier(name, "Nombre de grupo de fases");
            Objects.requireNonNull(phases, "phases");
            Objects.requireNonNull(initialPhase, "initialPhase");
            if (phases.size() < 2 || phases.size() > 64) {
                throw new IllegalArgumentException("Grupo de fases '" + name + "' debe tener entre 2 y 64 fases.");
            }
            var seen = new LinkedHashSet<String>();
            var seenUpper = new LinkedHashSet<String>();
            for (String phase : phases) {
                Hardware.requireIdentifier(phase, "Nombre de fase");
                if (!seen.add(phase) || !seenUpper.add(phase.toUpperCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("Fase duplicada (sin distinguir mayúsculas) '" + phase + "' en grupo '" + name + "'");
                }
            }
            if (!seen.contains(initialPhase)) {
                throw new IllegalArgumentException("Fase inicial '" + initialPhase + "' no existe en grupo '" + name + "'");
            }
            phases = List.copyOf(phases);
        }

        public int indexOf(String phaseName) {
            int idx = phases.indexOf(phaseName);
            if (idx < 0) {
                throw new IllegalArgumentException("Fase desconocida '" + phaseName + "' en grupo '" + name + "'");
            }
            return idx;
        }
    }

    /** Temporizador cooperativo descendente de 16 bits (1..65000 ms). */
    public record TimerDecl(String name) {
        public TimerDecl {
            Hardware.requireIdentifier(name, "Nombre de temporizador");
        }
    }
}
