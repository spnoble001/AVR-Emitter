package dev.transpilador.v2.emit;

import java.util.*;

/**
 * Catálogo didáctico en español del repertorio de instrucciones ATmega328P (AVRASM2).
 * Proporciona explicación contextualizada con los operandos reales de cada línea,
 * ciclos de reloj y banderas de SREG afectadas para los tooltips del visor ASM.
 */
public final class AvrInstructionCatalog {

    public record InstructionDoc(
            String mnemonic,
            String category,
            String summary,
            String cycles,
            String sregFlags) {}

    private static final Map<String, InstructionDoc> DOCS = buildCatalog();

    private AvrInstructionCatalog() {}

    public static Optional<InstructionDoc> lookup(String mnemonic) {
        if (mnemonic == null || mnemonic.isBlank()) return Optional.empty();
        return Optional.ofNullable(DOCS.get(mnemonic.toLowerCase(Locale.ROOT)));
    }

    public static Map<String, InstructionDoc> all() {
        return DOCS;
    }

    /**
     * Analiza una línea de código AVRASM2 y devuelve un tooltip HTML didáctico en español
     * (con explicación contextual de los operandos, ciclos y banderas SREG), o {@code null}
     * si la línea está vacía o es solo un comentario.
     */
    public static String tooltipHtmlForLine(String rawLine) {
        if (rawLine == null) return null;
        String code = rawLine;
        int semi = rawLine.indexOf(';');
        if (semi >= 0) code = rawLine.substring(0, semi);
        code = code.trim();
        if (code.isEmpty()) return null;

        // Etiqueta pura (ej. "bucle:")
        if (code.endsWith(":") && !code.contains(" ") && !code.contains("\t")) {
            String lbl = code.substring(0, code.length() - 1);
            return formatHtml(
                    lbl + ":",
                    "Etiqueta de código",
                    "Marca una dirección en memoria Flash destino de saltos (<code>rjmp</code>/<code>jmp</code>/<code>breq</code>) o llamadas (<code>rcall</code>/<code>call</code>).",
                    "0 ciclos (solo marca simbólica)",
                    "Ninguna");
        }

        // Si tiene etiqueta + directiva/instrucción en la misma línea (ej. "uart_txt_0: .db ...")
        int colon = code.indexOf(':');
        if (colon > 0 && !containsWhitespaceBefore(code, colon)) {
            code = code.substring(colon + 1).trim();
            if (code.isEmpty()) return null;
        }

        String[] parts = code.split("\\s+", 2);
        String mnem = parts[0].toLowerCase(Locale.ROOT);
        String operandsRaw = parts.length > 1 ? parts[1].trim() : "";
        String[] ops = splitOperands(operandsRaw);

        // Directivas del ensamblador (.def, .equ, .org, .dseg, .cseg, .db, .byte, .include)
        if (mnem.startsWith(".")) {
            return directiveTooltip(mnem, operandsRaw);
        }

        InstructionDoc doc = DOCS.get(mnem);
        if (doc == null) return null;

        String contextual = contextualExplanation(mnem, ops, doc.summary());
        String headline = operandsRaw.isEmpty() ? mnem : (mnem + " " + operandsRaw);
        return formatHtml(headline, doc.category(), contextual, doc.cycles(), doc.sregFlags());
    }

    private static String directiveTooltip(String dir, String args) {
        return switch (dir) {
            case ".include" -> formatHtml(dir + " " + args, "Directiva AVRASM2",
                    "Incluye definiciones simbólicas de pines, puertos y registros del ATmega328P (<code>m328Pdef.inc</code>).",
                    "Tiempo de ensamblado", "Ninguna");
            case ".def" -> formatHtml(dir + " " + args, "Directiva AVRASM2",
                    "Asocia un nombre simbólico legible a un registro físico de CPU (<code>r0..r31</code>) sin consumir SRAM.",
                    "Tiempo de ensamblado", "Ninguna");
            case ".equ" -> formatHtml(dir + " " + args, "Directiva AVRASM2",
                    "Define una constante simbólica en tiempo de ensamblado (como un número de fase FSM).",
                    "Tiempo de ensamblado", "Ninguna");
            case ".cseg" -> formatHtml(".cseg", "Directiva de segmento",
                    "Inicia el segmento de código en memoria Flash (donde viven las instrucciones y tablas constantes).",
                    "Tiempo de ensamblado", "Ninguna");
            case ".dseg" -> formatHtml(".dseg", "Directiva de segmento",
                    "Inicia el segmento de datos en memoria SRAM interna (desde <code>0x0100</code>).",
                    "Tiempo de ensamblado", "Ninguna");
            case ".org" -> formatHtml(dir + " " + args, "Directiva de dirección",
                    "Ubica el contador de ensamblado en la dirección exacta indicada (vector de reset o inicio de SRAM).",
                    "Tiempo de ensamblado", "Ninguna");
            case ".byte" -> formatHtml(dir + " " + args, "Directiva SRAM",
                    "Reserva " + (args.isEmpty() ? "N" : escape(args)) + " byte(s) en memoria SRAM dentro de <code>.dseg</code>.",
                    "Tiempo de ensamblado", "Ninguna");
            case ".db" -> formatHtml(dir, "Directiva Flash",
                    "Almacena bytes constantes (texto UART terminado en 0) directamente en memoria Flash de programa.",
                    "Tiempo de ensamblado", "Ninguna");
            default -> null;
        };
    }

    private static String contextualExplanation(String mnem, String[] ops, String fallback) {
        String a = ops.length > 0 ? "<code>" + escape(ops[0]) + "</code>" : "";
        String b = ops.length > 1 ? "<code>" + escape(ops[1]) + "</code>" : "";
        return switch (mnem) {
            case "ldi" -> "Carga el valor inmediato " + b + " directamente en el registro alto " + a + " (<code>r16..r31</code>).";
            case "lds" -> "Lee 1 byte desde la dirección de memoria/registro extendido " + b + " hacia " + a + ".";
            case "sts" -> "Escribe el contenido de " + b + " en la dirección de memoria/registro extendido " + a + ".";
            case "mov" -> "Copia el valor del registro " + b + " dentro de " + a + " sin modificar banderas.";
            case "clr" -> "Pone el registro " + a + " en <code>0</code> (equivalente a <code>eor r, r</code>).";
            case "ser" -> "Pone todos los bits del registro " + a + " en <code>1</code> (<code>0xFF</code>).";
            case "in" -> "Lee el registro de E/S " + b + " (puerto o estado) y lo guarda en " + a + ".";
            case "out" -> "Escribe el valor de " + b + " en el registro de E/S " + a + ".";
            case "sbi" -> "Pone en <b>1 (ALTO)</b> el bit " + b + " del registro de E/S " + a + " sin alterar los demás bits.";
            case "cbi" -> "Pone en <b>0 (BAJO)</b> el bit " + b + " del registro de E/S " + a + " sin alterar los demás bits.";
            case "sbic" -> "Salta la siguiente instrucción si el bit " + b + " de " + a + " está en <b>0 (BAJO / 0 V)</b>.";
            case "sbis" -> "Salta la siguiente instrucción si el bit " + b + " de " + a + " está en <b>1 (ALTO / 5 V)</b>.";
            case "sbrc" -> "Salta la siguiente instrucción si el bit " + b + " del registro " + a + " es <b>0</b>.";
            case "sbrs" -> "Salta la siguiente instrucción si el bit " + b + " del registro " + a + " es <b>1</b>.";
            case "inc" -> "Suma <code>1</code> al registro " + a + " (no modifica la bandera de acarreo <code>C</code>).";
            case "dec" -> "Resta <code>1</code> al registro " + a + " y activa <code>Z=1</code> si llega exactamente a <code>0</code>.";
            case "add" -> "Suma " + a + " + " + b + " y guarda el resultado en " + a + ".";
            case "adc" -> "Suma " + a + " + " + b + " + bandera de acarreo <code>C</code> (aritmética multiprecisión).";
            case "sub" -> "Resta " + a + " − " + b + " y guarda el resultado en " + a + ".";
            case "subi" -> "Resta el inmediato " + b + " al registro alto " + a + ".";
            case "sbc" -> "Resta " + a + " − " + b + " − préstamo <code>C</code> (16 bits).";
            case "sbci" -> "Resta inmediato " + b + " y préstamo <code>C</code> al byte alto " + a + " (16 bits).";
            case "and" -> "Operación bit a bit AND: " + a + " = " + a + " &amp; " + b + ".";
            case "andi" -> "Enmascara bits con AND inmediato: " + a + " = " + a + " &amp; " + b + ".";
            case "or" -> "Operación bit a bit OR: " + a + " = " + a + " | " + b + " (útil también para probar si un par 16-bit es 0).";
            case "ori" -> "Activa bits con OR inmediato: " + a + " = " + a + " | " + b + ".";
            case "eor" -> "Operación XOR bit a bit: " + a + " = " + a + " ^ " + b + " (invierte bits donde " + b + " tiene 1).";
            case "cp" -> "Compara " + a + " con " + b + " calculando " + a + " − " + b + " solo para actualizar <code>SREG</code> (sin modificar registros).";
            case "cpi" -> "Compara el registro alto " + a + " con la constante " + b + " (calcula " + a + " − " + b + " sin alterar " + a + ").";
            case "cpc" -> "Compara con acarreo " + a + " contra " + b + " continuando una comparación previa de 16 bits.";
            case "tst" -> "Prueba si " + a + " es cero o negativo actualizando <code>Z</code> y <code>N</code> sin modificar " + a + ".";
            case "mul" -> "Multiplica sin signo " + a + " × " + b + " dejando el producto de 16 bits en <code>r1:r0</code>.";
            case "lsl" -> "Desplaza " + a + " 1 bit a la izquierda (multiplica ×2; el bit 7 pasa a <code>C</code>).";
            case "lsr" -> "Desplaza " + a + " 1 bit a la derecha sin signo (divide ÷2; el bit 0 pasa a <code>C</code>).";
            case "ror" -> "Rota " + a + " 1 bit a la derecha a través de <code>C</code> (continúa división ÷2 de 16 bits).";
            case "rol" -> "Rota " + a + " 1 bit a la izquierda a través de <code>C</code>.";
            case "lpm" -> "Lee 1 byte constante desde memoria Flash usando el puntero <code>Z</code> (<code>r31:r30</code>).";
            case "push" -> "Apila el valor de " + a + " en la SRAM apuntada por <code>SP</code> y decrementa <code>SP</code>.";
            case "pop" -> "Incrementa <code>SP</code> y recupera en " + a + " el último byte guardado en la pila.";
            case "rjmp" -> "Salto relativo incondicional hacia " + a + " (alcance ±2048 palabras en Flash).";
            case "jmp" -> "Salto absoluto incondicional hacia " + a + " en cualquier punto de los 32 KB de Flash.";
            case "rcall" -> "Llama a la subrutina " + a + " guardando la dirección de retorno en la pila (<code>SP</code>).";
            case "call" -> "Llamada absoluta a la subrutina " + a + " guardando la dirección de retorno en la pila.";
            case "ret" -> "Retorna de una subrutina recuperando el contador de programa <code>PC</code> desde la pila.";
            case "breq" -> "Salta a " + a + " si <code>Z = 1</code> (los valores comparados son iguales o el resultado fue cero).";
            case "brne" -> "Salta a " + a + " si <code>Z = 0</code> (los valores son distintos o el resultado no fue cero).";
            case "brlo" -> "Salta a " + a + " si <code>C = 1</code> (menor sin signo: <code>izq &lt; der</code>).";
            case "brsh" -> "Salta a " + a + " si <code>C = 0</code> (mayor o igual sin signo: <code>izq &gt;= der</code>).";
            case "nop" -> "No realiza operación; consume exactamente 1 ciclo de reloj para calibrar tiempos.";
            case "cli" -> "Deshabilita interrupciones globales poniendo <code>SREG.I = 0</code>.";
            case "sei" -> "Habilita interrupciones globales poniendo <code>SREG.I = 1</code>.";
            default -> fallback;
        };
    }

    private static String formatHtml(String headline, String category, String desc, String cycles, String flags) {
        boolean affectsFlags = !"Ninguna".equals(flags) && !flags.startsWith("Ninguna");
        String flagColor = affectsFlags ? "#D98C4E" : "#848E89";
        return "<html><div style='width:330px; padding:8px 10px; background:#141716; color:#ECEFED; font-family:Segoe UI,sans-serif; font-size:12px;'>"
                + "<div style='margin-bottom:5px;'>"
                + "<span style='font-family:Consolas,monospace; font-weight:bold; color:#4CC6BC; font-size:12.5px;'>" + escape(headline) + "</span>"
                + " &nbsp;<span style='color:#848E89; font-size:11px;'>" + escape(category) + "</span>"
                + "</div>"
                + "<div style='color:#D5DCD8; margin-bottom:7px; line-height:1.4;'>" + desc + "</div>"
                + "<div style='border-top:1px solid #262B29; padding-top:5px; font-family:Consolas,monospace; font-size:11px;'>"
                + "<span style='color:#36ADA3;'>Ciclos:</span> " + escape(cycles)
                + "<br/><span style='color:" + flagColor + ";'>SREG:</span> <span style='color:" + flagColor + ";'>" + escape(flags) + "</span>"
                + "</div></div></html>";
    }

    private static String[] splitOperands(String raw) {
        if (raw.isEmpty()) return new String[0];
        List<String> out = new ArrayList<>(2);
        int depth = 0;
        int start = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == ',' && depth == 0) {
                out.add(raw.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(raw.substring(start).trim());
        return out.toArray(String[]::new);
    }

    private static boolean containsWhitespaceBefore(String s, int end) {
        for (int i = 0; i < end; i++) {
            if (Character.isWhitespace(s.charAt(i))) return true;
        }
        return false;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Map<String, InstructionDoc> buildCatalog() {
        Map<String, InstructionDoc> m = new LinkedHashMap<>();
        put(m, "ldi", "Transferencia", "Carga inmediato en registro alto (r16..r31).", "1 ciclo", "Ninguna");
        put(m, "lds", "Memoria SRAM/IO", "Carga 1 byte desde espacio de datos (SRAM o E/S extendida).", "2 ciclos", "Ninguna");
        put(m, "sts", "Memoria SRAM/IO", "Almacena 1 byte en espacio de datos (SRAM o E/S extendida).", "2 ciclos", "Ninguna");
        put(m, "ld", "Memoria SRAM", "Carga indirecta desde SRAM mediante puntero X/Y/Z.", "2 ciclos", "Ninguna");
        put(m, "st", "Memoria SRAM", "Escritura indirecta en SRAM mediante puntero X/Y/Z.", "2 ciclos", "Ninguna");
        put(m, "lpm", "Memoria Flash", "Lee 1 byte de memoria de programa Flash apuntado por Z.", "3 ciclos", "Ninguna");
        put(m, "mov", "Transferencia", "Copia el contenido de un registro a otro.", "1 ciclo", "Ninguna");
        put(m, "movw", "Transferencia", "Copia un par de registros de 16 bits.", "1 ciclo", "Ninguna");
        put(m, "in", "E/S directa", "Lee registro de E/S (0x00..0x3F) a un registro general.", "1 ciclo", "Ninguna");
        put(m, "out", "E/S directa", "Escribe un registro general en un registro de E/S (0x00..0x3F).", "1 ciclo", "Ninguna");
        put(m, "sbi", "Bits de E/S", "Pone en 1 un bit de un registro de E/S bajo (PORT/DDR).", "2 ciclos", "Ninguna");
        put(m, "cbi", "Bits de E/S", "Pone en 0 un bit de un registro de E/S bajo (PORT/DDR).", "2 ciclos", "Ninguna");
        put(m, "sbic", "Salto condicional por bit", "Salta la siguiente instrucción si el bit de E/S está en 0.", "1 / 2 / 3 ciclos (según salto)", "Ninguna");
        put(m, "sbis", "Salto condicional por bit", "Salta la siguiente instrucción si el bit de E/S está en 1.", "1 / 2 / 3 ciclos (según salto)", "Ninguna");
        put(m, "sbrc", "Salto condicional por bit", "Salta la siguiente instrucción si el bit del registro es 0.", "1 / 2 / 3 ciclos (según salto)", "Ninguna");
        put(m, "sbrs", "Salto condicional por bit", "Salta la siguiente instrucción si el bit del registro es 1.", "1 / 2 / 3 ciclos (según salto)", "Ninguna");
        put(m, "clr", "Lógica", "Limpia un registro dejándolo en 0.", "1 ciclo", "S=0, V=0, N=0, Z=1");
        put(m, "ser", "Transferencia", "Carga 0xFF en un registro alto.", "1 ciclo", "Ninguna");
        put(m, "inc", "Aritmética", "Incrementa un registro en 1.", "1 ciclo", "S, V, N, Z");
        put(m, "dec", "Aritmética", "Decrementa un registro en 1.", "1 ciclo", "S, V, N, Z");
        put(m, "add", "Aritmética", "Suma dos registros sin acarreo previo.", "1 ciclo", "H, S, V, N, Z, C");
        put(m, "adc", "Aritmética", "Suma dos registros más el acarreo C.", "1 ciclo", "H, S, V, N, Z, C");
        put(m, "sub", "Aritmética", "Resta dos registros.", "1 ciclo", "H, S, V, N, Z, C");
        put(m, "subi", "Aritmética", "Resta una constante inmediata de 8 bits.", "1 ciclo", "H, S, V, N, Z, C");
        put(m, "sbc", "Aritmética", "Resta con préstamo (carry).", "1 ciclo", "H, S, V, N, Z, C");
        put(m, "sbci", "Aritmética", "Resta inmediato con préstamo (16 bits).", "1 ciclo", "H, S, V, N, Z, C");
        put(m, "adiw", "Aritmética 16-bit", "Suma inmediato (0..63) a un par de registros alto.", "2 ciclos", "S, V, N, Z, C");
        put(m, "sbiw", "Aritmética 16-bit", "Resta inmediato (0..63) a un par de registros alto.", "2 ciclos", "S, V, N, Z, C");
        put(m, "and", "Lógica", "AND bit a bit entre registros.", "1 ciclo", "S, V=0, N, Z");
        put(m, "andi", "Lógica", "AND bit a bit con máscara inmediata.", "1 ciclo", "S, V=0, N, Z");
        put(m, "or", "Lógica", "OR bit a bit entre registros.", "1 ciclo", "S, V=0, N, Z");
        put(m, "ori", "Lógica", "OR bit a bit con máscara inmediata.", "1 ciclo", "S, V=0, N, Z");
        put(m, "eor", "Lógica", "XOR bit a bit entre registros.", "1 ciclo", "S, V=0, N, Z");
        put(m, "com", "Lógica", "Complemento a 1 (invierte todos los bits).", "1 ciclo", "S, V=0, N, Z, C=1");
        put(m, "neg", "Aritmética", "Complemento a 2 (niega signo).", "1 ciclo", "H, S, V, N, Z, C");
        put(m, "cp", "Comparación", "Compara dos registros (Rd − Rr) actualizando banderas.", "1 ciclo", "H, S, V, N, Z, C");
        put(m, "cpi", "Comparación", "Compara registro alto contra inmediato (Rd − K).", "1 ciclo", "H, S, V, N, Z, C");
        put(m, "cpc", "Comparación 16-bit", "Compara con acarreo previo para enteros multiprecisión.", "1 ciclo", "H, S, V, N, Z, C");
        put(m, "tst", "Comparación", "Prueba cero o signo de un registro.", "1 ciclo", "S, V=0, N, Z");
        put(m, "mul", "Multiplicación", "Multiplicación sin signo 8×8 → r1:r0.", "2 ciclos", "Z, C");
        put(m, "lsl", "Desplazamiento", "Desplazamiento lógico a la izquierda (×2).", "1 ciclo", "H, S, V, N, Z, C");
        put(m, "lsr", "Desplazamiento", "Desplazamiento lógico a la derecha (÷2 sin signo).", "1 ciclo", "S, V, N=0, Z, C");
        put(m, "rol", "Desplazamiento", "Rotación a la izquierda a través de C.", "1 ciclo", "H, S, V, N, Z, C");
        put(m, "ror", "Desplazamiento", "Rotación a la derecha a través de C.", "1 ciclo", "S, V, N, Z, C");
        put(m, "asr", "Desplazamiento", "Desplazamiento aritmético a la derecha (preserva signo).", "1 ciclo", "S, V, N, Z, C");
        put(m, "swap", "Bits", "Intercambia los 4 bits altos con los 4 bits bajos.", "1 ciclo", "Ninguna");
        put(m, "push", "Pila (SRAM)", "Guarda un registro en la pila y decrementa SP.", "2 ciclos", "Ninguna");
        put(m, "pop", "Pila (SRAM)", "Incrementa SP y restaura un registro desde la pila.", "2 ciclos", "Ninguna");
        put(m, "rjmp", "Control de flujo", "Salto relativo incondicional (±2048 palabras).", "2 ciclos", "Ninguna");
        put(m, "jmp", "Control de flujo", "Salto absoluto incondicional (toda la Flash).", "3 ciclos", "Ninguna");
        put(m, "rcall", "Subrutina", "Llamada relativa a subrutina (guarda PC en pila).", "3 ciclos", "Ninguna");
        put(m, "call", "Subrutina", "Llamada absoluta a subrutina (guarda PC en pila).", "4 ciclos", "Ninguna");
        put(m, "ret", "Subrutina", "Retorna de subrutina recuperando PC de la pila.", "4 ciclos", "Ninguna");
        put(m, "reti", "Interrupción", "Retorna de rutina de interrupción y rehabilita I=1.", "4 ciclos", "I=1");
        put(m, "breq", "Salto condicional", "Salta si igual / cero (Z = 1).", "1 ciclo (no salta) / 2 ciclos (salta)", "Ninguna (lee Z)");
        put(m, "brne", "Salto condicional", "Salta si distinto / no cero (Z = 0).", "1 ciclo (no salta) / 2 ciclos (salta)", "Ninguna (lee Z)");
        put(m, "brlo", "Salto condicional", "Salta si menor sin signo (C = 1).", "1 ciclo (no salta) / 2 ciclos (salta)", "Ninguna (lee C)");
        put(m, "brsh", "Salto condicional", "Salta si mayor o igual sin signo (C = 0).", "1 ciclo (no salta) / 2 ciclos (salta)", "Ninguna (lee C)");
        put(m, "brcs", "Salto condicional", "Salta si acarreo activo (C = 1).", "1 ciclo (no salta) / 2 ciclos (salta)", "Ninguna (lee C)");
        put(m, "brcc", "Salto condicional", "Salta si acarreo inactivo (C = 0).", "1 ciclo (no salta) / 2 ciclos (salta)", "Ninguna (lee C)");
        put(m, "brlt", "Salto condicional", "Salta si menor con signo (S = 1).", "1 ciclo (no salta) / 2 ciclos (salta)", "Ninguna (lee S)");
        put(m, "brge", "Salto condicional", "Salta si mayor o igual con signo (S = 0).", "1 ciclo (no salta) / 2 ciclos (salta)", "Ninguna (lee S)");
        put(m, "nop", "Control", "No operación: espera exactamente 1 ciclo de reloj.", "1 ciclo", "Ninguna");
        put(m, "cli", "Interrupciones", "Deshabilita interrupciones globales (I = 0).", "1 ciclo", "I=0");
        put(m, "sei", "Interrupciones", "Habilita interrupciones globales (I = 1).", "1 ciclo", "I=1");
        return Collections.unmodifiableMap(m);
    }

    private static void put(Map<String, InstructionDoc> m, String mnem, String cat, String summary, String cycles, String flags) {
        m.put(mnem, new InstructionDoc(mnem, cat, summary, cycles, flags));
    }
}
