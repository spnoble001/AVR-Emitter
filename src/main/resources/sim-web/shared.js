// Primitivas compartidas y constantes físicas del ATmega328P DIP-28 (Paleta Lean 4 carbón técnico)

export const PADS = [
  { num: 1, label: 'PC6/RST', key: 'PC6' }, { num: 2, label: 'PD0/RX', key: 'PD0' },
  { num: 3, label: 'PD1/TX', key: 'PD1' },  { num: 4, label: 'PD2', key: 'PD2' },
  { num: 5, label: 'PD3/OC2B', key: 'PD3' },{ num: 6, label: 'PD4', key: 'PD4' },
  { num: 7, label: 'VCC', key: 'VCC' },     { num: 8, label: 'GND', key: 'GND' },
  { num: 9, label: 'PB6/XT1', key: 'PB6' }, { num: 10, label: 'PB7/XT2', key: 'PB7' },
  { num: 11, label: 'PD5/OC0B', key: 'PD5' },{ num: 12, label: 'PD6/OC0A', key: 'PD6' },
  { num: 13, label: 'PD7', key: 'PD7' },    { num: 14, label: 'PB0', key: 'PB0' },
  { num: 15, label: 'PB1/OC1A', key: 'PB1' },{ num: 16, label: 'PB2/OC1B', key: 'PB2' },
  { num: 17, label: 'PB3/OC2A', key: 'PB3' },{ num: 18, label: 'PB4', key: 'PB4' },
  { num: 19, label: 'PB5', key: 'PB5' },    { num: 20, label: 'AVCC', key: 'AVCC' },
  { num: 21, label: 'AREF', key: 'AREF' },  { num: 22, label: 'GND', key: 'GND' },
  { num: 23, label: 'PC0/ADC0', key: 'PC0' },{ num: 24, label: 'PC1/ADC1', key: 'PC1' },
  { num: 25, label: 'PC2/ADC2', key: 'PC2' },{ num: 26, label: 'PC3/ADC3', key: 'PC3' },
  { num: 27, label: 'PC4/ADC4', key: 'PC4' },{ num: 28, label: 'PC5/ADC5', key: 'PC5' }
];

export const SEG_MASKS = [0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7c, 0x07, 0x7f, 0x67];

export const COL = {
  sheet: '#111413',
  grid: '#1e2422',
  icBody: '#181d1b',
  icBorder: '#3e4944',
  icLabel: '#ecefed',
  high: '#de5b57',
  low: '#4b93ba',
  analog: '#a084dc',
  idle: '#626d68',
  power: '#de5b57',
  gnd: '#7c8882',
  resistor: '#c98a55',
  text: '#ecefed',
  muted: '#848e89',
  accentBlue: '#4cc6bc',
  highlight: '#d98c4e'
};

export function clamp(v, lo, hi) {
  return Math.max(lo, Math.min(hi, v));
}

export function snap16(v) {
  return Math.round(v / 16) * 16;
}

export function pinColor(spec, simState, pinLabel) {
  if (spec?.components.some(c => c.type === 'ANALOG' && c.pin === pinLabel)) {
    return COL.analog;
  }
  if (!simState || !simState.sessionActive || simState.pinHigh?.[pinLabel] === undefined) {
    return COL.idle;
  }
  return simState.pinHigh[pinLabel] ? COL.high : COL.low;
}

export function describePinState(spec, simState, pinLabel) {
  const pad = PADS.find(p => p.key === pinLabel);
  const dip = pad ? `DIP-${pad.num}` : '';
  const comp = spec?.components.find(c => c.pins?.some(p => p.label === pinLabel));
  const owner = comp ? `${comp.name} (${comp.type})` : 'Libre';

  if (comp?.type === 'ANALOG') {
    const mv = simState?.analogMv?.[comp.name] ?? 0;
    return `<span class="tt-pin">${pinLabel}</span> · ${dip} · ${owner}<br><span class="tt-an">${(mv / 1000).toFixed(2)} V (ADC ${Math.floor(mv * 1023 / 5000)}/1023)</span>`;
  }
  if (comp?.type === 'PWM') {
    const duty = simState?.pwmDuties?.[comp.name] ?? comp.initialDuty ?? 0;
    const pct = ((duty * 100) / 255).toFixed(1);
    const measHz = simState?.pwmMeasuredHz?.[comp.name] ?? 0;
    const freqTxt = measHz > 0 ? `${measHz.toFixed(1)} Hz (VCD real)` : (duty === 0 || duty === 255 ? 'DC fijo' : `≈${spec.pwmFrequencyHz} Hz`);
    return `<span class="tt-pin">${pinLabel}</span> · ${dip} · ${owner}<br><span class="tt-hi">PWM ${comp.channel}: ${pct}% (${duty}/255 · ${freqTxt})</span>`;
  }
  if (!simState || !simState.sessionActive || simState.pinHigh?.[pinLabel] === undefined) {
    return `<span class="tt-pin">${pinLabel}</span> · ${dip} · ${owner}<br>Estado: Reposo (sin iniciar)`;
  }
  const hi = !!simState.pinHigh[pinLabel];
  return `<span class="tt-pin">${pinLabel}</span> · ${dip} · ${owner}<br>${hi ? '<span class="tt-hi">5.00 V (ALTO · 1)</span>' : '<span class="tt-lo">0.00 V (BAJO · 0)</span>'}`;
}

export function roundRect(ctx, x, y, w, h, r, fill, stroke) {
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, r);
  if (fill) ctx.fill();
  if (stroke) ctx.stroke();
}

export function drawText(ctx, str, x, y, size, bold, color, align = 'left') {
  ctx.font = `${bold ? '700' : '400'} ${size}px "Segoe UI", system-ui, sans-serif`;
  ctx.fillStyle = color;
  ctx.textAlign = align;
  ctx.fillText(str, x, y);
}

export function drawSevenSeg(ctx, digit, x, y, w = 72, h = 108) {
  const sx = w / 72;
  const sy = h / 108;
  ctx.fillStyle = '#141716';
  roundRect(ctx, x, y, w, h, 6 * sx, true, false);
  ctx.strokeStyle = '#323a36';
  ctx.lineWidth = 1.4;
  roundRect(ctx, x, y, w, h, 6 * sx, false, true);

  const segs = [
    [14, 10, 44, 7], [55, 17, 7, 32], [55, 57, 7, 32], [14, 89, 44, 7],
    [10, 57, 7, 32], [10, 17, 7, 32], [14, 49, 44, 7]
  ];
  for (let i = 0; i < 7; i++) {
    const lit = digit >= 0 && digit < 10 && (SEG_MASKS[digit] & (1 << i)) !== 0;
    ctx.fillStyle = lit ? '#e85650' : '#262123';
    if (lit) {
      ctx.save();
      ctx.shadowColor = 'rgba(232, 86, 80, 0.65)';
      ctx.shadowBlur = 7;
      roundRect(ctx, x + segs[i][0] * sx, y + segs[i][1] * sy, segs[i][2] * sx, segs[i][3] * sy, 3 * sx, true, false);
      ctx.restore();
    } else {
      roundRect(ctx, x + segs[i][0] * sx, y + segs[i][1] * sy, segs[i][2] * sx, segs[i][3] * sy, 3 * sx, true, false);
    }
  }
}

/** Dibuja una resistencia axial realista con sus 4 bandas de color (ej. 330 Ω o 10 kΩ). */
export function drawAxialResistor(ctx, x1, y1, x2, y2, ohms) {
  const dx = x2 - x1;
  const dy = y2 - y1;
  const len = Math.hypot(dx, dy);
  if (len < 8) return;
  const ang = Math.atan2(dy, dx);

  const bands = ohms === 330
    ? ['#f97316', '#f97316', '#78350f', '#eab308']
    : ['#78350f', '#0f172a', '#f97316', '#eab308'];

  ctx.save();
  ctx.translate(x1, y1);
  ctx.rotate(ang);

  ctx.strokeStyle = '#9aa6a0';
  ctx.lineWidth = 2.2;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(0, 0);
  ctx.lineTo(len, 0);
  ctx.stroke();

  const bodyW = Math.min(34, Math.max(20, len * 0.56));
  const bodyH = 10;
  const bx = (len - bodyW) / 2;

  ctx.fillStyle = '#e7cfa1';
  ctx.strokeStyle = '#a1804f';
  ctx.lineWidth = 1;
  roundRect(ctx, bx, -bodyH / 2, bodyW, bodyH, 3.5, true, true);

  const pos = [0.18, 0.36, 0.54, 0.78];
  const bw = Math.max(2.4, bodyW * 0.1);
  for (let i = 0; i < 4; i++) {
    ctx.fillStyle = bands[i];
    ctx.fillRect(bx + bodyW * pos[i], -bodyH / 2 + 0.6, bw, bodyH - 1.2);
  }

  ctx.fillStyle = 'rgba(255,255,255,0.28)';
  ctx.fillRect(bx + 2, -bodyH / 2 + 1, bodyW - 4, 2.2);

  ctx.restore();
}
