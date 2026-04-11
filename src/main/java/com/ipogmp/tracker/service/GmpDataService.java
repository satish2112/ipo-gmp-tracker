package com.ipogmp.tracker.service;

import com.ipogmp.tracker.model.Ipo;
import com.ipogmp.tracker.repository.IpoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

/**
 * GMP Data Service — Simulates live GMP fluctuations.
 *
 * Architecture note: This class is intentionally isolated so you can swap the
 * mock implementation with a real web scraper or third-party API client without
 * touching any other part of the codebase.  Simply implement fetchLiveGmpData()
 * to call your preferred data source.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GmpDataService {

    private final IpoRepository ipoRepository;
    private final IpoService ipoService;
    private final Random random = new Random();

    /**
     * Entry point called by the scheduler every 30–60 seconds.
     * 1. Fetches the latest GMP values (mocked here).
     * 2. Persists changes to MongoDB.
     * 3. Triggers WebSocket broadcast via IpoService.
     */
    public void refreshAllGmpData() {
        log.info("⏰ Refreshing GMP data at {}", LocalDateTime.now());

        List<Ipo> ipos = ipoRepository.findByStatusIn(
                List.of(Ipo.IpoStatus.OPEN, Ipo.IpoStatus.UPCOMING));

        if (ipos.isEmpty()) {
            log.debug("No active IPOs to refresh.");
            return;
        }

        for (Ipo ipo : ipos) {
            Double newGmp = fetchLiveGmpData(ipo);
            if (newGmp != null && !newGmp.equals(ipo.getGmp())) {
                ipoService.updateGmp(ipo.getId(), newGmp);
                log.info("  ↳ {} GMP updated: {} → {}", ipo.getName(), ipo.getGmp(), newGmp);
            }
        }

        // After all individual updates, push a full list snapshot to clients
        ipoService.broadcastAllIpos();
    }

    /**
     * Fetches the current GMP for a single IPO.
     *
     * --- MOCK IMPLEMENTATION ---
     * Simulates realistic market fluctuation: ±5% random walk from current value,
     * clamped to stay above -issuePrice.
     *
     * --- TO REPLACE WITH REAL SOURCE ---
     * Options:
     *   A) HTTP scraping: Use Jsoup to parse an HTML page (e.g., ipowatch.in)
     *      Example: Document doc = Jsoup.connect("https://example.com/ipo-gmp").get();
     *   B) REST API: Call a financial data API (e.g., RapidAPI IPO endpoints)
     *   C) Scheduled import: Read from a Google Sheet / CSV via URL
     *
     * @param ipo The IPO document to refresh.
     * @return New GMP value, or null if unchanged / unavailable.
     */
    private Double fetchLiveGmpData(Ipo ipo) {
        if (ipo.getGmp() == null || ipo.getIssuePrice() == null) return null;

        // Simulate ±5% fluctuation with bias toward existing value
        double fluctuation = (random.nextDouble() - 0.48) * ipo.getIssuePrice() * 0.05;
        double newGmp = ipo.getGmp() + fluctuation;

        // Clamp: GMP cannot go below -issuePrice or above 3x issuePrice
        newGmp = Math.max(-ipo.getIssuePrice(), newGmp);
        newGmp = Math.min(ipo.getIssuePrice() * 3, newGmp);

        // Round to 2 decimal places
        return Math.round(newGmp * 100.0) / 100.0;
    }

    /**
     * Seeds the database with realistic sample IPO data on first startup.
     * Called from DataInitializer on application start.
     */
    public List<Ipo> getSampleIpos() {
        return List.of(
            buildIpo("Bajaj Housing Finance", 95.0, 800.0, 400.0, 70.0, 700, 6560.0, "KFin Technologies", Ipo.IpoStatus.OPEN),
            buildIpo("Premier Energies", 120.0, 1200.0, 600.0, 450.0, 33, 2830.0, "Link Intime", Ipo.IpoStatus.OPEN),
            buildIpo("Waaree Energies", 300.0, 2500.0, 1200.0, 1503.0, 7, 4322.0, "Bigshare Services", Ipo.IpoStatus.OPEN),
            buildIpo("Hyundai India", -50.0, 300.0, 150.0, 1960.0, 8, 27870.0, "Kfin Technologies", Ipo.IpoStatus.UPCOMING),
            buildIpo("NTPC Green Energy", 5.0, 100.0, 50.0, 108.0, 138, 10000.0, "KFin Technologies", Ipo.IpoStatus.UPCOMING),
            buildIpo("Swiggy", 25.0, 500.0, 250.0, 390.0, 38, 11327.0, "Link Intime", Ipo.IpoStatus.UPCOMING),
            buildIpo("Mobikwik", 180.0, 1500.0, 750.0, 279.0, 53, 572.0, "Link Intime", Ipo.IpoStatus.OPEN),
            buildIpo("Acme Solar", 40.0, 600.0, 300.0, 289.0, 52, 2900.0, "KFin Technologies", Ipo.IpoStatus.OPEN)
        );
    }

    private Ipo buildIpo(String name, Double gmp, Double kostak, Double sts,
                          Double issuePrice, Integer lotSize, Double issueSize,
                          String registrar, Ipo.IpoStatus status) {
        return Ipo.builder()
                .name(name)
                .gmp(gmp)
                .kostakRate(kostak)
                .subjectToSauda(sts)
                .issuePrice(issuePrice)
                .lotSize(lotSize)
                .issueSize(issueSize)
                .registrar(registrar)
                .status(status)
                .openDate(LocalDateTime.now().minusDays(1))
                .closeDate(LocalDateTime.now().plusDays(3))
                .listingDate(LocalDateTime.now().plusDays(7))
                .lastUpdated(LocalDateTime.now())
                .previousGmp(gmp)
                .build();
    }
}
