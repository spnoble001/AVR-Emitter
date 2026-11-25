package dev.transpilador.v2.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Bloque 1 · Hardware físico del ATmega328P (puertos, pines y componentes).
 * Mantiene los contratos físicos del datasheet sin acoplarse a emisores concretos.
 */
public final class Hardware {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,31}");

    private Hardware() {}

    public static String requireIdentifier(String value, String kind) {
        Objects.requireNonNull(value, kind + " no puede ser nulo");
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(kind + " inválido '" + value + "': usa letras, dígitos o '_' empezando por letra (máx. 32).");
        }
        return value;
    }

    public enum Port {
        B('B', 5),
        C('C', 5),
        D('D', 7);

        private final char letter;
        private final int maxBit;

        Port(char letter, int maxBit) {
            this.letter = letter;
            this.maxBit = maxBit;
        }

        public char letter() { return letter; }
        public int maxBit() { return maxBit; }
        public String ddr() { return "DDR" + letter; }
        public String portReg() { return "PORT" + letter; }
        public String pinReg() { return "PIN" + letter; }
    }

    public record Pin(Port port, int bit) {
        public Pin {
            Objects.requireNonNull(port, "port");
            if (bit < 0 || bit > port.maxBit()) {
                throw new IllegalArgumentException("Pin fuera de rango para ATmega328P: P" + port.letter() + bit);
            }
        }

        public static Pin pb(int bit) { return new Pin(Port.B, bit); }
        public static Pin pc(int bit) { return new Pin(Port.C, bit); }
        public static Pin pd(int bit) { return new Pin(Port.D, bit); }

        public static Pin parse(String text) {
            Objects.requireNonNull(text, "pin");
            String norm = text.trim().toUpperCase(java.util.Locale.ROOT);
            if (norm.length() != 3 || norm.charAt(0) != 'P') {
                throw new IllegalArgumentException("Formato de pin inválido: " + text);
            }
            Port p = switch (norm.charAt(1)) {
                case 'B' -> Port.B;
                case 'C' -> Port.C;
                case 'D' -> Port.D;
                default -> throw new IllegalArgumentException("Puerto inválido en pin: " + text);
            };
            int bit = norm.charAt(2) - '0';
            return new Pin(p, bit);
        }

        public String label() { return "P" + port.letter() + bit; }
        public int bitMask() { return 1 << bit; }

        /** Número de patilla física en encapsulado PDIP-28 del ATmega328P. */
        public int dip28Pin() {
            return switch (port) {
                case B -> switch (bit) {
                    case 0 -> 14;
                    case 1 -> 15;
                    case 2 -> 16;
                    case 3 -> 17;
                    case 4 -> 18;
                    case 5 -> 19;
                    default -> throw new IllegalStateException();
                };
                case C -> switch (bit) {
                    case 0 -> 23;
                    case 1 -> 24;
                    case 2 -> 25;
                    case 3 -> 26;
                    case 4 -> 27;
                    case 5 -> 28;
                    default -> throw new IllegalStateException();
                };
                case D -> switch (bit) {
                    case 0 -> 2;
                    case 1 -> 3;
                    case 2 -> 4;
                    case 3 -> 5;
                    case 4 -> 6;
                    case 5 -> 11;
                    case 6 -> 12;
                    case 7 -> 13;
                    default -> throw new IllegalStateException();
                };
            };
        }

        public OptionalInt analogChannel() {
            return port == Port.C ? OptionalInt.of(bit) : OptionalInt.empty();
        }

        public Optional<PwmChannel> pwmChannel() {
            for (PwmChannel ch : PwmChannel.values()) {
                if (ch.pin().equals(this)) return Optional.of(ch);
            }
            return Optional.empty();
        }

        @Override
        public String toString() { return label(); }
    }

    public enum Connection {
        /** Activo en alto (botón con pull-down externo a GND; LED cátodo a GND). */
        VCC,
        /** Activo en bajo (botón con pull-up interno a VCC; LED ánodo a VCC). */
        GND
    }

    /** Frecuencias de reloj soportadas para ATmega328P (1 MHz RC/8, 8 MHz RC/cristal, 16 MHz cristal/Arduino). */
    public enum ClockFreq {
        MHZ_1(1_000_000L, "1 MHz", 8, "(1 << ADPS1) | (1 << ADPS0)", 8),
        MHZ_8(8_000_000L, "8 MHz", 64, "(1 << ADPS2) | (1 << ADPS1)", 64),
        MHZ_16(16_000_000L, "16 MHz", 128, "(1 << ADPS2) | (1 << ADPS1) | (1 << ADPS0)", 64);

        private final long hz;
        private final String label;
        private final int adcPrescaler;
        private final String adcPrescalerExpr;
        private final int pwmPrescaler;

        ClockFreq(long hz, String label, int adcPrescaler, String adcPrescalerExpr, int pwmPrescaler) {
            this.hz = hz;
            this.label = label;
            this.adcPrescaler = adcPrescaler;
            this.adcPrescalerExpr = adcPrescalerExpr;
            this.pwmPrescaler = pwmPrescaler;
        }

        public long hz() { return hz; }
        public String label() { return label; }
        public long cyclesPerMs() { return hz / 1_000L; }
        /** Prescaler ADC que mantiene el reloj del conversor exactamente en 125 kHz (rango datasheet: 50..200 kHz). */
        public int adcPrescaler() { return adcPrescaler; }
        public String adcPrescalerExpr() { return adcPrescalerExpr; }
        public int adcClockKHz() { return (int) (hz / adcPrescaler / 1000L); }
        /** Prescaler Fast PWM 8-bit: /8 a 1 MHz (≈488 Hz), /64 a 8 MHz (≈488 Hz) y /64 a 16 MHz (≈977 Hz). */
        public int pwmPrescaler() { return pwmPrescaler; }
        public int pwmFrequencyHz() { return (int) Math.round((double) hz / (pwmPrescaler * 256.0)); }

        /**
         * Bits CS del registro TCCRnB según la tabla exacta del datasheet ATmega328P.
         * Importante: en Timer2 el prescaler /64 es CS22 (0b100), distinto a Timer0/1 (CSn1|CSn0 = 0b011).
         */
        public String pwmClockSelectExpr(PwmTimer timer) {
            return switch (timer) {
                case TIMER0 -> pwmPrescaler == 8 ? "(1 << CS01)" : "(1 << CS01) | (1 << CS00)";
                case TIMER1 -> pwmPrescaler == 8 ? "(1 << CS11)" : "(1 << CS11) | (1 << CS10)";
                case TIMER2 -> pwmPrescaler == 8 ? "(1 << CS21)" : "(1 << CS22)";
            };
        }

        public static ClockFreq fromHz(long hz) {
            for (ClockFreq cf : values()) {
                if (cf.hz == hz) return cf;
            }
            throw new IllegalArgumentException(
                    "Frecuencia F_CPU no soportada: " + hz + " Hz. Usa 1000000 (1 MHz), 8000000 (8 MHz) o 16000000 (16 MHz).");
        }

        @Override public String toString() { return label; }
    }

    public enum PwmTimer {
        TIMER0("Timer0"),
        TIMER1("Timer1"),
        TIMER2("Timer2");

        private final String displayName;
        PwmTimer(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
    }

    /** Seis canales hardware Fast PWM de 8 bits del ATmega328P. */
    public enum PwmChannel {
        OC0A(Pin.pd(6), PwmTimer.TIMER0, 'A', "OCR0A", "TCCR0A", "COM0A1", "COM0A0", "TCCR0B", false),
        OC0B(Pin.pd(5), PwmTimer.TIMER0, 'B', "OCR0B", "TCCR0A", "COM0B1", "COM0B0", "TCCR0B", false),
        OC1A(Pin.pb(1), PwmTimer.TIMER1, 'A', "OCR1AL", "TCCR1A", "COM1A1", "COM1A0", "TCCR1B", true),
        OC1B(Pin.pb(2), PwmTimer.TIMER1, 'B', "OCR1BL", "TCCR1A", "COM1B1", "COM1B0", "TCCR1B", true),
        OC2A(Pin.pb(3), PwmTimer.TIMER2, 'A', "OCR2A", "TCCR2A", "COM2A1", "COM2A0", "TCCR2B", true),
        OC2B(Pin.pd(3), PwmTimer.TIMER2, 'B', "OCR2B", "TCCR2A", "COM2B1", "COM2B0", "TCCR2B", true);

        private final Pin pin;
        private final PwmTimer timer;
        private final char channelLetter;
        private final String ocrReg;
        private final String tccrA;
        private final String com1Bit;
        private final String com0Bit;
        private final String tccrB;
        private final boolean extendedIo;

        PwmChannel(Pin pin, PwmTimer timer, char channelLetter, String ocrReg, String tccrA,
                   String com1Bit, String com0Bit, String tccrB, boolean extendedIo) {
            this.pin = pin;
            this.timer = timer;
            this.channelLetter = channelLetter;
            this.ocrReg = ocrReg;
            this.tccrA = tccrA;
            this.com1Bit = com1Bit;
            this.com0Bit = com0Bit;
            this.tccrB = tccrB;
            this.extendedIo = extendedIo;
        }

        public Pin pin() { return pin; }
        public PwmTimer timer() { return timer; }
        public char channelLetter() { return channelLetter; }
        public String ocrReg() { return ocrReg; }
        public String tccrA() { return tccrA; }
        public String com1Bit() { return com1Bit; }
        public String com0Bit() { return com0Bit; }
        public String tccrB() { return tccrB; }
        /** True para Timer1/Timer2 (registros en espacio de memoria 0x80..0xB6, requieren LDS/STS en ASM). */
        public boolean extendedIo() { return extendedIo; }
    }

    public enum UartMode {
        TX_ONLY(true, false),
        RX_ONLY(false, true),
        FULL_DUPLEX(true, true),
        HALF_DUPLEX(true, true);

        private final boolean tx;
        private final boolean rx;

        UartMode(boolean tx, boolean rx) {
            this.tx = tx;
            this.rx = rx;
        }

        public boolean usesTx() { return tx; }
        public boolean usesRx() { return rx; }
    }

    public enum UartBaud {
        B2400(2400),
        B4800(4800),
        B9600(9600),
        B19200(19200),
        B38400(38400),
        B57600(57600),
        B115200(115200);

        private final int bps;
        UartBaud(int bps) { this.bps = bps; }
        public int bps() { return bps; }

        /**
         * Calcula UBRR0 (12 bits: 0..4095) con U2X0=1 a F_CPU dado.
         * Ejemplos: 9600 bps -> 12 (@1MHz), 103 (@8MHz), 207 (@16MHz); 2400 bps -> 416 (@8MHz), 832 (@16MHz).
         */
        public int ubrrU2x(long fCpuHz) {
            return Math.max(0, (int) Math.round((double) fCpuHz / (8.0 * bps) - 1.0));
        }

        /** Error porcentual absoluto de baudrate real con U2X0=1 respecto al nominal. */
        public double errorPercentU2x(long fCpuHz) {
            int ubrr = ubrrU2x(fCpuHz);
            double actual = (double) fCpuHz / (8.0 * (ubrr + 1.0));
            return Math.abs(actual - bps) * 100.0 / bps;
        }
    }

    public sealed interface Component permits Button, Led, BcdDisplay, AnalogInput, PwmOutput, UartPort {
        String name();
        List<Pin> occupiedPins();
    }

    public record Button(String name, Pin pin, Connection connection) implements Component {
        public Button {
            requireIdentifier(name, "Nombre de botón");
            Objects.requireNonNull(pin, "pin");
            Objects.requireNonNull(connection, "connection");
        }
        @Override public List<Pin> occupiedPins() { return List.of(pin); }
        /** True si presionar el botón produce nivel lógico 1 en el pin. */
        public boolean activeHigh() { return connection == Connection.VCC; }
    }

    public record Led(String name, Pin pin, Connection connection, boolean initialOn) implements Component {
        public Led {
            requireIdentifier(name, "Nombre de LED");
            Objects.requireNonNull(pin, "pin");
            Objects.requireNonNull(connection, "connection");
        }
        public Led(String name, Pin pin, Connection connection) {
            this(name, pin, connection, false);
        }
        @Override public List<Pin> occupiedPins() { return List.of(pin); }
        /** True si encender el LED requiere escribir nivel lógico 1 en PORT. */
        public boolean activeHigh() { return connection == Connection.GND; }
    }

    public record BcdDisplay(String name, Pin firstPin, int initialDigit) implements Component {
        public BcdDisplay {
            requireIdentifier(name, "Nombre de display BCD");
            Objects.requireNonNull(firstPin, "firstPin");
            if (firstPin.bit() + 3 > firstPin.port().maxBit()) {
                throw new IllegalArgumentException(
                        "Display BCD '" + name + "' necesita 4 pines consecutivos en " + firstPin.port()
                                + " desde " + firstPin.label());
            }
            if (initialDigit < 0 || initialDigit > 9) {
                throw new IllegalArgumentException("Dígito inicial BCD fuera de rango (0..9): " + initialDigit);
            }
        }
        public BcdDisplay(String name, Pin firstPin) { this(name, firstPin, 0); }

        @Override
        public List<Pin> occupiedPins() {
            return List.of(
                    new Pin(firstPin.port(), firstPin.bit()),
                    new Pin(firstPin.port(), firstPin.bit() + 1),
                    new Pin(firstPin.port(), firstPin.bit() + 2),
                    new Pin(firstPin.port(), firstPin.bit() + 3)
            );
        }

        public int portMask() { return 0x0F << firstPin.bit(); }
    }

    public record AnalogInput(String name, Pin pin) implements Component {
        public AnalogInput {
            requireIdentifier(name, "Nombre de entrada analógica");
            Objects.requireNonNull(pin, "pin");
            if (pin.analogChannel().isEmpty()) {
                throw new IllegalArgumentException("Entrada analógica '" + name + "' debe conectarse a PC0..PC5, no a " + pin);
            }
        }
        public int channel() { return pin.analogChannel().orElseThrow(); }
        @Override public List<Pin> occupiedPins() { return List.of(pin); }
    }

    public record PwmOutput(String name, Pin pin, int initialDuty) implements Component {
        public PwmOutput {
            requireIdentifier(name, "Nombre de salida PWM");
            Objects.requireNonNull(pin, "pin");
            if (pin.pwmChannel().isEmpty()) {
                throw new IllegalArgumentException(
                        "Salida PWM '" + name + "' debe conectarse a un pin OCnx (PD6, PD5, PB1, PB2, PB3, PD3), no a " + pin);
            }
            if (initialDuty < 0 || initialDuty > 255) {
                throw new IllegalArgumentException("Duty inicial PWM fuera de rango (0..255): " + initialDuty);
            }
        }
        public PwmOutput(String name, Pin pin) { this(name, pin, 0); }
        public PwmChannel channel() { return pin.pwmChannel().orElseThrow(); }
        @Override public List<Pin> occupiedPins() { return List.of(pin); }
    }

    public record UartPort(String name, UartBaud baud, UartMode mode) implements Component {
        public static final Pin RX_PIN = Pin.pd(0);
        public static final Pin TX_PIN = Pin.pd(1);

        public UartPort {
            requireIdentifier(name, "Nombre de UART");
            Objects.requireNonNull(baud, "baud");
            Objects.requireNonNull(mode, "mode");
        }

        @Override
        public List<Pin> occupiedPins() {
            if (mode.usesRx() && mode.usesTx()) return List.of(RX_PIN, TX_PIN);
            return mode.usesRx() ? List.of(RX_PIN) : List.of(TX_PIN);
        }
    }
}
