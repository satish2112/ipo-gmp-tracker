package com.ipogmp.tracker.service;

import com.ipogmp.tracker.model.GmpHistory;
import com.ipogmp.tracker.model.Ipo;
import com.ipogmp.tracker.repository.GmpHistoryRepository;
import com.ipogmp.tracker.repository.IpoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

/**
 * GMP Data Service
 *
 * Save logic (per IPO per scheduler tick):
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Fetch GMP from API / scraper                                       │
 * │         │                                                           │
 * │         ▼                                                           │
 * │  Is this the FIRST fetch today for this IPO?                       │
 * │    YES → save to MongoDB as EntryType.OPEN                         │
 * │          set dailyOpenGmp = newGmp                                  │
 * │          set gmpRecordedDate = today                                │
 * │         │                                                           │
 * │    NO  → Has the GMP CHANGED since last save?                      │
 * │             YES → save to MongoDB as EntryType.UPDATE              │
 * │                   set previousGmp = old gmp                        │
 * │             NO  → skip DB write (no change, no noise)              │
 * │                   client continues to see existing MongoDB data     │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * To plug in a real data source, replace fetchLiveGmpFromApi().
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GmpDataService {

    private final IpoRepository        ipoRepository;
    private final GmpHistoryRepository historyRepository;
    private final IpoService           ipoService;

    private static final String DATA_SOURCE = "MOCK"; // change to "API" or "SCRAPER" as needed
    private final Random random = new Random();

    // ══════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT (called by GmpScheduler)
    // ══════════════════════════════════════════════════════════════════

    public void refreshAllGmpData() {
        LocalDate today = LocalDate.now();
        log.info("⏰ GMP refresh started — {}", today);

        List<Ipo> activeIpos = ipoRepository.findByStatusIn(
            List.of(Ipo.IpoStatus.OPEN, Ipo.IpoStatus.UPCOMING)
        );

        if (activeIpos.isEmpty()) {
            log.info("No active IPOs to refresh.");
            return;
        }

        int saved = 0;
        int skipped = 0;

        for (Ipo ipo : activeIpos) {
            boolean changed = processIpo(ipo, today);
            if (changed) saved++;
            else skipped++;
        }

        log.info("✅ GMP refresh done — {} updated, {} unchanged (reading from DB)", saved, skipped);

        // Always push current DB state to WebSocket clients
        ipoService.broadcastAllIpos();
    }

    // ══════════════════════════════════════════════════════════════════
    // PER-IPO LOGIC
    // ══════════════════════════════════════════════════════════════════

    /**
     * Processes one IPO:
     *  1. Fetch new GMP from API
     *  2. Decide whether to save (first-of-day or value changed)
     *  3. Persist to MongoDB + history if needed
     *
     * @return true if MongoDB was written, false if unchanged
     */
    private boolean processIpo(Ipo ipo, LocalDate today) {
        Double newGmp = fetchLiveGmpFromApi(ipo);
        if (newGmp == null) {
            log.warn("  ↳ {} — API returned null, skipping", ipo.getName());
            return false;
        }

        boolean isFirstOfDay = isFirstSaveToday(ipo, today);
        boolean hasChanged   = hasGmpChanged(ipo, newGmp);

        if (isFirstOfDay) {
            // ── OPEN: first GMP of the day ──────────────────────────────
            log.info("  ↳ [OPEN]   {} — GMP = ₹{} (first save today)", ipo.getName(), newGmp);
            applyGmpUpdate(ipo, newGmp, today, true);
            saveHistory(ipo, newGmp, null, GmpHistory.EntryType.OPEN);
            ipoService.broadcastSingleUpdate("GMP_UPDATED", ipo);
            return true;

        } else if (hasChanged) {
            // ── UPDATE: intraday change ──────────────────────────────────
            Double oldGmp = ipo.getGmp();
            log.info("  ↳ [UPDATE] {} — GMP changed ₹{} → ₹{}", ipo.getName(), oldGmp, newGmp);
            applyGmpUpdate(ipo, newGmp, today, false);
            saveHistory(ipo, newGmp, oldGmp, GmpHistory.EntryType.UPDATE);
            ipoService.broadcastSingleUpdate("GMP_UPDATED", ipo);
            return true;

        } else {
            // ── NO CHANGE: serve existing MongoDB data ───────────────────
            log.debug("  ↳ [SKIP]   {} — GMP ₹{} unchanged", ipo.getName(), ipo.getGmp());
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════

    /** Apply GMP change to the Ipo document and persist to MongoDB */
    private void applyGmpUpdate(Ipo ipo, Double newGmp, LocalDate today, boolean isFirstOfDay) {
        if (!isFirstOfDay) {
            ipo.setPreviousGmp(ipo.getGmp());
        }
        ipo.setGmp(newGmp);
        ipo.setLastUpdated(LocalDateTime.now());
        ipo.setGmpRecordedDate(today);

        if (isFirstOfDay) {
            // dailyOpenGmp is set once per day and never overwritten
            ipo.setDailyOpenGmp(newGmp);
            ipo.setPreviousGmp(newGmp);
        }

        ipoRepository.save(ipo);
    }

    /** Persist a history entry to the gmp_history collection */
    private void saveHistory(Ipo ipo, Double gmp, Double prevGmp, GmpHistory.EntryType type) {
        Double change = (prevGmp != null && gmp != null)
            ? Math.round((gmp - prevGmp) * 100.0) / 100.0
            : null;

        GmpHistory entry = GmpHistory.builder()
            .ipoId(ipo.getId())
            .ipoName(ipo.getName())
            .tradeDate(LocalDate.now())
            .gmp(gmp)
            .previousGmp(prevGmp)
            .gmpChange(change)
            .entryType(type)
            .recordedAt(LocalDateTime.now())
            .source(DATA_SOURCE)
            .build();

        historyRepository.save(entry);
    }

    /**
     * Is this the first GMP save for today?
     * Checks both:
     *  a) Whether gmpRecordedDate on the Ipo is before today (simple in-doc check)
     *  b) Whether an OPEN history entry exists for today (authoritative check)
     */
    private boolean isFirstSaveToday(Ipo ipo, LocalDate today) {
        // Fast check: date field on the Ipo document
        if (ipo.getGmpRecordedDate() == null || ipo.getGmpRecordedDate().isBefore(today)) {
            return true;
        }
        // Authoritative check: look in history collection
        return !historyRepository.existsByIpoIdAndTradeDateAndEntryType(
            ipo.getId(), today, GmpHistory.EntryType.OPEN
        );
    }

    /** Returns true if the new GMP differs from the stored value by more than ₹0.01 */
    private boolean hasGmpChanged(Ipo ipo, Double newGmp) {
        if (ipo.getGmp() == null) return true;
        return Math.abs(ipo.getGmp() - newGmp) > 0.01;
    }

    // ══════════════════════════════════════════════════════════════════
    // API / DATA SOURCE
    // ══════════════════════════════════════════════════════════════════

    /**
     * Fetches the current GMP for one IPO from the data source.
     *
     * ── CURRENT: MOCK SIMULATION ─────────────────────────────────────
     * Simulates realistic market movement: small random walk from current
     * GMP, with some ticks returning the same value (no-change scenario).
     *
     * ── TO REPLACE WITH A REAL API ───────────────────────────────────
     *
     * Option A — Jsoup scraping:
     *   Document doc = Jsoup.connect("https://www.ipowatch.in/ipo-gmp/")
     *       .userAgent("Mozilla/5.0").timeout(5000).get();
     *   // parse relevant element by IPO name
     *
     * Option B — HTTP REST API (RapidAPI / custom):
     *   HttpClient client = HttpClient.newHttpClient();
     *   HttpRequest req = HttpRequest.newBuilder()
     *       .uri(URI.create("https://your-api.com/gmp?name=" + ipo.getName()))
     *       .header("X-API-Key", apiKey)
     *       .GET().build();
     *   HttpResponse<String> resp = client.send(req, BodyHandlers.ofString());
     *   // parse JSON → extract gmp field
     *
     * Option C — Google Sheets CSV:
     *   URL url = new URL("https://docs.google.com/spreadsheets/d/.../export?format=csv");
     *   // parse CSV → find row for ipo.getName()
     *
     * Return null if the source is unavailable or the IPO is not found.
     */
    private Double fetchLiveGmpFromApi(Ipo ipo) {
        if (ipo.getGmp() == null || ipo.getIssuePrice() == null) return null;

        // 40% chance: no change this tick (simulate stable GMP periods)
        if (random.nextDouble() < 0.40) {
            return ipo.getGmp(); // same value → will be detected as no-change
        }

        // Small random fluctuation: ±3% of issue price
        double delta  = (random.nextDouble() - 0.48) * ipo.getIssuePrice() * 0.03;
        double newGmp = ipo.getGmp() + delta;

        // Clamp to realistic bounds
        newGmp = Math.max(-ipo.getIssuePrice() * 0.5, newGmp);
        newGmp = Math.min(ipo.getIssuePrice() * 3.0,  newGmp);

        // Round to 2 decimal places
        return Math.round(newGmp * 100.0) / 100.0;
    }

    // ══════════════════════════════════════════════════════════════════
    // SEED DATA
    // ══════════════════════════════════════════════════════════════════

    public List<Ipo> getSampleIpos() {
        LocalDate today = LocalDate.now();
        return List.of(
            build("Waaree Energies",        310.0, 2600.0, 1300.0, 1503.0,  7, 4322.0, "Bigshare Services",  Ipo.IpoStatus.OPEN,     today),
            build("Bajaj Housing Finance",   98.0,  820.0,  410.0,  700.0, 700, 6560.0, "KFin Technologies",  Ipo.IpoStatus.OPEN,     today),
            build("Premier Energies",       125.0, 1250.0,  620.0,  450.0,  33, 2830.0, "Link Intime",        Ipo.IpoStatus.OPEN,     today),
            build("Mobikwik",               185.0, 1550.0,  775.0,  279.0,  53,  572.0, "Link Intime",        Ipo.IpoStatus.OPEN,     today),
            build("Swiggy",                  28.0,  510.0,  255.0,  390.0,  38,11327.0, "Link Intime",        Ipo.IpoStatus.UPCOMING, today),
            build("NTPC Green Energy",        6.0,  105.0,   52.0,  108.0, 138,10000.0, "KFin Technologies",  Ipo.IpoStatus.UPCOMING, today),
            build("Hyundai India",          -45.0,  310.0,  155.0, 1960.0,   8,27870.0, "KFin Technologies",  Ipo.IpoStatus.CLOSED,   today),
            build("Acme Solar",              42.0,  620.0,  310.0,  289.0,  52, 2900.0, "KFin Technologies",  Ipo.IpoStatus.OPEN,     today)
        );
    }

    private Ipo build(String name, Double gmp, Double kostak, Double sts,
                      Double issuePrice, Integer lotSize, Double issueSize,
                      String registrar, Ipo.IpoStatus status, LocalDate today) {
        return Ipo.builder()
            .name(name).gmp(gmp).previousGmp(gmp).dailyOpenGmp(gmp)
            .gmpRecordedDate(today)
            .kostakRate(kostak).subjectToSauda(sts).issuePrice(issuePrice)
            .lotSize(lotSize).issueSize(issueSize).registrar(registrar)
            .status(status)
            .openDate(LocalDateTime.now().minusDays(1))
            .closeDate(LocalDateTime.now().plusDays(3))
            .listingDate(LocalDateTime.now().plusDays(7))
            .lastUpdated(LocalDateTime.now())
            .build();
    }
}
