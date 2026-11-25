/**
 * Inspector "Rayos X" del núcleo ATmega328P en tiempo real (conectado a simavr REGS/MEM/STATE):
 *  - Registros r0..r31 en cuadrícula 4×8 con alias simbólicos, punteros X/Y/Z y resaltado de cambios.
 *  - Registro de estado SREG (I, T, H, S, V, N, Z, C) bit a bit con tooltips didácticos en español.
 *  - PC, SP (con uso de pila desde RAMEND=0x08FF), ciclos, puertos DDR/PORT/PIN y estado lógico decodificado.
 */

const SREG_BITS = [
  { bit: 7, key: 'I', desc: 'I (Global Interrupt Enable): 1 habilita interrupciones globales (sei/cli)' },
  { bit: 6, key: 'T', desc: 'T (Bit Copy Storage): bit temporal usado por las instrucciones BLD y BST' },
  { bit: 5, key: 'H', desc: 'H (Half Carry): acarreo del bit 3 al bit 4 (aritmética BCD)' },
  { bit: 4, key: 'S', desc: 'S (Sign = N ⊕ V): signo real de comparaciones con signo' },
  { bit: 3, key: 'V', desc: 'V (Two\'s Complement Overflow): desbordamiento aritmético con signo' },
  { bit: 2, key: 'N', desc: 'N (Negative): 1 si el bit 7 del último resultado es 1' },
  { bit: 1, key: 'Z', desc: 'Z (Zero): 1 cuando el resultado o comparación (cp/cpi/dec) dio exactamente 0' },
  { bit: 0, key: 'C', desc: 'C (Carry): acarreo o préstamo sin signo (usado por brlo/brsh/adc/ror)' }
];

const PTR_TAGS = { 26: 'XL', 27: 'XH', 28: 'YL', 29: 'YH', 30: 'ZL', 31: 'ZH' };
const RAMEND = 0x08FF;

export class CpuXRayPanel {
  constructor(rootEl, coreSummaryEl, sregBarEl) {
    this.rootEl = rootEl;
    this.coreSummaryEl = coreSummaryEl;
    this.sregBarEl = sregBarEl;
    this.spec = null;
    this.buildSkeleton();
  }

  buildSkeleton() {
    this.rootEl.innerHTML = `
      <div class="xray-col xray-regs-col">
        <div class="xray-sec-head">
          <span>Registros generales (r0..r31)</span>
          <span class="xray-ptrs" id="xrayPtrs">X=0x0000 · Y=0x0000 · Z=0x0000</span>
        </div>
        <div class="xray-reg-grid" id="xrayRegGrid"></div>
      </div>
      <div class="xray-col xray-io-col">
        <div class="xray-sec-head"><span>Puertos E/S (DDR · PORT · PIN)</span></div>
        <div class="xray-ports" id="xrayPorts"></div>
      </div>
      <div class="xray-col xray-state-col">
        <div class="xray-sec-head"><span>Estado decodificado</span></div>
        <div class="xray-state" id="xrayState"></div>
      </div>
    `;
    this.regGridEl = this.rootEl.querySelector('#xrayRegGrid');
    this.ptrsEl = this.rootEl.querySelector('#xrayPtrs');
    this.portsEl = this.rootEl.querySelector('#xrayPorts');
    this.stateEl = this.rootEl.querySelector('#xrayState');

    // Construir 32 celdas de registro una sola vez para actualizaciones O(1) sin parpadeo
    this.regCells = [];
    for (let i = 0; i < 32; i++) {
      const cell = document.createElement('div');
      cell.className = 'xray-reg';
      if (i >= 26) cell.classList.add('ptr');
      cell.innerHTML = `
        <div class="xr-top">
          <span class="xr-name">r${i}${PTR_TAGS[i] ? `<small>${PTR_TAGS[i]}</small>` : ''}</span>
          <span class="xr-alias"></span>
        </div>
        <div class="xr-val">0x00 <small>0</small></div>
      `;
      this.regGridEl.appendChild(cell);
      this.regCells.push({
        el: cell,
        aliasEl: cell.querySelector('.xr-alias'),
        valEl: cell.querySelector('.xr-val')
      });
    }

    // Construir los 8 chips interactivos de SREG en la cabecera del dock
    this.sregCells = [];
    this.sregBarEl.innerHTML = '';
    const lbl = document.createElement('span');
    lbl.className = 'sreg-lbl';
    lbl.textContent = 'SREG:';
    this.sregBarEl.appendChild(lbl);
    for (const def of SREG_BITS) {
      const chip = document.createElement('span');
      chip.className = 'sreg-bit';
      chip.title = def.desc;
      chip.innerHTML = `<b>${def.key}</b><i>0</i>`;
      this.sregBarEl.appendChild(chip);
      this.sregCells.push({ def, el: chip, valEl: chip.querySelector('i') });
    }
  }

  setSpec(spec) {
    this.spec = spec;
    this.render(null);
  }

  render(simState) {
    const cpu = simState?.cpu;
    if (!cpu) {
      this.coreSummaryEl.textContent = 'Inicia o pulsa +1 instr / +10 ms para inspeccionar registros reales del ATmega328P';
      for (let i = 0; i < 32; i++) {
        this.regCells[i].el.classList.remove('changed', 'nonzero');
        this.regCells[i].aliasEl.textContent = '';
        this.regCells[i].valEl.innerHTML = '0x00 <small>0</small>';
      }
      for (const c of this.sregCells) {
        c.el.classList.remove('on', 'changed');
        c.valEl.textContent = '0';
      }
      this.ptrsEl.textContent = 'X=0x0000 · Y=0x0000 · Z=0x0000';
      this.portsEl.innerHTML = '<div class="xray-empty">Sin sesión simavr activa</div>';
      this.renderDecodedState(null);
      return;
    }

    const spUsed = Math.max(0, RAMEND - (cpu.sp ?? RAMEND));
    this.coreSummaryEl.textContent =
      `PC: 0x${hex4(cpu.pc ?? 0)}  ·  SP: 0x${hex4(cpu.sp ?? RAMEND)} (${spUsed} B pila)  ·  Ciclos: ${(simState.cycles ?? 0).toLocaleString('es-MX')}`;

    // SREG bit a bit
    const sreg = cpu.sreg ?? 0;
    const prevSreg = cpu.prevSreg ?? sreg;
    for (const c of this.sregCells) {
      const bitOn = ((sreg >> c.def.bit) & 1) !== 0;
      const bitChanged = (((sreg ^ prevSreg) >> c.def.bit) & 1) !== 0;
      c.el.classList.toggle('on', bitOn);
      c.el.classList.toggle('changed', bitChanged);
      c.valEl.textContent = bitOn ? '1' : '0';
    }

    // Banco de registros r0..r31
    const regs = cpu.regs || [];
    const changedMask = Number(cpu.changedRegsMask ?? 0);
    const aliases = cpu.registerAliases || {};
    for (let i = 0; i < 32; i++) {
      const val = (regs[i] ?? 0) & 0xFF;
      const changed = (Math.floor(changedMask / (2 ** i)) % 2) === 1;
      const alias = aliases['r' + i] || '';
      const cell = this.regCells[i];
      cell.el.classList.toggle('changed', changed);
      cell.el.classList.toggle('nonzero', val !== 0);
      cell.el.title = alias
        ? `r${i} (${alias}) = 0x${hex2(val)} (${val} dec · 0b${bin8(val)})`
        : `r${i} = 0x${hex2(val)} (${val} dec · 0b${bin8(val)})`;
      cell.aliasEl.textContent = alias ? shortenAlias(alias) : '';
      cell.valEl.innerHTML = `0x${hex2(val)} <small>${val}</small>`;
    }

    const xVal = ((regs[27] ?? 0) << 8) | (regs[26] ?? 0);
    const yVal = ((regs[29] ?? 0) << 8) | (regs[28] ?? 0);
    const zVal = ((regs[31] ?? 0) << 8) | (regs[30] ?? 0);
    this.ptrsEl.textContent = `X=0x${hex4(xVal)} · Y=0x${hex4(yVal)} · Z=0x${hex4(zVal)}`;

    // Puertos físicos B, C, D
    const ports = cpu.ports || {};
    let portsHtml = '';
    for (const letter of ['B', 'C', 'D']) {
      const p = ports[letter] || { ddr: 0, port: 0, pin: 0 };
      portsHtml += `
        <div class="xp-row">
          <div class="xp-hdr">
            <span class="xp-name">PORT${letter}</span>
            <span class="xp-hex">DDR 0x${hex2(p.ddr)} · OUT 0x${hex2(p.port)} · PIN 0x${hex2(p.pin)}</span>
          </div>
          <div class="xp-bits">
            ${renderPortBits(letter, p)}
          </div>
        </div>
      `;
    }
    this.portsEl.innerHTML = portsHtml;

    this.renderDecodedState(cpu);
  }

  renderDecodedState(cpu) {
    const varsSpec = this.spec?.vars || [];
    const fsmsSpec = this.spec?.fsmGraphs || [];
    const timersSpec = this.spec?.timers || [];
    if (varsSpec.length === 0 && fsmsSpec.length === 0 && timersSpec.length === 0) {
      this.stateEl.innerHTML = '<div class="xray-empty">Este circuito es combinacional puro (sin variables, FSM ni temporizadores).</div>';
      return;
    }

    let html = '';
    for (const g of fsmsSpec) {
      const phase = cpu?.livePhases?.[g.group] ?? g.initialPhase;
      html += `
        <div class="xs-item">
          <span class="xs-tag fsm">FSM</span>
          <span class="xs-name">${escapeHtml(g.group)}</span>
          <span class="xs-val fsm-val">${escapeHtml(phase)}</span>
        </div>
      `;
    }
    for (const v of varsSpec) {
      const val = cpu?.liveVars?.[v.name] ?? v.initial;
      const span = Math.max(1, v.max - v.min);
      const pct = Math.max(0, Math.min(100, Math.round(((val - v.min) / span) * 100)));
      html += `
        <div class="xs-item">
          <span class="xs-tag var">VAR</span>
          <span class="xs-name">${escapeHtml(v.name)}</span>
          <div class="xs-bar"><i style="width:${pct}%"></i></div>
          <span class="xs-val">${val} <small>[${v.min}..${v.max}]</small></span>
        </div>
      `;
    }
    for (const t of timersSpec) {
      const rem = cpu?.liveTimersMs?.[t] ?? 0;
      html += `
        <div class="xs-item">
          <span class="xs-tag tmr">TMR</span>
          <span class="xs-name">${escapeHtml(t)}</span>
          <span class="xs-val ${rem > 0 ? 'tmr-on' : ''}">${rem > 0 ? rem + ' ms' : 'inactivo'}</span>
        </div>
      `;
    }
    this.stateEl.innerHTML = html;
  }
}

function renderPortBits(letter, p) {
  let out = '';
  for (let b = 7; b >= 0; b--) {
    const isOut = ((p.ddr >> b) & 1) !== 0;
    const isHigh = ((p.pin >> b) & 1) !== 0;
    const unavailable = (letter === 'B' && b >= 6) || (letter === 'C' && b >= 6);
    const cls = unavailable ? 'na' : (isHigh ? 'hi' : 'lo');
    const dir = isOut ? 'SAL' : 'ENT';
    out += `<span class="xp-bit ${cls} ${isOut ? 'out' : 'in'}" title="P${letter}${b} · ${dir} · ${isHigh ? '5V (1)' : '0V (0)'}">${b}</span>`;
  }
  return out;
}

function shortenAlias(s) {
  const clean = s.replace(/\s+en\s+\w+$/, '').trim();
  return clean.length > 10 ? clean.slice(0, 9) + '…' : clean;
}

function hex2(n) { return ((n ?? 0) & 0xFF).toString(16).toUpperCase().padStart(2, '0'); }
function hex4(n) { return ((n ?? 0) & 0xFFFF).toString(16).toUpperCase().padStart(4, '0'); }
function bin8(n) { return ((n ?? 0) & 0xFF).toString(2).padStart(8, '0'); }
function escapeHtml(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
