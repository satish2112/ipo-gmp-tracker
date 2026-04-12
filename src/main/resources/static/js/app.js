/**
 * IPO GMP Tracker — Dashboard JS v3
 * Reads all data from MongoDB via REST + WebSocket.
 * Detail panel chart loads real GMP history from /api/ipos/{id}/history
 */
'use strict';

let allIpos       = [];
let currentFilter = 'ALL';
let currentSort   = { col: 'gmp', dir: 'desc' };
let selectedId    = null;
let stompClient   = null;
let dpChart       = null;
let activeTab     = 'overview';
let toastTimer    = null;

document.addEventListener('DOMContentLoaded', () => {
  setupFilters();
  setupSearch();
  setupSort();
  setupDetailPanel();
  connectWebSocket();
  fetchAllIpos();
});

// ══════════════════════════════════════════════════════════════
// WEBSOCKET
// ══════════════════════════════════════════════════════════════
function connectWebSocket() {
  setStatus('Connecting…', false);
  const sock = new SockJS('/ws');
  stompClient = new StompJs.Client({
    webSocketFactory: () => sock,
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
  });

  stompClient.onConnect = () => {
    setStatus('Live ✓', true);
    stompClient.subscribe('/topic/ipos', frame => {
      try { handleWsMessage(JSON.parse(frame.body)); }
      catch(e) { console.error('WS parse:', e); }
    });
  };

  stompClient.onDisconnect = () => setStatus('Reconnecting…', false);
  stompClient.onStompError = () => setStatus('Connection error', false);
  stompClient.activate();
}

function handleWsMessage(msg) {
  const { event, data, id } = msg;
  switch (event) {
    case 'ALL_IPOS':
      allIpos = data;
      render();
      updateStats();
      setStatus('Updated ' + timeStr(), true);
      break;
    case 'GMP_UPDATED':
    case 'IPO_UPDATED':
      upsertIpo(data);
      showToast(data.name + ' — GMP ₹' + data.gmp, data.gmpTrend);
      setStatus('Updated ' + timeStr(), true);
      break;
    case 'IPO_CREATED':
      upsertIpo(data);
      showToast('New IPO added: ' + data.name, 'NEUTRAL');
      break;
    case 'IPO_DELETED':
      allIpos = allIpos.filter(i => i.id !== id);
      if (selectedId === id) closeDetail();
      render();
      updateStats();
      break;
  }
}

// ══════════════════════════════════════════════════════════════
// REST — initial load (data always served from MongoDB)
// ══════════════════════════════════════════════════════════════
async function fetchAllIpos() {
  try {
    const r = await fetch('/api/ipos');
    const j = await r.json();
    if (j.success) {
      allIpos = j.data;
      render();
      updateStats();
      setStatus('Updated ' + timeStr(), true);
    }
  } catch(e) { console.error('REST fetch:', e); }
}

// ══════════════════════════════════════════════════════════════
// DATA
// ══════════════════════════════════════════════════════════════
function upsertIpo(ipo) {
  const idx = allIpos.findIndex(i => i.id === ipo.id);
  if (idx >= 0) {
    allIpos[idx] = ipo;
    render();
    setTimeout(() => flashRow(ipo.id, ipo.gmpTrend), 30);
  } else {
    allIpos.unshift(ipo);
    render();
  }
  updateStats();
  if (selectedId === ipo.id) renderDetailPanel(ipo);
}

// ══════════════════════════════════════════════════════════════
// TABLE RENDER
// ══════════════════════════════════════════════════════════════
function render() {
  const tbody = document.getElementById('ipoTableBody');
  const list  = filteredSorted();

  if (!list.length) {
    tbody.innerHTML = `<tr class="empty-row"><td colspan="7">
      <i class="bi bi-inbox" style="font-size:1.5rem;display:block;margin-bottom:8px;opacity:.3"></i>
      No IPOs match the current filter.
    </td></tr>`;
    document.getElementById('rowCount').textContent = '0 results';
    return;
  }

  tbody.innerHTML = list.map(buildRow).join('');

  document.querySelectorAll('.ipo-row').forEach(row =>
    row.addEventListener('click', () => openDetail(row.dataset.id))
  );

  if (selectedId) {
    const r = document.querySelector(`.ipo-row[data-id="${selectedId}"]`);
    if (r) r.classList.add('selected');
  }

  document.getElementById('rowCount').textContent =
    `${list.length} of ${allIpos.length} IPOs`;
}

function buildRow(ipo) {
  const g     = ipo.gmp ?? 0;
  const gCls  = g >= 0 ? 'green fw' : 'red fw';
  const gSign = g >= 0 ? '+' : '';
  const pct   = ipo.gmpPercentage != null ? fmt(ipo.gmpPercentage, 2) + '%' : '—';
  const pCls  = (ipo.gmpPercentage ?? 0) >= 0 ? 'green' : 'red';
  const est   = ipo.expectedListingPrice != null ? '₹' + fmtN(ipo.expectedListingPrice, 0) : '—';

  // Daily change badge
  let dayChg = '';
  if (ipo.dailyGmpChange != null && ipo.dailyGmpChange !== 0) {
    const sign = ipo.dailyGmpChange > 0 ? '+' : '';
    const dc   = ipo.dailyGmpChange > 0 ? 'green' : 'red';
    dayChg = `<span class="${dc}" style="font-size:10px;margin-left:4px">${sign}₹${fmtN(Math.abs(ipo.dailyGmpChange), 2)}</span>`;
  }

  return `<tr class="ipo-row" data-id="${ipo.id}">
    <td class="col-name-cell">
      <div style="display:flex;align-items:center;gap:4px">
        <span class="ipo-name">${esc(ipo.name)}</span>${dayChg}
      </div>
      <div class="sparkbar-row">${sparkBars(ipo)}</div>
    </td>
    <td class="col-num ${gCls}">${gSign}₹${fmtN(Math.abs(g), 2)}</td>
    <td class="col-num ${pCls}">${pct}</td>
    <td class="col-num muted hide-sm">₹${fmtN(ipo.issuePrice, 0)}</td>
    <td class="col-num fw">${est}</td>
    <td class="col-num muted hide-md">${ipo.kostakRate != null ? '₹' + fmtN(ipo.kostakRate, 0) : '—'}</td>
    <td class="col-center hide-sm">${badge(ipo.status)}</td>
  </tr>`;
}

function sparkBars(ipo) {
  if (!ipo.gmp || !ipo.issuePrice) return '';
  const b  = ipo.issuePrice * 0.018;
  const g  = ipo.gmp;
  const vs = [g-b*2.5, g-b*1.5, g-b*0.5, g, g+b*0.4, g+b*0.7, g];
  const mx = Math.max(...vs.map(Math.abs), 1);
  return vs.map(v => {
    const h  = Math.max(3, Math.round((Math.abs(v)/mx) * 13));
    const c  = v >= 0 ? 'var(--green)' : 'var(--red)';
    return `<span class="spark" style="height:${h}px;background:${c}"></span>`;
  }).join('');
}

function badge(s) {
  const m = { OPEN:'badge-open', UPCOMING:'badge-upcoming', CLOSED:'badge-closed', LISTED:'badge-listed' };
  return `<span class="badge ${m[s]||'badge-closed'}">${s||'—'}</span>`;
}

// ══════════════════════════════════════════════════════════════
// DETAIL PANEL
// ══════════════════════════════════════════════════════════════
function setupDetailPanel() {
  document.getElementById('closeDetail').addEventListener('click', closeDetail);
  document.querySelectorAll('.dp-tab').forEach(t =>
    t.addEventListener('click', () => switchTab(t.dataset.tab))
  );
}

function openDetail(id) {
  selectedId = id;
  const ipo  = allIpos.find(i => i.id === id);
  if (!ipo) return;

  document.querySelectorAll('.ipo-row').forEach(r => r.classList.remove('selected'));
  const row = document.querySelector(`.ipo-row[data-id="${id}"]`);
  if (row) row.classList.add('selected');

  renderDetailPanel(ipo);
  document.getElementById('detailPanel').classList.add('open');
  document.getElementById('tableSection').classList.add('panel-open');
  switchTab(activeTab);
}

function renderDetailPanel(ipo) {
  const g    = ipo.gmp ?? 0;
  const cls  = g >= 0 ? 'green' : 'red';
  const sign = g >= 0 ? '+' : '';
  const pct  = ipo.gmpPercentage != null ? (g>=0?'+':'')+fmt(ipo.gmpPercentage,2)+'%' : '—';
  const est  = ipo.expectedListingPrice != null ? '₹'+fmtN(ipo.expectedListingPrice,0) : '—';

  // Daily open change
  let dayChgHtml = '';
  if (ipo.dailyOpenGmp != null && ipo.dailyGmpChange != null) {
    const s2  = ipo.dailyGmpChange > 0 ? '+' : '';
    const c2  = ipo.dailyGmpChange > 0 ? 'green' : ipo.dailyGmpChange < 0 ? 'red' : 'muted';
    dayChgHtml = `<div class="dp-section">
      <div class="dp-section-title">Today's Movement</div>
      <div style="font-size:13px">
        Open: <strong>₹${fmtN(ipo.dailyOpenGmp,2)}</strong> &nbsp;→&nbsp;
        Now: <strong class="${c2}">${s2}₹${fmtN(Math.abs(ipo.dailyGmpChange),2)}</strong>
      </div>
    </div>`;
  }

  document.getElementById('dp-name').textContent = ipo.name;
  document.getElementById('dp-badge').innerHTML  = badge(ipo.status);
  document.getElementById('dp-gmp').innerHTML    = `<span class="${cls}">${sign}₹${fmtN(Math.abs(g),2)}</span>`;
  document.getElementById('dp-pct').innerHTML    = `<span class="${cls}">${pct}</span>`;
  document.getElementById('dp-listing').textContent = est;
  document.getElementById('dp-kostak').textContent  = ipo.kostakRate != null ? '₹'+fmtN(ipo.kostakRate,0) : '—';
  document.getElementById('dp-registrar').textContent = ipo.registrar || '—';

  // Dates
  const dates = [];
  if (ipo.openDate)    dates.push(`Open: <strong>${fmtDate(ipo.openDate)}</strong>`);
  if (ipo.closeDate)   dates.push(`Close: <strong>${fmtDate(ipo.closeDate)}</strong>`);
  if (ipo.listingDate) dates.push(`Listing: <strong>${fmtDate(ipo.listingDate)}</strong>`);
  document.getElementById('dp-dates').innerHTML = dates.length ? dates.join('<br>') : '—';

  // Today's movement section
  document.getElementById('dp-day-change').innerHTML = dayChgHtml;

  // Financials tab
  const rows = [
    ['Issue Price',      '₹'+fmtN(ipo.issuePrice,0)],
    ['Current GMP',      `<span class="${cls}">${sign}₹${fmtN(Math.abs(g),2)}</span>`],
    ['Daily Open GMP',   ipo.dailyOpenGmp!=null ? '₹'+fmtN(ipo.dailyOpenGmp,2) : '—'],
    ['Est. Listing',     est],
    ['GMP %',            `<span class="${cls}">${pct}</span>`],
    ['Kostak Rate',      ipo.kostakRate!=null ? '₹'+fmtN(ipo.kostakRate,0) : '—'],
    ['Subj. to Sauda',   ipo.subjectToSauda!=null ? '₹'+fmtN(ipo.subjectToSauda,0) : '—'],
    ['Lot Size',         ipo.lotSize!=null ? ipo.lotSize+' shares' : '—'],
    ['Issue Size',       ipo.issueSize!=null ? '₹'+fmtN(ipo.issueSize,0)+' Cr' : '—'],
    ['GMP Recorded',     ipo.gmpRecordedDate || '—'],
    ['Last Updated',     ipo.lastUpdated ? new Date(ipo.lastUpdated).toLocaleTimeString('en-IN') : '—'],
  ];
  document.getElementById('dp-fin-table').innerHTML =
    rows.map(([k,v]) => `<tr><td>${k}</td><td>${v}</td></tr>`).join('');

  if (activeTab === 'chart') loadAndBuildChart(ipo.id);
}

function closeDetail() {
  selectedId = null;
  document.getElementById('detailPanel').classList.remove('open');
  document.getElementById('tableSection').classList.remove('panel-open');
  document.querySelectorAll('.ipo-row').forEach(r => r.classList.remove('selected'));
  if (dpChart) { dpChart.destroy(); dpChart = null; }
}

function switchTab(tab) {
  activeTab = tab;
  ['overview','financials','chart'].forEach(t => {
    document.getElementById('tab-'+t).classList.toggle('hidden', t !== tab);
  });
  document.querySelectorAll('.dp-tab').forEach(b =>
    b.classList.toggle('active', b.dataset.tab === tab)
  );
  if (tab === 'chart' && selectedId) {
    loadAndBuildChart(selectedId);
  }
}

// ── Real history chart from MongoDB via REST ───────────────────────────────
async function loadAndBuildChart(ipoId) {
  const ctx = document.getElementById('dp-chart');
  if (!ctx) return;

  if (dpChart) { dpChart.destroy(); dpChart = null; }

  const label = document.getElementById('chart-loading');
  if (label) label.textContent = 'Loading history…';

  try {
    const r = await fetch(`/api/ipos/${ipoId}/history?days=7`);
    const j = await r.json();

    if (!j.success || !j.data || j.data.length === 0) {
      // Fallback: simulate from current GMP
      const ipo = allIpos.find(i => i.id === ipoId);
      if (ipo) buildSimulatedChart(ipo);
      return;
    }

    // Build chart from real MongoDB history
    const entries = j.data;
    const labels  = entries.map(e => {
      const d = new Date(e.recordedAt);
      return d.toLocaleDateString('en-IN',{month:'short',day:'numeric'}) +
             ' ' + d.toLocaleTimeString('en-IN',{hour:'2-digit',minute:'2-digit',hour12:false});
    });
    const vals    = entries.map(e => e.gmp);
    const ipo     = allIpos.find(i => i.id === ipoId);
    const g       = ipo?.gmp ?? 0;

    buildChart(ctx, labels, vals, g >= 0);
    if (label) label.textContent = `${entries.length} data points from MongoDB`;

  } catch(e) {
    console.error('History fetch failed:', e);
    const ipo = allIpos.find(i => i.id === ipoId);
    if (ipo) buildSimulatedChart(ipo);
  }
}

function buildSimulatedChart(ipo) {
  const ctx  = document.getElementById('dp-chart');
  const g    = ipo.gmp ?? 0;
  const b    = (ipo.issuePrice ?? 100) * 0.012;
  const days = ['D-7','D-6','D-5','D-4','D-3','D-2','D-1','Today'];
  const mods = [0.80, 0.86, 0.91, 0.95, 0.98, 1.02, 1.0, 1.0];
  const vals = mods.map(m => Math.round(g*m + (Math.random()-0.5)*b));

  const lbl = document.getElementById('chart-loading');
  if (lbl) lbl.textContent = 'Simulated trend (insufficient history)';

  buildChart(ctx, days, vals, g >= 0);
}

function buildChart(ctx, labels, vals, isPositive) {
  const dark    = window.matchMedia('(prefers-color-scheme: dark)').matches;
  const lineClr = isPositive ? (dark?'#9fe1cb':'#27500a') : (dark?'#f09595':'#a32d2d');
  const fillClr = isPositive ? 'rgba(99,153,34,0.1)' : 'rgba(226,75,74,0.1)';
  const gridClr = dark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.05)';
  const tickClr = dark ? '#8b94a8' : '#6b7280';

  dpChart = new Chart(ctx, {
    type: 'line',
    data: {
      labels,
      datasets: [{
        data: vals, borderColor: lineClr, backgroundColor: fillClr,
        fill: true, tension: 0.4, pointRadius: 3,
        pointBackgroundColor: lineClr, borderWidth: 2,
      }]
    },
    options: {
      responsive: true, maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        x: { ticks: { font:{size:10}, color:tickClr, maxRotation:45 }, grid:{color:gridClr} },
        y: { ticks: { font:{size:10}, color:tickClr, callback: v => '₹'+v }, grid:{color:gridClr} }
      }
    }
  });
}

// ══════════════════════════════════════════════════════════════
// STATS
// ══════════════════════════════════════════════════════════════
function updateStats() {
  const active = allIpos.filter(i => i.status==='OPEN'||i.status==='UPCOMING');
  const gmps   = allIpos.map(i => i.gmp ?? 0);
  const avg    = gmps.length ? Math.round(gmps.reduce((a,b)=>a+b,0)/gmps.length) : 0;
  const top    = [...allIpos].sort((a,b)=>(b.gmp??0)-(a.gmp??0))[0];

  document.getElementById('m-total').textContent = allIpos.length;
  document.getElementById('m-open').textContent  = active.length;
  document.getElementById('m-avg').textContent   = (avg>=0?'₹':'-₹')+Math.abs(avg);
  document.getElementById('m-top').textContent   = top ? top.name.split(' ')[0] : '—';
}

// ══════════════════════════════════════════════════════════════
// FILTERS + SORT
// ══════════════════════════════════════════════════════════════
function setupFilters() {
  document.querySelectorAll('.filter-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentFilter = btn.dataset.filter;
      render();
    });
  });
}

function setupSearch() {
  document.getElementById('searchInput').addEventListener('input', () => {
    document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
    currentFilter = '__SEARCH__';
    render();
  });
}

function setupSort() {
  document.querySelectorAll('.sortable').forEach(th => {
    th.addEventListener('click', () => {
      const col = th.dataset.col;
      currentSort = currentSort.col===col
        ? { col, dir: currentSort.dir==='asc'?'desc':'asc' }
        : { col, dir: 'desc' };
      render();
    });
  });
}

function filteredSorted() {
  const q = document.getElementById('searchInput')?.value?.toLowerCase() || '';
  let data = [...allIpos];
  if (q) {
    data = data.filter(i => i.name.toLowerCase().includes(q));
  } else if (currentFilter !== 'ALL' && currentFilter !== '__SEARCH__') {
    data = data.filter(i => i.status === currentFilter);
  }
  data.sort((a,b) => {
    let av = a[currentSort.col], bv = b[currentSort.col];
    if (typeof av === 'string') av = av.toLowerCase();
    if (typeof bv === 'string') bv = bv.toLowerCase();
    if (av==null) return 1; if (bv==null) return -1;
    return currentSort.dir==='asc' ? (av>bv?1:-1) : (av<bv?1:-1);
  });
  return data;
}

// ══════════════════════════════════════════════════════════════
// FLASH + TOAST
// ══════════════════════════════════════════════════════════════
function flashRow(id, trend) {
  const row = document.querySelector(`.ipo-row[data-id="${id}"]`);
  if (!row) return;
  const cls = trend==='UP'?'flash-up':trend==='DOWN'?'flash-down':'';
  if (!cls) return;
  row.classList.remove('flash-up','flash-down');
  void row.offsetWidth;
  row.classList.add(cls);
  setTimeout(() => row.classList.remove(cls), 1300);
}

function showToast(msg, trend) {
  const el = document.getElementById('toast');
  document.getElementById('toast-msg').textContent = msg;
  el.style.display = 'flex';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.style.display='none', 3500);
}

// ══════════════════════════════════════════════════════════════
// UTILS
// ══════════════════════════════════════════════════════════════
const fmt  = (v, d=2) => v==null ? '—' : Number(v).toFixed(d);
const fmtN = (v, d=0) => v==null ? '—' : Number(v).toLocaleString('en-IN',{minimumFractionDigits:d,maximumFractionDigits:d});
const fmtDate = iso => iso ? new Date(iso).toLocaleDateString('en-IN',{day:'numeric',month:'short',year:'numeric'}) : '—';
const esc = s => s ? s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;') : '';
const timeStr = () => new Date().toLocaleTimeString('en-IN',{hour12:false});

function setStatus(text, ok) {
  const el = document.getElementById('lastRefreshTime');
  if (!el) return;
  el.textContent = text;
  el.style.color = ok ? 'var(--green)' : 'var(--text-muted)';
}
