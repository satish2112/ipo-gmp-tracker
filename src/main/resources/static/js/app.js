/**
 * IPO GMP Tracker — Dashboard JS v5
 * Light mode default. Card view on mobile, table on desktop.
 * Type filter (SME / Mainboard). Full WebSocket + REST.
 */
'use strict';

let allIpos      = [];
let activeFilter = 'ALL';  // status filter
let activeType   = null;   // 'EQ' | 'SME' | null
let sortCol      = 'gmp';
let sortDir      = 'desc';
let selectedId   = null;
let stompClient  = null;
let dpChart      = null;
let activeTab    = 'overview';
let toastTimer   = null;

// ══════════════════════════════════════════════════════
// INIT
// ══════════════════════════════════════════════════════
document.addEventListener('DOMContentLoaded', () => {
  initTheme();
  initFilters();
  initSearch();
  initSort();
  initDetailPanel();
  connectWS();
  loadIpos();
});

// ══════════════════════════════════════════════════════
// THEME  —  light is the DEFAULT
// ══════════════════════════════════════════════════════
function initTheme() {
  // Default: light. Only switch if user explicitly saved 'dark' before.
  const saved = localStorage.getItem('ipo-theme') || 'light';
  applyTheme(saved);

  document.getElementById('themeToggle').addEventListener('click', () => {
    const cur = document.documentElement.getAttribute('data-theme');
    applyTheme(cur === 'dark' ? 'light' : 'dark');
  });
}

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  localStorage.setItem('ipo-theme', theme);
  const ic = document.getElementById('themeIcon');
  if (ic) ic.className = theme === 'dark' ? 'bi bi-sun' : 'bi bi-moon';
  // Rebuild chart if open (chart colours are theme-dependent)
  if (dpChart && selectedId && activeTab === 'chart') loadChart(selectedId);
}

const isDark = () => document.documentElement.getAttribute('data-theme') === 'dark';

// ══════════════════════════════════════════════════════
// WEBSOCKET
// ══════════════════════════════════════════════════════
function connectWS() {
  setStatus('Connecting…', false);
  setWsDot(false);

  stompClient = new StompJs.Client({
    webSocketFactory: () => new SockJS('/ws'),
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
  });

  stompClient.onConnect = () => {
    setStatus('Live ✓', true);
    setWsDot(true);
    stompClient.subscribe('/topic/ipos', frame => {
      try { handleWS(JSON.parse(frame.body)); }
      catch(e) { console.error('WS parse:', e); }
    });
  };

  stompClient.onDisconnect = () => { setStatus('Reconnecting…', false); setWsDot(false); };
  stompClient.onStompError = () => { setStatus('Error', false); setWsDot(false); };
  stompClient.activate();
}

function handleWS({ event, data, id }) {
  switch (event) {
    case 'ALL_IPOS':
      allIpos = data;
      hideSkeleton();
      render();
      updateStats();
      setStatus('Updated ' + now(), true);
      break;
    case 'GMP_UPDATED':
    case 'IPO_UPDATED':
      upsert(data);
      toast(data.name + ' GMP ₹' + fmtN(data.gmp, 2), data.gmpTrend);
      setStatus('Updated ' + now(), true);
      break;
    case 'IPO_CREATED':
      upsert(data);
      toast('New IPO: ' + data.name, 'NEUTRAL');
      break;
    case 'IPO_DELETED':
      allIpos = allIpos.filter(i => i.id !== id);
      if (selectedId === id) closeDP();
      render(); updateStats();
      break;
  }
}

// ══════════════════════════════════════════════════════
// REST LOAD
// ══════════════════════════════════════════════════════
async function loadIpos() {
  try {
    const r = await fetch('/api/ipos');
    const j = await r.json();
    if (j.success) {
      allIpos = j.data;
      hideSkeleton();
      render();
      updateStats();
      setStatus('Updated ' + now(), true);
    }
  } catch(e) { console.error('Fetch error:', e); }
}

// ══════════════════════════════════════════════════════
// DATA
// ══════════════════════════════════════════════════════
function upsert(ipo) {
  const i = allIpos.findIndex(x => x.id === ipo.id);
  if (i >= 0) {
    allIpos[i] = ipo;
    render();
    setTimeout(() => flashRow(ipo.id, ipo.gmpTrend), 30);
  } else {
    allIpos.unshift(ipo);
    render();
  }
  updateStats();
  if (selectedId === ipo.id) renderDP(ipo);
}

function hideSkeleton() {
  document.querySelectorAll('.skel-row, .ssr').forEach(r => r.remove());
}

// ══════════════════════════════════════════════════════
// RENDER — table (desktop) + cards (mobile)
// ══════════════════════════════════════════════════════
function render() {
  const list = filtered();

  // ── Table body ──
  const tbody = document.getElementById('ipoTableBody');
  if (!list.length) {
    tbody.innerHTML = `<tr class="empty-row"><td colspan="7" style="text-align:center;padding:60px 20px;color:var(--tx2)">
      <i class="bi bi-inbox" style="font-size:2rem;display:block;margin-bottom:10px;opacity:.2"></i>
      No IPOs match this filter.
    </td></tr>`;
  } else {
    tbody.innerHTML = list.map(buildRow).join('');
    tbody.querySelectorAll('.irow').forEach(r =>
      r.addEventListener('click', () => openDP(r.dataset.id))
    );
    if (selectedId) {
      const sel = tbody.querySelector(`.irow[data-id="${selectedId}"]`);
      if (sel) sel.classList.add('sel');
    }
  }

  // ── Card grid ──
  const grid = document.getElementById('cardGrid');
  grid.innerHTML = list.length
    ? list.map(buildCard).join('')
    : `<div style="text-align:center;padding:40px;color:var(--tx2)">No IPOs found.</div>`;

  grid.querySelectorAll('.ipo-card').forEach(c =>
    c.addEventListener('click', () => openDP(c.dataset.id))
  );
  if (selectedId) {
    const sc = grid.querySelector(`.ipo-card[data-id="${selectedId}"]`);
    if (sc) sc.classList.add('sel');
  }

  document.getElementById('rowCount').textContent =
    `${list.length} of ${allIpos.length} IPOs`;
}

// ── Table row ──
function buildRow(ipo) {
  const g    = ipo.gmp ?? 0;
  const gCls = g >= 0 ? 'g fw' : 'r fw';
  const pct  = ipo.gmpPercentage != null ? fmt(ipo.gmpPercentage, 2) + '%' : '—';
  const pCls = (ipo.gmpPercentage ?? 0) >= 0 ? 'g' : 'r';
  const est  = ipo.expectedListingPrice != null ? '₹' + fmtN(ipo.expectedListingPrice, 0) : '—';

  // Heat class on GMP cell
  const p = ipo.gmpPercentage ?? 0;
  const heat = p >= 20 ? 'heat-hi' : p >= 5 ? 'heat-mid' : p < 0 ? 'heat-neg' : '';

  // Trend arrow
  const arrow = ipo.gmpTrend === 'UP'
    ? '<i class="bi bi-arrow-up arrow-up"></i>'
    : ipo.gmpTrend === 'DOWN'
    ? '<i class="bi bi-arrow-down arrow-down"></i>'
    : '';

  // Daily change
  let dayChg = '';
  if (ipo.dailyGmpChange != null && Math.abs(ipo.dailyGmpChange) > 0.01) {
    const sign = ipo.dailyGmpChange > 0 ? '+' : '';
    const dc   = ipo.dailyGmpChange > 0 ? 'g' : 'r';
    dayChg = `<span class="${dc}" style="font-size:10px;margin-left:5px">${sign}₹${fmtN(Math.abs(ipo.dailyGmpChange), 2)}</span>`;
  }

  return `<tr class="irow" data-id="${ipo.id}">
    <td class="td-name">
      <div style="display:flex;align-items:center;gap:4px">
        <span class="iname">${esc(ipo.name)}</span>${dayChg}
      </div>
      <div class="sparkbar">${sparks(ipo)}</div>
    </td>
    <td class="th-r ${gCls} ${heat}">${arrow}${g >= 0 ? '+' : ''}₹${fmtN(Math.abs(g), 2)}</td>
    <td class="th-r ${pCls}">${pct}</td>
    <td class="th-r muted hide-sm">₹${fmtN(ipo.issuePrice, 0)}</td>
    <td class="th-r fw">${est}</td>
    <td class="th-r muted hide-md">${ipo.kostakRate != null ? '₹' + fmtN(ipo.kostakRate, 0) : '—'}</td>
    <td class="th-c hide-sm">${bdg(ipo.status)}</td>
  </tr>`;
}

// ── Mobile card ──
function buildCard(ipo) {
  const g    = ipo.gmp ?? 0;
  const gCls = g >= 0 ? 'g' : 'r';
  const sign = g >= 0 ? '+' : '-';
  const pct  = ipo.gmpPercentage != null ? fmt(ipo.gmpPercentage, 2) + '%' : '—';
  const est  = ipo.expectedListingPrice != null ? '₹' + fmtN(ipo.expectedListingPrice, 0) : '—';
  const pcls = g >= 0 ? 'card-pos' : 'card-neg';

  const arrow = g >= 0
    ? '<i class="bi bi-arrow-up-short" style="font-size:16px"></i>'
    : '<i class="bi bi-arrow-down-short" style="font-size:16px"></i>';

  return `<div class="ipo-card ${pcls}" data-id="${ipo.id}">
    <div class="card-top">
      <div>
        <div class="card-name">${esc(ipo.name)}</div>
        <div class="card-type">${ipo.type || 'EQ'} · Mainboard</div>
      </div>
      <div class="card-gmp">
        <div class="card-gmp-val ${gCls}">${arrow}${sign}₹${fmtN(Math.abs(g), 2)}</div>
        <div class="card-gmp-pct ${gCls}">${pct}</div>
      </div>
    </div>
    <div class="card-row">
      <div class="card-kv">
        <div class="card-kv-l">Price Band</div>
        <div class="card-kv-v">₹${fmtN(ipo.issuePrice, 0)}</div>
      </div>
      <div class="card-kv">
        <div class="card-kv-l">Est. Listing</div>
        <div class="card-kv-v fw">${est}</div>
      </div>
      <div class="card-kv">
        <div class="card-kv-l">Kostak</div>
        <div class="card-kv-v">${ipo.kostakRate != null ? '₹' + fmtN(ipo.kostakRate, 0) : '—'}</div>
      </div>
    </div>
    <div class="card-foot">
      ${bdg(ipo.status)}
      <span style="font-size:11px;color:var(--tx3)">Tap for details</span>
    </div>
  </div>`;
}

// ── Sparkbars ──
function sparks(ipo) {
  if (!ipo.gmp || !ipo.issuePrice) return '';
  const b  = ipo.issuePrice * 0.018;
  const g  = ipo.gmp;
  const vs = [g - b*2.5, g - b*1.5, g - b*0.7, g, g + b*0.4, g + b*0.8, g];
  const mx = Math.max(...vs.map(Math.abs), 1);
  return vs.map(v => {
    const h = Math.max(3, Math.round((Math.abs(v) / mx) * 10));
    const c = v >= 0 ? 'var(--g)' : 'var(--r)';
    return `<span class="spark" style="height:${h}px;background:${c};opacity:.7"></span>`;
  }).join('');
}

// ── Badge ──
function bdg(s) {
  const m = { OPEN:'bdg-open', UPCOMING:'bdg-upcoming', CLOSED:'bdg-closed', LISTED:'bdg-listed' };
  return `<span class="badge ${m[s] || 'bdg-closed'}">${s || '—'}</span>`;
}

// ══════════════════════════════════════════════════════
// STATS
// ══════════════════════════════════════════════════════
function updateStats() {
  const active = allIpos.filter(i => i.status === 'OPEN' || i.status === 'UPCOMING');
  const gmps   = allIpos.map(i => i.gmp ?? 0);
  const avg    = gmps.length ? Math.round(gmps.reduce((a, b) => a + b, 0) / gmps.length) : 0;
  const top    = [...allIpos].sort((a, b) => (b.gmp ?? 0) - (a.gmp ?? 0))[0];

  document.getElementById('m-total').textContent = allIpos.length;
  document.getElementById('m-open').textContent  = active.length;
  document.getElementById('m-avg').textContent   = (avg >= 0 ? '₹' : '-₹') + Math.abs(avg);

  if (top) {
    const name = top.name.length > 16 ? top.name.split(' ').slice(0, 2).join(' ') + '…' : top.name;
    document.getElementById('m-top').textContent    = name;
    document.getElementById('m-top').title          = top.name;
    document.getElementById('m-top-gmp').textContent = '₹' + fmtN(top.gmp, 2) + ' GMP';
  }
}

// ══════════════════════════════════════════════════════
// FILTERS
// ══════════════════════════════════════════════════════
function initFilters() {
  // Status tabs
  document.querySelectorAll('.ctab:not(.ctab--type)').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.ctab:not(.ctab--type)').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      activeFilter = btn.dataset.filter;
      clearSearch();
      render();
    });
  });

  // Type tabs (Mainboard / SME)
  document.querySelectorAll('.ctab--type').forEach(btn => {
    btn.addEventListener('click', () => {
      const already = btn.classList.contains('active');
      document.querySelectorAll('.ctab--type').forEach(b => b.classList.remove('active'));
      if (!already) {
        btn.classList.add('active');
        activeType = btn.dataset.type;
      } else {
        activeType = null;
      }
      render();
    });
  });
}

function initSearch() {
  const inp = document.getElementById('searchInput');
  const clr = document.getElementById('searchClear');

  inp.addEventListener('input', () => {
    const q = inp.value.trim();
    clr.style.display = q ? 'flex' : 'none';
    render();
  });

  clr.addEventListener('click', () => clearSearch());
}

function clearSearch() {
  const inp = document.getElementById('searchInput');
  const clr = document.getElementById('searchClear');
  if (inp) inp.value = '';
  if (clr) clr.style.display = 'none';
}

function initSort() {
  document.querySelectorAll('.sortable').forEach(th => {
    th.addEventListener('click', () => {
      const col = th.dataset.col;
      if (!col) return;
      if (sortCol === col) {
        sortDir = sortDir === 'asc' ? 'desc' : 'asc';
      } else {
        sortCol = col;
        sortDir = 'desc';
      }
      // Update sort icons
      document.querySelectorAll('.si').forEach(i => {
        i.className = 'bi bi-chevron-expand si'; i.style.opacity = '.4';
      });
      const ic = th.querySelector('.si');
      if (ic) {
        ic.className = sortDir === 'asc' ? 'bi bi-chevron-up si' : 'bi bi-chevron-down si';
        ic.style.opacity = '1';
      }
      render();
    });
  });
}

function filtered() {
  const q = document.getElementById('searchInput')?.value?.toLowerCase().trim() || '';
  let data = [...allIpos];

  // Search
  if (q) { data = data.filter(i => i.name.toLowerCase().includes(q)); }
  // Status filter
  else if (activeFilter !== 'ALL') { data = data.filter(i => i.status === activeFilter); }
  // Type filter
  if (activeType) { data = data.filter(i => (i.type || 'EQ') === activeType); }

  // Sort
  data.sort((a, b) => {
    let av = a[sortCol], bv = b[sortCol];
    if (typeof av === 'string') av = av.toLowerCase();
    if (typeof bv === 'string') bv = bv.toLowerCase();
    if (av == null) return 1;
    if (bv == null) return -1;
    return sortDir === 'asc' ? (av > bv ? 1 : -1) : (av < bv ? 1 : -1);
  });

  return data;
}

// ══════════════════════════════════════════════════════
// DETAIL PANEL
// ══════════════════════════════════════════════════════
function initDetailPanel() {
  document.getElementById('dpClose').addEventListener('click', closeDP);
  document.querySelectorAll('.dptab').forEach(t =>
    t.addEventListener('click', () => switchTab(t.dataset.tab))
  );
}

function openDP(id) {
  selectedId = id;
  const ipo = allIpos.find(i => i.id === id);
  if (!ipo) return;

  // Highlight rows + cards
  document.querySelectorAll('.irow, .ipo-card').forEach(r => r.classList.remove('sel'));
  document.querySelector(`.irow[data-id="${id}"]`)?.classList.add('sel');
  document.querySelector(`.ipo-card[data-id="${id}"]`)?.classList.add('sel');

  document.getElementById('dp-empty').style.display = 'none';
  renderDP(ipo);
  document.getElementById('detailPane').classList.add('open');
  document.getElementById('listPanel').classList.add('shifted');
  switchTab(activeTab);
}

function renderDP(ipo) {
  const g    = ipo.gmp ?? 0;
  const cls  = g >= 0 ? 'g' : 'r';
  const sign = g >= 0 ? '+' : '';
  const pct  = ipo.gmpPercentage != null ? (g >= 0 ? '+' : '') + fmt(ipo.gmpPercentage, 2) + '%' : '—';
  const est  = ipo.expectedListingPrice != null ? '₹' + fmtN(ipo.expectedListingPrice, 0) : '—';

  document.getElementById('dp-name').textContent    = ipo.name;
  document.getElementById('dp-badge').innerHTML     = bdg(ipo.status);
  document.getElementById('dp-gmp').innerHTML       = `<span class="${cls}">${sign}₹${fmtN(Math.abs(g), 2)}</span>`;
  document.getElementById('dp-pct').innerHTML       = `<span class="${cls}">${pct}</span>`;
  document.getElementById('dp-listing').textContent = est;
  document.getElementById('dp-kostak').textContent  = ipo.kostakRate != null ? '₹' + fmtN(ipo.kostakRate, 0) : '—';
  document.getElementById('dp-registrar').textContent = ipo.registrar || '—';

  // Key dates
  const dates = [];
  if (ipo.openDate)    dates.push(`Open: <strong>${fmtDate(ipo.openDate)}</strong>`);
  if (ipo.closeDate)   dates.push(`Close: <strong>${fmtDate(ipo.closeDate)}</strong>`);
  if (ipo.listingDate) dates.push(`Listing: <strong>${fmtDate(ipo.listingDate)}</strong>`);
  document.getElementById('dp-dates').innerHTML = dates.join('<br>') || '—';

  // Today's movement
  let mv = '';
  if (ipo.dailyOpenGmp != null && ipo.dailyGmpChange != null) {
    const s2 = ipo.dailyGmpChange > 0 ? '+' : '';
    const c2 = ipo.dailyGmpChange > 0 ? 'g' : ipo.dailyGmpChange < 0 ? 'r' : 'muted';
    mv = `<div class="dp-sec">
      <div class="dp-sec-hd">Today's Movement</div>
      <div class="dp-move">
        <span class="muted" style="font-size:12px">Open</span>
        <strong>₹${fmtN(ipo.dailyOpenGmp, 2)}</strong>
        <span class="muted" style="font-size:11px">→</span>
        <strong class="${c2}">${s2}₹${fmtN(Math.abs(ipo.dailyGmpChange), 2)}</strong>
      </div>
    </div>`;
  }
  document.getElementById('dp-movement').innerHTML = mv;

  // Financials table
  const rows = [
    ['Issue Price',    '₹' + fmtN(ipo.issuePrice, 0)],
    ['GMP',            `<span class="${cls}">${sign}₹${fmtN(Math.abs(g), 2)}</span>`],
    ['Daily Open GMP', ipo.dailyOpenGmp != null ? '₹' + fmtN(ipo.dailyOpenGmp, 2) : '—'],
    ['Est. Listing',   est],
    ['GMP %',          `<span class="${cls}">${pct}</span>`],
    ['Kostak Rate',    ipo.kostakRate != null ? '₹' + fmtN(ipo.kostakRate, 0) : '—'],
    ['Subj. to Sauda', ipo.subjectToSauda != null ? '₹' + fmtN(ipo.subjectToSauda, 0) : '—'],
    ['Lot Size',       ipo.lotSize != null ? ipo.lotSize + ' shares' : '—'],
    ['Issue Size',     ipo.issueSize != null ? '₹' + fmtN(ipo.issueSize, 0) + ' Cr' : '—'],
    ['Last Updated',   ipo.lastUpdated ? new Date(ipo.lastUpdated).toLocaleTimeString('en-IN') : '—'],
  ];
  document.getElementById('dp-fin').innerHTML =
    rows.map(([k, v]) => `<tr><td>${k}</td><td>${v}</td></tr>`).join('');

  if (activeTab === 'chart') loadChart(ipo.id);
}

function closeDP() {
  selectedId = null;
  document.getElementById('detailPane').classList.remove('open');
  document.getElementById('listPanel').classList.remove('shifted');
  document.querySelectorAll('.irow, .ipo-card').forEach(r => r.classList.remove('sel'));
  document.getElementById('dp-empty').style.display = '';
  if (dpChart) { dpChart.destroy(); dpChart = null; }
}

function switchTab(tab) {
  activeTab = tab;
  ['overview', 'financials', 'chart'].forEach(t => {
    document.getElementById('tab-' + t).classList.toggle('hidden', t !== tab);
  });
  document.querySelectorAll('.dptab').forEach(b =>
    b.classList.toggle('active', b.dataset.tab === tab)
  );
  if (tab === 'chart' && selectedId) loadChart(selectedId);
}

// ══════════════════════════════════════════════════════
// CHART
// ══════════════════════════════════════════════════════
async function loadChart(ipoId) {
  const ctx = document.getElementById('dp-chart');
  if (!ctx) return;
  if (dpChart) { dpChart.destroy(); dpChart = null; }

  const info = document.getElementById('chart-info');
  if (info) info.textContent = 'Loading…';

  try {
    const r = await fetch(`/api/ipos/${ipoId}/history?days=7`);
    const j = await r.json();

    if (!j.success || !j.data?.length) {
      const ipo = allIpos.find(i => i.id === ipoId);
      if (ipo) buildSimChart(ipo);
      return;
    }

    const labels = j.data.map(e => {
      const d = new Date(e.recordedAt);
      return d.toLocaleDateString('en-IN', { month:'short', day:'numeric' }) + ' ' +
             d.toLocaleTimeString('en-IN', { hour:'2-digit', minute:'2-digit', hour12:false });
    });
    const vals = j.data.map(e => e.gmp);
    const ipo  = allIpos.find(i => i.id === ipoId);

    drawChart(ctx, labels, vals, (ipo?.gmp ?? 0) >= 0);
    if (info) info.textContent = `${j.data.length} data points`;

  } catch(e) {
    const ipo = allIpos.find(i => i.id === ipoId);
    if (ipo) buildSimChart(ipo);
  }
}

function buildSimChart(ipo) {
  const ctx  = document.getElementById('dp-chart');
  const g    = ipo.gmp ?? 0;
  const b    = (ipo.issuePrice ?? 100) * 0.012;
  const days = ['D-7','D-6','D-5','D-4','D-3','D-2','D-1','Today'];
  const mods = [0.80, 0.86, 0.91, 0.95, 0.98, 1.02, 1.0, 1.0];
  const vals = mods.map(m => Math.round(g * m + (Math.random() - 0.5) * b));
  const info = document.getElementById('chart-info');
  if (info) info.textContent = 'Simulated (no history yet)';
  drawChart(ctx, days, vals, g >= 0);
}

function drawChart(ctx, labels, vals, pos) {
  const dark  = isDark();
  const line  = pos ? (dark ? '#3fb950' : '#059669') : (dark ? '#f85149' : '#dc2626');
  const fill  = pos ? 'rgba(5,150,105,.08)' : 'rgba(220,38,38,.08)';
  const grid  = dark ? 'rgba(255,255,255,.04)' : 'rgba(0,0,0,.05)';
  const tick  = dark ? '#8b949e' : '#64748b';

  dpChart = new Chart(ctx, {
    type: 'line',
    data: {
      labels,
      datasets: [{
        data: vals, borderColor: line, backgroundColor: fill,
        fill: true, tension: 0.4, pointRadius: 3,
        pointBackgroundColor: line, borderWidth: 2,
      }]
    },
    options: {
      responsive: true, maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        x: { ticks: { font:{size:10}, color:tick, maxRotation:45 }, grid: { color:grid } },
        y: { ticks: { font:{size:10}, color:tick, callback: v => '₹'+v }, grid: { color:grid } }
      }
    }
  });
}

// ══════════════════════════════════════════════════════
// FLASH + TOAST
// ══════════════════════════════════════════════════════
function flashRow(id, trend) {
  const cls = trend === 'UP' ? 'flash-up' : trend === 'DOWN' ? 'flash-down' : '';
  if (!cls) return;
  [`.irow[data-id="${id}"]`, `.ipo-card[data-id="${id}"]`].forEach(sel => {
    const el = document.querySelector(sel);
    if (!el) return;
    el.classList.remove('flash-up', 'flash-down');
    void el.offsetWidth;
    el.classList.add(cls);
    setTimeout(() => el.classList.remove(cls), 1400);
  });
}

function toast(msg, trend) {
  const el   = document.getElementById('toast');
  const icon = document.getElementById('toast-icon');
  document.getElementById('toast-msg').textContent = msg;
  if (icon) {
    icon.className = trend === 'UP'
      ? 'bi bi-arrow-up-circle-fill'
      : trend === 'DOWN'
      ? 'bi bi-arrow-down-circle-fill'
      : 'bi bi-plus-circle-fill';
    icon.style.color = trend === 'UP' ? 'var(--g)' : trend === 'DOWN' ? 'var(--r)' : 'var(--accent)';
  }
  el.style.display = 'flex';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.style.display = 'none', 3500);
}

// ══════════════════════════════════════════════════════
// UI HELPERS
// ══════════════════════════════════════════════════════
function setStatus(txt, ok) {
  const el = document.getElementById('lastRefreshTime');
  if (!el) return;
  el.textContent = txt;
  el.style.color = ok ? 'var(--g)' : 'var(--tx2)';
}

function setWsDot(live) {
  document.getElementById('wsDot')?.classList.toggle('live', live);
}

// ══════════════════════════════════════════════════════
// UTILS
// ══════════════════════════════════════════════════════
const fmt     = (v, d = 2) => v == null ? '—' : Number(v).toFixed(d);
const fmtN    = (v, d = 0) => v == null ? '—' : Number(v).toLocaleString('en-IN', { minimumFractionDigits:d, maximumFractionDigits:d });
const fmtDate = iso => iso ? new Date(iso).toLocaleDateString('en-IN', { day:'numeric', month:'short', year:'numeric' }) : '—';
const esc     = s => s ? s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;') : '';
const now     = () => new Date().toLocaleTimeString('en-IN', { hour12:false });
