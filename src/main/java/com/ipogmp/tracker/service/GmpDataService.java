package com.ipogmp.tracker.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ipogmp.tracker.model.GmpHistory;
import com.ipogmp.tracker.model.Ipo;
import com.ipogmp.tracker.repository.GmpHistoryRepository;
import com.ipogmp.tracker.repository.IpoRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GMP Data Service
 *
 * Two modes controlled by ipoalerts.api.enabled:
 *
 *  LIVE MODE (enabled=true):
 *    syncFromIpoAlertsApi() is called 3x per day by GmpScheduler cron.
 *    Fetches currently open IPOs from ipoalerts.in API (1 IPO per request).
 *    Upserts IPO metadata into MongoDB. GMP stays as mock (free plan = no GMP addon).
 *    Daily call counter enforces the 25-request/day free plan quota.
 *
 *  MOCK MODE (enabled=false):
 *    refreshAllGmpData() is called by GmpScheduler every 45s.
 *    Simulates GMP movement locally — no external calls.
 *    Use for local development to conserve API quota.
 *
 * API Facts (confirmed from ipoalerts.in docs):
 *   Base URL  : https://api.ipoalerts.in
 *   Endpoint  : GET /ipos   (no /v1/ prefix)
 *   Auth      : x-api-key header (lowercase)
 *   Pagination: page=1,2,3... (1-based) + limit=1 (free plan max)
 *   Response  : { "meta": { "totalPages": N, ... }, "ipos": [ {...} ] }
 *   GMP field : PAID addon only — null on free plan
 *
 * Save logic (per IPO per sync):
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Fetch IPO metadata from API                                        │
 * │  GMP is null (free plan) → keep existing GMP or mock generates it  │
 * │         │                                                           │
 * │  Is this the FIRST fetch today for this IPO?                       │
 * │    YES → save as EntryType.OPEN, set dailyOpenGmp                  │
 * │         │                                                           │
 * │    NO  → Has GMP CHANGED since last save?                          │
 * │             YES → save as EntryType.UPDATE, track previousGmp      │
 * │             NO  → skip (serve existing MongoDB data)               │
 * └─────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GmpDataService {

    private final IpoRepository        ipoRepository;
    private final GmpHistoryRepository historyRepository;
    private final IpoService           ipoService;
    private final RestTemplate         restTemplate;

    @Value("${ipoalerts.api.key}")
    private String apiKey;

    @Value("${ipoalerts.api.base-url}")
    private String baseUrl;

    @Value("${ipoalerts.api.daily-limit:25}")
    private int dailyLimit;

    @Value("${ipoalerts.api.max-per-run:8}")
    private int maxPerRun;

    @Value("${ipoalerts.api.enabled:true}")
    private boolean apiEnabled;

    // ── Daily quota counter — resets at midnight via GmpScheduler cron ──
    private final AtomicInteger dailyCallCount = new AtomicInteger(0);
    private LocalDate           counterDate    = LocalDate.now();

    // ══════════════════════════════════════════════════════════════════
    // LIVE MODE — called by GmpScheduler cron (3x per day)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Fetches currently open IPOs from ipoalerts.in and upserts into MongoDB.
     * Strategy:
     *   1. Fetch page 1 → read meta.totalPages (how many open IPOs exist)
     *   2. Fetch remaining pages up to maxPerRun and quota
     *   3. 500ms delay between calls to stay under 6 req/min rate limit
     * Returns number of IPOs successfully synced.
     */
    public int syncFromIpoAlertsApi() {
        if (!apiEnabled) {
            log.info("ipoalerts API disabled — skipping sync (mock mode active)");
            return 0;
        }
        if (!hasQuota()) {
            log.warn("⚠️  Daily API limit ({}) reached. Sync skipped.", dailyLimit);
            return 0;
        }

        log.info("🌐 ipoalerts sync started — daily calls used so far: {}/{}", dailyCallCount.get(), dailyLimit);

        // ── Page 1: fetch first IPO AND discover totalPages ──────────
        IpoListResponse firstPage = fetchPage(1);
        if (firstPage == null || firstPage.getIpos() == null || firstPage.getIpos().isEmpty()) {
            log.info("  ↳ No open IPOs returned from API");
            return 0;
        }

        int totalPages = (firstPage.getMeta() != null && firstPage.getMeta().getTotalPages() != null)
                ? firstPage.getMeta().getTotalPages() : 1;

        log.info("  ↳ {} open IPO(s) found. Will fetch up to {} (quota remaining: {})",
                totalPages, maxPerRun, dailyLimit - dailyCallCount.get());

        int synced = 0;

        // Process page 1 result (already fetched — don't waste a quota call)
        try {
            upsertIpo(firstPage.getIpos().get(0));
            synced++;
        } catch (Exception e) {
            log.error("  ↳ Failed to upsert page-1 IPO: {}", e.getMessage());
        }

        // ── Pages 2..N ────────────────────────────────────────────────
        for (int page = 2; page <= totalPages && synced < maxPerRun && hasQuota(); page++) {
            sleepBetweenCalls(); // stay under 6 req/min rate limit

            IpoListResponse pageResult = fetchPage(page);
            if (pageResult == null || pageResult.getIpos() == null || pageResult.getIpos().isEmpty()) {
                log.debug("  ↳ Empty result at page={}, stopping", page);
                break;
            }
            try {
                upsertIpo(pageResult.getIpos().get(0));
                synced++;
            } catch (Exception e) {
                log.error("  ↳ Failed to upsert IPO at page={}: {}", page, e.getMessage());
            }
        }

        log.info("✅ ipoalerts sync done — {} IPOs upserted. Daily calls used: {}/{}",
                synced, dailyCallCount.get(), dailyLimit);

        if (synced > 0) {
            ipoService.broadcastAllIpos();
        }
        return synced;
    }

    /** Resets the daily call counter — called by GmpScheduler at midnight */
    public void resetDailyCounter() {
        log.info("🔄 Resetting daily API counter (was {} calls)", dailyCallCount.get());
        dailyCallCount.set(0);
        counterDate = LocalDate.now();
    }

    /** Exposed for admin panel display */
    public int getDailyCallsUsed() { return dailyCallCount.get(); }
    public int getDailyLimit()     { return dailyLimit; }
    public boolean isApiEnabled()  { return apiEnabled; }

    // ══════════════════════════════════════════════════════════════════
    // MOCK MODE — called by GmpScheduler every 45s when API disabled
    // ══════════════════════════════════════════════════════════════════

    public void refreshAllGmpData() {
        LocalDate today = LocalDate.now();
        log.info("⏰ Mock GMP refresh started — {}", today);

        List<Ipo> activeIpos = ipoRepository.findByStatusIn(
                List.of(Ipo.IpoStatus.OPEN, Ipo.IpoStatus.UPCOMING));

        if (activeIpos.isEmpty()) {
            log.info("No active IPOs to refresh.");
            ipoService.broadcastAllIpos();
            return;
        }

        int saved = 0, skipped = 0;
        for (Ipo ipo : activeIpos) {
            boolean changed = processGmp(ipo, today, fetchMockGmp(ipo));
            if (changed) saved++; else skipped++;
        }

        log.info("✅ Mock refresh done — {} updated, {} unchanged", saved, skipped);
        ipoService.broadcastAllIpos();
    }

    // ══════════════════════════════════════════════════════════════════
    // API CALL  (correct endpoint per ipoalerts.in docs)
    // ══════════════════════════════════════════════════════════════════

    /**
     * GET https://api.ipoalerts.in/ipos?status=open&limit=1&page={page}
     *
     * Confirmed from docs:
     *   - No /v1/ prefix in URL
     *   - Header: x-api-key (lowercase)
     *   - Free plan max limit: 1 per request
     *   - Pages are 1-based
     *   - Response: { "meta": { "totalPages": N }, "ipos": [...] }
     */
    private IpoListResponse fetchPage(int page) {
        String url = baseUrl + "/ipos?status=open&limit=1&page=" + page;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-api-key", apiKey);          // lowercase — confirmed from docs
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<IpoListResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), IpoListResponse.class);

            incrementCallCount();

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return response.getBody();
            }
            return null;

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("⚠️  429 Too Many Requests — rate limited for this minute. Stopping current run.");
                // Don't max out the daily counter — this is a per-minute limit, not daily quota
                // Next scheduled run (in 8 hours) will work fine
            } else if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.error("❌ 401 Unauthorized — check ipoalerts.api.key in application.properties");
            } else {
                log.error("❌ HTTP {} from ipoalerts at page={}: {}",
                        e.getStatusCode(), page, e.getMessage());
            }
            return null;
        } catch (Exception e) {
            log.error("❌ Network error calling ipoalerts API page={}: {}", page, e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // UPSERT  (API response → MongoDB)
    // ══════════════════════════════════════════════════════════════════

    private void upsertIpo(IpoAlertResponse src) {
        if (src.getName() == null || src.getName().isBlank()) {
            log.warn("  ↳ Skipping IPO with blank name from API");
            return;
        }

        Optional<Ipo> existing = ipoRepository.findByNameIgnoreCase(src.getName());
        Ipo ipo = existing.orElse(Ipo.builder().build());

        // Always set these
        ipo.setName(src.getName());
        ipo.setStatus(Ipo.IpoStatus.OPEN);
        ipo.setLastUpdated(LocalDateTime.now());

        // Map API fields — only overwrite if API returned a value
        // priceRange is "95-100" or "100" — we take the upper/fixed price
        if (src.getPriceRange() != null && !src.getPriceRange().isBlank()) {
            Double issuePrice = parseUpperPrice(src.getPriceRange());
            if (issuePrice != null) ipo.setIssuePrice(issuePrice);
        }
        if (src.getMinQty()      != null) ipo.setLotSize(src.getMinQty());       // minQty = lot size
        if (src.getIssueSize()   != null) ipo.setIssueSize(parseIssueSize(src.getIssueSize()));
        if (src.getStartDate()   != null) ipo.setOpenDate(src.getStartDate().atStartOfDay());
        if (src.getEndDate()     != null) ipo.setCloseDate(src.getEndDate().atStartOfDay());
        if (src.getListingDate() != null) ipo.setListingDate(src.getListingDate().atStartOfDay());

        // GMP: free plan returns null — keep existing GMP, or mock will handle it next cycle
        // If somehow GMP data is returned (future paid upgrade), process it
        if (src.getGmpValue() != null) {
            processGmp(ipo, LocalDate.now(), src.getGmpValue());
        } else {
            ipoRepository.save(ipo);
            log.debug("  ↳ Upserted IPO metadata [{}] (no GMP from API — free plan)", src.getName());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // GMP OPEN / UPDATE / SKIP LOGIC  (shared by live + mock)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Compares newGmp against stored value.
     * Decides: OPEN (first of day), UPDATE (changed), or SKIP (unchanged).
     * @return true if MongoDB was written
     */
    private boolean processGmp(Ipo ipo, LocalDate today, Double newGmp) {
        if (newGmp == null) {
            log.warn("  ↳ {} — null GMP, skipping", ipo.getName());
            return false;
        }

        boolean isFirstOfDay = isFirstSaveToday(ipo, today);
        boolean hasChanged   = hasGmpChanged(ipo, newGmp);

        if (isFirstOfDay) {
            log.info("  ↳ [OPEN]   {} — GMP=₹{} (first save today)", ipo.getName(), newGmp);
            applyGmpUpdate(ipo, newGmp, today, true);
            saveHistory(ipo, newGmp, null, GmpHistory.EntryType.OPEN, sourceLabel());
            ipoService.broadcastSingleUpdate("GMP_UPDATED", ipo);
            return true;

        } else if (hasChanged) {
            Double oldGmp = ipo.getGmp();
            log.info("  ↳ [UPDATE] {} — ₹{} → ₹{}", ipo.getName(), oldGmp, newGmp);
            applyGmpUpdate(ipo, newGmp, today, false);
            saveHistory(ipo, newGmp, oldGmp, GmpHistory.EntryType.UPDATE, sourceLabel());
            ipoService.broadcastSingleUpdate("GMP_UPDATED", ipo);
            return true;

        } else {
            log.debug("  ↳ [SKIP]   {} — GMP ₹{} unchanged", ipo.getName(), ipo.getGmp());
            return false;
        }
    }

    private void applyGmpUpdate(Ipo ipo, Double newGmp, LocalDate today, boolean isFirstOfDay) {
        if (!isFirstOfDay) ipo.setPreviousGmp(ipo.getGmp());
        ipo.setGmp(newGmp);
        ipo.setLastUpdated(LocalDateTime.now());
        ipo.setGmpRecordedDate(today);
        if (isFirstOfDay) {
            ipo.setDailyOpenGmp(newGmp);
            ipo.setPreviousGmp(newGmp);
        }
        ipoRepository.save(ipo);
    }

    private void saveHistory(Ipo ipo, Double gmp, Double prevGmp,
                             GmpHistory.EntryType type, String source) {
        Double change = (prevGmp != null && gmp != null)
                ? Math.round((gmp - prevGmp) * 100.0) / 100.0 : null;

        historyRepository.save(GmpHistory.builder()
                .ipoId(ipo.getId()).ipoName(ipo.getName())
                .tradeDate(LocalDate.now()).gmp(gmp).previousGmp(prevGmp)
                .gmpChange(change).entryType(type)
                .recordedAt(LocalDateTime.now()).source(source)
                .build());
    }

    private boolean isFirstSaveToday(Ipo ipo, LocalDate today) {
        if (ipo.getGmpRecordedDate() == null || ipo.getGmpRecordedDate().isBefore(today)) return true;
        return !historyRepository.existsByIpoIdAndTradeDateAndEntryType(
                ipo.getId(), today, GmpHistory.EntryType.OPEN);
    }

    private boolean hasGmpChanged(Ipo ipo, Double newGmp) {
        if (ipo.getGmp() == null) return true;
        return Math.abs(ipo.getGmp() - newGmp) > 0.01;
    }

    private String sourceLabel() { return apiEnabled ? "IPOALERTS_API" : "MOCK"; }

    // ══════════════════════════════════════════════════════════════════
    // QUOTA + RATE LIMIT HELPERS
    // ══════════════════════════════════════════════════════════════════

    private boolean hasQuota() {
        if (!LocalDate.now().equals(counterDate)) resetDailyCounter();
        return dailyCallCount.get() < dailyLimit;
    }

    private void incrementCallCount() {
        log.debug("  ↳ ipoalerts API call #{} today", dailyCallCount.incrementAndGet());
    }

    /** 500ms pause between calls — keeps us under the 6 req/min rate limit */
    private void sleepBetweenCalls() {
        try {
            // Rate limit: 6 req/min = 1 every 10s minimum. Use 11s to be safe.
            Thread.sleep(11000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
    // ══════════════════════════════════════════════════════════════════
    // FIELD PARSERS
    // ══════════════════════════════════════════════════════════════════

    /**
     * priceRange from API is "95-100" or just "100".
     * We take the upper bound (= issue price / cut-off price).
     */
    private Double parseUpperPrice(String priceRange) {
        try {
            String[] parts = priceRange.trim().split("-");
            return Double.parseDouble(parts[parts.length - 1].trim());
        } catch (Exception e) {
            log.warn("  ↳ Could not parse priceRange '{}': {}", priceRange, e.getMessage());
            return null;
        }
    }

    /**
     * issueSize from API is "192cr" or "1500".
     * We strip non-numeric characters and return the number.
     */
    private Double parseIssueSize(String issueSize) {
        try {
            return Double.parseDouble(issueSize.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            log.warn("  ↳ Could not parse issueSize '{}': {}", issueSize, e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // MOCK GMP  (used when apiEnabled=false)
    // ══════════════════════════════════════════════════════════════════

    private static final java.util.Random RAND = new java.util.Random();

    private Double fetchMockGmp(Ipo ipo) {
        if (ipo.getGmp() == null || ipo.getIssuePrice() == null) return null;
        if (RAND.nextDouble() < 0.40) return ipo.getGmp(); // 40% chance: no change
        double delta  = (RAND.nextDouble() - 0.48) * ipo.getIssuePrice() * 0.03;
        double newGmp = Math.max(-ipo.getIssuePrice() * 0.5,
                        Math.min( ipo.getIssuePrice() * 3.0, ipo.getGmp() + delta));
        return Math.round(newGmp * 100.0) / 100.0;
    }

    // ══════════════════════════════════════════════════════════════════
    // SEED DATA  (used by DataInitializer on first startup)
    // ══════════════════════════════════════════════════════════════════

    public List<Ipo> getSampleIpos() {
        LocalDate today = LocalDate.now();
        return List.of(
            build("Waaree Energies",       310.0, 2600.0, 1300.0, 1503.0,  7, 4322.0, "Bigshare Services", Ipo.IpoStatus.OPEN,     today),
            build("Bajaj Housing Finance",  98.0,  820.0,  410.0,  700.0, 700, 6560.0, "KFin Technologies", Ipo.IpoStatus.OPEN,     today),
            build("Premier Energies",      125.0, 1250.0,  620.0,  450.0,  33, 2830.0, "Link Intime",       Ipo.IpoStatus.OPEN,     today),
            build("Mobikwik",              185.0, 1550.0,  775.0,  279.0,  53,  572.0, "Link Intime",       Ipo.IpoStatus.OPEN,     today),
            build("Swiggy",                 28.0,  510.0,  255.0,  390.0,  38,11327.0, "Link Intime",       Ipo.IpoStatus.UPCOMING, today),
            build("NTPC Green Energy",       6.0,  105.0,   52.0,  108.0, 138,10000.0, "KFin Technologies", Ipo.IpoStatus.UPCOMING, today),
            build("Hyundai India",         -45.0,  310.0,  155.0, 1960.0,   8,27870.0, "KFin Technologies", Ipo.IpoStatus.CLOSED,   today),
            build("Acme Solar",             42.0,  620.0,  310.0,  289.0,  52, 2900.0, "KFin Technologies", Ipo.IpoStatus.OPEN,     today)
        );
    }

    private Ipo build(String name, Double gmp, Double kostak, Double sts,
                      Double issuePrice, Integer lotSize, Double issueSize,
                      String registrar, Ipo.IpoStatus status, LocalDate today) {
        return Ipo.builder()
                .name(name).gmp(gmp).previousGmp(gmp).dailyOpenGmp(gmp)
                .gmpRecordedDate(today).kostakRate(kostak).subjectToSauda(sts)
                .issuePrice(issuePrice).lotSize(lotSize).issueSize(issueSize)
                .registrar(registrar).status(status)
                .openDate(LocalDateTime.now().minusDays(1))
                .closeDate(LocalDateTime.now().plusDays(3))
                .listingDate(LocalDateTime.now().plusDays(7))
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════
    // DTOs  — confirmed field names from ipoalerts.in official docs
    // ══════════════════════════════════════════════════════════════════

    /**
     * Top-level list response:
     * {
     *   "meta": { "count": 10, "countOnPage": 1, "totalPages": 10, "page": 1, "limit": 1 },
     *   "ipos": [ { ...ipo object... } ]
     * }
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IpoListResponse {

        @JsonProperty("meta")
        private Meta meta;

        @JsonProperty("ipos")           // was "data" — now correct
        private List<IpoAlertResponse> ipos;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Meta {
        @JsonProperty("count")       private Integer count;
        @JsonProperty("countOnPage") private Integer countOnPage;
        @JsonProperty("totalPages")  private Integer totalPages;
        @JsonProperty("page")        private Integer page;
        @JsonProperty("limit")       private Integer limit;
    }

    /**
     * Single IPO object — field names confirmed from ipoalerts.in IPO Object docs.
     *
     * NOTE: gmp field is a PAID addon. On free plan it will be null.
     *       GMP tracking is handled by mock simulation in this service.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IpoAlertResponse {

        @JsonProperty("id")
        private String id;

        @JsonProperty("name")
        private String name;

        @JsonProperty("symbol")
        private String symbol;

        @JsonProperty("slug")
        private String slug;

        @JsonProperty("type")               // "EQ", "SME", "DEBT"
        private String type;

        @JsonProperty("status")             // "open", "upcoming", "closed", "listed"
        private String status;

        @JsonProperty("priceRange")         // e.g. "95-100" or "500" — NOT issue_price
        private String priceRange;

        @JsonProperty("minQty")             // lot size (minimum quantity)
        private Integer minQty;

        @JsonProperty("minAmount")          // minimum investment in ₹
        private Integer minAmount;

        @JsonProperty("issueSize")          // e.g. "192cr"
        private String issueSize;

        @JsonProperty("startDate")          // "YYYY-MM-DD" — subscription open date
        private LocalDate startDate;

        @JsonProperty("endDate")            // "YYYY-MM-DD" — subscription close date
        private LocalDate endDate;

        @JsonProperty("listingDate")        // "YYYY-MM-DD"
        private LocalDate listingDate;

        @JsonProperty("logo")
        private String logo;

        @JsonProperty("about")
        private String about;

        /**
         * GMP object — only present on Pro plan with GMP addon.
         * On free plan this will be null. We extract mean GMP if available.
         * Structure: { "aggregations": { "mean": 132.5, "min": 120, "max": 145 }, "sources": [...] }
         */
        @JsonProperty("gmp")
        private GmpData gmp;

        /** Helper — returns mean GMP value if available, else null */
        public Double getGmpValue() {
            if (gmp != null && gmp.getAggregations() != null) {
                return gmp.getAggregations().getMean();
            }
            return null;
        }
    }

    /** GMP object returned by API (Pro plan only) */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GmpData {
        @JsonProperty("aggregations")
        private GmpAggregations aggregations;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GmpAggregations {
        @JsonProperty("min")    private Double min;
        @JsonProperty("max")    private Double max;
        @JsonProperty("mean")   private Double mean;
        @JsonProperty("median") private Double median;
        @JsonProperty("mode")   private Double mode;
    }
}
