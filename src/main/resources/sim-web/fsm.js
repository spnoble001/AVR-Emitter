/**
 * Visualizador SVG en tiempo real de Máquinas de Estado Finito (FSM) conectadas a simavr:
 *  - Extrae nodos y transiciones automáticamente desde spec.fsmGraphs (incluyendo transiciones globales '*').
 *  - Ilumina el estado activo en vivo tanto para motor AVR ASM como C99.
 *  - Anima cada flecha de transición con una partícula luminosa viajera al ocurrir un cambio real.
 *  - Clic en cualquier flecha selecciona la tarjeta correspondiente en el Estudio (trazabilidad 4-vías).
 */

const SVG_NS = 'http://www.w3.org/2000/svg';

export class FsmGraphPanel {
  constructor(svgWrapEl, groupSelectEl, activeBadgeEl, logEl, onSelectRule) {
    this.svgWrapEl = svgWrapEl;
    this.groupSelectEl = groupSelectEl;
    this.activeBadgeEl = activeBadgeEl;
    this.logEl = logEl;
    this.onSelectRule = onSelectRule;

    this.graphs = [];
    this.selectedGroup = null;
    this.activePhase = null;
    this.lastEventSeq = 0;
    this.highlightedRuleIdx = -1;

    this.svg = null;
    this.nodeEls = new Map();     // phase -> { g, rect, glow, badge }
    this.edgeEls = [];            // [{ tr, g, path, labelBg, labelTxt }]
    this.particleLayer = null;

    this.groupSelectEl.addEventListener('change', () => {
      this.selectedGroup = this.groupSelectEl.value;
      this.buildSvg();
      this.applyActiveVisual();
    });
  }

  hasFsms() {
    return this.graphs.length > 0;
  }

  setSpec(spec) {
    this.graphs = spec?.fsmGraphs || [];
    this.lastEventSeq = 0;
    this.activePhase = null;

    if (this.graphs.length === 0) {
      this.selectedGroup = null;
      this.groupSelectEl.classList.add('hidden');
      this.activeBadgeEl.textContent = '';
      this.svgWrapEl.innerHTML =
        '<div class="xray-empty" style="text-align:center; max-width:420px; line-height:1.45;">Este proyecto no define grupos de fases (FSM).<br/>Prueba ejemplos como <b>10 · Semáforo cancelable</b>, <b>07 · Doble clic</b> o <b>15 · Integrador 3 estados</b>, o agrega un grupo FSM en el panel izquierdo.</div>';
      this.logEl.innerHTML = '<div class="xray-empty">Sin transiciones registradas</div>';
      return;
    }

    if (!this.graphs.some(g => g.group === this.selectedGroup)) {
      this.selectedGroup = this.graphs[0].group;
    }

    this.groupSelectEl.innerHTML = '';
    for (const g of this.graphs) {
      const opt = document.createElement('option');
      opt.value = g.group;
      opt.textContent = `FSM: ${g.group} (${g.states.length} fases)`;
      this.groupSelectEl.appendChild(opt);
    }
    this.groupSelectEl.value = this.selectedGroup;
    this.groupSelectEl.classList.toggle('hidden', this.graphs.length <= 1);

    this.buildSvg();
    this.applyActiveVisual();
    this.renderLog([]);
  }

  setHighlightedRule(ruleIndex) {
    this.highlightedRuleIdx = ruleIndex ?? -1;
    for (const e of this.edgeEls) {
      const sel = this.highlightedRuleIdx >= 0 && e.tr.ruleIndex === this.highlightedRuleIdx;
      e.path.setAttribute('stroke', sel ? '#d98c4e' : (e.tr.global ? '#c97a76' : '#535f5a'));
      e.path.setAttribute('stroke-width', sel ? '2.6' : '1.7');
      e.path.setAttribute('marker-end', sel ? 'url(#fsmArrowHi)' : (e.tr.global ? 'url(#fsmArrowGlob)' : 'url(#fsmArrow)'));
    }
  }

  update(simState) {
    if (this.graphs.length === 0 || !this.selectedGroup) return;
    const g = this.currentGraph();
    if (!g) return;

    const livePhase = simState?.cpu?.livePhases?.[g.group] ?? g.initialPhase;
    if (livePhase !== this.activePhase) {
      this.activePhase = livePhase;
      this.applyActiveVisual();
    }

    const events = (simState?.recentFsmTransitions || []).filter(ev => ev.group === g.group);
    const newEvents = events.filter(ev => ev.seq > this.lastEventSeq);
    if (newEvents.length > 0) {
      this.lastEventSeq = newEvents[newEvents.length - 1].seq;
      const latest = newEvents[newEvents.length - 1];
      this.animateTransition(latest.from, latest.to);
    }
    this.renderLog(events);
  }

  currentGraph() {
    return this.graphs.find(g => g.group === this.selectedGroup) || this.graphs[0] || null;
  }

  buildSvg() {
    this.nodeEls.clear();
    this.edgeEls = [];
    this.svgWrapEl.innerHTML = '';

    const g = this.currentGraph();
    if (!g) return;
    this.activePhase = this.activePhase && g.states.includes(this.activePhase) ? this.activePhase : g.initialPhase;

    const W = 740, H = 192;
    const svg = svgEl('svg', { viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: 'xMidYMid meet' });

    // Marcadores de flecha (paleta carbón + Veridian + cobre)
    const defs = svgEl('defs');
    defs.appendChild(makeArrowMarker('fsmArrow', '#5c6863'));
    defs.appendChild(makeArrowMarker('fsmArrowGlob', '#c97a76'));
    defs.appendChild(makeArrowMarker('fsmArrowHi', '#d98c4e'));
    defs.appendChild(makeArrowMarker('fsmArrowFire', '#4cc6bc'));
    svg.appendChild(defs);

    const edgeLayer = svgEl('g');
    const nodeLayer = svgEl('g');
    const particleLayer = svgEl('g');
    svg.appendChild(edgeLayer);
    svg.appendChild(nodeLayer);
    svg.appendChild(particleLayer);
    this.particleLayer = particleLayer;

    // Calcular coordenadas limpias de cada estado
    const positions = computeNodeLayout(g.states, W, H);
    const nodeW = 126, nodeH = 42;

    // Flecha de estado inicial
    const initPos = positions.get(g.initialPhase);
    if (initPos) {
      const ix = Math.max(16, initPos.x - nodeW / 2 - 42);
      const iy = initPos.y;
      edgeLayer.appendChild(svgEl('path', {
        d: `M ${ix} ${iy} L ${initPos.x - nodeW / 2 - 4} ${iy}`,
        stroke: '#36ada3',
        'stroke-width': '1.6',
        fill: 'none',
        'marker-end': 'url(#fsmArrowFire)'
      }));
      const tInit = svgEl('text', {
        x: ix + 2, y: iy - 7, fill: '#4cc6bc',
        'font-size': '9.5', 'font-weight': '600', 'font-family': 'Segoe UI, sans-serif'
      });
      tInit.textContent = 'Inicio';
      edgeLayer.appendChild(tInit);
    }

    // Agrupar aristas entre el mismo par (from, to) para separarlas si hay múltiples
    const pairCounts = new Map();
    for (const tr of g.transitions) {
      const key = `${tr.from}->${tr.to}`;
      pairCounts.set(key, (pairCounts.get(key) || 0) + 1);
    }
    const pairSeen = new Map();
    let globalIdx = 0;

    for (const tr of g.transitions) {
      const key = `${tr.from}->${tr.to}`;
      const revKey = `${tr.to}->${tr.from}`;
      const idxInPair = pairSeen.get(key) || 0;
      pairSeen.set(key, idxInPair + 1);
      const hasReverse = !tr.global && pairCounts.has(revKey);

      const toPos = positions.get(tr.to);
      if (!toPos) continue;

      let d, lx, ly;
      if (tr.global) {
        // Transición global (* -> estado) entrando desde arriba con estilo punteado rosa/ámbar
        const offset = (globalIdx++ % 3 - 1) * 24;
        const sx = toPos.x + offset;
        const sy = Math.max(12, toPos.y - nodeH / 2 - 42);
        const ex = toPos.x + offset * 0.4;
        const ey = toPos.y - nodeH / 2 - 4;
        d = `M ${sx} ${sy} Q ${sx + 16} ${(sy + ey) / 2} ${ex} ${ey}`;
        lx = sx + 14;
        ly = sy + 12;
      } else if (tr.from === tr.to) {
        // Bucle propio
        const cx = toPos.x;
        const topY = toPos.y - nodeH / 2;
        d = `M ${cx - 18} ${topY} C ${cx - 44} ${topY - 46}, ${cx + 44} ${topY - 46}, ${cx + 18} ${topY - 3}`;
        lx = cx;
        ly = topY - 34;
      } else {
        const fromPos = positions.get(tr.from);
        if (!fromPos) continue;
        const geom = curvedEdgeGeometry(fromPos, toPos, nodeW, nodeH, hasReverse ? 28 + idxInPair * 18 : idxInPair * 22);
        d = geom.d;
        lx = geom.lx;
        ly = geom.ly;
      }

      const eg = svgEl('g', { class: 'fsm-edge' });
      const titleEl = svgEl('title');
      titleEl.textContent = `Regla #${tr.ruleIndex + 1}: ${tr.title} (clic para resaltar código y pines)`;
      eg.appendChild(titleEl);

      // Área de clic transparente más ancha
      eg.appendChild(svgEl('path', {
        d, stroke: 'transparent', 'stroke-width': '14', fill: 'none'
      }));

      const path = svgEl('path', {
        d,
        stroke: tr.global ? '#c97a76' : '#535f5a',
        'stroke-width': '1.7',
        'stroke-dasharray': tr.global ? '5 3' : 'none',
        fill: 'none',
        'marker-end': tr.global ? 'url(#fsmArrowGlob)' : 'url(#fsmArrow)'
      });
      eg.appendChild(path);

      // Etiqueta legible sobre la flecha
      const labelText = tr.global ? `* ${tr.label}` : tr.label;
      const pillW = Math.max(46, labelText.length * 6.1 + 12);
      const labelBg = svgEl('rect', {
        x: lx - pillW / 2, y: ly - 9, width: pillW, height: 16, rx: 4,
        fill: '#111413', stroke: tr.global ? '#6e3b38' : '#2a312e', 'stroke-width': '1'
      });
      const labelTxt = svgEl('text', {
        x: lx, y: ly + 2.5, 'text-anchor': 'middle',
        fill: tr.global ? '#ebb3b0' : '#cdd5d1',
        'font-size': '9.5', 'font-weight': '600', 'font-family': 'Consolas, monospace'
      });
      labelTxt.textContent = labelText;
      eg.appendChild(labelBg);
      eg.appendChild(labelTxt);

      eg.addEventListener('click', (ev) => {
        ev.stopPropagation();
        if (this.onSelectRule) this.onSelectRule(tr.ruleIndex);
      });

      edgeLayer.appendChild(eg);
      this.edgeEls.push({ tr, g: eg, path, labelBg, labelTxt });
    }

    // Dibujar nodos de estado
    for (const phase of g.states) {
      const pos = positions.get(phase);
      const ng = svgEl('g');

      const glow = svgEl('rect', {
        x: pos.x - nodeW / 2 - 4, y: pos.y - nodeH / 2 - 4,
        width: nodeW + 8, height: nodeH + 8, rx: 8,
        fill: 'none', stroke: '#36ada3', 'stroke-width': '1.6', opacity: '0'
      });
      const rect = svgEl('rect', {
        x: pos.x - nodeW / 2, y: pos.y - nodeH / 2,
        width: nodeW, height: nodeH, rx: 6,
        fill: '#161918', stroke: '#353e3a', 'stroke-width': '1.5'
      });
      const txt = svgEl('text', {
        x: pos.x, y: pos.y + 4, 'text-anchor': 'middle',
        fill: '#dce1de', 'font-size': '11.5', 'font-weight': '700',
        'font-family': 'Consolas, monospace'
      });
      txt.textContent = phase.length > 13 ? phase.slice(0, 12) + '…' : phase;

      const badge = svgEl('text', {
        x: pos.x, y: pos.y + nodeH / 2 - 4, 'text-anchor': 'middle',
        fill: '#4cc6bc', 'font-size': '8.5', 'font-weight': '600',
        'font-family': 'Segoe UI, sans-serif', opacity: '0'
      });
      badge.textContent = 'Activo';

      ng.appendChild(glow);
      ng.appendChild(rect);
      ng.appendChild(txt);
      ng.appendChild(badge);
      nodeLayer.appendChild(ng);
      this.nodeEls.set(phase, { g: ng, rect, glow, txt, badge });
    }

    this.svg = svg;
    this.svgWrapEl.appendChild(svg);
    this.setHighlightedRule(this.highlightedRuleIdx);
  }

  applyActiveVisual() {
    if (!this.activePhase) return;
    this.activeBadgeEl.textContent = `Estado: ${this.activePhase}`;
    for (const [phase, ui] of this.nodeEls.entries()) {
      const active = phase === this.activePhase;
      ui.g.classList.toggle('fsm-node-active', active);
      ui.rect.setAttribute('fill', active ? '#142a27' : '#161918');
      ui.rect.setAttribute('stroke', active ? '#36ada3' : '#353e3a');
      ui.rect.setAttribute('stroke-width', active ? '2.2' : '1.4');
      ui.glow.setAttribute('opacity', active ? '0.55' : '0');
      ui.txt.setAttribute('fill', active ? '#ecefed' : '#b2bbb7');
      ui.txt.setAttribute('y', active ? String(Number(ui.rect.getAttribute('y')) + 19) : String(Number(ui.rect.getAttribute('y')) + 25));
      ui.badge.setAttribute('opacity', active ? '1' : '0');
    }
  }

  animateTransition(from, to) {
    if (!this.particleLayer) return;
    if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const match = this.edgeEls.find(e => e.tr.from === from && e.tr.to === to)
      || this.edgeEls.find(e => e.tr.global && e.tr.to === to);
    if (!match) return;

    const path = match.path;
    const origStroke = path.getAttribute('stroke');
    const origWidth = path.getAttribute('stroke-width');
    const origMarker = path.getAttribute('marker-end');

    path.setAttribute('stroke', '#4cc6bc');
    path.setAttribute('stroke-width', '2.8');
    path.setAttribute('marker-end', 'url(#fsmArrowFire)');

    const dot = svgEl('circle', { r: '5', fill: '#d98c4e', stroke: '#ecefed', 'stroke-width': '1.4' });
    this.particleLayer.appendChild(dot);

    const totalLen = path.getTotalLength();
    const start = performance.now();
    const duration = 460;

    const tick = (now) => {
      const t = Math.min(1, (now - start) / duration);
      const pt = path.getPointAtLength(t * totalLen);
      dot.setAttribute('cx', pt.x.toFixed(1));
      dot.setAttribute('cy', pt.y.toFixed(1));
      dot.setAttribute('opacity', String(1 - t * 0.25));
      if (t < 1) {
        requestAnimationFrame(tick);
      } else {
        dot.remove();
        if (this.highlightedRuleIdx !== match.tr.ruleIndex) {
          path.setAttribute('stroke', origStroke);
          path.setAttribute('stroke-width', origWidth);
          path.setAttribute('marker-end', origMarker);
        }
      }
    };
    requestAnimationFrame(tick);
  }

  renderLog(events) {
    if (!events || events.length === 0) {
      this.logEl.innerHTML = '<div class="xray-empty">Esperando transiciones en simavr…</div>';
      return;
    }
    const recent = events.slice(-10).reverse();
    this.logEl.innerHTML = recent.map((ev, idx) => `
      <div class="fsm-log-row ${idx === 0 ? 'latest' : ''}">
        <span class="fsm-log-t">${ev.elapsedMs.toFixed(0)} ms</span>
        <span>${escapeHtml(ev.from)}</span>
        <span class="fsm-log-arr">→</span>
        <b>${escapeHtml(ev.to)}</b>
      </div>
    `).join('');
  }
}

function computeNodeLayout(states, W, H) {
  const map = new Map();
  const n = states.length;
  if (n === 1) {
    map.set(states[0], { x: W / 2, y: H / 2 });
  } else if (n === 2) {
    map.set(states[0], { x: 210, y: H / 2 + 4 });
    map.set(states[1], { x: 530, y: H / 2 + 4 });
  } else if (n === 3) {
    map.set(states[0], { x: 155, y: 126 });
    map.set(states[1], { x: 370, y: 54 });
    map.set(states[2], { x: 585, y: 126 });
  } else {
    const cx = W / 2, cy = H / 2 + 4;
    const rx = Math.min(265, W * 0.36);
    const ry = Math.min(62, H * 0.34);
    for (let i = 0; i < n; i++) {
      const ang = Math.PI + (2 * Math.PI * i) / n;
      map.set(states[i], { x: cx + rx * Math.cos(ang), y: cy + ry * Math.sin(ang) });
    }
  }
  return map;
}

function curvedEdgeGeometry(a, b, nodeW, nodeH, bend) {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const dist = Math.max(1, Math.hypot(dx, dy));
  const ux = dx / dist, uy = dy / dist;
  const nx = -uy, ny = ux;

  // Recortar inicio y fin al perímetro del rectángulo del nodo
  const padA = rectEdgeDist(ux, uy, nodeW / 2 + 3, nodeH / 2 + 3);
  const padB = rectEdgeDist(-ux, -uy, nodeW / 2 + 7, nodeH / 2 + 7);
  const sx = a.x + ux * padA;
  const sy = a.y + uy * padA;
  const ex = b.x - ux * padB;
  const ey = b.y - uy * padB;

  const mx = (sx + ex) / 2 + nx * bend;
  const my = (sy + ey) / 2 + ny * bend;

  // Punto medio real de la Bézier cuadrática (t = 0.5)
  const lx = 0.25 * sx + 0.5 * mx + 0.25 * ex;
  const ly = 0.25 * sy + 0.5 * my + 0.25 * ey;
  return {
    d: `M ${sx.toFixed(1)} ${sy.toFixed(1)} Q ${mx.toFixed(1)} ${my.toFixed(1)} ${ex.toFixed(1)} ${ey.toFixed(1)}`,
    lx, ly
  };
}

function rectEdgeDist(ux, uy, hw, hh) {
  const tx = Math.abs(ux) > 1e-4 ? hw / Math.abs(ux) : 1e9;
  const ty = Math.abs(uy) > 1e-4 ? hh / Math.abs(uy) : 1e9;
  return Math.min(tx, ty);
}

function makeArrowMarker(id, color) {
  const mk = svgEl('marker', {
    id, viewBox: '0 0 10 10', refX: '8.5', refY: '5',
    markerWidth: '6.5', markerHeight: '6.5', orient: 'auto-start-reverse'
  });
  mk.appendChild(svgEl('path', { d: 'M 0 1.5 L 9 5 L 0 8.5 z', fill: color }));
  return mk;
}

function svgEl(tag, attrs = {}) {
  const el = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, String(v));
  return el;
}

function escapeHtml(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
