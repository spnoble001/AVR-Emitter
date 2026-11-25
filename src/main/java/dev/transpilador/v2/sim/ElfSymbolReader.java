package dev.transpilador.v2.sim;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lector ligero ELF32 (little-endian) en Java puro para extraer direcciones exactas
 * en SRAM (0x0100..0x08FF) de los símbolos compilados por avr-gcc, sin lanzar procesos externos.
 */
public final class ElfSymbolReader {
    private static final int SHT_SYMTAB = 2;
    private static final int AVR_SRAM_MASK = 0xFFFF0000;
    private static final int AVR_SRAM_BASE = 0x00800000;

    private ElfSymbolReader() {}

    /**
     * Devuelve un mapa {@code nombreSimbolo -> direccionSram (0x0100..0x08FF)} para todos los
     * símbolos ubicados en espacio de datos SRAM del archivo ELF generado por {@code avr-gcc}.
     */
    public static Map<String, Integer> readSramSymbols(Path elfFile) throws IOException {
        Map<String, Integer> symbols = new LinkedHashMap<>();
        if (elfFile == null || !Files.isRegularFile(elfFile)) return symbols;

        byte[] bytes = Files.readAllBytes(elfFile);
        if (bytes.length < 52) return symbols;
        // Verificar firma ELF32 little-endian
        if (bytes[0] != 0x7F || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F') return symbols;
        if (bytes[4] != 1 || bytes[5] != 1) return symbols;

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int shOff = buf.getInt(32);
        int shEntSize = buf.getShort(46) & 0xFFFF;
        int shNum = buf.getShort(48) & 0xFFFF;
        if (shOff <= 0 || shEntSize < 40 || shNum == 0) return symbols;
        if ((long) shOff + (long) shEntSize * shNum > bytes.length) return symbols;

        for (int i = 0; i < shNum; i++) {
            int sec = shOff + i * shEntSize;
            int shType = buf.getInt(sec + 4);
            if (shType != SHT_SYMTAB) continue;

            int symOff = buf.getInt(sec + 16);
            int symSize = buf.getInt(sec + 20);
            int strIdx = buf.getInt(sec + 24);
            int symEntSize = buf.getInt(sec + 36);
            if (symEntSize < 16 || strIdx < 0 || strIdx >= shNum) continue;

            int strSec = shOff + strIdx * shEntSize;
            int strOff = buf.getInt(strSec + 16);
            int strSize = buf.getInt(strSec + 20);
            if (strOff < 0 || strOff + strSize > bytes.length) continue;
            if (symOff < 0 || symOff + symSize > bytes.length) continue;

            int count = symSize / symEntSize;
            for (int k = 0; k < count; k++) {
                int entry = symOff + k * symEntSize;
                int stName = buf.getInt(entry);
                int stValue = buf.getInt(entry + 4);
                if (stName <= 0 || stName >= strSize) continue;
                if ((stValue & AVR_SRAM_MASK) != AVR_SRAM_BASE) continue;

                int sramAddr = stValue & 0xFFFF;
                if (sramAddr < 0x0100 || sramAddr >= 0x0900) continue;

                String name = readCString(bytes, strOff + stName, strOff + strSize);
                if (!name.isEmpty()) {
                    symbols.putIfAbsent(name, sramAddr);
                }
            }
        }
        return symbols;
    }

    private static String readCString(byte[] data, int start, int limit) {
        int end = start;
        while (end < limit && data[end] != 0) end++;
        return new String(data, start, end - start, StandardCharsets.US_ASCII);
    }
}
