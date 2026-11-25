import {
  PADS, COL, clamp, pinColor, describePinState, roundRect, drawText, drawSevenSeg, drawAxialResistor
} from './shared.js';

/**
 * Vista Protoboard Física Realista (estilo Wokwi / Tinkercad Circuits):
 *  - Protoboard de 63 columnas (a..e / canal central / f..j + rieles +5V rojo y GND azul).
 *  - ATmega328P DIP-28 negro mate atravesando el canal central con alimentación VCC/GND,
 *    resistencia axial real 10 kΩ en RESET y cristal HC-49S 16 MHz + 2×22 pF si aplica.
 *  - Botones táctiles 4 patas, LEDs 5 mm translúcidos con halo y resistencias 330 Ω de bandas,
 *    potenciómetro rotatorio giratorio, CD4511 DIP-16 + 7-seg, módulo USB-Serial UART y
 *    cables jumper curvos (Bézier con sombra).
 */
export class BreadboardView {
  constructor() {
    this.COLS = 63;
    this.P = 18;           // paso entre agujeros (px)
    this.PAD_X = 38;
    this.BOARD_W = this.PAD_X * 2 + (this.COLS - 1) * this.P; // 1192 px
    this.BOARD_H = 348;

    // Coordenadas Y de cada fila física
    this.Y_TOP_GND = 26;
    this.Y_TOP_VCC = 44;
    this.Y_TOP_ROWS = [78, 96, 114, 132, 150];   // a, b, c, d, e
    this.Y_BOT_ROWS = [194, 212, 230, 248, 266]; // f, g, h, i, j
    this.Y_BOT_VCC = 300;
    this.Y_BOT_GND = 318;

    // ATmega328P DIP-28 ocupa columnas 23..36 (14 patas por lado)
    this.MCU_START_COL = 23;

    this.jumperCache = []; // [{ pin, p0, c1, c2, p3 }]
    this.placements = [];
  }

  colX(col) {
    return this.PAD_X + col * this.P;
  }

  /** Devuelve la coordenada exacta del agujero (fila d o g para cables) de cada pin DIP-28. */
  mcuPinHole(pinLabel, offsetRow = 1) {
    const pad = PADS.find(p => p.key === pinLabel);
    if (!pad) return { x: this.colX(this.MCU_START_COL), y: this.Y_BOT_ROWS[1], side: 'bottom', col: this.MCU_START_COL };
    if (pad.num <= 14) {
      // Pines 1..14 en la fila inferior f (col 23..36)
      const col = this.MCU_START_COL + (pad.num - 1);
      const r = clamp(offsetRow, 0, 4);
      return { x: this.colX(col), y: this.Y_BOT_ROWS[r], side: 'bottom', col, num: pad.num };
    } else {
      // Pines 28..15 en la fila superior e (col 23..36 -> pin 28 en col 23, pin 15 en col 36)
      const col = this.MCU_START_COL + (28 - pad.num);
      const r = clamp(4 - offsetRow, 0, 4);
      return { x: this.colX(col), y: this.Y_TOP_ROWS[r], side: 'top', col, num: pad.num };
    }
  }

  autoLayout(spec) {
    this.placements = [];
    if (!spec) return;

    // Distribuir componentes limpiamente en zonas izquierda (entradas) y derecha (salidas)
    let leftCol = 3;
    let rightTopCol = 40;
    let rightBotCol = 40;

    // Primero reservar CD4511 + 7-seg en la zona derecha central (si existen)
    let bcdEndCol = 40;
    for (const c of spec.components) {
      if (c.type === 'BCD') {
        this.placements.push({ comp: c, col: bcdEndCol, zone: 'bcd' });
        bcdEndCol += 14;
      }
    }
    rightTopCol = Math.max(rightTopCol, bcdEndCol + 1);
    rightBotCol = Math.max(rightBotCol, bcdEndCol + 1);

    // Si hay muchos componentes a la derecha y no caben tras BCD, usar columnas altas libres
    const allocRightCol = (span) => {
      if (rightTopCol + span <= 60) {
        const c = rightTopCol;
        rightTopCol += span;
        return { col: c, strip: 'top' };
      }
      if (rightBotCol + span <= 60) {
        const c = rightBotCol;
        rightBotCol += span;
        return { col: c, strip: 'bottom' };
      }
      // Reutilizar columnas debajo/encima de zona libre izquierda
      const c = Math.min(56, leftCol);
      leftCol += span;
      return { col: c, strip: 'top' };
    };

    // Entradas a la izquierda (cols 3..20)
    for (const c of spec.components) {
      if (c.type === 'BUTTON') {
        this.placements.push({ comp: c, col: leftCol, zone: 'button' });
        leftCol += 5;
      } else if (c.type === 'ANALOG') {
        this.placements.push({ comp: c, col: leftCol, zone: 'analog' });
        leftCol += 6;
      }
    }

    // Salidas LED y PWM a la derecha, respetando el mismo lado físico (top/bottom) que su pin en ATmega328P
    // para evitar cruces diagonales sobre CD4511BE
    let ledTopCol = bcdEndCol > 40 ? Math.min(54, bcdEndCol) : 40;
    let ledBotCol = bcdEndCol > 40 ? Math.min(54, bcdEndCol) : 40;

    for (const c of spec.components) {
      if (c.type === 'LED' || c.type === 'PWM') {
        const pinSide = this.mcuPinHole(c.pin, 0).side; // 'top' | 'bottom'
        const span = 4;
        let chosenStrip = pinSide;
        let chosenCol;
        if (pinSide === 'top' && ledTopCol <= 59) {
          chosenCol = ledTopCol;
          ledTopCol += span;
        } else if (pinSide === 'bottom' && ledBotCol <= 59) {
          chosenCol = ledBotCol;
          ledBotCol += span;
        } else if (ledTopCol <= 59) {
          chosenStrip = 'top';
          chosenCol = ledTopCol;
          ledTopCol += span;
        } else if (ledBotCol <= 59) {
          chosenStrip = 'bottom';
          chosenCol = ledBotCol;
          ledBotCol += span;
        } else {
          const slot = allocRightCol(3);
          chosenStrip = slot.strip;
          chosenCol = slot.col;
        }
        this.placements.push({ comp: c, col: chosenCol, strip: chosenStrip, zone: c.type.toLowerCase() });
      } else if (c.type === 'UART') {
        this.placements.push({ comp: c, col: -7, zone: 'uart' });
      }
    }
  }

  getBounds(spec) {
    const hasUart = spec?.components.some(c => c.type === 'UART');
    return {
      minX: hasUart ? -168 : -24,
      maxX: this.BOARD_W + 28,
      minY: -66,
      maxY: this.BOARD_H + 54
    };
  }

  hitTestNet(wx, wy, spec, simState) {
    // 1. Pines del ATmega328P en protoboard
    for (const pad of PADS) {
      if (pad.key === 'VCC' || pad.key === 'AVCC' || pad.key === 'GND' || pad.key === 'AREF') continue;
      const h = this.mcuPinHole(pad.key, 0);
      if (Math.hypot(wx - h.x, wy - h.y) <= 10) {
        return { pin: pad.key, html: describePinState(spec, simState, pad.key) };
      }
    }
    // 2. Cables jumper Bézier
    for (const j of this.jumperCache) {
      if (distToCubicBezier(wx, wy, j.p0, j.c1, j.c2, j.p3) <= 7) {
        return { pin: j.pin, html: describePinState(spec, simState, j.pin) };
      }
    }
    return null;
  }

  draw(ctx, spec, simState, hitboxes, hoveredPin, highlight = null) {
    this.jumperCache = [];
    const hlPins = new Set(highlight?.pins || []);

    // 1. Placa física de la protoboard
    this.drawBreadboardChassis(ctx, spec);

    if (!spec) return;

    // 2. ATmega328P DIP-28 + cableado base de alimentación, RESET 10 kΩ y cristal 16 MHz
    this.drawAtmegaOnBoard(ctx, spec, simState, hoveredPin, hlPins);

    // 3. Componentes montados sobre la protoboard (primero cuerpos, luego jumpers encima!)
    const deferredJumpers = [];
    for (const pl of this.placements) {
      switch (pl.zone) {
        case 'button': this.drawTactileButton(ctx, pl, spec, simState, hitboxes, deferredJumpers); break;
        case 'analog': this.drawRotaryPot(ctx, pl, spec, simState, hitboxes, deferredJumpers); break;
        case 'led':    this.drawPhysicalLed(ctx, pl, spec, simState, deferredJumpers, false); break;
        case 'pwm':    this.drawPhysicalLed(ctx, pl, spec, simState, deferredJumpers, true); break;
        case 'bcd':    this.drawBcdModule(ctx, pl, spec, simState, deferredJumpers); break;
        case 'uart':   this.drawUartModule(ctx, pl, spec, simState, deferredJumpers); break;
      }
    }

    // 4. Dibujar todos los cables jumper curvos por encima de los cuerpos (con sombra real)
    for (const j of deferredJumpers) {
      const isHover = (hoveredPin && j.pin && hoveredPin === j.pin) || (j.pin && hlPins.has(j.pin));
      this.drawJumperWire(ctx, j.x1, j.y1, j.x2, j.y2, isHover ? COL.highlight : j.color, j.arch, isHover, j.pin, j.liveCol);
    }
  }

  // ==================== Chasis realista de la Protoboard de 63 columnas ====================
  drawBreadboardChassis(ctx, spec) {
    const W = this.BOARD_W, H = this.BOARD_H;

    // Sombra realista sobre la mesa de laboratorio
    ctx.save();
    ctx.shadowColor = 'rgba(15, 23, 42, 0.16)';
    ctx.shadowBlur = 24;
    ctx.shadowOffsetY = 8;
    ctx.fillStyle = '#f4f3ee';
    roundRect(ctx, 0, 0, W, H, 12, true, false);
    ctx.restore();

    // Borde moldeado plástico ABS
    ctx.strokeStyle = '#cbd5e1';
    ctx.lineWidth = 1.8;
    roundRect(ctx, 0, 0, W, H, 12, false, true);

    // Rieles superior e inferior (+ rojo, - azul)
    this.drawPowerRailLines(ctx, this.Y_TOP_GND, this.Y_TOP_VCC, true);
    this.drawPowerRailLines(ctx, this.Y_BOT_VCC, this.Y_BOT_GND, false);

    // Ranuras divisorias de plástico
    ctx.fillStyle = '#e5e3da';
    ctx.fillRect(10, 58, W - 20, 4);
    ctx.fillRect(10, 282, W - 20, 4);

    // Canal central DIP (sombreado hundido)
    ctx.fillStyle = '#e2e0d6';
    roundRect(ctx, 18, 162, W - 36, 20, 4, true, false);
    ctx.strokeStyle = '#d1cfc4';
    ctx.lineWidth = 1;
    roundRect(ctx, 18, 162, W - 36, 20, 4, false, true);

    // Letras de filas a..e y f..j en ambos extremos
    const topLabels = ['a', 'b', 'c', 'd', 'e'];
    const botLabels = ['f', 'g', 'h', 'i', 'j'];
    ctx.font = '700 9.5px Consolas, monospace';
    ctx.fillStyle = '#64748b';
    ctx.textAlign = 'center';
    for (let r = 0; r < 5; r++) {
      ctx.fillText(topLabels[r], 18, this.Y_TOP_ROWS[r] + 3);
      ctx.fillText(topLabels[r], W - 18, this.Y_TOP_ROWS[r] + 3);
      ctx.fillText(botLabels[r], 18, this.Y_BOT_ROWS[r] + 3);
      ctx.fillText(botLabels[r], W - 18, this.Y_BOT_ROWS[r] + 3);
    }

    // Agujeros de rieles de alimentación (grupos de 5 con hueco cada 6, estilo protoboard real)
    for (let c = 1; c < this.COLS - 1; c++) {
      if ((c - 1) % 6 === 5) continue;
      const x = this.colX(c);
      this.drawHole(ctx, x, this.Y_TOP_GND);
      this.drawHole(ctx, x, this.Y_TOP_VCC);
      this.drawHole(ctx, x, this.Y_BOT_VCC);
      this.drawHole(ctx, x, this.Y_BOT_GND);
    }

    // Agujeros principales a..e y f..j + numeración cada 5 columnas
    for (let c = 0; c < this.COLS; c++) {
      const x = this.colX(c);
      for (let r = 0; r < 5; r++) {
        this.drawHole(ctx, x, this.Y_TOP_ROWS[r]);
        this.drawHole(ctx, x, this.Y_BOT_ROWS[r]);
      }
      const num = c + 1;
      if (num === 1 || num % 5 === 0) {
        ctx.font = '700 8.5px Consolas, monospace';
        ctx.fillStyle = '#94a3b8';
        ctx.textAlign = 'center';
        ctx.fillText(String(num), x, 70);
        ctx.fillText(String(num), x, 279);
      }
    }

    // Puentes de alimentación conectando rieles superior e inferior en extremo derecho
    const bx = this.colX(61);
    this.drawJumperWire(ctx, bx - this.P, this.Y_TOP_VCC, bx - this.P, this.Y_BOT_VCC, '#dc2626', 22, false);
    this.drawJumperWire(ctx, bx, this.Y_TOP_GND, bx, this.Y_BOT_GND, '#1e293b', 34, false);

    // Etiqueta técnica centrada encima de la protoboard (sin tocar el banner superior izquierdo)
    if (spec) {
      drawText(ctx, `Protoboard 63 cols · ATmega328P @ ${spec.clockLabel} · Fuente regulada +5.0 V`,
        W / 2, -44, 11.5, true, '#94a09a', 'center');
    }
  }

  drawPowerRailLines(ctx, y1, y2, topStyle) {
    const x1 = this.colX(1) - 6, x2 = this.colX(this.COLS - 2) + 6;
    const yRed = topStyle ? y2 + 9 : y1 - 9;
    const yBlue = topStyle ? y1 - 9 : y2 + 9;
    ctx.strokeStyle = '#ef4444'; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.moveTo(x1, yRed); ctx.lineTo(x2, yRed); ctx.stroke();
    ctx.strokeStyle = '#3b82f6'; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.moveTo(x1, yBlue); ctx.lineTo(x2, yBlue); ctx.stroke();

    ctx.font = '700 12px Consolas, monospace';
    ctx.textAlign = 'center';
    ctx.fillStyle = '#dc2626';
    ctx.fillText('+', 18, (topStyle ? y2 : y1) + 4);
    ctx.fillText('+', this.BOARD_W - 18, (topStyle ? y2 : y1) + 4);
    ctx.fillStyle = '#2563eb';
    ctx.fillText('−', 18, (topStyle ? y1 : y2) + 4);
    ctx.fillText('−', this.BOARD_W - 18, (topStyle ? y1 : y2) + 4);
  }

  drawHole(ctx, x, y) {
    ctx.fillStyle = '#334155';
    ctx.fillRect(x - 3, y - 3, 6, 6);
    ctx.fillStyle = '#0f172a';
    ctx.fillRect(x - 2, y - 2, 4, 4);
  }

  // ==================== ATmega328P DIP-28 montado en canal central ====================
  drawAtmegaOnBoard(ctx, spec, simState, hoveredPin, hlPins = new Set()) {
    const x0 = this.colX(this.MCU_START_COL) - 9;
    const x1 = this.colX(this.MCU_START_COL + 13) + 9;
    const yTop = this.Y_TOP_ROWS[4] - 2;
    const yBot = this.Y_BOT_ROWS[0] + 2;
    const w = x1 - x0, h = yBot - yTop;

    // Patas metálicas plateadas (28 patas)
    for (let i = 0; i < 14; i++) {
      const px = this.colX(this.MCU_START_COL + i);
      ctx.fillStyle = '#cbd5e1';
      ctx.strokeStyle = '#64748b';
      ctx.lineWidth = 0.8;
      ctx.fillRect(px - 2.5, this.Y_TOP_ROWS[4] - 5, 5, 8);
      ctx.strokeRect(px - 2.5, this.Y_TOP_ROWS[4] - 5, 5, 8);
      ctx.fillRect(px - 2.5, this.Y_BOT_ROWS[0] - 3, 5, 8);
      ctx.strokeRect(px - 2.5, this.Y_BOT_ROWS[0] - 3, 5, 8);
    }

    // Cuerpo plástico negro epoxi DIP-28 con sombra
    ctx.save();
    ctx.shadowColor = 'rgba(15, 23, 42, 0.35)';
    ctx.shadowBlur = 8;
    ctx.shadowOffsetY = 3;
    ctx.fillStyle = '#1e2430';
    roundRect(ctx, x0, yTop + 3, w, h - 6, 4, true, false);
    ctx.restore();

    ctx.strokeStyle = '#3b4459';
    ctx.lineWidth = 1.2;
    roundRect(ctx, x0, yTop + 3, w, h - 6, 4, false, true);

    // Muesca semicircular pin 1 a la izquierda + punto blanco
    ctx.fillStyle = '#0f172a';
    ctx.beginPath();
    ctx.arc(x0 + 1, yTop + h / 2, 7, -Math.PI / 2, Math.PI / 2, false);
    ctx.fill();
    ctx.fillStyle = '#94a3b8';
    ctx.beginPath();
    ctx.arc(x0 + 11, yBot - 10, 2.2, 0, Math.PI * 2);
    ctx.fill();

    // Serigrafía láser ATMEGA328P-PU
    drawText(ctx, 'ATMEGA328P-PU', x0 + w / 2, yTop + h / 2 - 1, 11.5, true, '#cbd5e1', 'center');
    drawText(ctx, `U1 · ${spec.clockLabel}`, x0 + w / 2, yTop + h / 2 + 12, 8.5, false, '#94a3b8', 'center');

    // Pequeñas sondas luminosas sobre pines activos
    const usedPins = new Set();
    for (const c of spec.components) for (const p of c.pins) usedPins.add(p.label);
    for (const pad of PADS) {
      const isHl = hoveredPin === pad.key || hlPins.has(pad.key);
      if (!usedPins.has(pad.key) && !isHl) continue;
      const hole = this.mcuPinHole(pad.key, 0);
      const col = isHl ? COL.highlight : pinColor(spec, simState, pad.key);
      if (isHl) {
        ctx.fillStyle = 'rgba(245, 158, 11, 0.35)';
        ctx.beginPath(); ctx.arc(hole.x, hole.y, 7.5, 0, Math.PI * 2); ctx.fill();
      }
      ctx.fillStyle = col;
      ctx.beginPath(); ctx.arc(hole.x, hole.y, isHl ? 4.6 : 3.8, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = '#ffffff'; ctx.lineWidth = 1; ctx.stroke();
    }

    // Resistencia axial real 10 kΩ de RESET (Pin 1, col 23 -> riel +5V inferior)
    const rstX = this.colX(this.MCU_START_COL);
    drawAxialResistor(ctx, rstX, this.Y_BOT_ROWS[2], rstX, this.Y_BOT_VCC, 10000);
    this.drawCalloutPill(ctx, rstX, this.BOARD_H + 16, 'RST 10k', '#d98c4e', '#161918', '#5c3c24');

    // Puentes de alimentación del ATmega328P: VCC(7)=col29, GND(8)=col30, AVCC(20)=col31, GND(22)=col29
    this.drawShortWire(ctx, this.colX(29), this.Y_BOT_ROWS[3], this.colX(29), this.Y_BOT_VCC, '#dc2626');
    this.drawShortWire(ctx, this.colX(30), this.Y_BOT_ROWS[3], this.colX(30), this.Y_BOT_GND, '#1e293b');
    this.drawShortWire(ctx, this.colX(31), this.Y_TOP_ROWS[1], this.colX(31), this.Y_TOP_VCC, '#dc2626');
    this.drawShortWire(ctx, this.colX(29), this.Y_TOP_ROWS[1], this.colX(29), this.Y_TOP_GND, '#1e293b');

    // Cristal metálico 16 MHz + 2×22 pF en PB6/PB7 (cols 31..32, pines 9..10) si el reloj es 16 MHz
    if (spec.clockEnum === 'MHZ_16') {
      const x1c = this.colX(31), x2c = this.colX(32);
      const cy = this.Y_BOT_ROWS[2];
      // Cuerpo metálico HC-49S plateado
      ctx.fillStyle = '#e2e8f0';
      ctx.strokeStyle = '#64748b';
      ctx.lineWidth = 1.2;
      roundRect(ctx, x1c - 5, cy - 7, this.P + 10, 14, 7, true, true);
      drawText(ctx, '16M', (x1c + x2c) / 2, cy + 3, 7.5, true, '#334155', 'center');
      // 2 condensadores cerámicos 22 pF naranja hacia GND
      for (const cx of [x1c, x2c]) {
        ctx.strokeStyle = '#94a3b8'; ctx.lineWidth = 1.6;
        ctx.beginPath(); ctx.moveTo(cx, this.Y_BOT_ROWS[3]); ctx.lineTo(cx, this.Y_BOT_GND); ctx.stroke();
        ctx.fillStyle = '#ea580c';
        ctx.beginPath(); ctx.ellipse(cx, this.Y_BOT_ROWS[4] + 8, 5, 7, 0, 0, Math.PI * 2); ctx.fill();
        ctx.strokeStyle = '#9a3412'; ctx.lineWidth = 1; ctx.stroke();
      }
    }
  }

  // ==================== Botón Táctil 4 patas realista ====================
  drawTactileButton(ctx, pl, spec, simState, hitboxes, jumpers) {
    const c = pl.comp;
    const down = !!simState?.buttonPressed?.[c.name];
    const pinned = !!simState?.buttonPinned?.[c.name];
    const isVcc = c.connection === 'VCC';

    const colL = pl.col;
    const colR = pl.col + 2;
    const xL = this.colX(colL), xR = this.colX(colR);
    const yTop = this.Y_TOP_ROWS[4], yBot = this.Y_BOT_ROWS[0];
    const cx = (xL + xR) / 2, cy = (yTop + yBot) / 2;

    // 4 patas metálicas en esquinas
    ctx.fillStyle = '#cbd5e1';
    for (const px of [xL, xR]) {
      for (const py of [yTop, yBot]) {
        ctx.fillRect(px - 2.5, py - 2.5, 5, 5);
      }
    }

    // Carcasa cuadrada Omron 6×6 mm
    ctx.save();
    ctx.shadowColor = 'rgba(15, 23, 42, 0.28)';
    ctx.shadowBlur = 7;
    ctx.shadowOffsetY = 3;
    ctx.fillStyle = '#334155';
    roundRect(ctx, cx - 22, cy - 22, 44, 44, 5, true, false);
    ctx.restore();
    ctx.strokeStyle = '#64748b'; ctx.lineWidth = 1.2;
    roundRect(ctx, cx - 22, cy - 22, 44, 44, 5, false, true);

    // 4 remaches metálicos en esquinas
    ctx.fillStyle = '#94a3b8';
    for (const dx of [-15, 15]) for (const dy of [-15, 15]) {
      ctx.beginPath(); ctx.arc(cx + dx, cy + dy, 2.2, 0, Math.PI * 2); ctx.fill();
    }

    // Capuchón pulsable central
    const r = down ? 11.5 : 13;
    ctx.fillStyle = down ? '#2563eb' : (isVcc ? '#b45309' : '#0f172a');
    ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = down ? '#93c5fd' : '#475569';
    ctx.lineWidth = 1.6; ctx.stroke();

    // Brillo del capuchón
    ctx.fillStyle = 'rgba(255,255,255,0.22)';
    ctx.beginPath(); ctx.arc(cx - 3, cy - 3, r * 0.45, 0, Math.PI * 2); ctx.fill();

    // Etiqueta limpia fuera del borde superior + botón rápido [Fijar] fuera del borde inferior
    this.drawCalloutPill(ctx, cx, -16, c.name, isVcc ? '#d98c4e' : '#4cc6bc', '#161918', isVcc ? '#5c3c24' : '#275c57');
    const fx = cx - 24, fy = this.BOARD_H + 8, fw = 48, fh = 18;
    ctx.fillStyle = pinned ? '#172a28' : '#161918';
    roundRect(ctx, fx, fy, fw, fh, 5, true, false);
    ctx.strokeStyle = pinned ? '#36ada3' : '#2e3632'; ctx.lineWidth = 1.1;
    roundRect(ctx, fx, fy, fw, fh, 5, false, true);
    drawText(ctx, pinned ? 'Soltar' : 'Fijar', cx, fy + 12.5, 9, true, pinned ? '#4cc6bc' : '#b2bbb7', 'center');

    hitboxes.push({ kind: 'button', comp: c, x: cx - 22, y: cy - 22, w: 44, h: 44 });
    hitboxes.push({ kind: 'button-pin', comp: c, x: fx, y: fy, w: fw, h: fh });

    // Cableado eléctrico fiel a GND o +5V con resistencia física 10 kΩ
    if (!isVcc) {
      this.drawShortWire(ctx, xL, this.Y_BOT_ROWS[2], xL, this.Y_BOT_GND, '#1e293b');
    } else {
      this.drawShortWire(ctx, xL, this.Y_TOP_ROWS[1], xL, this.Y_TOP_VCC, '#dc2626');
      drawAxialResistor(ctx, xR, this.Y_BOT_ROWS[1], xR, this.Y_BOT_GND, 10000);
    }

    // Jumper curvo desde la pata derecha del botón hacia su pin en ATmega328P
    const target = this.mcuPinHole(c.pin, 2);
    const startY = target.side === 'top' ? this.Y_TOP_ROWS[2] : this.Y_BOT_ROWS[2];
    const wireCol = isVcc ? '#ea580c' : '#2563eb';
    jumpers.push({
      pin: c.pin, x1: xR, y1: startY, x2: target.x, y2: target.y,
      color: wireCol, arch: target.side === 'top' ? -46 : 46, liveCol: pinColor(spec, simState, c.pin)
    });
  }

  // ==================== Potenciómetro rotatorio realista 10 kΩ ====================
  drawRotaryPot(ctx, pl, spec, simState, hitboxes, jumpers) {
    const c = pl.comp;
    const mv = simState?.analogMv?.[c.name] ?? 0;
    const ratio = mv / 5000;
    const xL = this.colX(pl.col), xM = this.colX(pl.col + 1), xR = this.colX(pl.col + 2);
    const cy = this.Y_TOP_ROWS[2];

    // Pines metálicos en fila e
    ctx.fillStyle = '#cbd5e1';
    for (const px of [xL, xM, xR]) ctx.fillRect(px - 2, this.Y_TOP_ROWS[4] - 2, 4, 6);

    // Carcasa azul trimpot Bourns
    ctx.save();
    ctx.shadowColor = 'rgba(15, 23, 42, 0.25)';
    ctx.shadowBlur = 8;
    ctx.shadowOffsetY = 3;
    ctx.fillStyle = '#1d4ed8';
    roundRect(ctx, xM - 24, cy - 26, 48, 52, 6, true, false);
    ctx.restore();
    ctx.strokeStyle = '#1e3a8a'; ctx.lineWidth = 1.4;
    roundRect(ctx, xM - 24, cy - 26, 48, 52, 6, false, true);

    // Dial giratorio blanco con muesca angular (-135° a +135°)
    ctx.fillStyle = '#f8fafc';
    ctx.beginPath(); ctx.arc(xM, cy, 16, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = '#94a3b8'; ctx.lineWidth = 1.5; ctx.stroke();

    const angle = (-135 + ratio * 270) * (Math.PI / 180) - Math.PI / 2;
    ctx.strokeStyle = '#0f172a';
    ctx.lineWidth = 3;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(xM, cy);
    ctx.lineTo(xM + Math.cos(angle) * 12, cy + Math.sin(angle) * 12);
    ctx.stroke();

    // Etiqueta en pill fuera del borde superior + botones [-][+] debajo del potenciómetro
    this.drawCalloutPill(ctx, xM, -16, `${c.name} · ${(mv / 1000).toFixed(2)}V`, '#c4b0f0', '#161918', '#4a3d6b');
    const bDn = { x: xM - 24, y: this.Y_BOT_ROWS[0] + 4, w: 22, h: 18 };
    const bUp = { x: xM + 2, y: this.Y_BOT_ROWS[0] + 4, w: 22, h: 18 };
    for (const [b, lbl] of [[bDn, '−'], [bUp, '+']]) {
      ctx.fillStyle = '#ffffff';
      roundRect(ctx, b.x, b.y, b.w, b.h, 4, true, false);
      ctx.strokeStyle = '#94a3b8'; ctx.lineWidth = 1;
      roundRect(ctx, b.x, b.y, b.w, b.h, 4, false, true);
      drawText(ctx, lbl, b.x + b.w / 2, b.y + 13, 11, true, '#0f172a', 'center');
    }

    hitboxes.push({ kind: 'pot-step', comp: c, delta: -250, ...bDn });
    hitboxes.push({ kind: 'pot-step', comp: c, delta: 250, ...bUp });
    hitboxes.push({ kind: 'pot-drag', comp: c, x: xM - 24, y: cy - 26, w: 48, h: 52, potTop: cy - 24, potH: 48 });

    // Alimentación +5V (pata izq) y GND (pata der)
    this.drawShortWire(ctx, xL, this.Y_TOP_ROWS[0], xL, this.Y_TOP_VCC, '#dc2626');
    this.drawShortWire(ctx, xR, this.Y_TOP_ROWS[0], xR, this.Y_TOP_GND, '#1e293b');

    // Jumper violeta del cursor (pata central) hacia su pin ADC en ATmega328P
    const target = this.mcuPinHole(c.pin, 3);
    jumpers.push({
      pin: c.pin, x1: xM, y1: this.Y_TOP_ROWS[3], x2: target.x, y2: target.y,
      color: '#7c3aed', arch: -62, liveCol: COL.analog
    });
  }

  // ==================== LED 5 mm translúcido realista (+ PWM proporcional) ====================
  drawPhysicalLed(ctx, pl, spec, simState, jumpers, isPwm) {
    const c = pl.comp;
    const isVcc = !isPwm && c.connection === 'VCC';
    const duty = isPwm ? (simState?.pwmDuties?.[c.name] ?? c.initialDuty ?? 0) : 0;
    const lit = isPwm ? duty > 8 : (simState?.ledOn?.[c.name] ?? c.initialOn);
    const intensity = isPwm ? (duty / 255) : (lit ? 1 : 0);

    const isTop = pl.strip !== 'bottom';
    const xA = this.colX(pl.col), xK = this.colX(pl.col + 1);
    const cx = (xA + xK) / 2;
    const rowLead = isTop ? this.Y_TOP_ROWS[3] : this.Y_BOT_ROWS[1];
    const domeY = isTop ? this.Y_TOP_ROWS[2] : this.Y_BOT_ROWS[2];

    // Patas metálicas
    ctx.strokeStyle = '#94a3b8'; ctx.lineWidth = 2.2;
    ctx.beginPath();
    ctx.moveTo(xA, domeY + 6); ctx.lineTo(xA, rowLead);
    ctx.moveTo(xK, domeY + 6); ctx.lineTo(xK, rowLead);
    ctx.stroke();

    // Halo luminoso realista si está encendido (color semántico según nombre del LED)
    const nLow = (c.name || '').toLowerCase();
    const ledBase = nLow.includes('rojo') || nLow.includes('red') ? [232, 86, 80]
      : (nLow.includes('amarill') || nLow.includes('ambar') || nLow.includes('yellow')) ? [234, 179, 64]
      : nLow.includes('azul') || nLow.includes('blue') ? [75, 163, 214]
      : isPwm ? [54, 173, 163] : isVcc ? [217, 140, 78] : [54, 196, 152];
    if (intensity > 0.04) {
      const grad = ctx.createRadialGradient(cx, domeY, 3, cx, domeY, 28);
      grad.addColorStop(0, `rgba(${ledBase[0]}, ${ledBase[1]}, ${ledBase[2]}, ${0.65 * intensity})`);
      grad.addColorStop(1, `rgba(${ledBase[0]}, ${ledBase[1]}, ${ledBase[2]}, 0)`);
      ctx.fillStyle = grad;
      ctx.beginPath(); ctx.arc(cx, domeY, 28, 0, Math.PI * 2); ctx.fill();
    }

    // Cúpula translúcida 5 mm con borde biselado y brillo especular
    ctx.fillStyle = lit
      ? `rgb(${ledBase[0]}, ${ledBase[1]}, ${ledBase[2]})`
      : `rgba(${ledBase[0]}, ${ledBase[1]}, ${ledBase[2]}, 0.30)`;
    ctx.beginPath(); ctx.arc(cx, domeY, 11.5, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = lit ? `rgba(${ledBase[0]}, ${ledBase[1]}, ${ledBase[2]}, 0.9)` : '#64748b';
    ctx.lineWidth = 1.4; ctx.stroke();

    // Reflejo especular de vidrio
    ctx.fillStyle = 'rgba(255,255,255,0.65)';
    ctx.beginPath(); ctx.ellipse(cx - 3.5, domeY - 4, 4, 2.5, -0.5, 0, Math.PI * 2); ctx.fill();

    // Etiqueta limpia fuera de los rieles sobre fondo oscuro
    const pillY = isTop ? -16 : this.BOARD_H + 16;
    const text = isPwm ? `${c.name} ${Math.round(intensity * 100)}%` : c.name;
    this.drawCalloutPill(ctx, cx, pillY, text,
      isVcc ? '#d98c4e' : '#ecefed',
      '#161918',
      isVcc ? '#5c3c24' : '#2e3632');

    // Resistencia física real 330 Ω (naranja-naranja-café-dorado) hacia GND o +5V
    if (!isVcc) {
      drawAxialResistor(ctx, xK, isTop ? this.Y_TOP_ROWS[1] : this.Y_BOT_ROWS[2],
        xK, isTop ? this.Y_TOP_GND : this.Y_BOT_GND, 330);
    } else {
      drawAxialResistor(ctx, xK, isTop ? this.Y_TOP_ROWS[1] : this.Y_BOT_ROWS[2],
        xK, isTop ? this.Y_TOP_VCC : this.Y_BOT_VCC, 330);
    }

    // Jumper curvo desde la pata de señal hacia el pin real en el ATmega328P
    const target = this.mcuPinHole(c.pin, 2);
    const startY = isTop ? this.Y_TOP_ROWS[4] : this.Y_BOT_ROWS[0];
    jumpers.push({
      pin: c.pin, x1: xA, y1: startY, x2: target.x, y2: target.y,
      color: isPwm ? '#10b981' : isVcc ? '#f59e0b' : '#16a34a',
      arch: isTop ? -52 : 52, liveCol: pinColor(spec, simState, c.pin)
    });
  }

  // ==================== Módulo CD4511 DIP-16 + Display 7 segmentos ====================
  drawBcdModule(ctx, pl, spec, simState, jumpers) {
    const c = pl.comp;
    const digit = simState?.bcdDigits?.[c.name] ?? c.initialDigit ?? 0;

    // CD4511 DIP-16 en el canal central (8 columnas)
    const icX0 = this.colX(pl.col) - 7;
    const icX1 = this.colX(pl.col + 7) + 7;
    const yTop = this.Y_TOP_ROWS[4] - 2;
    const yBot = this.Y_BOT_ROWS[0] + 2;

    for (let i = 0; i < 8; i++) {
      const px = this.colX(pl.col + i);
      ctx.fillStyle = '#cbd5e1';
      ctx.fillRect(px - 2, this.Y_TOP_ROWS[4] - 5, 4, 8);
      ctx.fillRect(px - 2, this.Y_BOT_ROWS[0] - 3, 4, 8);
    }

    ctx.fillStyle = '#1e2430';
    roundRect(ctx, icX0, yTop + 3, icX1 - icX0, yBot - yTop - 6, 4, true, false);
    ctx.strokeStyle = '#475569'; ctx.lineWidth = 1.1;
    roundRect(ctx, icX0, yTop + 3, icX1 - icX0, yBot - yTop - 6, 4, false, true);
    drawText(ctx, 'CD4511BE', (icX0 + icX1) / 2, (yTop + yBot) / 2 + 4, 9.5, true, '#e2e8f0', 'center');

    // Alimentación CD4511 (VDD=pata16 col+0 top, GND=pata8 col+7 bot)
    this.drawShortWire(ctx, this.colX(pl.col), this.Y_TOP_ROWS[1], this.colX(pl.col), this.Y_TOP_VCC, '#dc2626');
    this.drawShortWire(ctx, this.colX(pl.col + 7), this.Y_BOT_ROWS[3], this.colX(pl.col + 7), this.Y_BOT_GND, '#1e293b');

    // Display 7 segmentos físico montado a la derecha del CD4511 (cols +9..+12)
    const dX = this.colX(pl.col + 9) - 10;
    const dY = this.Y_TOP_ROWS[1] - 4;
    drawSevenSeg(ctx, digit, dX, dY, 74, 114);
    this.drawCalloutPill(ctx, dX + 37, -16, `${c.name} = ${digit <= 9 ? digit : '-'}`, '#c4b0f0', '#161918', '#4a3d6b');

    // Resistencia 330 Ω de cátodo común al riel GND
    drawAxialResistor(ctx, this.colX(pl.col + 10), this.Y_BOT_ROWS[2], this.colX(pl.col + 10), this.Y_BOT_GND, 330);

    // 4 cables jumper de colores para los bits BCD A, B, C, D
    const bcdColors = ['#ef4444', '#f97316', '#eab308', '#8b5cf6'];
    c.pins.forEach((p, idx) => {
      const target = this.mcuPinHole(p.label, 1 + (idx % 2));
      const bx = this.colX(pl.col + 1 + idx);
      const by = target.side === 'top' ? this.Y_TOP_ROWS[2] : this.Y_BOT_ROWS[2];
      jumpers.push({
        pin: p.label, x1: bx, y1: by, x2: target.x, y2: target.y,
        color: bcdColors[idx % bcdColors.length],
        arch: target.side === 'top' ? (-34 - idx * 9) : (34 + idx * 9),
        liveCol: pinColor(spec, simState, p.label)
      });
    });
  }

  // ==================== Módulo USB-Serial FTDI en el borde izquierdo ====================
  drawUartModule(ctx, pl, spec, simState, jumpers) {
    const c = pl.comp;
    const x = -146, y = 104, w = 122, h = 136;

    // PCB rojo clásico estilo FTDI232RL
    ctx.save();
    ctx.shadowColor = 'rgba(15, 23, 42, 0.25)';
    ctx.shadowBlur = 12;
    ctx.shadowOffsetY = 4;
    ctx.fillStyle = '#991b1b';
    roundRect(ctx, x, y, w, h, 8, true, false);
    ctx.restore();
    ctx.strokeStyle = '#f87171'; ctx.lineWidth = 1.4;
    roundRect(ctx, x, y, w, h, 8, false, true);

    // Conector USB metálico a la izquierda
    ctx.fillStyle = '#cbd5e1';
    roundRect(ctx, x - 14, y + 44, 18, 48, 3, true, false);
    ctx.strokeStyle = '#64748b'; ctx.lineWidth = 1.2;
    roundRect(ctx, x - 14, y + 44, 18, 48, 3, false, true);

    drawText(ctx, 'USB-SERIAL', x + w / 2, y + 22, 10, true, '#fef2f2', 'center');
    drawText(ctx, `${c.baud} bps`, x + w / 2, y + 36, 9, false, '#fecaca', 'center');

    // Micro-LEDs TX / RX
    const hasTx = !!(simState?.uartTx && simState.uartTx.length > 0);
    ctx.fillStyle = hasTx ? '#4ade80' : '#14532d';
    ctx.fillRect(x + 22, y + 54, 10, 6);
    drawText(ctx, 'TX', x + 36, y + 60, 8.5, true, '#fecaca', 'left');

    ctx.fillStyle = simState?.uartRxReady ? '#facc15' : '#422006';
    ctx.fillRect(x + 22, y + 72, 10, 6);
    drawText(ctx, 'RX', x + 36, y + 78, 8.5, true, '#fecaca', 'left');

    // Cabezal de pines TXD, RXD, GND al borde derecho del módulo
    const pins = [
      { lbl: 'RXD', y: y + 42, mcuPin: 'PD1', col: '#38bdf8' },
      { lbl: 'TXD', y: y + 74, mcuPin: 'PD0', col: '#facc15' },
      { lbl: 'GND', y: y + 106, mcuPin: null, col: '#1e293b' }
    ];
    for (const p of pins) {
      drawText(ctx, p.lbl, x + w - 14, p.y + 3, 8.5, true, '#ffffff', 'right');
      ctx.fillStyle = '#eab308';
      ctx.fillRect(x + w - 6, p.y - 3, 10, 6);
      if (p.mcuPin) {
        const target = this.mcuPinHole(p.mcuPin, 3);
        jumpers.push({
          pin: p.mcuPin, x1: x + w + 2, y1: p.y, x2: target.x, y2: target.y,
          color: p.col, arch: 68, liveCol: pinColor(spec, simState, p.mcuPin)
        });
      } else {
        jumpers.push({
          pin: null, x1: x + w + 2, y1: p.y, x2: this.colX(2), y2: this.Y_BOT_GND,
          color: '#1e293b', arch: 30, liveCol: null
        });
      }
    }
  }

  // ==================== Etiquetas pill limpias y cables jumper ====================
  drawCalloutPill(ctx, cx, cy, text, fg, bg, border) {
    ctx.save();
    ctx.font = '700 9.5px "Segoe UI", system-ui, sans-serif';
    const tw = ctx.measureText(text).width;
    const pw = tw + 12, ph = 17;
    ctx.fillStyle = bg;
    roundRect(ctx, cx - pw / 2, cy - ph / 2, pw, ph, 8, true, false);
    ctx.strokeStyle = border;
    ctx.lineWidth = 1;
    roundRect(ctx, cx - pw / 2, cy - ph / 2, pw, ph, 8, false, true);
    ctx.fillStyle = fg;
    ctx.textAlign = 'center';
    ctx.fillText(text, cx, cy + 3.2);
    ctx.restore();
  }

  drawShortWire(ctx, x1, y1, x2, y2, color) {
    ctx.save();
    ctx.strokeStyle = color;
    ctx.lineWidth = 3;
    ctx.lineCap = 'round';
    ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke();
    ctx.fillStyle = '#cbd5e1';
    ctx.beginPath(); ctx.arc(x1, y1, 2.2, 0, Math.PI * 2); ctx.arc(x2, y2, 2.2, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }

  drawJumperWire(ctx, x1, y1, x2, y2, color, arch, highlight, pin = null, liveCol = null) {
    const mx = (x1 + x2) / 2;
    const c1 = { x: x1 + (mx - x1) * 0.45, y: y1 + arch };
    const c2 = { x: x2 + (mx - x2) * 0.45, y: y2 + arch };
    const p0 = { x: x1, y: y1 };
    const p3 = { x: x2, y: y2 };

    if (pin) this.jumperCache.push({ pin, p0, c1, c2, p3 });

    ctx.save();
    ctx.lineCap = 'round';

    // Sombra suave proyectada sobre la protoboard
    ctx.strokeStyle = 'rgba(15, 23, 42, 0.18)';
    ctx.lineWidth = highlight ? 6.5 : 4.8;
    ctx.beginPath();
    ctx.moveTo(x1, y1 + 4);
    ctx.bezierCurveTo(c1.x, c1.y + 4, c2.x, c2.y + 4, x2, y2 + 4);
    ctx.stroke();

    // Aislante plástico del jumper
    ctx.strokeStyle = color;
    ctx.lineWidth = highlight ? 4.4 : 3.2;
    ctx.beginPath();
    ctx.moveTo(x1, y1);
    ctx.bezierCurveTo(c1.x, c1.y, c2.x, c2.y, x2, y2);
    ctx.stroke();

    // Brillo tubular fino
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.30)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(x1, y1 - 0.8);
    ctx.bezierCurveTo(c1.x, c1.y - 0.8, c2.x, c2.y - 0.8, x2, y2 - 0.8);
    ctx.stroke();

    // Puntas metálicas / anillos de estado lógico en los extremos
    for (const pt of [p0, p3]) {
      ctx.fillStyle = liveCol || '#e2e8f0';
      ctx.beginPath(); ctx.arc(pt.x, pt.y, 3.2, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = '#0f172a'; ctx.lineWidth = 0.9; ctx.stroke();
    }
    ctx.restore();
  }
}

function distToCubicBezier(px, py, p0, c1, c2, p3) {
  let best = Infinity;
  const steps = 16;
  for (let i = 0; i <= steps; i++) {
    const t = i / steps, mt = 1 - t;
    const x = mt * mt * mt * p0.x + 3 * mt * mt * t * c1.x + 3 * mt * t * t * c2.x + t * t * t * p3.x;
    const y = mt * mt * mt * p0.y + 3 * mt * mt * t * c1.y + 3 * mt * t * t * c2.y + t * t * t * p3.y;
    const d = Math.hypot(px - x, py - y);
    if (d < best) best = d;
  }
  return best;
}
