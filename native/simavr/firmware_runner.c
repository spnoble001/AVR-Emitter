/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Local firmware runner v2. Supports configurable ATmega328P clock (1, 8, 16 MHz). */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include "sim_avr.h"
#include "sim_elf.h"
#include "sim_hex.h"
#include "avr_ioport.h"
#include "avr_adc.h"
#include "avr_uart.h"

static avr_t *cpu;
static FILE *reply;
static uint16_t analog_mv[6];
static unsigned char tx[512];
static size_t tx_size;
static unsigned long long tx_total;
static int rx_full;
static uint8_t external_mask[3], external_value[3];
static elf_firmware_t firmware;
static uint32_t cpu_freq_hz = 1000000;
static FILE *trace;
static unsigned long trace_bytes;

static void trace_value(int id, char value) {
    if (!trace || trace_bytes >= 8UL * 1024UL * 1024UL) return;
    unsigned long long ns = ((unsigned long long)cpu->cycle * 1000000000ULL) / cpu_freq_hz;
    int n = fprintf(trace, "#%llu\n%c%c\n", ns, value, '!' + id);
    if (n > 0) trace_bytes += (unsigned)n;
    if (trace_bytes >= 8UL * 1024UL * 1024UL) fprintf(trace, "$comment capture_limit_reached $end\n");
}

static void signal_changed(struct avr_irq_t *irq, uint32_t value, void *param) {
    (void)irq;
    trace_value((int)(intptr_t)param, (value & 1) ? '1' : '0');
}

static void direction_changed(struct avr_irq_t *irq, uint32_t value, void *param) {
    (void)irq;
    int p = (int)(intptr_t)param;
    avr_ioport_state_t io = {0};
    avr_ioctl(cpu, AVR_IOCTL_IOPORT_GETSTATE('B' + p), &io);
    for (int bit = 0; bit < 8; bit++) {
        if ((value & ~io.ddr) & (1 << bit)) {
            trace_value(p * 8 + bit, (io.port & (1 << bit)) ? '1' : '0');
        } else if ((io.ddr & ~value) & (1 << bit)) {
            trace_value(p * 8 + bit,
                        (external_mask[p] & (1 << bit))
                                ? ((external_value[p] & (1 << bit)) ? '1' : '0')
                                : (io.port & (1 << bit)) ? '1' : 'z');
        }
    }
}

static void output(struct avr_irq_t *irq, uint32_t value, void *param) {
    (void)irq; (void)param;
    if (tx_size == sizeof(tx)) {
        memmove(tx, tx + 1, sizeof(tx) - 1);
        tx_size--;
    }
    tx[tx_size++] = (unsigned char)value;
    tx_total++;
}

static void flow(struct avr_irq_t *irq, uint32_t value, void *param) {
    (void)irq; (void)value;
    rx_full = (int)(intptr_t)param;
}

static void adc_trigger(struct avr_irq_t *irq, uint32_t value, void *param) {
    (void)irq; (void)value; (void)param;
    for (int i = 0; i < 6; i++) {
        avr_raise_irq(avr_io_getirq(cpu, AVR_IOCTL_ADC_GETIRQ, ADC_IRQ_ADC0 + i), analog_mv[i]);
    }
}

static unsigned pwm_duty_channel(uint8_t tccrA, uint8_t com1Bit, uint8_t ocr, char port, uint8_t pinBit) {
    if (tccrA & (1u << com1Bit)) return (unsigned)ocr;
    avr_ioport_state_t io = {0};
    avr_ioctl(cpu, AVR_IOCTL_IOPORT_GETSTATE(port), &io);
    return ((io.pin >> pinBit) & 1u) ? 255u : 0u;
}

static void state(void) {
    if (trace) fflush(trace);
    fprintf(reply, "@STATE %llu %u %d", (unsigned long long)cpu->cycle, cpu->pc, cpu->state);
    for (char p = 'B'; p <= 'D'; p++) {
        avr_ioport_state_t io = {0};
        avr_ioctl(cpu, AVR_IOCTL_IOPORT_GETSTATE(p), &io);
        fprintf(reply, " %u %u %u", (unsigned)io.port, (unsigned)io.ddr, (unsigned)io.pin);
    }
    fprintf(reply, " %llu ", tx_total);
    if (tx_size == 0) fputc('-', reply);
    else for (size_t i = 0; i < tx_size; i++) fprintf(reply, "%02X", tx[i]);
    fputc('\n', reply);
    fflush(reply);
}

int main(int argc, char **argv) {
    if (argc < 2 || argc > 4) return 2;
    if (argc == 4) {
        unsigned long parsed = strtoul(argv[3], NULL, 10);
        if (parsed != 1000000UL && parsed != 8000000UL && parsed != 16000000UL) return 2;
        cpu_freq_hz = (uint32_t)parsed;
    }

    reply = fdopen(dup(STDOUT_FILENO), "w");
    if (!reply) return 2;
    if (dup2(STDERR_FILENO, STDOUT_FILENO) < 0) return 2;

    cpu = avr_make_mcu_by_name("atmega328p");
    if (!cpu || avr_init(cpu) < 0) return 3;
    cpu->frequency = cpu_freq_hz;
    cpu->vcc = cpu->avcc = cpu->aref = 5000;

    FILE *file = fopen(argv[1], "rb");
    if (!file) return 4;
    unsigned char magic[4];
    size_t n = fread(magic, 1, 4, file);
    fclose(file);
    if (n == 4 && memcmp(magic, "\177ELF", 4) == 0) {
        if (elf_read_firmware(argv[1], &firmware) != 0) return 4;
        if (firmware.mmcu[0] && strcmp(firmware.mmcu, "atmega328p") != 0) return 4;
        avr_load_firmware(cpu, &firmware);
    } else {
        uint32_t size = 0, start = 0;
        uint8_t *code = read_ihex_file(argv[1], &size, &start);
        if (!code || start > 32768 || size > 32768 - start) { free(code); return 4; }
        avr_loadcode(cpu, code, size, start);
        free(code);
    }
    cpu->frequency = cpu_freq_hz;
    cpu->vcc = cpu->avcc = cpu->aref = 5000;

    uint32_t flags = 0;
    avr_ioctl(cpu, AVR_IOCTL_UART_SET_FLAGS('0'), &flags);
    avr_irq_register_notify(avr_io_getirq(cpu, AVR_IOCTL_UART_GETIRQ('0'), UART_IRQ_OUTPUT), output, NULL);
    avr_irq_register_notify(avr_io_getirq(cpu, AVR_IOCTL_UART_GETIRQ('0'), UART_IRQ_OUT_XOFF), flow, (void *)(intptr_t)1);
    avr_irq_register_notify(avr_io_getirq(cpu, AVR_IOCTL_UART_GETIRQ('0'), UART_IRQ_OUT_XON), flow, NULL);
    avr_irq_register_notify(avr_io_getirq(cpu, AVR_IOCTL_ADC_GETIRQ, ADC_IRQ_OUT_TRIGGER), adc_trigger, NULL);

    if (argc >= 3 && argv[2][0] != '\0') {
        trace = fopen(argv[2], "w");
        if (!trace) return 4;
        fprintf(trace, "$timescale 1 ns $end\n$scope module atmega328p $end\n");
        for (int p = 0; p < 3; p++) {
            for (int bit = 0; bit < 8; bit++) {
                int id = p * 8 + bit;
                fprintf(trace, "$var wire 1 %c P%c%d $end\n", '!' + id, 'B' + p, bit);
                avr_irq_register_notify(avr_io_getirq(cpu, AVR_IOCTL_IOPORT_GETIRQ('B' + p), bit),
                                        signal_changed, (void *)(intptr_t)id);
            }
        }
        for (int p = 0; p < 3; p++) {
            avr_irq_register_notify(avr_io_getirq(cpu, AVR_IOCTL_IOPORT_GETIRQ('B' + p), IOPORT_IRQ_DIRECTION_ALL),
                                    direction_changed, (void *)(intptr_t)p);
        }
        fprintf(trace, "$upscope $end\n$enddefinitions $end\n#0\n$dumpvars\n");
        for (int id = 0; id < 24; id++) fprintf(trace, "x%c\n", '!' + id);
        fprintf(trace, "$end\n");
        fflush(trace);
    }

    fprintf(reply, "@READY 1 atmega328p %u\n", cpu_freq_hz);
    fflush(reply);

    char line[256], extra;
    unsigned a, b, c;
    char port;
    while (fgets(line, sizeof(line), stdin)) {
        size_t len = strcspn(line, "\r\n");
        if (len >= sizeof(line) - 1) return 5;
        line[len] = '\n';
        line[len + 1] = 0;
        if (strcmp(line, "QUIT\n") == 0) break;
        if (strcmp(line, "STATE\n") == 0) { state(); continue; }
        if (strcmp(line, "CAPS\n") == 0) { fprintf(reply, "@CAPS STEP REGS VCD RXREADY FREQ PWM\n"); fflush(reply); continue; }
        if (strcmp(line, "PWM\n") == 0) {
            /* Direcciones ATmega328P: TCCR0A=0x44 OCR0A=0x47 OCR0B=0x48, TCCR1A=0x80 OCR1AL=0x88 OCR1BL=0x8A, TCCR2A=0xB0 OCR2A=0xB3 OCR2B=0xB4 */
            unsigned d0a = pwm_duty_channel(cpu->data[0x44], 7, cpu->data[0x47], 'D', 6);
            unsigned d0b = pwm_duty_channel(cpu->data[0x44], 5, cpu->data[0x48], 'D', 5);
            unsigned d1a = pwm_duty_channel(cpu->data[0x80], 7, cpu->data[0x88], 'B', 1);
            unsigned d1b = pwm_duty_channel(cpu->data[0x80], 5, cpu->data[0x8A], 'B', 2);
            unsigned d2a = pwm_duty_channel(cpu->data[0xB0], 7, cpu->data[0xB3], 'B', 3);
            unsigned d2b = pwm_duty_channel(cpu->data[0xB0], 5, cpu->data[0xB4], 'D', 3);
            fprintf(reply, "@PWM %u %u %u %u %u %u\n", d0a, d0b, d1a, d1b, d2a, d2b);
            fflush(reply);
            continue;
        }
        if (strcmp(line, "RXREADY\n") == 0) {
            fprintf(reply, "@RXREADY %d\n", !rx_full && (cpu->data[0xc1] & (1 << 4)) != 0);
            fflush(reply);
            continue;
        }
        if (strcmp(line, "REGS\n") == 0) {
            unsigned sreg = 0;
            for (int bit = 0; bit < 8; bit++) sreg |= (unsigned)(cpu->sreg[bit] & 1) << bit;
            fprintf(reply, "@REGS");
            for (int i = 0; i < 32; i++) fprintf(reply, " %u", cpu->data[i]);
            fprintf(reply, " %u %u\n", sreg, (unsigned)cpu->data[R_SPL] | ((unsigned)cpu->data[R_SPH] << 8));
            fflush(reply);
            continue;
        }
        if (strcmp(line, "STEP\n") == 0) {
            if (cpu->state != cpu_Done && cpu->state != cpu_Crashed) avr_run(cpu);
            state();
            continue;
        }
        if (sscanf(line, "RUN %u %c", &a, &extra) == 1 && a > 0 && a <= 4000000) {
            uint64_t limit = cpu->cycle + a;
            while (cpu->cycle < limit && cpu->state != cpu_Done && cpu->state != cpu_Crashed) {
                uint64_t before = cpu->cycle;
                avr_run(cpu);
                if (cpu->cycle == before) {
                    fprintf(reply, "@ERROR CPU sin avance\n");
                    fflush(reply);
                    goto next;
                }
            }
            state();
            continue;
        }
        if (sscanf(line, "PIN %c %u %u %c", &port, &a, &b, &extra) == 3 && port >= 'B' && port <= 'D' && a < 8 && b < 2) {
            int index = port - 'B';
            external_mask[index] |= 1 << a;
            external_value[index] = (external_value[index] & ~(1 << a)) | (b << a);
            avr_ioport_external_t external = {.name = port, .mask = external_mask[index], .value = external_value[index]};
            avr_ioctl(cpu, AVR_IOCTL_IOPORT_SET_EXTERNAL(port), &external);
            avr_raise_irq(avr_io_getirq(cpu, AVR_IOCTL_IOPORT_GETIRQ(port), (int)a), b);
            trace_value(index * 8 + (int)a, b ? '1' : '0');
            fprintf(reply, "@OK\n");
            fflush(reply);
            continue;
        }
        if (sscanf(line, "ADC %u %u %c", &a, &b, &extra) == 2 && a < 6 && b <= 5000) {
            analog_mv[a] = (uint16_t)b;
            fprintf(reply, "@OK\n");
            fflush(reply);
            continue;
        }
        if (sscanf(line, "RX %u %c", &a, &extra) == 1 && a <= 255) {
            if (rx_full || !(cpu->data[0xc1] & (1 << 4))) fprintf(reply, "@RX BUSY\n");
            else {
                avr_raise_irq(avr_io_getirq(cpu, AVR_IOCTL_UART_GETIRQ('0'), UART_IRQ_INPUT), a);
                fprintf(reply, "@OK\n");
            }
            fflush(reply);
            continue;
        }
        if (sscanf(line, "MEM %u %u %c", &a, &b, &extra) == 2 && a >= 256 && a <= 2303 && b > 0 && b <= 16 && a + b <= 2304) {
            fprintf(reply, "@MEM");
            for (c = 0; c < b; c++) fprintf(reply, " %u", cpu->data[a + c]);
            fprintf(reply, "\n");
            fflush(reply);
            continue;
        }
        fprintf(reply, "@ERROR Comando invalido\n");
        fflush(reply);
        next: ;
    }

    avr_terminate(cpu);
    if (trace) fclose(trace);
    fclose(reply);
    return 0;
}
