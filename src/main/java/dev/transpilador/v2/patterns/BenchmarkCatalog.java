package dev.transpilador.v2.patterns;

import dev.transpilador.v2.model.*;

import java.util.List;

import static dev.transpilador.v2.model.Effect.*;
import static dev.transpilador.v2.model.Expr.*;
import static dev.transpilador.v2.model.Hardware.*;
import static dev.transpilador.v2.model.Rule.*;
import static dev.transpilador.v2.model.State.VarPolicy.*;

/**
 * Catálogo de los 15 programas benchmark que demuestran cobertura completa del dominio V1
 * (GPIO, BCD, Tiempo cooperativo, FSM, ADC, Fast PWM y UART0) usando únicamente los 5 bloques ortogonales.
 */
public final class BenchmarkCatalog {
    private BenchmarkCatalog() {}

    public static List<ProgramSpec> all() {
        return List.of(
                b01LedBoton(),
                b02CompuertasAndXor(),
                b03CadaTresPulsaciones(),
                b04ContadorBcdPausa(),
                b05ContadorDecimal00a99(),
                b06MultiprocesoDosLeds(),
                b07DobleClic(),
                b08PulsacionCortaLarga(),
                b09SecuenciaALuegoB(),
                b10SemaforoCancelable(),
                b11AdcUmbral(),
                b12AdcABcd(),
                b13PwmFijoYRampa(),
                b14AdcAPwm(),
                b15MaquinaTresEstadosUartAdc()
        );
    }

    /** 01 · Combinacional mínimo: botón pull-up en PB0 controla LED en PB1 (sin reloj ni pila). */
    public static ProgramSpec b01LedBoton() {
        return ProgramSpec.builder("01_led_boton")
                .button("boton", Pin.pb(0), Connection.GND)
                .led("led", Pin.pb(1), Connection.GND)
                .rule("Seguir estado del botón", always(), pressed("boton"),
                        List.of(ledOn("led")), List.of(ledOff("led")))
                .build();
    }

    /** 02 · Compuertas lógicas AND y XOR con dos botones. */
    public static ProgramSpec b02CompuertasAndXor() {
        return ProgramSpec.builder("02_compuertas_and_xor")
                .button("btn_a", Pin.pd(2), Connection.GND)
                .button("btn_b", Pin.pd(3), Connection.GND)
                .led("led_and", Pin.pb(0), Connection.GND)
                .led("led_xor", Pin.pb(1), Connection.GND)
                .rule("LED AND", always(), and(pressed("btn_a"), pressed("btn_b")),
                        List.of(ledOn("led_and")), List.of(ledOff("led_and")))
                .rule("LED XOR", always(), xor(pressed("btn_a"), pressed("btn_b")),
                        List.of(ledOn("led_xor")), List.of(ledOff("led_xor")))
                .build();
    }

    /**
     * 03 · Caso del usuario sin parches:
     * Al presionar y soltar PB0, encender LED A solo cuando se completan 3 pulsaciones (módulo 3).
     */
    public static ProgramSpec b03CadaTresPulsaciones() {
        return ProgramSpec.builder("03_cada_3_pulsaciones")
                .button("btn_a", Pin.pb(0), Connection.GND)
                .led("led_a", Pin.pb(1), Connection.GND)
                .var("pulsaciones", 0, 2, 0, WRAP)
                .rule("Contar liberación del botón A (0..2 circular)", onRelease("btn_a"),
                        inc("pulsaciones"))
                .rule("Encender LED A al completar la tercera pulsación", onRelease("btn_a"),
                        cmp(var("pulsaciones"), CmpOp.EQ, u8(0)),
                        List.of(ledOn("led_a")), List.of(ledOff("led_a")))
                .build();
    }

    /** 04 · Contador 0..9 en display BCD CD4511 con botón de avance, pausa y reinicio. */
    public static ProgramSpec b04ContadorBcdPausa() {
        return ProgramSpec.builder("04_contador_bcd_pausa")
                .button("btn_avanzar", Pin.pd(0), Connection.GND)
                .button("btn_pausa", Pin.pd(1), Connection.GND)
                .button("btn_reset", Pin.pd(2), Connection.GND)
                .bcd("display", Pin.pb(0), 0)
                .var("cuenta", 0, 9, 0, WRAP)
                .rule("Avanzar contador si no está en pausa", onPress("btn_avanzar"),
                        not(pressed("btn_pausa")),
                        inc("cuenta"), showBcd("display", var("cuenta")))
                .rule("Reiniciar contador a 0", onPress("btn_reset"),
                        resetVar("cuenta"), showBcd("display", var("cuenta")))
                .build();
    }

    /** 05 · Contador decimal 00..99 saturado sobre dos displays BCD (unidades y decenas). */
    public static ProgramSpec b05ContadorDecimal00a99() {
        return ProgramSpec.builder("05_contador_00_99")
                .button("btn_mas", Pin.pd(2), Connection.GND)
                .button("btn_menos", Pin.pd(3), Connection.GND)
                .bcd("unidades", Pin.pb(0), 0)
                .bcd("decenas", Pin.pc(0), 0)
                .var("valor", 0, 99, 0, CLAMP)
                .rule("Incrementar valor saturado hasta 99", onPress("btn_mas"),
                        inc("valor"), showDecimal2("unidades", "decenas", var("valor")))
                .rule("Decrementar valor saturado hasta 0", onPress("btn_menos"),
                        dec("valor"), showDecimal2("unidades", "decenas", var("valor")))
                .build();
    }

    /** 06 · Multitarea cooperativa: dos LEDs parpadeando de forma independiente (250 ms y 500 ms). */
    public static ProgramSpec b06MultiprocesoDosLeds() {
        return ProgramSpec.builder("06_multiproceso_dos_leds")
                .led("led_lento", Pin.pb(0), Connection.GND)
                .led("led_rapido", Pin.pb(5), Connection.GND)
                .rule("Alternar LED lento cada 500 ms", everyMs(500), toggleLed("led_lento"))
                .rule("Alternar LED rápido cada 250 ms", everyMs(250), toggleLed("led_rapido"))
                .build();
    }

    /** 07 · Doble clic con FSM explícita y temporizador de 400 ms (sin nodo especial). */
    public static ProgramSpec b07DobleClic() {
        return ProgramSpec.builder("07_doble_clic")
                .button("btn", Pin.pd(2), Connection.GND)
                .led("led", Pin.pb(0), Connection.GND)
                .phases("clic", "REPOSO", "REPOSO", "ESPERANDO_2DO")
                .timer("ventana_doble")
                .rule("Primer clic abre ventana de 400 ms", onPress("btn"),
                        inPhase("clic", "REPOSO"),
                        setPhase("clic", "ESPERANDO_2DO"), startTimer("ventana_doble", 400))
                .rule("Segundo clic dentro de ventana alterna LED", onPress("btn"),
                        inPhase("clic", "ESPERANDO_2DO"),
                        toggleLed("led"), stopTimer("ventana_doble"), setPhase("clic", "REPOSO"))
                .rule("Si vence la ventana, volver a reposo", onTimer("ventana_doble"),
                        inPhase("clic", "ESPERANDO_2DO"),
                        setPhase("clic", "REPOSO"))
                .build();
    }

    /** 08 · Pulsación corta (< 1000 ms incrementa) vs. larga (>= 1000 ms reinicia). */
    public static ProgramSpec b08PulsacionCortaLarga() {
        return ProgramSpec.builder("08_pulsacion_corta_larga")
                .button("btn", Pin.pd(2), Connection.GND)
                .bcd("display", Pin.pb(0), 0)
                .var("cuenta", 0, 9, 0, WRAP)
                .phases("pulso", "SUELTO", "SUELTO", "MIDIENDO", "LARGA_HECHA")
                .timer("t_larga")
                .rule("Iniciar medición al presionar", onPress("btn"),
                        inPhase("pulso", "SUELTO"),
                        setPhase("pulso", "MIDIENDO"), startTimer("t_larga", 1000))
                .rule("Al alcanzar 1000 ms, reiniciar contador", onTimer("t_larga"),
                        inPhase("pulso", "MIDIENDO"),
                        resetVar("cuenta"), showBcd("display", var("cuenta")), setPhase("pulso", "LARGA_HECHA"))
                .rule("Soltar antes de 1000 ms avanza contador", onRelease("btn"),
                        inPhase("pulso", "MIDIENDO"),
                        stopTimer("t_larga"), inc("cuenta"), showBcd("display", var("cuenta")), setPhase("pulso", "SUELTO"))
                .rule("Soltar tras pulsación larga vuelve a reposo", onRelease("btn"),
                        inPhase("pulso", "LARGA_HECHA"),
                        setPhase("pulso", "SUELTO"))
                .build();
    }

    /** 09 · Secuencia ordenada: encender LED solo si se presiona B dentro de 500 ms tras A. */
    public static ProgramSpec b09SecuenciaALuegoB() {
        return ProgramSpec.builder("09_secuencia_a_luego_b")
                .button("btn_a", Pin.pd(2), Connection.GND)
                .button("btn_b", Pin.pd(3), Connection.GND)
                .led("led_ok", Pin.pb(0), Connection.GND)
                .phases("sec", "INICIO", "INICIO", "ARMADO")
                .timer("t_ventana")
                .rule("Botón A arma la ventana de 500 ms", onPress("btn_a"),
                        inPhase("sec", "INICIO"),
                        setPhase("sec", "ARMADO"), startTimer("t_ventana", 500))
                .rule("Botón B dentro de la ventana enciende LED", onPress("btn_b"),
                        inPhase("sec", "ARMADO"),
                        ledOn("led_ok"), stopTimer("t_ventana"), setPhase("sec", "INICIO"))
                .rule("Ventana vencida desarma la secuencia", onTimer("t_ventana"),
                        inPhase("sec", "ARMADO"),
                        setPhase("sec", "INICIO"))
                .build();
    }

    /** 10 · Semáforo cooperativo de 3 fases con botón de emergencia cancelable. */
    public static ProgramSpec b10SemaforoCancelable() {
        return ProgramSpec.builder("10_semaforo_cancelable")
                .button("btn_emergencia", Pin.pd(2), Connection.GND)
                .led("led_verde", Pin.pb(0), Connection.GND)
                .led("led_amarillo", Pin.pb(1), Connection.GND)
                .led("led_rojo", Pin.pb(2), Connection.GND)
                .phases("sem", "VERDE", "VERDE", "AMARILLO", "ROJO")
                .timer("t_fase")
                .rule("Arrancar en verde por 300 ms", onBoot(),
                        ledOn("led_verde"), startTimer("t_fase", 300))
                .rule("Verde -> Amarillo (150 ms)", onTimer("t_fase"),
                        inPhase("sem", "VERDE"),
                        ledOff("led_verde"), ledOn("led_amarillo"), setPhase("sem", "AMARILLO"), startTimer("t_fase", 150))
                .rule("Amarillo -> Rojo (300 ms)", onTimer("t_fase"),
                        inPhase("sem", "AMARILLO"),
                        ledOff("led_amarillo"), ledOn("led_rojo"), setPhase("sem", "ROJO"), startTimer("t_fase", 300))
                .rule("Rojo -> Verde (300 ms)", onTimer("t_fase"),
                        inPhase("sem", "ROJO"),
                        ledOff("led_rojo"), ledOn("led_verde"), setPhase("sem", "VERDE"), startTimer("t_fase", 300))
                .rule("Emergencia cancela cualquier fase hacia Rojo", onPress("btn_emergencia"),
                        ledOff("led_verde"), ledOff("led_amarillo"), ledOn("led_rojo"),
                        setPhase("sem", "ROJO"), startTimer("t_fase", 300))
                .build();
    }

    /** 11 · ADC 10 bits: PC0 > 3.0 V (> 614) enciende LED en PB5. */
    public static ProgramSpec b11AdcUmbral() {
        return ProgramSpec.builder("11_adc_umbral")
                .analog("pot", Pin.pc(0))
                .led("led", Pin.pb(5), Connection.GND)
                .rule("Encender LED si potenciómetro supera 3.0 V (> 614)", always(),
                        cmp(adc10("pot"), CmpOp.GT, u10(614)),
                        List.of(ledOn("led")), List.of(ledOff("led")))
                .build();
    }

    /** 12 · ADC 8 bits equivalente escalado 0..9 en display BCD CD4511. */
    public static ProgramSpec b12AdcABcd() {
        return ProgramSpec.builder("12_adc_a_bcd")
                .analog("pot", Pin.pc(0))
                .bcd("display", Pin.pb(0), 0)
                .rule("Mostrar nivel analógico 0..9 en display", always(),
                        showBcd("display", scaleAdc8To("pot", 0, 9)))
                .build();
    }

    /** 13 · Dos canales Fast PWM: OC1A fijo al 50% (128) y OC0A en rampa cooperativa cada 10 ms. */
    public static ProgramSpec b13PwmFijoYRampa() {
        return ProgramSpec.builder("13_pwm_fijo_y_rampa")
                .pwm("pwm_fijo", Pin.pb(1), 128)
                .pwm("pwm_rampa", Pin.pd(6), 0)
                .var("brillo", 0, 255, 0, WRAP)
                .rule("Fijar PWM PB1 al 50% al iniciar", onBoot(),
                        setPwm("pwm_fijo", u8(128)))
                .rule("Incrementar brillo PWM PD6 cada 10 ms", everyMs(10),
                        inc("brillo"), setPwm("pwm_rampa", var("brillo")))
                .build();
    }

    /** 14 · ADC 8-bit controla directamente el brillo PWM en PD6/OC0A. */
    public static ProgramSpec b14AdcAPwm() {
        return ProgramSpec.builder("14_adc_a_pwm")
                .analog("pot", Pin.pc(0))
                .pwm("led_pwm", Pin.pd(6), 0)
                .rule("Copiar lectura analógica 8-bit a PWM", always(),
                        setPwm("led_pwm", adc8("pot")))
                .build();
    }

    /**
     * 15 · Integrador completo del curso:
     * Latido cooperativo + FSM 3 estados (NORMAL/ADVERTENCIA/FALLO en ventana 3000 ms) + UART0 + ADC0.
     */
    public static ProgramSpec b15MaquinaTresEstadosUartAdc() {
        return ProgramSpec.builder("15_maquina_3_estados_uart_adc")
                .button("btn_alerta", Pin.pd(2), Connection.GND)
                .analog("sensor", Pin.pc(0))
                .led("led_latido", Pin.pb(0), Connection.GND)
                .led("led_fallo", Pin.pb(5), Connection.GND)
                .uart("serie", UartBaud.B9600, UartMode.FULL_DUPLEX)
                .phases("estado", "NORMAL", "NORMAL", "ADVERTENCIA", "FALLO")
                .timer("ventana_fallo")
                .rule("Latido cooperativo cada 200 ms", everyMs(200), toggleLed("led_latido"))
                .rule("Primera alerta pasa a ADVERTENCIA y abre ventana 3000 ms", onPress("btn_alerta"),
                        inPhase("estado", "NORMAL"),
                        setPhase("estado", "ADVERTENCIA"), startTimer("ventana_fallo", 3000),
                        sendText("serie", "ADVERTENCIA\r\n"))
                .rule("Segunda alerta dentro de ventana pasa a FALLO", onPress("btn_alerta"),
                        inPhase("estado", "ADVERTENCIA"),
                        setPhase("estado", "FALLO"), stopTimer("ventana_fallo"), ledOn("led_fallo"),
                        sendText("serie", "FALLO\r\n"))
                .rule("Si vence la ventana sin segunda alerta, volver a NORMAL", onTimer("ventana_fallo"),
                        inPhase("estado", "ADVERTENCIA"),
                        setPhase("estado", "NORMAL"), sendText("serie", "NORMAL\r\n"))
                .rule("Comando UART 'r' reinicia a NORMAL", onUartRx("serie"),
                        uartChar("serie", 'r'),
                        setPhase("estado", "NORMAL"), stopTimer("ventana_fallo"), ledOff("led_fallo"),
                        sendText("serie", "RESET\r\n"))
                .rule("Comando UART 's' reporta si sensor supera 512", onUartRx("serie"),
                        and(uartChar("serie", 's'), cmp(adc10("sensor"), CmpOp.GT, u10(512))),
                        List.of(sendText("serie", "ALTO\r\n")),
                        List.of())
                .build();
    }
}
