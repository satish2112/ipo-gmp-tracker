/**
 * IPO GMP Tracker — Dashboard JavaScript
 * Handles: WebSocket (STOMP), table rendering, filtering, sorting, animations
 */

'use strict';

// ── State ──────────────────────────────────────────────────────────────────
let allIpos       = [];
let currentFilter = 'ALL';
let currentSort   = { col: 'gmp', dir: 'desc' };
let stompClient   = null;
let toast         = null;

// ── Bootstrap Toast init ───────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    toast = new bootstrap.Toast(document.getElementById('updateToast'), { delay: 3000 });
    setupFilters();
    setupSearch();
    setupSort();
    connectWebSocket();
    // Fetch initial data via REST as fallback
    fetchAllIpos();
});

// ── WebSocket / STOMP ──────────────────────────────────────────────────────
function connectWebSocket() {
    const statusEl = document.getElementById('lastRefreshTime');
    statusEl.textContent = 'Connecting...';
    statusEl.className = 'text-secondary small ws-connecting';

    const sock   = new SockJS('/ws');
    stompClient  = new StompJs.Client({
        webSocketFactory: () => sock,
        reconnectDelay:   5000,
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000,
    });

    stompClient.onConnect = () => {
        console.log('✅ WebSocket connected');
        statusEl.textContent = 'Live ✓';
        statusEl.className = 'text-success small ws-connected';

        stompClient.subscribe('/topic/ipos', (frame) => {
            try {
                const msg = JSON.parse(frame.body);
                handleWebSocketMessage(msg);
            } catch (e) {
                console.error('WS parse error:', e);
            }
        });
    };

    stompClient.onDisconnect = () => {
        console.warn('⚠️ WebSocket disconnected');
        statusEl.textContent = 'Reconnecting...';
        statusEl.className = 'text-warning small ws-connecting';
    };

    stompClient.onStompError = (frame) => {
        console.error('STOMP error:', frame);
        statusEl.textContent = 'Connection error';
        statusEl.className = 'text-danger small ws-error';
    };

    stompClient.activate();
}

function handleWebSocketMessage(msg) {
    const { event, data, id } = msg;

    switch (event) {
        case 'ALL_IPOS':
            updateAllIpos(data);
            updateRefreshTime();
            break;
        case 'IPO_UPDATED':
        case 'GMP_UPDATED':
            upsertIpo(data);
            showUpdateToast(`${data.name} — GMP updated to ₹${data.gmp}`, data.gmpTrend);
            updateRefreshTime();
            break;
        case 'IPO_CREATED':
            upsertIpo(data);
            showUpdateToast(`New IPO added: ${data.name}`, 'NEUTRAL');
            break;
        case 'IPO_DELETED':
            removeIpo(id);
            break;
    }
}

// ── REST Fetch (initial load / reconnect fallback) ─────────────────────────
async function fetchAllIpos() {
    try {
        const res = await fetch('/api/ipos');
        const json = await res.json();
        if (json.success) {
            updateAllIpos(json.data);
        }
    } catch (e) {
        console.error('REST fetch failed:', e);
    }
}

// ── Data Management ────────────────────────────────────────────────────────
function updateAllIpos(ipos) {
    allIpos = ipos;
    renderTable();
    updateStats();
}

function upsertIpo(ipo) {
    const idx = allIpos.findIndex(i => i.id === ipo.id);
    if (idx >= 0) {
        const prev = allIpos[idx];
        allIpos[idx] = ipo;
        renderTable();
        // Flash the updated row
        setTimeout(() => flashRow(ipo.id, ipo.gmpTrend), 50);
    } else {
        allIpos.unshift(ipo);
        renderTable();
    }
    updateStats();
}

function removeIpo(id) {
    allIpos = allIpos.filter(i => i.id !== id);
    renderTable();
    updateStats();
}

// ── Table Rendering ────────────────────────────────────────────────────────
function renderTable() {
    const tbody = document.getElementById('ipoTableBody');
    const filtered = getFilteredSorted();

    if (filtered.length === 0) {
        tbody.innerHTML = `
            <tr><td colspan="9" class="text-center py-5 text-secondary">
                <i class="bi bi-inbox fs-1 d-block mb-2"></i>
                No IPOs match the current filter.
            </td></tr>`;
        document.getElementById('rowCount').textContent = '0 results';
        return;
    }

    tbody.innerHTML = filtered.map(ipo => buildRow(ipo)).join('');
    document.getElementById('rowCount').textContent =
        `Showing ${filtered.length} of ${allIpos.length} IPOs`;
}

function buildRow(ipo) {
    const gmpClass   = ipo.gmp >= 0 ? 'gmp-positive' : 'gmp-negative';
    const gmpPct     = ipo.gmpPercentage != null
                       ? `<span class="${ipo.gmpPercentage >= 0 ? 'text-success' : 'text-danger'}">
                              ${ipo.gmpPercentage.toFixed(2)}%</span>` : '—';
    const statusBadge = getStatusBadge(ipo.status);
    const updatedTime = ipo.lastUpdated
        ? new Date(ipo.lastUpdated).toLocaleTimeString('en-IN', { hour12: false }) : '—';

    return `
    <tr class="ipo-row" data-id="${ipo.id}" data-status="${ipo.status}" data-gmp="${ipo.gmp}">
        <td class="ps-4 fw-semibold">${escHtml(ipo.name)}</td>
        <td class="text-end">
            <span class="${gmpClass}">₹${fmt(ipo.gmp, 2)}</span>
        </td>
        <td class="text-end d-none d-md-table-cell">${gmpPct}</td>
        <td class="text-end d-none d-lg-table-cell">${ipo.kostakRate != null ? '₹' + fmt(ipo.kostakRate, 0) : '—'}</td>
        <td class="text-end d-none d-xl-table-cell">${ipo.subjectToSauda != null ? '₹' + fmt(ipo.subjectToSauda, 0) : '—'}</td>
        <td class="text-end">₹${fmt(ipo.issuePrice, 0)}</td>
        <td class="text-end fw-bold">${ipo.expectedListingPrice != null ? '₹' + fmt(ipo.expectedListingPrice, 0) : '—'}</td>
        <td class="text-center d-none d-md-table-cell">${statusBadge}</td>
        <td class="text-end text-secondary small d-none d-lg-table-cell">${updatedTime}</td>
    </tr>`;
}

function getStatusBadge(status) {
    const map = {
        OPEN:     'bg-success',
        UPCOMING: 'bg-primary',
        CLOSED:   'bg-secondary',
        LISTED:   'bg-warning text-dark',
    };
    return `<span class="badge ${map[status] || 'bg-dark text-light'}">${status || '—'}</span>`;
}

// ── Flash Animation ────────────────────────────────────────────────────────
function flashRow(id, trend) {
    const row = document.querySelector(`tr[data-id="${id}"]`);
    if (!row) return;
    const cls = trend === 'UP' ? 'flash-up' : trend === 'DOWN' ? 'flash-down' : 'flash-neutral';
    row.classList.remove('flash-up', 'flash-down', 'flash-neutral');
    void row.offsetWidth; // reflow
    row.classList.add(cls);
    setTimeout(() => row.classList.remove(cls), 1400);
}

// ── Stats Panel ────────────────────────────────────────────────────────────
function updateStats() {
    const active = allIpos.filter(i => i.status === 'OPEN' || i.status === 'UPCOMING');
    const gmps   = allIpos.filter(i => i.gmp != null).map(i => i.gmp);
    const avgGmp = gmps.length ? (gmps.reduce((a, b) => a + b, 0) / gmps.length) : 0;
    const topIpo = allIpos.reduce((best, i) =>
        (!best || (i.gmp != null && i.gmp > best.gmp)) ? i : best, null);

    document.getElementById('totalIpos').textContent  = allIpos.length;
    document.getElementById('activeIpos').textContent = active.length;
    document.getElementById('avgGmp').textContent     = '₹' + fmt(avgGmp, 0);
    document.getElementById('topGmpIpo').textContent  =
        topIpo ? topIpo.name.split(' ')[0] + '…' : '—';
}

// ── Filters ────────────────────────────────────────────────────────────────
function setupFilters() {
    document.querySelectorAll('.filter-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.filter-btn').forEach(b =>
                b.classList.remove('active', 'btn-warning', 'btn-outline-success',
                                   'btn-outline-primary', 'btn-outline-secondary'));
            btn.classList.add('active');
            currentFilter = btn.dataset.filter;
            renderTable();
        });
    });
}

function setupSearch() {
    document.getElementById('searchInput').addEventListener('input', (e) => {
        currentFilter = '__SEARCH__';
        renderTable();
    });
}

function getFilteredSorted() {
    const query = document.getElementById('searchInput')?.value?.toLowerCase() || '';
    let data = [...allIpos];

    // Filter
    if (query) {
        data = data.filter(i => i.name.toLowerCase().includes(query));
    } else if (currentFilter !== 'ALL') {
        data = data.filter(i => i.status === currentFilter);
    }

    // Sort
    data.sort((a, b) => {
        let av = a[currentSort.col], bv = b[currentSort.col];
        if (typeof av === 'string') av = av.toLowerCase();
        if (typeof bv === 'string') bv = bv.toLowerCase();
        if (av == null) return 1;
        if (bv == null) return -1;
        return currentSort.dir === 'asc' ? (av > bv ? 1 : -1) : (av < bv ? 1 : -1);
    });

    return data;
}

// ── Sorting ────────────────────────────────────────────────────────────────
function setupSort() {
    document.querySelectorAll('.sortable').forEach(th => {
        th.addEventListener('click', () => {
            const col = th.dataset.col;
            if (currentSort.col === col) {
                currentSort.dir = currentSort.dir === 'asc' ? 'desc' : 'asc';
            } else {
                currentSort = { col, dir: 'desc' };
            }
            renderTable();
        });
    });
}

// ── Toast ──────────────────────────────────────────────────────────────────
function showUpdateToast(message, trend) {
    const el = document.getElementById('updateToast');
    const toastMsg = document.getElementById('toastMessage');
    toastMsg.textContent = message;
    el.className = 'toast align-items-center border-0 ' +
        (trend === 'UP' ? 'text-bg-success' :
         trend === 'DOWN' ? 'text-bg-danger' : 'text-bg-secondary');
    toast.show();
}

// ── Helpers ────────────────────────────────────────────────────────────────
function fmt(val, decimals = 2) {
    if (val == null) return '—';
    return Number(val).toLocaleString('en-IN', {
        minimumFractionDigits: decimals,
        maximumFractionDigits: decimals,
    });
}

function escHtml(str) {
    if (!str) return '';
    return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function updateRefreshTime() {
    const el = document.getElementById('lastRefreshTime');
    el.textContent = 'Updated ' + new Date().toLocaleTimeString('en-IN', { hour12: false });
    el.className = 'text-success small ws-connected';
}
