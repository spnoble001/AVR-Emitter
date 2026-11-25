import {
  PADS, COL, clamp, snap16, pinColor, describePinState, roundRect, drawText, drawSevenSeg
} from './shared.js';

/**
 * Vista Esquemático CAD cableado estilo Proteus ISIS / KiCad:
 *  - ATmega328P DIP-28 centrado con circuito RESET 10 kΩ y reloj.
 *  - Símbolos electrónicos arrastrables en rejilla 16 px (sin tarjetas HTML).
 *  - Ruteo ortogonal Manhattan por carriles con arcos de salto (bridge hops) en cruces.
 *  - Resaltado de red eléctrica y tooltip en vivo al pasar el ratón por pines o cables.
 */
export class SchematicView {
  constructor() {
    this.MCU = { x: 0, y: 0, w: 192, h: 372 };
    this.nodePos = new Map();
    this.wireCache = []; // [{ pin, pts, col }]
  }

  pinSideOfMcu(pinLabel) {
    const pad = PADS.find(p => p.key === pinLabel);
    return pad && pad.num <= 14 ? 'left' : 'right';
  }

  padAnchor(pinLabel) {
    const pad = PADS.find(p => p.key === pinLabel);
    if (!pad) return { x: this.MCU.x, y: this.MCU.y, side: 'right', num: 0 };
    const leftSide = pad.num <= 14;
    const row = leftSide ? (pad.num - 1) : (28 - pad.num);
    const py = this.MCU.y + 24 + row * 25;
    return {
      x: leftSide ? (this.MCU.x - 18) : (this.MCU.x + this.MCU.w + 18),
      y: py,
      side: leftSide ? 'left' : 'right',
      num: pad.num
    };
  }

  componentSize(c) {
    switch (c.type) {
      case 'BUTTON': return { w: 210, h: 120 };
      case 'LED':    return { w: 204, h: 104 };
      case 'ANALOG': return { w: 224, h: 140 };
      case 'BCD':    return { w: 300, h: 176 };
      case 'PWM':    return { w: 296, h: 116 };
      case 'UART':   return { w: 266, h: 108 };
      default:       return { w: 180, h: 90 };
    }
  }

  autoLayout(spec) {
    this.nodePos.clear();
    if (!spec) return;
    const leftComps = [];
    const rightComps = [];
    for (const c of spec.components) {
      const primaryPin = c.pin || c.firstPin || 'PD1';
      const preferred = (c.type === 'BUTTON' || c.type === 'ANALOG')
        ? 'left'
        : (c.type === 'LED' || c.type === 'BCD' || c.type === 'PWM')
          ? 'right'
          : this.pinSideOfMcu(primaryPin);
      if (preferred === 'left') leftComps.push(c);
      else rightComps.push(c);
    }

    const pinY = (c) => this.padAnchor(c.pin || c.firstPin || 'PD1').y;
    leftComps.sort((a, b) => pinY(a) - pinY(b));
    rightComps.sort((a, b) => pinY(a) - pinY(b));

    const placeCol = (list, side) => {
      const totalH = list.reduce((acc, c) => acc + this.componentSize(c).h + 28, -28);
      let y = this.MCU.y + this.MCU.h / 2 - totalH / 2;
      for (const c of list) {
        const sz = this.componentSize(c);
        const x = side === 'left'
          ? (this.MCU.x - 116 - sz.w)
          : (this.MCU.x + this.MCU.w + 116);
        this.nodePos.set(c.name, { x: snap16(x), y: snap16(y), side });
        y += sz.h + 28;
      }
    };
    placeCol(leftComps, 'left');
    placeCol(rightComps, 'right');
  }

  getBounds(spec) {
    let minX = this.MCU.x - 56, maxX = this.MCU.x + this.MCU.w + 56;
    let minY = this.MCU.y - 60, maxY = this.MCU.y + this.MCU.h + 76;
    if (spec) {
      for (const [name, pos] of this.nodePos.entries()) {
        const c = spec.components.find(item => item.name === name);
        if (!c) continue;
        const sz = this.componentSize(c);
        minX = Math.min(minX, pos.x - 24);
        maxX = Math.max(maxX, pos.x + sz.w + 24);
        minY = Math.min(minY, pos.y - 24);
        maxY = Math.max(maxY, pos.y + sz.h + 24);
      }
    }
    return { minX, maxX, minY, maxY };
  }

  moveNode(name, wx, wy, offset) {
    const cur = this.nodePos.get(name);
    if (!cur) return;
    cur.x = snap16(wx - offset.x);
    cur.y = snap16(wy - offset.y);
  }

  getNodePos(name) {
    return this.nodePos.get(name);
  }

  hitTestNet(wx, wy, spec, simState) {
    // 1. Pines del ATmega328P
    for (const pad of PADS) {
      if (pad.key === 'VCC' || pad.key === 'AVCC' || pad.key === 'GND' || pad.key === 'AREF') continue;
      const a = this.padAnchor(pad.key);
      if (Math.hypot(wx - a.x, wy - a.y) <= 10) {
        return { pin: pad.key, html: describePinState(spec, simState, pad.key) };
      }
    }
    // 2. Segmentos de cables ruteados
    for (const w of this.wireCache) {
      for (let i = 0; i < w.pts.length - 1; i++) {
        if (distPointToSeg(wx, wy, w.pts[i], w.pts[i + 1]) <= 6) {
          return { pin: w.pin, html: describePinState(spec, simState, w.pin) };
        }
      }
    }
    return null;
  }

  draw(ctx, spec, simState, hitboxes, hoveredPin, highlight = null) {
    if (!spec) return;
    const hlPins = new Set(highlight?.pins || []);
    const hlComps = new Set(highlight?.components || []);
    this.drawRoutedWires(ctx, spec, simState, hoveredPin, hlPins);
    this.drawMcuDip28(ctx, spec, simState, hoveredPin, hlPins);
    for (const c of spec.components) {
      const pos = this.nodePos.get(c.name);
      if (!pos) continue;
      if (hlComps.has(c.name)) {
        const sz = this.componentSize(c);
        ctx.save();
        ctx.fillStyle = 'rgba(173, 107, 59, 0.20)';
        roundRect(ctx, pos.x - 7, pos.y - 7, sz.w + 14, sz.h + 14, 8, true, false);
        ctx.strokeStyle = '#d98c4e';
        ctx.lineWidth = 2;
        ctx.setLineDash([6, 3]);
        roundRect(ctx, pos.x - 7, pos.y - 7, sz.w + 14, sz.h + 14, 8, false, true);
        ctx.restore();
      }
      this.drawComponentSymbol(ctx, c, pos, spec, simState, hitboxes);
    }
  }

  componentTerminals(c, pos) {
    const sz = this.componentSize(c);
    const onLeft = pos.x + sz.w / 2 < this.MCU.x + this.MCU.w / 2;
    const tx = onLeft ? pos.x + sz.w : pos.x;

    if (c.type === 'BCD') {
      return c.pins.map((p, idx) => ({
        pin: p.label,
        x: tx,
        y: pos.y + 44 + idx * 24,
        side: onLeft ? 'right' : 'left'
      }));
    }
    if (c.type === 'UART') {
      return [
        { pin: 'PD1', x: tx, y: pos.y + 40, side: onLeft ? 'right' : 'left' },
        { pin: 'PD0', x: tx, y: pos.y + 72, side: onLeft ? 'right' : 'left' }
      ];
    }
    return [{
      pin: c.pin,
      x: tx,
      y: pos.y + Math.round(sz.h / 2) + 8,
      side: onLeft ? 'right' : 'left'
    }];
  }

  drawRoutedWires(ctx, spec, simState, hoveredPin, hlPins = new Set()) {
    this.wireCache = [];
    let leftLane = 0;
    let rightLane = 0;

    // Fase 1: calcular todas las polilíneas Manhattan
    for (const c of spec.components) {
      const pos = this.nodePos.get(c.name);
      if (!pos) continue;
      for (const term of this.componentTerminals(c, pos)) {
        const mcuPin = this.padAnchor(term.pin);
        const col = pinColor(spec, simState, term.pin);
        const pts = [{ x: mcuPin.x, y: mcuPin.y }];

        if (mcuPin.side === 'left' && term.side === 'right') {
          const laneX = this.MCU.x - 42 - (leftLane++ % 7) * 10;
          pts.push({ x: laneX, y: mcuPin.y }, { x: laneX, y: term.y }, { x: term.x, y: term.y });
        } else if (mcuPin.side === 'right' && term.side === 'left') {
          const laneX = this.MCU.x + this.MCU.w + 42 + (rightLane++ % 7) * 10;
          pts.push({ x: laneX, y: mcuPin.y }, { x: laneX, y: term.y }, { x: term.x, y: term.y });
        } else {
          const outX = mcuPin.side === 'left'
            ? (this.MCU.x - 42 - (leftLane++ % 6) * 10)
            : (this.MCU.x + this.MCU.w + 42 + (rightLane++ % 6) * 10);
          const goTop = mcuPin.y < this.MCU.y + this.MCU.h / 2;
          const bypassY = goTop
            ? (this.MCU.y - 36 - ((leftLane + rightLane) % 5) * 10)
            : (this.MCU.y + this.MCU.h + 26 + ((leftLane + rightLane) % 5) * 10);
          const inX = term.side === 'right'
            ? (this.MCU.x - 42 - (leftLane++ % 6) * 10)
            : (this.MCU.x + this.MCU.w + 42 + (rightLane++ % 6) * 10);
          pts.push(
            { x: outX, y: mcuPin.y },
            { x: outX, y: bypassY },
            { x: inX, y: bypassY },
            { x: inX, y: term.y },
            { x: term.x, y: term.y }
          );
        }
        this.wireCache.push({ pin: term.pin, pts, col, term });
      }
    }

    // Recopilar todos los segmentos verticales para dibujar arcos de salto (bridge hops) en cruces
    const vSegs = [];
    for (const w of this.wireCache) {
      for (let i = 0; i < w.pts.length - 1; i++) {
        const a = w.pts[i], b = w.pts[i + 1];
        if (Math.abs(a.x - b.x) < 0.5 && Math.abs(a.y - b.y) > 2) {
          vSegs.push({ pin: w.pin, x: a.x, y1: Math.min(a.y, b.y), y2: Math.max(a.y, b.y) });
        }
      }
    }

    // Fase 2: dibujar cables con arcos de salto en cruces ortogonales y resaltado si hovered o trazado
    for (const w of this.wireCache) {
      const isHover = (hoveredPin && hoveredPin === w.pin) || hlPins.has(w.pin);
      ctx.save();
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';

      if (isHover) {
        ctx.strokeStyle = 'rgba(217, 140, 78, 0.38)';
        ctx.lineWidth = 8.5;
        strokePolylineWithHops(ctx, w.pts, w.pin, vSegs);
      } else if (w.col === COL.high) {
        ctx.strokeStyle = 'rgba(222, 91, 87, 0.22)';
        ctx.lineWidth = 6;
        strokePolylineWithHops(ctx, w.pts, w.pin, vSegs);
      }

      ctx.strokeStyle = isHover ? COL.highlight : w.col;
      ctx.lineWidth = isHover ? 3.1 : 2.2;
      strokePolylineWithHops(ctx, w.pts, w.pin, vSegs);
      drawProbe(ctx, w.term.x, w.term.y, isHover ? COL.highlight : w.col);
      ctx.restore();
    }
  }

  drawMcuDip28(ctx, spec, simState, hoveredPin, hlPins = new Set()) {
    const usedPins = new Set();
    for (const c of spec.components) {
      for (const p of c.pins) usedPins.add(p.label);
    }

    const { x, y, w, h } = this.MCU;

    // Pull-up RESET (pin 1)
    const rstY = y + 24;
    drawVcc(ctx, x - 28, y - 34);
    ctx.strokeStyle = COL.power;
    ctx.lineWidth = 1.8;
    ctx.beginPath(); ctx.moveTo(x - 28, y - 34); ctx.lineTo(x - 28, y - 26); ctx.stroke();
    drawResistorV(ctx, x - 28, y - 26, 22, '10k', 'right');
    ctx.strokeStyle = COL.high;
    ctx.beginPath(); ctx.moveTo(x - 28, y - 4); ctx.lineTo(x - 28, rstY); ctx.lineTo(x, rstY); ctx.stroke();

    // Cuerpo crema ISIS
    ctx.fillStyle = COL.icBody;
    roundRect(ctx, x, y, w, h, 6, true, false);
    ctx.strokeStyle = COL.icBorder;
    ctx.lineWidth = 2.2;
    roundRect(ctx, x, y, w, h, 6, false, true);

    // Muesca pin 1
    ctx.fillStyle = COL.sheet;
    ctx.beginPath(); ctx.arc(x + w / 2, y, 13, 0, Math.PI, false); ctx.fill();
    ctx.strokeStyle = COL.icBorder; ctx.lineWidth = 2; ctx.stroke();

    drawText(ctx, 'U1', x + w / 2, y + 28, 12, true, '#4cc6bc', 'center');
    ctx.save();
    ctx.translate(x + w / 2, y + h / 2 + 12);
    ctx.rotate(-Math.PI / 2);
    drawText(ctx, 'ATMEGA328P', 0, 4, 12.5, true, '#4cc6bc', 'center');
    ctx.restore();

    const clkDesc = spec.clockEnum === 'MHZ_16'
      ? 'XTAL 16 MHz + 2×22 pF'
      : `RC Interno ${spec.clockLabel}`;
    drawText(ctx, clkDesc, x + w / 2, y + h + 18, 10.5, true, COL.muted, 'center');

    for (const pad of PADS) {
      const leftSide = pad.num <= 14;
      const row = leftSide ? (pad.num - 1) : (28 - pad.num);
      const py = y + 24 + row * 25;
      const active = usedPins.has(pad.key);
      const isPower = pad.key === 'VCC' || pad.key === 'AVCC';
      const isGnd = pad.key === 'GND';
      const isHover = (hoveredPin && hoveredPin === pad.key) || hlPins.has(pad.key);

      const lx1 = leftSide ? x - 18 : x + w;
      const lx2 = leftSide ? x : x + w + 18;
      const col = isHover ? COL.highlight
        : active ? pinColor(spec, simState, pad.key)
        : isPower ? COL.power
        : isGnd ? COL.gnd
        : COL.icBorder;

      ctx.strokeStyle = col;
      ctx.lineWidth = (active || isHover) ? 2.4 : 1.4;
      ctx.beginPath(); ctx.moveTo(lx1, py); ctx.lineTo(lx2, py); ctx.stroke();

      if (active || isPower || isGnd || isHover) {
        drawProbe(ctx, leftSide ? lx1 : lx2, py, isHover ? COL.highlight : active ? col : (isPower ? COL.high : COL.low));
      }

      ctx.font = `${(active || isHover) ? '700' : '400'} 9.5px Consolas, monospace`;
      ctx.fillStyle = (active || isHover) ? COL.text : COL.muted;
      ctx.textAlign = leftSide ? 'right' : 'left';
      if (!(leftSide && pad.num === 1)) {
        ctx.fillText(String(pad.num), leftSide ? lx1 - 7 : lx2 + 7, py + 3);
      }

      ctx.fillStyle = isHover ? COL.highlight : (active ? COL.icLabel : COL.muted);
      ctx.textAlign = leftSide ? 'left' : 'right';
      ctx.fillText(pad.label, leftSide ? x + 7 : x + w - 7, py + 3);
    }
    ctx.textAlign = 'left';
  }

  drawComponentSymbol(ctx, c, pos, spec, simState, hitboxes) {
    const sz = this.componentSize(c);
    const onLeft = pos.x + sz.w / 2 < this.MCU.x + this.MCU.w / 2;

    const tagCol = c.connection === 'VCC' ? '#d98c4e' : COL.text;
    drawText(ctx, c.name, pos.x + 2, pos.y + 12, 12, true, tagCol, 'left');
    hitboxes.push({ kind: 'node', comp: c, x: pos.x, y: pos.y, w: sz.w, h: sz.h });

    switch (c.type) {
      case 'BUTTON': this.drawButtonSymbol(ctx, c, pos, sz, onLeft, spec, simState, hitboxes); break;
      case 'LED':    this.drawLedSymbol(ctx, c, pos, sz, onLeft, spec, simState); break;
      case 'ANALOG': this.drawAnalogSymbol(ctx, c, pos, sz, onLeft, simState, hitboxes); break;
      case 'BCD':    this.drawBcdSymbol(ctx, c, pos, spec, simState); break;
      case 'PWM':    this.drawPwmSymbol(ctx, c, pos, sz, spec, simState); break;
      case 'UART':   this.drawUartSymbol(ctx, c, pos, sz, onLeft, simState); break;
    }
  }

  drawButtonSymbol(ctx, c, pos, sz, onLeft, spec, simState, hitboxes) {
    const down = !!simState?.buttonPressed?.[c.name];
    const pinned = !!simState?.buttonPinned?.[c.name];
    const isVcc = c.connection === 'VCC';
    const col = pinColor(spec, simState, c.pin);
    const wy = pos.y + Math.round(sz.h / 2) + 8;

    drawText(ctx, isVcc ? 'Activo alto (+5V · 10k↓)' : 'Activo bajo (GND · PU)',
      pos.x + 2, pos.y + 24, 9.5, true, isVcc ? '#d98c4e' : COL.accentBlue, 'left');

    const swX = pos.x + 62, swW = 86;
    ctx.strokeStyle = col;
    ctx.lineWidth = 2.2;
    ctx.beginPath();
    if (onLeft) { ctx.moveTo(swX + swW, wy); ctx.lineTo(pos.x + sz.w, wy); }
    else { ctx.moveTo(pos.x, wy); ctx.lineTo(swX, wy); }
    ctx.stroke();

    if (isVcc) {
      const jx = onLeft ? (swX + swW + 24) : (swX - 24);
      drawJunction(ctx, jx, wy, col);
      ctx.strokeStyle = col; ctx.lineWidth = 2;
      ctx.beginPath(); ctx.moveTo(jx, wy); ctx.lineTo(jx, wy + 8); ctx.stroke();
      drawResistorV(ctx, jx, wy + 8, 24, '10k', 'right');
      ctx.strokeStyle = COL.gnd;
      ctx.beginPath(); ctx.moveTo(jx, wy + 32); ctx.lineTo(jx, wy + 38); ctx.stroke();
      drawGnd(ctx, jx, wy + 38);
    }

    const farX = onLeft ? (pos.x + 22) : (pos.x + sz.w - 22);
    const swFar = onLeft ? swX : (swX + swW);
    if (!isVcc) {
      ctx.strokeStyle = COL.gnd; ctx.lineWidth = 2.2;
      ctx.beginPath(); ctx.moveTo(swFar, wy); ctx.lineTo(farX, wy); ctx.lineTo(farX, wy + 22); ctx.stroke();
      drawGnd(ctx, farX, wy + 22);
    } else {
      ctx.strokeStyle = COL.power; ctx.lineWidth = 2.2;
      ctx.beginPath(); ctx.moveTo(swFar, wy); ctx.lineTo(farX, wy); ctx.lineTo(farX, wy - 20); ctx.stroke();
      drawVcc(ctx, farX, wy - 20);
    }

    ctx.fillStyle = down ? '#172a28' : '#181c1b';
    roundRect(ctx, swX, wy - 24, swW, 44, 6, true, false);
    ctx.strokeStyle = down ? COL.accentBlue : '#39433e';
    ctx.lineWidth = down ? 1.8 : 1.2;
    roundRect(ctx, swX, wy - 24, swW, 44, 6, false, true);

    ctx.strokeStyle = COL.text; ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(swX, wy); ctx.lineTo(swX + 14, wy);
    ctx.moveTo(swX + swW - 14, wy); ctx.lineTo(swX + swW, wy);
    ctx.stroke();

    for (const cx of [swX + 16, swX + swW - 16]) {
      ctx.fillStyle = '#111413';
      ctx.beginPath(); ctx.arc(cx, wy, 4, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = isVcc ? COL.power : COL.accentBlue; ctx.lineWidth = 1.8; ctx.stroke();
    }

    const barY = down ? wy - 4 : wy - 14;
    ctx.strokeStyle = COL.text; ctx.lineWidth = 2.2;
    ctx.beginPath();
    ctx.moveTo(swX + 16, barY); ctx.lineTo(swX + swW - 16, barY);
    ctx.moveTo(swX + swW / 2, barY); ctx.lineTo(swX + swW / 2, barY - 10);
    ctx.moveTo(swX + swW / 2 - 10, barY - 10); ctx.lineTo(swX + swW / 2 + 10, barY - 10);
    ctx.stroke();

    drawText(ctx, down ? 'Cerrado' : 'Pulsar', swX + swW / 2, wy + 15, 9.5, true, down ? COL.accentBlue : COL.muted, 'center');
    hitboxes.push({ kind: 'button', comp: c, x: swX, y: wy - 24, w: swW, h: 44 });

    const fx = swX + 8, fy = wy + 26, fw = 70, fh = 20;
    ctx.fillStyle = pinned ? '#172a28' : '#161a18';
    roundRect(ctx, fx, fy, fw, fh, 5, true, false);
    ctx.strokeStyle = pinned ? COL.accentBlue : '#323b37'; ctx.lineWidth = 1.1;
    roundRect(ctx, fx, fy, fw, fh, 5, false, true);
    drawText(ctx, pinned ? 'Soltar' : 'Fijar', fx + fw / 2, fy + 13.5, 9.5, true, pinned ? '#4cc6bc' : '#9ca7a1', 'center');
    hitboxes.push({ kind: 'button-pin', comp: c, x: fx, y: fy, w: fw, h: fh });
  }

  drawLedSymbol(ctx, c, pos, sz, onLeft, spec, simState) {
    const on = simState?.ledOn?.[c.name] ?? c.initialOn;
    const isVcc = c.connection === 'VCC';
    const col = pinColor(spec, simState, c.pin);
    const wy = pos.y + Math.round(sz.h / 2) + 8;

    drawText(ctx, isVcc ? 'Activo bajo (+5V → LED)' : 'Activo alto (LED → GND)',
      pos.x + 2, pos.y + 24, 9.5, true, isVcc ? '#d98c4e' : '#36ada3', 'left');

    const startX = onLeft ? pos.x + sz.w : pos.x;
    const dir = onLeft ? -1 : 1;

    ctx.strokeStyle = col; ctx.lineWidth = 2.2;
    ctx.beginPath(); ctx.moveTo(startX, wy); ctx.lineTo(startX + dir * 24, wy); ctx.stroke();

    if (!isVcc) {
      const rx = onLeft ? startX - 24 - 52 : startX + 24;
      drawResistorH(ctx, rx, wy, 52, '330 Ω');
      const ledX = onLeft ? rx - 50 : rx + 68;
      ctx.strokeStyle = col; ctx.lineWidth = 2.2;
      ctx.beginPath(); ctx.moveTo(onLeft ? rx : rx + 52, wy); ctx.lineTo(onLeft ? ledX + 34 : ledX, wy); ctx.stroke();
      drawLedDiode(ctx, ledX, wy, !onLeft, on, on ? 1 : 0, c.name);
      const gx = onLeft ? pos.x + 18 : pos.x + sz.w - 18;
      ctx.strokeStyle = COL.gnd; ctx.lineWidth = 2.2;
      ctx.beginPath(); ctx.moveTo(onLeft ? ledX : ledX + 34, wy); ctx.lineTo(gx, wy); ctx.lineTo(gx, wy + 18); ctx.stroke();
      drawGnd(ctx, gx, wy + 18);
    } else {
      const ledX = onLeft ? startX - 24 - 34 : startX + 24;
      drawLedDiode(ctx, ledX, wy, onLeft, on, on ? 1 : 0, c.name);
      const rx = onLeft ? ledX - 16 - 52 : ledX + 34 + 16;
      ctx.strokeStyle = COL.power; ctx.lineWidth = 2.2;
      ctx.beginPath(); ctx.moveTo(onLeft ? ledX : ledX + 34, wy); ctx.lineTo(onLeft ? rx + 52 : rx, wy); ctx.stroke();
      drawResistorH(ctx, rx, wy, 52, '330 Ω');
      const vx = onLeft ? pos.x + 18 : pos.x + sz.w - 18;
      ctx.strokeStyle = COL.power; ctx.lineWidth = 2.2;
      ctx.beginPath(); ctx.moveTo(onLeft ? rx : rx + 52, wy); ctx.lineTo(vx, wy); ctx.lineTo(vx, wy - 18); ctx.stroke();
      drawVcc(ctx, vx, wy - 18);
    }
  }

  drawAnalogSymbol(ctx, c, pos, sz, onLeft, simState, hitboxes) {
    const mv = simState?.analogMv?.[c.name] ?? 0;
    const pct = Math.round((mv * 100) / 5000);
    const s10 = Math.floor((mv * 1023) / 5000);
    drawText(ctx, `POT-HG 10k · ${(mv / 1000).toFixed(2)}V (${pct}% · ${s10})`,
      pos.x + 2, pos.y + 24, 9.5, true, COL.analog, 'left');

    const potX = onLeft ? pos.x + 124 : pos.x + 96;
    const potTop = pos.y + 54, potH = 54;

    drawVcc(ctx, potX, potTop - 8);
    ctx.strokeStyle = COL.power; ctx.lineWidth = 1.8;
    ctx.beginPath(); ctx.moveTo(potX, potTop - 8); ctx.lineTo(potX, potTop); ctx.stroke();
    drawResistorV(ctx, potX, potTop, potH, '10k', onLeft ? 'left' : 'right');
    ctx.strokeStyle = COL.gnd;
    ctx.beginPath(); ctx.moveTo(potX, potTop + potH); ctx.lineTo(potX, potTop + potH + 8); ctx.stroke();
    drawGnd(ctx, potX, potTop + potH + 8);

    const wiperY = potTop + potH - Math.round((mv / 5000) * potH);
    const termX = onLeft ? pos.x + sz.w : pos.x;
    const midY = pos.y + Math.round(sz.h / 2) + 8;
    ctx.strokeStyle = COL.analog; ctx.lineWidth = 2.2;
    ctx.beginPath();
    ctx.moveTo(potX + (onLeft ? 8 : -8), wiperY);
    ctx.lineTo(onLeft ? potX + 38 : potX - 38, wiperY);
    ctx.lineTo(onLeft ? potX + 38 : potX - 38, midY);
    ctx.lineTo(termX, midY);
    ctx.stroke();

    ctx.fillStyle = COL.analog;
    ctx.beginPath();
    if (onLeft) {
      ctx.moveTo(potX + 7, wiperY); ctx.lineTo(potX + 15, wiperY - 4.5); ctx.lineTo(potX + 15, wiperY + 4.5);
    } else {
      ctx.moveTo(potX - 7, wiperY); ctx.lineTo(potX - 15, wiperY - 4.5); ctx.lineTo(potX - 15, wiperY + 4.5);
    }
    ctx.closePath(); ctx.fill();

    const ctlX = onLeft ? pos.x + 8 : pos.x + 148;
    const btnUp = { x: ctlX, y: potTop + 2, w: 30, h: 22 };
    const btnDn = { x: ctlX, y: potTop + 30, w: 30, h: 22 };
    drawMiniBtn(ctx, btnUp.x, btnUp.y, btnUp.w, btnUp.h, '+');
    drawMiniBtn(ctx, btnDn.x, btnDn.y, btnDn.w, btnDn.h, '−');
    hitboxes.push({ kind: 'pot-step', comp: c, delta: 250, ...btnUp });
    hitboxes.push({ kind: 'pot-step', comp: c, delta: -250, ...btnDn });
    hitboxes.push({ kind: 'pot-drag', comp: c, x: potX - 18, y: potTop - 4, w: 36, h: potH + 8, potTop, potH });
  }

  drawBcdSymbol(ctx, c, pos, spec, simState) {
    const digit = simState?.bcdDigits?.[c.name] ?? c.initialDigit ?? 0;
    const bin = (digit & 0xf).toString(2).padStart(4, '0');
    drawText(ctx, `CD4511 + 7-SEG · BCD ${bin} = ${digit <= 9 ? digit : '—'}`,
      pos.x + 2, pos.y + 25, 9.5, true, COL.analog, 'left');

    const icX = pos.x + 14, icY = pos.y + 34, icW = 92, icH = 114;
    ctx.fillStyle = COL.icBody;
    roundRect(ctx, icX, icY, icW, icH, 5, true, false);
    ctx.strokeStyle = COL.icBorder; ctx.lineWidth = 1.8;
    roundRect(ctx, icX, icY, icW, icH, 5, false, true);
    drawText(ctx, 'U·4511', icX + icW / 2, icY + 18, 11, true, '#4cc6bc', 'center');

    for (let i = 0; i < 4; i++) {
      const py = pos.y + 44 + i * 24;
      ctx.strokeStyle = pinColor(spec, simState, c.pins[i].label); ctx.lineWidth = 2;
      ctx.beginPath(); ctx.moveTo(pos.x, py); ctx.lineTo(icX, py); ctx.stroke();
      drawText(ctx, String.fromCharCode(65 + i), icX + 6, py + 4, 10, true, COL.icLabel, 'left');
    }
    drawText(ctx, 'a..g', icX + icW - 6, icY + 60, 9.5, true, COL.icLabel, 'right');
    drawText(ctx, 'LT=+5V BI=+5V LE=GND', icX, icY + icH + 14, 8.5, false, COL.muted, 'left');

    const busY = icY + 56, rx = icX + icW + 20;
    ctx.strokeStyle = COL.gnd; ctx.lineWidth = 2.2;
    ctx.beginPath(); ctx.moveTo(icX + icW, busY); ctx.lineTo(rx, busY); ctx.stroke();

    ctx.fillStyle = '#1b1815';
    roundRect(ctx, rx, busY - 16, 56, 32, 5, true, false);
    ctx.strokeStyle = COL.resistor; ctx.lineWidth = 1.4;
    roundRect(ctx, rx, busY - 16, 56, 32, 5, false, true);
    drawText(ctx, '7×330 Ω', rx + 28, busY + 4, 9.5, true, COL.resistor, 'center');

    const dispX = rx + 76, dispY = icY + 2;
    ctx.strokeStyle = COL.gnd; ctx.lineWidth = 2.2;
    ctx.beginPath(); ctx.moveTo(rx + 56, busY); ctx.lineTo(dispX, busY); ctx.stroke();
    drawSevenSeg(ctx, digit, dispX, dispY);
    ctx.strokeStyle = COL.gnd; ctx.lineWidth = 1.8;
    ctx.beginPath(); ctx.moveTo(dispX + 36, dispY + 108); ctx.lineTo(dispX + 36, dispY + 122); ctx.stroke();
    drawGnd(ctx, dispX + 36, dispY + 122);
  }

  drawPwmSymbol(ctx, c, pos, sz, spec, simState) {
    const duty = simState?.pwmDuties?.[c.name] ?? c.initialDuty ?? 0;
    const pct = (duty * 100) / 255;
    const col = pinColor(spec, simState, c.pin);
    const wy = pos.y + Math.round(sz.h / 2) + 8;

    drawText(ctx, `PWM ${c.channel} (${c.timer} ≈${spec.pwmFrequencyHz} Hz) · ${pct.toFixed(1)}% (${duty}/255)`,
      pos.x + 2, pos.y + 24, 9.5, true, '#36ada3', 'left');

    ctx.strokeStyle = col; ctx.lineWidth = 2.2;
    ctx.beginPath(); ctx.moveTo(pos.x, wy); ctx.lineTo(pos.x + 14, wy); ctx.stroke();
    drawResistorH(ctx, pos.x + 14, wy, 44, '330 Ω');
    ctx.strokeStyle = col;
    ctx.beginPath(); ctx.moveTo(pos.x + 58, wy); ctx.lineTo(pos.x + 70, wy); ctx.stroke();
    drawLedDiode(ctx, pos.x + 70, wy, true, duty > 10, duty / 255, c.name);
    ctx.strokeStyle = COL.gnd; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.moveTo(pos.x + 104, wy); ctx.lineTo(pos.x + 122, wy); ctx.lineTo(pos.x + 122, wy + 16); ctx.stroke();
    drawGnd(ctx, pos.x + 122, wy + 16);

    const ox = pos.x + 140, oy = pos.y + 38, ow = 152, oh = 64;
    ctx.fillStyle = '#0d0f0e';
    roundRect(ctx, ox, oy, ow, oh, 6, true, false);
    ctx.strokeStyle = '#2d3531'; ctx.lineWidth = 1.2;
    roundRect(ctx, ox, oy, ow, oh, 6, false, true);

    ctx.strokeStyle = '#1b201e'; ctx.lineWidth = 1;
    for (let gx = ox + 22; gx < ox + ow; gx += 22) {
      ctx.beginPath(); ctx.moveTo(gx, oy + 4); ctx.lineTo(gx, oy + oh - 4); ctx.stroke();
    }

    ctx.strokeStyle = '#4cc6bc'; ctx.lineWidth = 2;
    const yHi = oy + 16, yLo = oy + oh - 12, pW = 44, x0 = ox + 10;
    ctx.beginPath();
    if (duty === 0) {
      ctx.moveTo(x0, yLo); ctx.lineTo(x0 + pW * 3, yLo);
    } else if (duty === 255) {
      ctx.moveTo(x0, yHi); ctx.lineTo(x0 + pW * 3, yHi);
    } else {
      const hiW = clamp(Math.round((pW * duty) / 255), 3, pW - 3);
      ctx.moveTo(x0, yLo);
      for (let k = 0; k < 3; k++) {
        const sx = x0 + k * pW;
        ctx.lineTo(sx, yHi); ctx.lineTo(sx + hiW, yHi); ctx.lineTo(sx + hiW, yLo); ctx.lineTo(sx + pW, yLo);
      }
    }
    ctx.stroke();
    drawText(ctx, `${Math.round(pct)}%`, ox + ow - 6, oy + 14, 9.5, true, '#4cc6bc', 'right');
  }

  drawUartSymbol(ctx, c, pos, sz, onLeft, simState) {
    drawText(ctx, `USART0 ${c.baud} bps 8N1 (${c.mode})`, pos.x + 2, pos.y + 24, 9.5, true, '#d98c4e', 'left');
    const bx = pos.x + (onLeft ? 0 : 12), by = pos.y + 32, bw = sz.w - 12, bh = 58;
    ctx.fillStyle = '#0d0f0e';
    roundRect(ctx, bx, by, bw, bh, 6, true, false);
    ctx.strokeStyle = '#2d3531'; ctx.lineWidth = 1.4;
    roundRect(ctx, bx, by, bw, bh, 6, false, true);

    const txLabelX = onLeft ? bx + bw - 6 : bx + 8;
    const align = onLeft ? 'right' : 'left';
    drawText(ctx, 'RXD', txLabelX, pos.y + 44, 9.5, true, '#4cc6bc', align);
    drawText(ctx, 'TXD', txLabelX, pos.y + 76, 9.5, true, '#d98c4e', align);

    const preview = (simState?.uartTx || '').replace(/\r/g, '').replace(/\n/g, ' | ') || '(esperando datos)';
    const trimmed = preview.length > 26 ? '…' + preview.slice(-26) : preview;
    drawText(ctx, 'Terminal UART', bx + bw / 2, by + 20, 9.5, true, '#848e89', 'center');
    drawText(ctx, '> ' + trimmed, bx + bw / 2, by + 40, 10, false, '#8ec9a2', 'center');
  }
}

// ==================== Helpers geométricos y símbolos esquemáticos ====================

/** Dibuja una polilínea insertando arcos de salto (bridge hops) cuando un segmento horizontal cruza uno vertical ajeno. */
function strokePolylineWithHops(ctx, pts, pin, vSegs) {
  if (pts.length < 2) return;
  const R = 5;
  ctx.beginPath();
  ctx.moveTo(pts[0].x, pts[0].y);
  for (let i = 0; i < pts.length - 1; i++) {
    const a = pts[i], b = pts[i + 1];
    const isHoriz = Math.abs(a.y - b.y) < 0.5 && Math.abs(a.x - b.x) > R * 2 + 2;
    if (!isHoriz) {
      ctx.lineTo(b.x, b.y);
      continue;
    }
    const dir = b.x > a.x ? 1 : -1;
    const xMin = Math.min(a.x, b.x) + R + 1;
    const xMax = Math.max(a.x, b.x) - R - 1;
    const crossings = [];
    for (const v of vSegs) {
      if (v.pin === pin) continue;
      if (v.x > xMin && v.x < xMax && a.y > v.y1 + 2 && a.y < v.y2 - 2) {
        crossings.push(v.x);
      }
    }
    if (crossings.length === 0) {
      ctx.lineTo(b.x, b.y);
      continue;
    }
    crossings.sort((u, v) => (dir > 0 ? u - v : v - u));
    for (const cx of crossings) {
      ctx.lineTo(cx - dir * R, a.y);
      ctx.arc(cx, a.y, R, dir > 0 ? Math.PI : 0, dir > 0 ? 0 : Math.PI, dir < 0);
    }
    ctx.lineTo(b.x, b.y);
  }
  ctx.stroke();
}

function distPointToSeg(px, py, a, b) {
  const dx = b.x - a.x, dy = b.y - a.y;
  const len2 = dx * dx + dy * dy;
  if (len2 < 0.001) return Math.hypot(px - a.x, py - a.y);
  const t = clamp(((px - a.x) * dx + (py - a.y) * dy) / len2, 0, 1);
  return Math.hypot(px - (a.x + t * dx), py - (a.y + t * dy));
}

function drawProbe(ctx, x, y, col) {
  ctx.fillStyle = col;
  ctx.fillRect(x - 3.5, y - 3.5, 7, 7);
  ctx.strokeStyle = 'rgba(15,23,42,0.45)';
  ctx.lineWidth = 1;
  ctx.strokeRect(x - 3.5, y - 3.5, 7, 7);
}

function drawJunction(ctx, x, y, col) {
  ctx.fillStyle = col;
  ctx.beginPath(); ctx.arc(x, y, 4, 0, Math.PI * 2); ctx.fill();
}

function drawVcc(ctx, x, y) {
  ctx.strokeStyle = COL.power; ctx.lineWidth = 2;
  ctx.beginPath(); ctx.moveTo(x - 9, y); ctx.lineTo(x + 9, y); ctx.moveTo(x, y); ctx.lineTo(x, y + 6); ctx.stroke();
  drawText(ctx, '+5V', x, y - 4, 9.5, true, COL.power, 'center');
}

function drawGnd(ctx, x, y) {
  ctx.strokeStyle = COL.gnd; ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(x - 9, y); ctx.lineTo(x + 9, y);
  ctx.moveTo(x - 5.5, y + 4); ctx.lineTo(x + 5.5, y + 4);
  ctx.moveTo(x - 2.5, y + 8); ctx.lineTo(x + 2.5, y + 8);
  ctx.stroke();
  drawText(ctx, 'GND', x, y + 19, 8.5, true, COL.gnd, 'center');
}

function drawResistorH(ctx, x, cy, w, label) {
  const lead = 6, body = w - lead * 2, peaks = 6, step = body / (peaks * 2);
  ctx.strokeStyle = COL.resistor; ctx.lineWidth = 1.8;
  ctx.beginPath(); ctx.moveTo(x, cy); ctx.lineTo(x + lead, cy);
  for (let i = 0; i < peaks * 2; i++) ctx.lineTo(x + lead + (i + 0.5) * step, cy + (i % 2 === 0 ? -5.5 : 5.5));
  ctx.lineTo(x + w - lead, cy); ctx.lineTo(x + w, cy); ctx.stroke();
  drawText(ctx, label, x + w / 2, cy - 9, 9.5, true, COL.resistor, 'center');
}

function drawResistorV(ctx, cx, y, h, label, labelSide = 'right') {
  const lead = 3, body = h - lead * 2, peaks = 5, step = body / (peaks * 2);
  ctx.strokeStyle = COL.resistor; ctx.lineWidth = 1.8;
  ctx.beginPath(); ctx.moveTo(cx, y); ctx.lineTo(cx, y + lead);
  for (let i = 0; i < peaks * 2; i++) ctx.lineTo(cx + (i % 2 === 0 ? -5 : 5), y + lead + (i + 0.5) * step);
  ctx.lineTo(cx, y + h - lead); ctx.lineTo(cx, y + h); ctx.stroke();
  drawText(ctx, label, labelSide === 'left' ? cx - 9 : cx + 8, y + h / 2 + 3, 9.5, true, COL.resistor, labelSide === 'left' ? 'right' : 'left');
}

function ledTint(name) {
  const n = (name || '').toLowerCase();
  if (n.includes('rojo') || n.includes('red') || n.includes('alert') || n.includes('err')) {
    return { rgb: '232, 86, 80', fill: '#e85650', stroke: '#b83c37', ray: '#f08580' };
  }
  if (n.includes('amarill') || n.includes('ambar') || n.includes('yellow') || n.includes('warn')) {
    return { rgb: '234, 179, 64', fill: '#eab340', stroke: '#b88524', ray: '#f2c96b' };
  }
  if (n.includes('azul') || n.includes('blue')) {
    return { rgb: '75, 163, 214', fill: '#4ba3d6', stroke: '#3278a1', ray: '#7cc0e8' };
  }
  return { rgb: '54, 196, 152', fill: '#36c498', stroke: '#238c6b', ray: '#5ed9b3' };
}

function drawLedDiode(ctx, x, wy, pointsRight, lit, intensity, name = '') {
  const tint = ledTint(name);
  if (lit && intensity > 0.03) {
    const grad = ctx.createRadialGradient(x + 17, wy, 4, x + 17, wy, 28);
    grad.addColorStop(0, `rgba(${tint.rgb}, ${0.55 * intensity})`);
    grad.addColorStop(1, `rgba(${tint.rgb}, 0)`);
    ctx.fillStyle = grad;
    ctx.beginPath(); ctx.arc(x + 17, wy, 28, 0, Math.PI * 2); ctx.fill();
  }
  ctx.beginPath();
  if (pointsRight) { ctx.moveTo(x, wy - 12); ctx.lineTo(x + 28, wy); ctx.lineTo(x, wy + 12); }
  else { ctx.moveTo(x + 34, wy - 12); ctx.lineTo(x + 6, wy); ctx.lineTo(x + 34, wy + 12); }
  ctx.closePath();
  ctx.fillStyle = lit ? tint.fill : '#252b28'; ctx.fill();
  ctx.strokeStyle = lit ? tint.stroke : '#5a6660'; ctx.lineWidth = 2; ctx.stroke();

  const barX = pointsRight ? x + 30 : x + 4;
  ctx.beginPath(); ctx.moveTo(barX, wy - 13); ctx.lineTo(barX, wy + 13); ctx.stroke();

  ctx.strokeStyle = lit ? tint.ray : '#5a6660'; ctx.lineWidth = 1.4;
  for (const dx of [0, 8]) {
    const ax = x + 11 + dx, ay = wy - 16;
    ctx.beginPath();
    ctx.moveTo(ax, ay); ctx.lineTo(ax + 7, ay - 7);
    ctx.moveTo(ax + 7, ay - 7); ctx.lineTo(ax + 3, ay - 7);
    ctx.moveTo(ax + 7, ay - 7); ctx.lineTo(ax + 7, ay - 3);
    ctx.stroke();
  }
}

function drawMiniBtn(ctx, x, y, w, h, label) {
  ctx.fillStyle = '#181c1b';
  roundRect(ctx, x, y, w, h, 5, true, false);
  ctx.strokeStyle = '#363f3b'; ctx.lineWidth = 1.2;
  roundRect(ctx, x, y, w, h, 5, false, true);
  drawText(ctx, label, x + w / 2, y + h / 2 + 4, 12, true, COL.text, 'center');
}
