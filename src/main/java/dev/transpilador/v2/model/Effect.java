package dev.transpilador.v2.model;

import java.util.Objects;

/**
 * Bloque 4 · Efectos atómicos e intencionales sobre salidas físicas y estado.
 */
public sealed interface Effect permits
        Effect.WriteLed,
        Effect.ToggleLed,
        Effect.WriteBcd,
        Effect.WriteDecimal2,
        Effect.WritePwm,
        Effect.SendUartText,
        Effect.SendUartByte,
        Effect.SetVar,
        Effect.StepVar,
        Effect.ResetVar,
        Effect.SetPhase,
        Effect.StartTimer,
        Effect.StopTimer,
        Effect.DelayMs {

    enum LedState { ON, OFF }
    enum StepDir { INC, DEC }

    record WriteLed(String ledName, LedState state) implements Effect {
        public WriteLed {
            Hardware.requireIdentifier(ledName, "LED");
            Objects.requireNonNull(state, "state");
        }
    }

    record ToggleLed(String ledName) implements Effect {
        public ToggleLed { Hardware.requireIdentifier(ledName, "LED"); }
    }

    /** Escribe un valor U8 (0..9) en un display BCD CD4511. */
    record WriteBcd(String displayName, Expr.ValueExpr value) implements Effect {
        public WriteBcd {
            Hardware.requireIdentifier(displayName, "Display BCD");
            Objects.requireNonNull(value, "value");
        }
    }

    /** Descompone un valor U8 (0..99) en unidades y decenas sobre dos displays BCD. */
    record WriteDecimal2(String unitsDisplay, String tensDisplay, Expr.ValueExpr value) implements Effect {
        public WriteDecimal2 {
            Hardware.requireIdentifier(unitsDisplay, "Display de unidades");
            Hardware.requireIdentifier(tensDisplay, "Display de decenas");
            Objects.requireNonNull(value, "value");
            if (unitsDisplay.equals(tensDisplay)) {
                throw new IllegalArgumentException("Unidades y decenas deben usar displays distintos.");
            }
        }
    }

    /** Escribe cualquier expresión U8 (constante, contador o ADC 8-bit) en un canal Fast PWM. */
    record WritePwm(String pwmName, Expr.ValueExpr duty) implements Effect {
        public WritePwm {
            Hardware.requireIdentifier(pwmName, "Salida PWM");
            Objects.requireNonNull(duty, "duty");
        }
    }

    record SendUartText(String uartName, String ascii) implements Effect {
        public SendUartText {
            Hardware.requireIdentifier(uartName, "Puerto UART");
            Objects.requireNonNull(ascii, "ascii");
            if (ascii.isEmpty() || ascii.length() > 256) {
                throw new IllegalArgumentException("Texto UART debe tener entre 1 y 256 caracteres.");
            }
            if (ascii.chars().anyMatch(c -> c == 0 || c > 127)) {
                throw new IllegalArgumentException("Texto UART admite solo ASCII de 7 bits sin byte nulo (1..127); usa SendUartByte para enviar 0.");
            }
        }
    }

    record SendUartByte(String uartName, Expr.ValueExpr value) implements Effect {
        public SendUartByte {
            Hardware.requireIdentifier(uartName, "Puerto UART");
            Objects.requireNonNull(value, "value");
        }
    }

    record SetVar(String varName, Expr.ValueExpr value) implements Effect {
        public SetVar {
            Hardware.requireIdentifier(varName, "Variable");
            Objects.requireNonNull(value, "value");
        }
    }

    record StepVar(String varName, StepDir direction) implements Effect {
        public StepVar {
            Hardware.requireIdentifier(varName, "Variable");
            Objects.requireNonNull(direction, "direction");
        }
    }

    record ResetVar(String varName) implements Effect {
        public ResetVar { Hardware.requireIdentifier(varName, "Variable"); }
    }

    record SetPhase(String groupName, String phaseName) implements Effect {
        public SetPhase {
            Hardware.requireIdentifier(groupName, "Grupo de fases");
            Hardware.requireIdentifier(phaseName, "Fase");
        }
    }

    record StartTimer(String timerName, int durationMs) implements Effect {
        public StartTimer {
            Hardware.requireIdentifier(timerName, "Temporizador");
            if (durationMs < 1 || durationMs > 65_000) {
                throw new IllegalArgumentException("Duración de temporizador fuera de rango (1..65000 ms): " + durationMs);
            }
        }
    }

    record StopTimer(String timerName) implements Effect {
        public StopTimer { Hardware.requireIdentifier(timerName, "Temporizador"); }
    }

    /** Retardo bloqueante explícito (solo cuando el usuario pide detener el bucle N ms). */
    record DelayMs(int durationMs) implements Effect {
        public DelayMs {
            if (durationMs < 1 || durationMs > 10_000) {
                throw new IllegalArgumentException("Retardo bloqueante fuera de rango (1..10000 ms): " + durationMs);
            }
        }
    }

    // Fábricas concisas
    static Effect ledOn(String led) { return new WriteLed(led, LedState.ON); }
    static Effect ledOff(String led) { return new WriteLed(led, LedState.OFF); }
    static Effect toggleLed(String led) { return new ToggleLed(led); }
    static Effect showBcd(String disp, Expr.ValueExpr val) { return new WriteBcd(disp, val); }
    static Effect showDecimal2(String units, String tens, Expr.ValueExpr val) { return new WriteDecimal2(units, tens, val); }
    static Effect setPwm(String pwm, Expr.ValueExpr duty) { return new WritePwm(pwm, duty); }
    static Effect sendText(String uart, String ascii) { return new SendUartText(uart, ascii); }
    static Effect sendByte(String uart, Expr.ValueExpr val) { return new SendUartByte(uart, val); }
    static Effect setVar(String var, Expr.ValueExpr val) { return new SetVar(var, val); }
    static Effect inc(String var) { return new StepVar(var, StepDir.INC); }
    static Effect dec(String var) { return new StepVar(var, StepDir.DEC); }
    static Effect resetVar(String var) { return new ResetVar(var); }
    static Effect setPhase(String group, String phase) { return new SetPhase(group, phase); }
    static Effect startTimer(String timer, int ms) { return new StartTimer(timer, ms); }
    static Effect stopTimer(String timer) { return new StopTimer(timer); }
    static Effect delayMs(int ms) { return new DelayMs(ms); }
}
