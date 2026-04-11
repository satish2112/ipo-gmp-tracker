/**
 * IPO GMP Tracker — Admin Panel JavaScript
 * Handles: CRUD operations, modal management, live table refresh
 */

'use strict';

let editingId  = null;
let deleteId   = null;
let ipoModal   = null;
let deleteModal = null;

document.addEventListener('DOMContentLoaded', () => {
    ipoModal    = new bootstrap.Modal(document.getElementById('ipoModal'));
    deleteModal = new bootstrap.Modal(document.getElementById('deleteModal'));

    // Auto-calculate expected listing price
    ['issuePrice', 'gmp'].forEach(id => {
        document.getElementById(id).addEventListener('input', updateEstListing);
    });

    // Delete confirm button
    document.getElementById('confirmDeleteBtn').addEventListener('click', executeDelete);
});

// ── Expected Listing Price ─────────────────────────────────────────────────
function updateEstListing() {
    const ip  = parseFloat(document.getElementById('issuePrice').value) || 0;
    const gmp = parseFloat(document.getElementById('gmp').value) || 0;
    document.getElementById('estListing').value =
        ip > 0 ? '₹' + (ip + gmp).toFixed(2) : '';
}

// ── Modal: Create ──────────────────────────────────────────────────────────
function openCreateModal() {
    editingId = null;
    document.getElementById('modalTitle').textContent = 'Add New IPO';
    document.getElementById('ipoForm').reset();
    document.getElementById('ipoId').value = '';
    document.getElementById('estListing').value = '';
    ipoModal.show();
}

// ── Modal: Edit ────────────────────────────────────────────────────────────
async function openEditModal(id) {
    editingId = id;
    document.getElementById('modalTitle').textContent = 'Edit IPO';

    try {
        const res  = await apiFetch(`/api/ipos/${id}`);
        const ipo  = res.data;

        document.getElementById('ipoId').value          = ipo.id;
        document.getElementById('ipoName').value        = ipo.name;
        document.getElementById('issuePrice').value     = ipo.issuePrice;
        document.getElementById('gmp').value            = ipo.gmp;
        document.getElementById('kostakRate').value     = ipo.kostakRate || '';
        document.getElementById('subjectToSauda').value = ipo.subjectToSauda || '';
        document.getElementById('lotSize').value        = ipo.lotSize || '';
        document.getElementById('issueSize').value      = ipo.issueSize || '';
        document.getElementById('registrar').value      = ipo.registrar || '';
        document.getElementById('ipoStatus').value      = ipo.status || 'UPCOMING';

        if (ipo.openDate)    document.getElementById('openDate').value    = toDatetimeLocal(ipo.openDate);
        if (ipo.closeDate)   document.getElementById('closeDate').value   = toDatetimeLocal(ipo.closeDate);
        if (ipo.listingDate) document.getElementById('listingDate').value = toDatetimeLocal(ipo.listingDate);

        updateEstListing();
        ipoModal.show();
    } catch (e) {
        showAlert('Failed to load IPO data: ' + e.message, 'danger');
    }
}

// ── Save (Create or Update) ────────────────────────────────────────────────
async function saveIpo() {
    const form = document.getElementById('ipoForm');
    const name = document.getElementById('ipoName').value.trim();
    const gmp  = document.getElementById('gmp').value;
    const ip   = document.getElementById('issuePrice').value;

    if (!name || !gmp || !ip) {
        showAlert('Please fill in all required fields (Name, GMP, Issue Price).', 'warning');
        return;
    }

    const payload = {
        name:           name,
        gmp:            parseFloat(gmp),
        issuePrice:     parseFloat(ip),
        kostakRate:     parseFloatOrNull('kostakRate'),
        subjectToSauda: parseFloatOrNull('subjectToSauda'),
        lotSize:        parseIntOrNull('lotSize'),
        issueSize:      parseFloatOrNull('issueSize'),
        registrar:      document.getElementById('registrar').value.trim() || null,
        status:         document.getElementById('ipoStatus').value,
        openDate:       datetimeLocalToISO('openDate'),
        closeDate:      datetimeLocalToISO('closeDate'),
        listingDate:    datetimeLocalToISO('listingDate'),
    };

    const saveBtn = document.getElementById('saveBtn');
    saveBtn.disabled = true;
    saveBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span> Saving...';

    try {
        if (editingId) {
            await apiFetch(`/api/ipos/${editingId}`, 'PUT', payload);
            showAlert('IPO updated successfully!', 'success');
        } else {
            await apiFetch('/api/ipos', 'POST', payload);
            showAlert('IPO created successfully!', 'success');
        }
        ipoModal.hide();
        setTimeout(() => location.reload(), 800);
    } catch (e) {
        showAlert('Save failed: ' + e.message, 'danger');
    } finally {
        saveBtn.disabled = false;
        saveBtn.innerHTML = '<i class="bi bi-check-circle-fill me-1"></i> Save';
    }
}

// ── Delete ─────────────────────────────────────────────────────────────────
function confirmDelete(id, name) {
    deleteId = id;
    document.getElementById('deleteIpoName').textContent = name;
    deleteModal.show();
}

async function executeDelete() {
    if (!deleteId) return;
    const btn = document.getElementById('confirmDeleteBtn');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span>';

    try {
        await apiFetch(`/api/ipos/${deleteId}`, 'DELETE');
        deleteModal.hide();
        showAlert('IPO deleted.', 'success');
        setTimeout(() => location.reload(), 600);
    } catch (e) {
        showAlert('Delete failed: ' + e.message, 'danger');
        btn.disabled = false;
        btn.innerHTML = '<i class="bi bi-trash-fill me-1"></i> Delete';
    }
}

// ── API Helper ─────────────────────────────────────────────────────────────
async function apiFetch(url, method = 'GET', body = null) {
    const opts = {
        method,
        headers: { 'Content-Type': 'application/json' },
    };
    if (body) opts.body = JSON.stringify(body);

    const res  = await fetch(url, opts);
    const json = await res.json();

    if (!json.success) {
        throw new Error(json.error || 'Request failed');
    }
    return json;
}

// ── Alert Banner ───────────────────────────────────────────────────────────
function showAlert(message, type = 'info') {
    const container = document.getElementById('alertPlaceholder');
    const id = 'alert_' + Date.now();
    container.innerHTML = `
        <div id="${id}" class="alert alert-${type} alert-dismissible fade show py-2 small" role="alert">
            ${message}
            <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
        </div>`;
    setTimeout(() => {
        const el = document.getElementById(id);
        if (el) el.remove();
    }, 4000);
}

// ── Datetime Helpers ───────────────────────────────────────────────────────
function toDatetimeLocal(iso) {
    if (!iso) return '';
    return iso.substring(0, 16);
}

function datetimeLocalToISO(id) {
    const val = document.getElementById(id).value;
    return val ? val + ':00' : null;
}

function parseFloatOrNull(id) {
    const v = document.getElementById(id).value;
    return v ? parseFloat(v) : null;
}

function parseIntOrNull(id) {
    const v = document.getElementById(id).value;
    return v ? parseInt(v, 10) : null;
}
