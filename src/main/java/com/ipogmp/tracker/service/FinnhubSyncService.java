package com.ipogmp.tracker.service;

import com.ipogmp.tracker.model.Ipo;
import com.ipogmp.tracker.repository.IpoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Syncs Finnhub IPO Calendar data into MongoDB.
 *
 * Strategy per returned IPO:
 *  1. Exact name match  → update only NULL fields (never overwrite manual data)
 *  2. Fuzzy name match  → same
 *  3. No match          → create new IPO with GMP = 0 (GMP filled later by InvestorGain sync)
 *
 * Fields updated from Finnhub:
 *  - issuePrice    (only if currently null — mid of price range)
 *  - listingDate   (only if currently null)
 *  - issueSize     (only if currently null — totalSharesValue / 1 Cr)
 *  - status        (always — Finnhub is authoritative for priced/withdrawn)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinnhubSyncService {

    private final FinnhubService finnhubService;
    private final IpoRepository  ipoRepository;

    @Value("${finnhub.api.enabled:true}")
    private boolean enabled;

    public int sync() {
        if (!enabled) {
            log.info("Finnhub sync disabled — skipping");
            return 0;
        }

        LocalDate from = LocalDate.now().minusDays(30);
        LocalDate to   = LocalDate.now().plusDays(90);

        List<FinnhubService.FinnhubIpo> list = finnhubService.fetchIpoCalendar(from, to);
        if (list.isEmpty()) return 0;

        // Load all IPOs once for fuzzy matching (list is small, avoids N DB calls)
        List<Ipo> allExisting = ipoRepository.findAll();

        int created = 0, updated = 0;
        for (FinnhubService.FinnhubIpo fi : list) {
            if (fi.getName() == null || fi.getName().isBlank()) continue;
            try {
                SyncResult r = upsert(fi, allExisting);
                if      (r == SyncResult.CREATED) created++;
                else if (r == SyncResult.UPDATED) updated++;
            } catch (Exception e) {
                log.warn("Skipping Finnhub IPO '{}': {}", fi.getName(), e.getMessage());
            }
        }

        log.info("Finnhub sync complete — created: {}, updated: {}, total from API: {}",
            created, updated, list.size());
        return created + updated;
    }

    // ── Core upsert logic ──────────────────────────────────────────────────────

    private enum SyncResult { CREATED, UPDATED, SKIPPED }

    private SyncResult upsert(FinnhubService.FinnhubIpo fi, List<Ipo> allExisting) {
        // Find existing by exact name first, then fuzzy
        Optional<Ipo> exactMatch = ipoRepository.findByNameIgnoreCase(fi.getName());
        Ipo ipo = exactMatch.orElseGet(() -> fuzzyFind(fi.getName(), allExisting));

        boolean isNew = (ipo == null);
        if (isNew) {
            ipo = Ipo.builder()
                .name(fi.getName())
                .gmp(0.0)
                .status(Ipo.IpoStatus.UPCOMING)
                .lastUpdated(LocalDateTime.now())
                .build();
        }

        boolean changed = isNew;

        // Issue price — midpoint of range, never overwrite
        if (ipo.getIssuePrice() == null) {
            Double p = parsePrice(fi.getPrice());
            if (p != null) { ipo.setIssuePrice(p); changed = true; }
        }

        // Listing date — never overwrite
        if (ipo.getListingDate() == null && fi.getDate() != null) {
            try {
                ipo.setListingDate(LocalDate.parse(fi.getDate()).atStartOfDay());
                changed = true;
            } catch (Exception ignored) {}
        }

        // Issue size in crores — never overwrite
        if (ipo.getIssueSize() == null && fi.getTotalSharesValue() != null
                && fi.getTotalSharesValue() > 0) {
            // totalSharesValue is in local currency units; divide by 1 Cr to get crores
            ipo.setIssueSize(Math.round(fi.getTotalSharesValue() / 10_000_000.0 * 100) / 100.0);
            changed = true;
        }

        // Status — always update from Finnhub (it's the authoritative source for lifecycle)
        Ipo.IpoStatus newStatus = mapStatus(fi.getStatus(), fi.getDate());
        if (newStatus != ipo.getStatus()) {
            ipo.setStatus(newStatus);
            changed = true;
        }

        if (changed) {
            ipo.setLastUpdated(LocalDateTime.now());
            Ipo saved = ipoRepository.save(ipo);
            // Add newly created IPO to in-memory list so later iterations can fuzzy-find it
            if (isNew) allExisting.add(saved);
            log.info("{} '{}' | price=₹{} | status={} | exchange={}",
                isNew ? "Created" : "Updated",
                ipo.getName(), ipo.getIssuePrice(), ipo.getStatus(), fi.getExchange());
            return isNew ? SyncResult.CREATED : SyncResult.UPDATED;
        }

        return SyncResult.SKIPPED;
    }

    // ── Name matching ──────────────────────────────────────────────────────────

    /**
     * Fuzzy match: normalize both names (strip legal suffixes + punctuation),
     * then check if either contains the other.
     */
    private Ipo fuzzyFind(String name, List<Ipo> all) {
        String norm = normalizeForMatch(name);
        if (norm.length() < 3) return null;  // too short to match safely
        return all.stream()
            .filter(i -> {
                String n = normalizeForMatch(i.getName());
                return n.length() >= 3 && (n.contains(norm) || norm.contains(n));
            })
            .findFirst()
            .orElse(null);
    }

    private String normalizeForMatch(String name) {
        if (name == null) return "";
        return name.toLowerCase()
            .replaceAll("\\b(ltd|limited|inc|corp|corporation|holdings|group|"
                + "technologies|pvt|private|plc|llc|sa|ag|bv|nv|co)\\b", "")
            .replaceAll("[^a-z0-9]", "")
            .trim();
    }

    // ── Price parsing ──────────────────────────────────────────────────────────

    /**
     * Parse Finnhub's price string into a double midpoint.
     * Handles: "500", "400-420", "$15.00-17.00", "Rs. 400-420"
     */
    private Double parsePrice(String priceStr) {
        if (priceStr == null || priceStr.isBlank()) return null;
        // Strip all currency symbols, spaces, commas
        String s = priceStr.replaceAll("[₹$€£,\\sRs.INR]", "");
        try {
            if (s.contains("-")) {
                String[] parts = s.split("-", 2);
                double lo = Double.parseDouble(parts[0]);
                double hi = Double.parseDouble(parts[1]);
                return Math.round((lo + hi) / 2.0 * 100) / 100.0;
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Status mapping ─────────────────────────────────────────────────────────

    /**
     * Map Finnhub status strings to our IpoStatus enum.
     *
     * "priced"    → LISTED if date is past, UPCOMING if date is future
     * "withdrawn" → CLOSED
     * anything else ("expected", "filed", null) → UPCOMING
     */
    private Ipo.IpoStatus mapStatus(String finnhubStatus, String dateStr) {
        if (finnhubStatus == null) return Ipo.IpoStatus.UPCOMING;
        return switch (finnhubStatus.toLowerCase()) {
            case "priced" -> {
                if (dateStr != null) {
                    try {
                        LocalDate d = LocalDate.parse(dateStr);
                        yield d.isBefore(LocalDate.now()) ? Ipo.IpoStatus.LISTED : Ipo.IpoStatus.UPCOMING;
                    } catch (Exception ignored) {}
                }
                yield Ipo.IpoStatus.UPCOMING;
            }
            case "withdrawn" -> Ipo.IpoStatus.CLOSED;
            default          -> Ipo.IpoStatus.UPCOMING;
        };
    }
}
