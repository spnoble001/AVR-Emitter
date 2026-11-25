package dev.transpilador.v2.model;

import java.util.List;
import java.util.Objects;

/**
 * Bloque 3 · Expresiones de valor (U8 / U10) y condiciones booleanas.
 */
public final class Expr {
    private Expr() {}

    public sealed interface ValueExpr permits ConstU8, ConstU10, ReadVar, ReadAdc8, ReadAdc10, ReadUartByte, Scale {
        /** True si la expresión puede exceder 8 bits (0..1023). */
        default boolean is10Bit() { return false; }
    }

    public record ConstU8(int value) implements ValueExpr {
        public ConstU8 {
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException("Constante U8 fuera de rango (0..255): " + value);
            }
        }
    }

    public record ConstU10(int value) implements ValueExpr {
        public ConstU10 {
            if (value < 0 || value > 1023) {
                throw new IllegalArgumentException("Constante U10 fuera de rango (0..1023): " + value);
            }
        }
        @Override public boolean is10Bit() { return true; }
    }

    public record ReadVar(String varName) implements ValueExpr {
        public ReadVar { Hardware.requireIdentifier(varName, "Variable"); }
    }

    /** Lectura ADC de 8 bits (equivalente a ADCH = muestra10 >> 2, rango 0..255). */
    public record ReadAdc8(String analogName) implements ValueExpr {
        public ReadAdc8 { Hardware.requireIdentifier(analogName, "Entrada analógica"); }
    }

    /** Lectura ADC completa de 10 bits (0..1023). */
    public record ReadAdc10(String analogName) implements ValueExpr {
        public ReadAdc10 { Hardware.requireIdentifier(analogName, "Entrada analógica"); }
        @Override public boolean is10Bit() { return true; }
    }

    /** Último byte recibido por UART0 en la pasada actual. */
    public record ReadUartByte(String uartName) implements ValueExpr {
        public ReadUartByte { Hardware.requireIdentifier(uartName, "Puerto UART"); }
    }

    /**
     * Escalado lineal entero sin división en tiempo de ejecución para entradas 8-bit (0..255) o 10-bit (0..1023).
     * Fórmula: outMin + ((source * (outMax - outMin + 1)) >> shift), donde shift = 8 (si inMax==255) o 10 (si inMax==1023).
     */
    public record Scale(ValueExpr source, int inMax, int outMin, int outMax) implements ValueExpr {
        public Scale {
            Objects.requireNonNull(source, "source");
            if (inMax != 255 && inMax != 1023) {
                throw new IllegalArgumentException("Scale requiere inMax = 255 o 1023, recibido: " + inMax);
            }
            if (outMin < 0 || outMax > 255 || outMin >= outMax) {
                throw new IllegalArgumentException("Rango de salida inválido en Scale: [" + outMin + ".." + outMax + "]");
            }
        }

        public int shiftBits() { return inMax == 255 ? 8 : 10; }
        public int span() { return outMax - outMin + 1; }
    }

    public enum CmpOp {
        EQ("=="),
        NE("!="),
        LT("<"),
        LE("<="),
        GT(">"),
        GE(">=");

        private final String symbol;
        CmpOp(String symbol) { this.symbol = symbol; }
        public String symbol() { return symbol; }
    }

    public sealed interface BoolExpr permits
            Truth, Pressed, Compare, InRange, Modulo, InPhase, TimerActive, UartByteEquals, Not, And, Or, Xor {}

    public record Truth(boolean value) implements BoolExpr {}

    public record Pressed(String buttonName) implements BoolExpr {
        public Pressed { Hardware.requireIdentifier(buttonName, "Botón"); }
    }

    public record Compare(ValueExpr left, CmpOp op, ValueExpr right) implements BoolExpr {
        public Compare {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(right, "right");
        }
    }

    public record InRange(ValueExpr value, int min, int max) implements BoolExpr {
        public InRange {
            Objects.requireNonNull(value, "value");
            int limit = value.is10Bit() ? 1023 : 255;
            if (min < 0 || max > limit || min > max) {
                throw new IllegalArgumentException("Rango inválido en InRange: [" + min + ".." + max + "]");
            }
        }
    }

    public record Modulo(String varName, int divisor, int remainder, boolean includeZero) implements BoolExpr {
        public Modulo {
            Hardware.requireIdentifier(varName, "Variable");
            if (divisor < 1 || divisor > 255) {
                throw new IllegalArgumentException("Divisor módulo fuera de rango (1..255): " + divisor);
            }
            if (remainder < 0 || remainder >= divisor) {
                throw new IllegalArgumentException("Resto módulo fuera de rango (0.." + (divisor - 1) + "): " + remainder);
            }
        }
    }

    public record InPhase(String groupName, String phaseName) implements BoolExpr {
        public InPhase {
            Hardware.requireIdentifier(groupName, "Grupo de fases");
            Hardware.requireIdentifier(phaseName, "Fase");
        }
    }

    public record TimerActive(String timerName) implements BoolExpr {
        public TimerActive { Hardware.requireIdentifier(timerName, "Temporizador"); }
    }

    public record UartByteEquals(String uartName, int expectedByte) implements BoolExpr {
        public UartByteEquals {
            Hardware.requireIdentifier(uartName, "Puerto UART");
            if (expectedByte < 0 || expectedByte > 255) {
                throw new IllegalArgumentException("Byte UART fuera de rango (0..255): " + expectedByte);
            }
        }
    }

    public record Not(BoolExpr inner) implements BoolExpr {
        public Not { Objects.requireNonNull(inner, "inner"); }
    }

    public record And(List<BoolExpr> items) implements BoolExpr {
        public And {
            Objects.requireNonNull(items, "items");
            if (items.size() < 2) throw new IllegalArgumentException("And requiere al menos 2 condiciones");
            items = List.copyOf(items);
        }
    }

    public record Or(List<BoolExpr> items) implements BoolExpr {
        public Or {
            Objects.requireNonNull(items, "items");
            if (items.size() < 2) throw new IllegalArgumentException("Or requiere al menos 2 condiciones");
            items = List.copyOf(items);
        }
    }

    public record Xor(BoolExpr left, BoolExpr right) implements BoolExpr {
        public Xor {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    // Fábricas concisas para legibilidad en benchmarks, patrones y pruebas
    public static BoolExpr alwaysTrue() { return new Truth(true); }
    public static BoolExpr pressed(String btn) { return new Pressed(btn); }
    public static BoolExpr not(BoolExpr e) { return new Not(e); }
    public static BoolExpr and(BoolExpr... items) { return new And(List.of(items)); }
    public static BoolExpr or(BoolExpr... items) { return new Or(List.of(items)); }
    public static BoolExpr xor(BoolExpr l, BoolExpr r) { return new Xor(l, r); }
    public static BoolExpr cmp(ValueExpr l, CmpOp op, ValueExpr r) { return new Compare(l, op, r); }
    public static BoolExpr inPhase(String group, String phase) { return new InPhase(group, phase); }
    public static BoolExpr timerActive(String timer) { return new TimerActive(timer); }
    public static BoolExpr uartChar(String uart, char ch) { return new UartByteEquals(uart, ch & 0xFF); }
    public static ValueExpr u8(int v) { return new ConstU8(v); }
    public static ValueExpr u10(int v) { return new ConstU10(v); }
    public static ValueExpr var(String name) { return new ReadVar(name); }
    public static ValueExpr adc8(String name) { return new ReadAdc8(name); }
    public static ValueExpr adc10(String name) { return new ReadAdc10(name); }
    public static ValueExpr uartByte(String name) { return new ReadUartByte(name); }
    public static ValueExpr scaleAdc8To(String analog, int outMin, int outMax) {
        return new Scale(adc8(analog), 255, outMin, outMax);
    }
    public static ValueExpr scaleAdc10To(String analog, int outMin, int outMax) {
        return new Scale(adc10(analog), 1023, outMin, outMax);
    }
}
