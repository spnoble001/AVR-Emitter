import { clamp } from './shared.js';

const CHANNEL_COLORS = ['#4cc6bc', '#d98c4e', '#a084dc', '#6db1d6', '#de7a76', '#8ec9a2'];
const MAX_SAMPLES = 400;
const LABEL_W = 166;

/**
 * Osciloscopio / Analizador Lógico interactivo estilo Saleae / Proteus DSO:
 *  - Almacena hasta 400 muestras con marca de tiempo real en ms (desde simavr).
 *  - Soporta canales digitales (BUTTON/LED/BCD), PWM hardware (forma modulada por duty %)
 *    y analógicos (ADC 0..5 V).
 *  - Zoom temporal (1x..8x), cursores arrastrables A (ámbar) y B (cian) con cálculo automático
 *    de Δt (ms) y frecuencia estimada (Hz), y clic en etiqueta para silenciar un canal.
 */
export class InteractiveScope {
  constructor(canvas, readoutEl, zoomLabelEl) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.readoutEl = readoutEl;
    this.zoomLabelEl = zoomLabelEl;

    this.frames = [];       // [{ tMs, values: Map<id, {kind, v}> }]
    this.channelMeta = [];  // [{ id, label, kind }]
    this.muted = new Set();

    this.zoom = 1;          // 1, 2, 4, 8
    this.showCursors = true;
    this.cursorA = 0.28;    // fracción [0..1] del área visible
    this.cursorB = 0.68;
    this.draggingCursor = null; // 'A' | 'B' | null

    this.bindMouse();
  }

  clear() {
    this.frames = [];
    this.draw();
    this.updateReadout();
  }

  setSpec(spec) {
    this.channelMeta = [];
    this.muted.clear();
    if (!spec) return;
    for (const c of spec.components) {
      if (c.type === 'BUTTON' || c.type === 'LED') {
        this.channelMeta.push({ id: c.name, label: `${c.name} (${c.pin})`, kind: 'digital' });
      } else if (c.type === 'PWM') {
        this.channelMeta.push({ id: c.name, label: `${c.name} [PWM]`, kind: 'pwm' });
      } else if (c.type === 'ANALOG') {
        this.channelMeta.push({ id: c.name, label: `${c.name} [ADC]`, kind: 'analog' });
      } else if (c.type === 'BCD') {
        this.channelMeta.push({ id: c.name, label: `${c.name} [BCD]`, kind: 'bcd' });
      }
    }
    this.frames = [];
    this.draw();
    this.updateReadout();
  }

  record(spec, simState) {
    if (!spec || !simState || !simState.sessionActive) return;
    const values = {};
    for (const c of spec.components) {
      if (c.type === 'BUTTON' || c.type === 'LED') {
        values[c.name] = simState.pinHigh?.[c.pin] ? 1 : 0;
      } else if (c.type === 'PWM') {
        const dutyRaw = simState.pwmDuties?.[c.name] ?? c.initialDuty ?? 0;
        values[c.name] = {
          dutyRaw,
          duty: dutyRaw / 255,
          hz: simState.pwmMeasuredHz?.[c.name] ?? 0
        };
      } else if (c.type === 'ANALOG') {
        values[c.name] = (simState.analogMv?.[c.name] ?? 0) / 5000;
      } else if (c.type === 'BCD') {
        values[c.name] = simState.bcdDigits?.[c.name] ?? c.initialDigit ?? 0;
      }
    }
    this.frames.push({ tMs: simState.elapsedMs ?? 0, values });
    if (this.frames.length > MAX_SAMPLES) this.frames.shift();
    this.draw();
    this.updateReadout();
  }

  stepZoom(delta) {
    const levels = [1, 2, 4, 8];
    const idx = clamp(levels.indexOf(this.zoom) + delta, 0, levels.length - 1);
    this.zoom = levels[idx];
    if (this.zoomLabelEl) this.zoomLabelEl.textContent = `${this.zoom}×`;
    this.draw();
    this.updateReadout();
  }

  toggleCursors() {
    this.showCursors = !this.showCursors;
    this.draw();
    this.updateReadout();
    return this.showCursors;
  }

  resize() {
    const dpr = window.devicePixelRatio || 1;
    const rect = this.canvas.getBoundingClientRect();
    if (rect.width < 10 || rect.height < 10) return;
    this.canvas.width = Math.floor(rect.width * dpr);
    this.canvas.height = Math.floor(rect.height * dpr);
    this.draw();
  }

  visibleSlice() {
    const visibleCount = Math.max(16, Math.floor(MAX_SAMPLES / this.zoom));
    if (this.frames.length <= visibleCount) return this.frames;
    return this.frames.slice(this.frames.length - visibleCount);
  }

  timeAtFraction(frac) {
    const slice = this.visibleSlice();
    if (slice.length === 0) return null;
    if (slice.length === 1) return slice[0].tMs;
    const idx = clamp(frac * (slice.length - 1), 0, slice.length - 1);
    const i0 = Math.floor(idx), i1 = Math.min(slice.length - 1, i0 + 1);
    const alpha = idx - i0;
    return slice[i0].tMs * (1 - alpha) + slice[i1].tMs * alpha;
  }

  updateReadout() {
    if (!this.readoutEl) return;
    if (!this.showCursors) {
      this.readoutEl.textContent = `Muestras: ${this.frames.length} · Zoom ${this.zoom}×`;
      return;
    }
    const tA = this.timeAtFraction(this.cursorA);
    const tB = this.timeAtFraction(this.cursorB);
    if (tA === null || tB === null) {
      this.readoutEl.textContent = 'Inicia la simulación y arrastra los cursores A (ámbar) y B (cian) para medir Δt';
      return;
    }
    const dt = Math.abs(tB - tA);
    const freq = dt > 0.05 ? `${(1000 / dt).toFixed(1)} Hz` : '-';
    this.readoutEl.textContent = `A: ${tA.toFixed(1)} ms  |  B: ${tB.toFixed(1)} ms  |  Δt = ${dt.toFixed(1)} ms  (${freq})`;
  }

  draw() {
    const dpr = window.devicePixelRatio || 1;
    const w = this.canvas.width / dpr;
    const h = this.canvas.height / dpr;
    if (w < 10 || h < 10) return;

    const ctx = this.ctx;
    ctx.save();
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = '#0f1110';
    ctx.fillRect(0, 0, w, h);

    const plotW = Math.max(80, w - LABEL_W - 14);
    const topAxisH = 18;
    const slice = this.visibleSlice();

    // Regla de tiempo superior (en ms)
    ctx.fillStyle = '#151817';
    ctx.fillRect(LABEL_W, 0, plotW, topAxisH);
    ctx.strokeStyle = '#242927';
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(0, topAxisH); ctx.lineTo(w, topAxisH); ctx.stroke();

    const ticks = 6;
    ctx.font = '700 9.5px Consolas, monospace';
    ctx.fillStyle = '#848e89';
    ctx.textAlign = 'center';
    for (let t = 0; t <= ticks; t++) {
      const frac = t / ticks;
      const gx = LABEL_W + frac * plotW;
      ctx.strokeStyle = '#1b201e';
      ctx.beginPath(); ctx.moveTo(gx, topAxisH); ctx.lineTo(gx, h); ctx.stroke();
      const tm = this.timeAtFraction(frac);
      if (tm !== null && t > 0 && t < ticks) {
        ctx.fillText(`${tm.toFixed(0)} ms`, gx, 13);
      }
    }

    if (this.channelMeta.length === 0) {
      ctx.font = '12px "Segoe UI", sans-serif';
      ctx.fillStyle = '#64748b';
      ctx.textAlign = 'left';
      ctx.fillText('Agrega componentes al circuito para observar sus señales en vivo.', 12, 46);
      ctx.restore();
      return;
    }

    const visibleChannels = this.channelMeta.slice(0, 6);
    const rowH = Math.max(22, (h - topAxisH - 4) / visibleChannels.length);

    visibleChannels.forEach((ch, idx) => {
      const yTop = topAxisH + idx * rowH;
      const yHi = yTop + 4;
      const yLo = yTop + rowH - 5;
      const isMuted = this.muted.has(ch.id);
      const col = isMuted ? '#475569' : CHANNEL_COLORS[idx % CHANNEL_COLORS.length];

      // Fondo sutil alterno de fila
      if (idx % 2 === 1) {
        ctx.fillStyle = 'rgba(255,255,255,0.018)';
        ctx.fillRect(0, yTop, w, rowH);
      }

      // Etiqueta del canal (clic para silenciar)
      ctx.font = '700 10px Consolas, monospace';
      ctx.fillStyle = col;
      ctx.textAlign = 'left';
      ctx.fillText((isMuted ? '○ ' : '● ') + ch.label, 8, yTop + rowH / 2 + 3.5);

      // Línea base de referencia
      ctx.strokeStyle = '#1e293b';
      ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(LABEL_W, yLo + 2); ctx.lineTo(LABEL_W + plotW, yLo + 2); ctx.stroke();

      if (isMuted || slice.length === 0) return;

      const dx = plotW / Math.max(1, slice.length - 1);
      ctx.strokeStyle = col;
      ctx.lineWidth = 1.75;
      ctx.beginPath();

      if (ch.kind === 'digital') {
        for (let i = 0; i < slice.length; i++) {
          const x0 = LABEL_W + i * dx;
          const x1 = LABEL_W + Math.min(slice.length - 1, i + 1) * dx;
          const yv = (slice[i].values[ch.id] ?? 0) > 0.5 ? yHi : yLo;
          if (i === 0) ctx.moveTo(x0, yv);
          else ctx.lineTo(x0, yv);
          ctx.lineTo(x1, yv);
        }
        ctx.stroke();
      } else if (ch.kind === 'analog') {
        for (let i = 0; i < slice.length; i++) {
          const x = LABEL_W + i * dx;
          const v = clamp(slice[i].values[ch.id] ?? 0, 0, 1);
          const yv = yLo - v * (yLo - yHi);
          if (i === 0) ctx.moveTo(x, yv);
          else ctx.lineTo(x, yv);
        }
        ctx.stroke();
        const lastV = slice[slice.length - 1].values[ch.id] ?? 0;
        ctx.font = '700 9px Consolas, monospace';
        ctx.fillStyle = '#d8b4fe';
        ctx.textAlign = 'right';
        ctx.fillText(`${(lastV * 5).toFixed(2)}V`, LABEL_W + plotW - 4, yTop + 12);
      } else if (ch.kind === 'bcd') {
        // Bus decodificado real (muestra el dígito exacto 0..9 en cápsulas hexagonales en cada transición)
        const midY = (yHi + yLo) / 2;
        const capH = Math.max(12, yLo - yHi);
        let segStart = 0;
        for (let i = 0; i <= slice.length; i++) {
          const val = i < slice.length ? (slice[i].values[ch.id] ?? 0) : null;
          const prevVal = slice[segStart].values[ch.id] ?? 0;
          if (i === slice.length || val !== prevVal) {
            const x0 = LABEL_W + segStart * dx;
            const x1 = LABEL_W + (i - 1) * dx + (i === slice.length ? 0 : dx);
            const wSeg = Math.max(4, x1 - x0);
            const bev = Math.min(4, wSeg / 4);
            ctx.beginPath();
            ctx.moveTo(x0 + bev, yHi);
            ctx.lineTo(x0 + wSeg - bev, yHi);
            ctx.lineTo(x0 + wSeg, midY);
            ctx.lineTo(x0 + wSeg - bev, yLo);
            ctx.lineTo(x0 + bev, yLo);
            ctx.lineTo(x0, midY);
            ctx.closePath();
            ctx.fillStyle = 'rgba(196, 181, 253, 0.14)';
            ctx.fill();
            ctx.strokeStyle = col;
            ctx.lineWidth = 1.4;
            ctx.stroke();

            if (wSeg >= 14) {
              ctx.font = '700 10px Consolas, monospace';
              ctx.fillStyle = '#f8fafc';
              ctx.textAlign = 'center';
              const txt = wSeg >= 52
                ? `${prevVal} (${(prevVal & 0xF).toString(2).padStart(4, '0')})`
                : String(prevVal);
              ctx.fillText(txt, x0 + wSeg / 2, midY + 3.5);
            }
            segStart = i;
          }
        }
      } else if (ch.kind === 'pwm') {
        // Onda PWM calibrada con la frecuencia real medida desde signals.vcd (incluye extremos activos OCR=1..254)
        const tStart = slice[0].tMs;
        const tEnd = slice[slice.length - 1].tMs;
        const spanMs = Math.max(0.01, tEnd - tStart);
        const lastSample = slice[slice.length - 1].values[ch.id] || { dutyRaw: 0, duty: 0, hz: 0 };
        const lastDutyRaw = lastSample.dutyRaw ?? Math.round(clamp(lastSample.duty ?? 0, 0, 1) * 255);
        const lastDuty = clamp(lastSample.duty ?? (lastDutyRaw / 255), 0, 1);
        const measHz = lastSample.hz ?? 0;
        const isSwitching = measHz > 0 && lastDutyRaw > 0 && lastDutyRaw < 255;

        if (isSwitching) {
          const periodMs = 1000.0 / measHz;
          const periodPx = (periodMs / spanMs) * plotW;
          if (periodPx >= 4.0) {
            // Zoom suficiente: dibujar ciclos calibrados exactamente al eje temporal real (medibles con cursores A/B)
            ctx.beginPath();
            const pxPerMs = plotW / spanMs;
            let started = false;
            const firstCycle = Math.floor(tStart / periodMs);
            const lastCycle = Math.ceil(tEnd / periodMs);
            for (let k = firstCycle; k <= lastCycle; k++) {
              const tRise = k * periodMs;
              const sIdx = clamp(Math.floor(((tRise - tStart) / spanMs) * (slice.length - 1)), 0, slice.length - 1);
              const sVal = slice[sIdx].values[ch.id] || lastSample;
              const dRaw = sVal.dutyRaw ?? Math.round(clamp(sVal.duty ?? lastDuty, 0, 1) * 255);
              const d = clamp(sVal.duty ?? (dRaw / 255), 0, 1);
              const tNext = (k + 1) * periodMs;
              const xRise = clamp(LABEL_W + (tRise - tStart) * pxPerMs, LABEL_W, LABEL_W + plotW);
              const xNext = clamp(LABEL_W + (tNext - tStart) * pxPerMs, LABEL_W, LABEL_W + plotW);
              if (dRaw <= 0) {
                if (!started) { ctx.moveTo(xRise, yLo); started = true; }
                ctx.lineTo(xNext, yLo);
              } else if (dRaw >= 255) {
                if (!started) { ctx.moveTo(xRise, yHi); started = true; }
                ctx.lineTo(xNext, yHi);
              } else {
                const tFall = tRise + periodMs * d;
                const rawXFall = LABEL_W + (tFall - tStart) * pxPerMs;
                const xFall = clamp(Math.max(rawXFall, xRise + 0.9), LABEL_W, xNext);
                if (!started) { ctx.moveTo(xRise, yLo); started = true; }
                ctx.lineTo(xRise, yHi);
                ctx.lineTo(xFall, yHi);
                ctx.lineTo(xFall, yLo);
                ctx.lineTo(xNext, yLo);
              }
            }
            ctx.stroke();
          } else {
            // Zoom amplio (< 4 px por periodo real): banda honesta de portadora PWM sombreada según duty real
            ctx.fillStyle = `rgba(52, 211, 153, ${0.16 + 0.34 * lastDuty})`;
            ctx.fillRect(LABEL_W, yHi, plotW, yLo - yHi);
            ctx.strokeStyle = col;
            ctx.lineWidth = 1.2;
            ctx.beginPath();
            ctx.moveTo(LABEL_W, yHi); ctx.lineTo(LABEL_W + plotW, yHi);
            ctx.moveTo(LABEL_W, yLo); ctx.lineTo(LABEL_W + plotW, yLo);
            ctx.stroke();
          }
          const pct = lastDuty * 100;
          const pctStr = (pct > 0 && pct < 1) || (pct > 99 && pct < 100) ? pct.toFixed(1) : String(Math.round(pct));
          ctx.font = '700 9px Consolas, monospace';
          ctx.fillStyle = '#a7f3d0';
          ctx.textAlign = 'right';
          ctx.fillText(`${measHz.toFixed(1)} Hz (VCD) · ${pctStr}%`, LABEL_W + plotW - 4, yTop + 12);
        } else {
          // Extremo DC 0 % o 100 % (sin conmutación activa)
          const yStatic = lastDutyRaw >= 128 ? yHi : yLo;
          ctx.beginPath();
          ctx.moveTo(LABEL_W, yStatic);
          ctx.lineTo(LABEL_W + plotW, yStatic);
          ctx.stroke();
          ctx.font = '700 9px Consolas, monospace';
          ctx.fillStyle = '#a7f3d0';
          ctx.textAlign = 'right';
          ctx.fillText(`DC ${lastDutyRaw >= 255 ? 100 : (lastDutyRaw <= 0 ? 0 : Math.round(lastDuty * 100))}%`, LABEL_W + plotW - 4, yTop + 12);
        }
      }
    });

    // Cursores verticales arrastrables A (ámbar) y B (cian) + banda Δt
    if (this.showCursors) {
      const xA = LABEL_W + this.cursorA * plotW;
      const xB = LABEL_W + this.cursorB * plotW;
      ctx.fillStyle = 'rgba(56, 189, 248, 0.07)';
      ctx.fillRect(Math.min(xA, xB), topAxisH, Math.abs(xB - xA), h - topAxisH);

      this.drawCursorLine(ctx, xA, topAxisH, h, '#f59e0b', 'A');
      this.drawCursorLine(ctx, xB, topAxisH, h, '#38bdf8', 'B');
    }

    ctx.restore();
  }

  drawCursorLine(ctx, x, topY, h, color, tag) {
    ctx.save();
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.5;
    ctx.setLineDash([4, 3]);
    ctx.beginPath(); ctx.moveTo(x, topY); ctx.lineTo(x, h); ctx.stroke();
    ctx.setLineDash([]);

    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.roundRect(x - 8, 2, 16, 14, 3);
    ctx.fill();
    ctx.font = '700 9.5px Consolas, monospace';
    ctx.fillStyle = '#0f172a';
    ctx.textAlign = 'center';
    ctx.fillText(tag, x, 12.5);
    ctx.restore();
  }

  bindMouse() {
    const getPlot = () => {
      const rect = this.canvas.getBoundingClientRect();
      return { rect, plotW: Math.max(80, rect.width - LABEL_W - 14) };
    };

    this.canvas.addEventListener('mousedown', (e) => {
      const { rect, plotW } = getPlot();
      const sx = e.clientX - rect.left;
      const sy = e.clientY - rect.top;

      // Clic en etiqueta izquierda para silenciar/activar canal
      if (sx < LABEL_W && sy > 18) {
        const visible = this.channelMeta.slice(0, 6);
        const rowH = Math.max(22, (rect.height - 22) / Math.max(1, visible.length));
        const idx = Math.floor((sy - 18) / rowH);
        if (idx >= 0 && idx < visible.length) {
          const id = visible[idx].id;
          if (this.muted.has(id)) this.muted.delete(id);
          else this.muted.add(id);
          this.draw();
        }
        return;
      }

      if (!this.showCursors || sx < LABEL_W) return;
      const xA = LABEL_W + this.cursorA * plotW;
      const xB = LABEL_W + this.cursorB * plotW;
      this.draggingCursor = Math.abs(sx - xA) <= Math.abs(sx - xB) ? 'A' : 'B';
      this.moveActiveCursor(sx, plotW);
    });

    window.addEventListener('mousemove', (e) => {
      if (!this.draggingCursor) return;
      const { rect, plotW } = getPlot();
      this.moveActiveCursor(e.clientX - rect.left, plotW);
    });

    window.addEventListener('mouseup', () => {
      this.draggingCursor = null;
    });
  }

  moveActiveCursor(sx, plotW) {
    const frac = clamp((sx - LABEL_W) / plotW, 0, 1);
    if (this.draggingCursor === 'A') this.cursorA = frac;
    else if (this.draggingCursor === 'B') this.cursorB = frac;
    this.draw();
    this.updateReadout();
  }
}
