import { COL, clamp } from './shared.js';
import { SchematicView } from './schematic.js';
import { BreadboardView } from './breadboard.js';
import { InteractiveScope } from './scope.js';
import { CpuXRayPanel } from './xray.js';
import { FsmGraphPanel } from './fsm.js';

// ==================== Instancias y estado compartido ====================
const schematicView = new SchematicView();
const breadboardView = new BreadboardView();

const canvas = document.getElementById('cadCanvas');
const ctx = canvas.getContext('2d');
const traceCanvas = document.getElementById('traceCanvas');
const cursorReadout = document.getElementById('cursorReadout');
const scopeZoomLabel = document.getElementById('scopeZoomLabel');
const scope = new InteractiveScope(traceCanvas, cursorReadout, scopeZoomLabel);

const btnStart = document.getElementById('btnStart');
const btnPause = document.getElementById('btnPause');
const btnStepInstr = document.getElementById('btnStepInstr');
const btnStep = document.getElementById('btnStep');
const btnReset = document.getElementById('btnReset');
const modeSchematicBtn = document.getElementById('modeSchematic');
const modeBreadboardBtn = document.getElementById('modeBreadboard');
const backendSelect = document.getElementById('backendSelect');
const btnCenter = document.getElementById('btnCenter');
const zoomBadge = document.getElementById('zoomBadge');
const ssePill = document.getElementById('ssePill');
const clockPill = document.getElementById('clockPill');
const statusText = document.getElementById('statusText');
const customFwBadge = document.getElementById('customFwBadge');
const customFwText = document.getElementById('customFwText');
const btnClearCustomFw = document.getElementById('btnClearCustomFw');
const hintText = document.getElementById('hintText');
const hoverTooltip = document.getElementById('hoverTooltip');
const highlightBanner = document.getElementById('highlightBanner');
const highlightBannerText = document.getElementById('highlightBannerText');
const btnClearHighlight = document.getElementById('btnClearHighlight');

// Dock unificado por pestañas
const inspectorDock = document.getElementById('inspectorDock');
const btnToggleDock = document.getElementById('btnToggleDock');
const tabButtons = {
  xray: document.getElementById('tabXray'),
  fsm: document.getElementById('tabFsm'),
  trace: document.getElementById('tabTrace'),
  uart: document.getElementById('tabUart')
};
const tabHeads = {
  xray: document.getElementById('xrayHeadRight'),
  fsm: document.getElementById('fsmHeadRight'),
  trace: document.getElementById('traceHeadRight'),
  uart: document.getElementById('uartHeadRight')
};
const tabPanes = {
  xray: document.getElementById('xrayDock'),
  fsm: document.getElementById('fsmDock'),
  trace: document.getElementById('traceDock'),
  uart: document.getElementById('uartDock')
};

const btnScopeZoomOut = document.getElementById('btnScopeZoomOut');
const btnScopeZoomIn = document.getElementById('btnScopeZoomIn');
const btnScopeCursors = document.getElementById('btnScopeCursors');
const btnClearTrace = document.getElementById('btnClearTrace');
const uartTitle = document.getElementById('uartTitle');
const uartInput = document.getElementById('uartInput');
const btnUartSend = document.getElementById('btnUartSend');
const uartLog = document.getElementById('uartLog');

const xrayPanel = new CpuXRayPanel(
  document.getElementById('xrayBody'),
  document.getElementById('xrayCoreSummary'),
  document.getElementById('xraySregBar')
);
const fsmPanel = new FsmGraphPanel(
  document.getElementById('fsmCanvasWrap'),
  document.getElementById('fsmGroupSelect'),
  document.getElementById('fsmActiveBadge'),
  document.getElementById('fsmLog'),
  (ruleIndex) => postJson('/api/ui/select-rule', { ruleIndex })
);

let spec = null;
let simState = null;
let highlightState = null;
let lastSpecSig = null;
let lastTraceSeq = -1;
let viewMode = 'schematic'; // 'schematic' | 'breadboard'
let activeDockTab = 'xray';
let dockExpanded = false;

const cam = { x: 0, y: 0, zoom: 1.0 };
const hitboxes = [];

let dragMode = null; // 'pan' | 'node' | 'pot'
let dragNodeName = null;
let dragOffset = { x: 0, y: 0 };
let panStart = { sx: 0, sy: 0, cx: 0, cy: 0 };
let activeButtonName = null;
let activePotHit = null;
let hoveredPin = null;

function activeView() {
  return viewMode === 'breadboard' ? breadboardView : schematicView;
}

// ==================== Gestión del dock unificado ====================
function setDockState(tabKey, expanded) {
  if (tabKey && tabPanes[tabKey]) {
    activeDockTab = tabKey;
  }
  dockExpanded = !!expanded;
  inspectorDock.classList.toggle('collapsed', !dockExpanded);
  btnToggleDock.textContent = dockExpanded ? 'Ocultar' : 'Expandir';

  for (const key of Object.keys(tabPanes)) {
    const isAct = key === activeDockTab;
    tabButtons[key].classList.toggle('active', dockExpanded && isAct);
    tabHeads[key].classList.toggle('hidden', !dockExpanded || !isAct);
    tabPanes[key].classList.toggle('hidden', !dockExpanded || !isAct);
  }
  resizeAll();
  centerView();
}

for (const [key, btn] of Object.entries(tabButtons)) {
  btn.addEventListener('click', () => {
    if (dockExpanded && activeDockTab === key) {
      setDockState(key, false);
    } else {
      setDockState(key, true);
    }
  });
}

btnToggleDock.addEventListener('click', () => {
  setDockState(activeDockTab, !dockExpanded);
});

// ==================== API REST + SSE hacia SimBridgeServer ====================
async function postJson(url, body = {}) {
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (res.ok && url.startsWith('/api/sim/')) {
      applyState(await res.json());
    }
  } catch (err) {
    statusText.textContent = 'Error de comunicación con simavr: ' + err.message;
  }
}

function connectSse() {
  ssePill.textContent = 'Conectando';
  ssePill.className = 'sse-status warn';
  const es = new EventSource('/api/events');
  es.onopen = () => {
    ssePill.textContent = 'simavr conectado';
    ssePill.className = 'sse-status ok';
  };
  es.addEventListener('spec', (ev) => {
    try { applySpec(JSON.parse(ev.data)); } catch (e) { console.error(e); }
  });
  es.addEventListener('state', (ev) => {
    try { applyState(JSON.parse(ev.data)); } catch (e) { console.error(e); }
  });
  es.addEventListener('highlight', (ev) => {
    try { applyHighlight(JSON.parse(ev.data)); } catch (e) { console.error(e); }
  });
  es.onerror = () => {
    ssePill.textContent = 'Reconectando';
    ssePill.className = 'sse-status err';
  };
}

function applyHighlight(hl) {
  highlightState = hl || null;
  if (highlightState && highlightState.ruleIndex >= 0) {
    const pinsStr = (highlightState.pins && highlightState.pins.length > 0)
      ? ` · Pines: ${highlightState.pins.join(', ')}`
      : '';
    const ord = String(highlightState.ruleIndex + 1).padStart(2, '0');
    highlightBannerText.textContent = `Regla ${ord}: ${highlightState.title}${pinsStr}`;
    highlightBanner.classList.remove('hidden');
    fsmPanel.setHighlightedRule(highlightState.ruleIndex);
  } else {
    highlightBanner.classList.add('hidden');
    fsmPanel.setHighlightedRule(-1);
  }
  requestDraw();
}

function applySpec(nextSpec) {
  const sig = nextSpec.name + '|' + JSON.stringify(nextSpec.components.map(c => c.name + c.type + (c.pin || c.firstPin || '')))
    + '|' + JSON.stringify(nextSpec.fsmGraphs || []);
  const changedCircuit = sig !== lastSpecSig;
  spec = nextSpec;
  clockPill.textContent = `ATmega328P · ${spec.clockLabel}`;

  const uart = spec.components.find(c => c.type === 'UART');
  tabButtons.uart.classList.toggle('hidden', !uart);
  if (uart) {
    uartTitle.textContent = `USART0 ${uart.baud} bps 8N1 (${uart.mode})`;
  } else if (activeDockTab === 'uart') {
    setDockState('xray', dockExpanded);
  }

  const fsmCount = (spec.fsmGraphs || []).length;
  tabButtons.fsm.textContent = fsmCount > 0 ? `Grafo FSM (${fsmCount})` : 'Grafo FSM';

  if (changedCircuit) {
    lastSpecSig = sig;
    schematicView.autoLayout(spec);
    breadboardView.autoLayout(spec);
    scope.setSpec(spec);
    xrayPanel.setSpec(spec);
    fsmPanel.setSpec(spec);
    resizeAll();
    centerView();
  }
  requestDraw();
}

function applyState(st) {
  simState = st;
  statusText.textContent = st.status || 'Listo';
  if (st.customFirmwareLabel) {
    customFwText.textContent = `Manual: ${st.customFirmwareLabel}`;
    customFwBadge.classList.remove('hidden');
  } else {
    customFwBadge.classList.add('hidden');
  }
  btnStart.disabled = st.running || st.busy;
  btnPause.disabled = !(st.running || (st.busy && st.desiredRunning));
  btnStepInstr.disabled = st.busy;
  btnStep.disabled = st.busy;
  btnReset.disabled = !st.sessionActive && !st.busy;
  if (backendSelect.value !== st.backend) {
    backendSelect.value = st.backend;
  }
  btnUartSend.disabled = !st.sessionActive || !st.uartRxReady;
  if (uartLog.textContent !== (st.uartTx || '')) {
    uartLog.textContent = st.uartTx || '';
    uartLog.scrollTop = uartLog.scrollHeight;
  }

  if (st.highlight !== undefined && JSON.stringify(st.highlight) !== JSON.stringify(highlightState)) {
    applyHighlight(st.highlight);
  }

  if (st.sessionActive && st.stateSeq !== lastTraceSeq) {
    lastTraceSeq = st.stateSeq;
    scope.record(spec, st);
  }
  xrayPanel.render(st);
  fsmPanel.update(st);
  requestDraw();
}

// ==================== Modo Esquemático <-> Protoboard ====================
function setViewMode(nextMode) {
  viewMode = nextMode === 'breadboard' ? 'breadboard' : 'schematic';
  modeSchematicBtn.classList.toggle('active', viewMode === 'schematic');
  modeBreadboardBtn.classList.toggle('active', viewMode === 'breadboard');
  hintText.textContent = viewMode === 'breadboard'
    ? 'Protoboard · Rueda: zoom · Arrastra fondo: mover · Pulsa botones, gira potenciómetro o sondea cables'
    : 'Esquemático · Rueda: zoom · Arrastra fondo: mover · Arrastra símbolo: reubicar · Sondea pines y cables';
  hoveredPin = null;
  hideTooltip();
  centerView();
}

// ==================== Cámara y renderizado ====================
function screenToWorld(sx, sy) {
  return { x: (sx - cam.x) / cam.zoom, y: (sy - cam.y) / cam.zoom };
}

function centerView() {
  const rect = canvas.getBoundingClientRect();
  if (rect.width < 10 || rect.height < 10) return;
  const b = activeView().getBounds(spec);
  const bw = Math.max(300, b.maxX - b.minX);
  const bh = Math.max(260, b.maxY - b.minY);
  const pad = 28;
  cam.zoom = clamp(Math.min((rect.width - pad * 2) / bw, (rect.height - pad * 2) / bh), 0.35, 1.45);
  cam.x = rect.width / 2 - ((b.minX + b.maxX) / 2) * cam.zoom;
  cam.y = rect.height / 2 - ((b.minY + b.maxY) / 2) * cam.zoom;
  zoomBadge.textContent = `${Math.round(cam.zoom * 100)}%`;
  requestDraw();
}

let drawQueued = false;
function requestDraw() {
  if (drawQueued) return;
  drawQueued = true;
  requestAnimationFrame(() => {
    drawQueued = false;
    drawFrame();
  });
}

function resizeAll() {
  const dpr = window.devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  canvas.width = Math.max(1, Math.floor(rect.width * dpr));
  canvas.height = Math.max(1, Math.floor(rect.height * dpr));
  if (dockExpanded && activeDockTab === 'trace') {
    scope.resize();
  }
  requestDraw();
}

function drawFrame() {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.width / dpr;
  const h = canvas.height / dpr;

  ctx.save();
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

  // Fondo carbón técnico para esquemático y tapete de banco oscuro para protoboard
  ctx.fillStyle = viewMode === 'breadboard' ? '#121514' : COL.sheet;
  ctx.fillRect(0, 0, w, h);
  drawGrid(w, h);

  ctx.translate(cam.x, cam.y);
  ctx.scale(cam.zoom, cam.zoom);

  hitboxes.length = 0;
  activeView().draw(ctx, spec, simState, hitboxes, hoveredPin, highlightState);

  ctx.restore();
}

function drawGrid(viewW, viewH) {
  const step = 16 * cam.zoom;
  if (step < 6) return;
  const startX = ((cam.x % step) + step) % step;
  const startY = ((cam.y % step) + step) % step;
  ctx.fillStyle = viewMode === 'breadboard' ? '#1d2320' : COL.grid;
  for (let x = startX; x < viewW; x += step) {
    for (let y = startY; y < viewH; y += step) {
      ctx.fillRect(x - 0.75, y - 0.75, 1.5, 1.5);
    }
  }
}

// ==================== Interacción de ratón y sondas hover ====================
function hitTest(wx, wy) {
  for (let i = hitboxes.length - 1; i >= 0; i--) {
    const hb = hitboxes[i];
    if (wx >= hb.x && wx <= hb.x + hb.w && wy >= hb.y && wy <= hb.y + hb.h) return hb;
  }
  return null;
}

function showTooltip(sx, sy, html) {
  hoverTooltip.innerHTML = html;
  hoverTooltip.classList.remove('hidden');
  const stageRect = canvas.getBoundingClientRect();
  const tx = clamp(sx + 14, 8, stageRect.width - hoverTooltip.offsetWidth - 10);
  const ty = clamp(sy - 38, 8, stageRect.height - hoverTooltip.offsetHeight - 10);
  hoverTooltip.style.left = `${tx}px`;
  hoverTooltip.style.top = `${ty}px`;
}

function hideTooltip() {
  hoverTooltip.classList.add('hidden');
}

canvas.addEventListener('contextmenu', (e) => e.preventDefault());

canvas.addEventListener('mousedown', (e) => {
  const rect = canvas.getBoundingClientRect();
  const sx = e.clientX - rect.left, sy = e.clientY - rect.top;
  const wpt = screenToWorld(sx, sy);
  const hit = hitTest(wpt.x, wpt.y);

  if (hit && hit.kind === 'button') {
    if (e.button === 2 || e.shiftKey) {
      postJson('/api/input/button', { name: hit.comp.name, togglePin: true });
    } else {
      activeButtonName = hit.comp.name;
      postJson('/api/input/button', { name: hit.comp.name, pressed: true });
    }
    return;
  }
  if (hit && hit.kind === 'button-pin') {
    postJson('/api/input/button', { name: hit.comp.name, togglePin: true });
    return;
  }
  if (hit && hit.kind === 'pot-step') {
    const cur = simState?.analogMv?.[hit.comp.name] ?? 0;
    postJson('/api/input/analog', { name: hit.comp.name, mv: clamp(cur + hit.delta, 0, 5000) });
    return;
  }
  if (hit && hit.kind === 'pot-drag') {
    dragMode = 'pot';
    activePotHit = hit;
    updatePotFromY(hit, wpt.y);
    return;
  }
  if (hit && hit.kind === 'node' && viewMode === 'schematic' && e.button === 0) {
    dragMode = 'node';
    dragNodeName = hit.comp.name;
    const cur = schematicView.getNodePos(dragNodeName);
    dragOffset = { x: wpt.x - cur.x, y: wpt.y - cur.y };
    canvas.classList.add('grabbing');
    return;
  }

  dragMode = 'pan';
  panStart = { sx, sy, cx: cam.x, cy: cam.y };
  canvas.classList.add('grabbing');
});

window.addEventListener('mousemove', (e) => {
  const rect = canvas.getBoundingClientRect();
  const sx = e.clientX - rect.left, sy = e.clientY - rect.top;
  const wpt = screenToWorld(sx, sy);

  if (dragMode === 'pan') {
    cam.x = panStart.cx + (sx - panStart.sx);
    cam.y = panStart.cy + (sy - panStart.sy);
    hideTooltip();
    requestDraw();
    return;
  }
  if (dragMode === 'node' && dragNodeName) {
    schematicView.moveNode(dragNodeName, wpt.x, wpt.y, dragOffset);
    hideTooltip();
    requestDraw();
    return;
  }
  if (dragMode === 'pot' && activePotHit) {
    updatePotFromY(activePotHit, wpt.y);
    return;
  }

  if (sx < 0 || sy < 0 || sx > rect.width || sy > rect.height) {
    if (hoveredPin !== null) { hoveredPin = null; requestDraw(); }
    hideTooltip();
    return;
  }

  const hit = hitTest(wpt.x, wpt.y);
  canvas.classList.toggle('pointer', !!hit && hit.kind !== 'node');

  const net = activeView().hitTestNet(wpt.x, wpt.y, spec, simState);
  if (net) {
    if (hoveredPin !== net.pin) {
      hoveredPin = net.pin;
      requestDraw();
    }
    showTooltip(sx, sy, net.html);
  } else {
    if (hoveredPin !== null) {
      hoveredPin = null;
      requestDraw();
    }
    hideTooltip();
  }
});

window.addEventListener('mouseup', () => {
  if (activeButtonName) {
    postJson('/api/input/button', { name: activeButtonName, pressed: false });
    activeButtonName = null;
  }
  dragMode = null;
  dragNodeName = null;
  activePotHit = null;
  canvas.classList.remove('grabbing');
});

canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  const rect = canvas.getBoundingClientRect();
  const sx = e.clientX - rect.left, sy = e.clientY - rect.top;
  const before = screenToWorld(sx, sy);
  const factor = e.deltaY < 0 ? 1.12 : (1 / 1.12);
  cam.zoom = clamp(cam.zoom * factor, 0.32, 2.8);
  cam.x = sx - before.x * cam.zoom;
  cam.y = sy - before.y * cam.zoom;
  zoomBadge.textContent = `${Math.round(cam.zoom * 100)}%`;
  requestDraw();
}, { passive: false });

canvas.addEventListener('dblclick', () => centerView());

function updatePotFromY(hit, wy) {
  const ratio = clamp((hit.potTop + hit.potH - wy) / hit.potH, 0, 1);
  postJson('/api/input/analog', { name: hit.comp.name, mv: Math.round(ratio * 5000) });
}

// ==================== Controles de barra y herramientas ====================
btnStart.addEventListener('click', () => postJson('/api/sim/start'));
btnPause.addEventListener('click', () => postJson('/api/sim/pause'));
btnStepInstr.addEventListener('click', () => postJson('/api/sim/step-instr'));
btnStep.addEventListener('click', () => postJson('/api/sim/step', { ms: 10 }));
btnReset.addEventListener('click', () => { scope.clear(); postJson('/api/sim/reset'); });
btnClearCustomFw.addEventListener('click', () => { scope.clear(); postJson('/api/sim/clear-custom'); });
backendSelect.addEventListener('change', () => { scope.clear(); postJson('/api/sim/backend', { backend: backendSelect.value }); });

modeSchematicBtn.addEventListener('click', () => setViewMode('schematic'));
modeBreadboardBtn.addEventListener('click', () => setViewMode('breadboard'));
btnCenter.addEventListener('click', () => centerView());

btnClearHighlight.addEventListener('click', () => {
  applyHighlight(null);
  postJson('/api/ui/clear-highlight');
});
window.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && highlightState) {
    applyHighlight(null);
    postJson('/api/ui/clear-highlight');
  }
});
btnScopeZoomOut.addEventListener('click', () => scope.stepZoom(-1));
btnScopeZoomIn.addEventListener('click', () => scope.stepZoom(1));
btnScopeCursors.addEventListener('click', () => {
  btnScopeCursors.classList.toggle('active', scope.toggleCursors());
});
btnClearTrace.addEventListener('click', () => scope.clear());

function sendUart() {
  const text = uartInput.value;
  if (!text) return;
  uartInput.value = '';
  postJson('/api/input/uart', { text });
}
btnUartSend.addEventListener('click', sendUart);
uartInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') sendUart(); });
window.addEventListener('resize', resizeAll);

// ==================== Arranque y parámetros URL (?mode=breadboard&trace=1&xray=1&fsm=1&snapshot=1) ====================
const params = new URLSearchParams(window.location.search);
if (params.get('mode') === 'breadboard') {
  viewMode = 'breadboard';
  modeSchematicBtn.classList.remove('active');
  modeBreadboardBtn.classList.add('active');
}
if (params.get('fsm') === '1') {
  setDockState('fsm', true);
} else if (params.get('xray') === '1') {
  setDockState('xray', true);
} else if (params.get('trace') === '1') {
  setDockState('trace', true);
} else {
  setDockState('xray', false);
}
const isSnapshot = params.get('snapshot') === '1';

resizeAll();

Promise.all([
  fetch('/api/spec').then(r => r.json()),
  fetch('/api/state').then(r => r.json())
]).then(([sp, st]) => {
  applySpec(sp);
  applyState(st);
  if (isSnapshot) {
    if (params.get('trace') === '1' && st.sessionActive) {
      for (let i = 0; i < 40; i++) {
        const fake = {
          ...st,
          stateSeq: st.stateSeq + i + 1,
          elapsedMs: i * 10,
          pinHigh: { ...st.pinHigh, PB0: ((Math.floor(i / 4)) % 2) === 0, PB5: ((Math.floor(i / 6)) % 2) === 1 }
        };
        scope.record(sp, fake);
      }
    }
    ssePill.textContent = 'simavr conectado';
    ssePill.className = 'sse-status ok';
  }
}).catch(() => {});

if (!isSnapshot) {
  connectSse();
}
