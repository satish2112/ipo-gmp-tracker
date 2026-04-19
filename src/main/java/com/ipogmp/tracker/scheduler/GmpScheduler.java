package com.ipogmp.tracker.scheduler;

import com.ipogmp.tracker.service.GmpDataService;
import com.ipogmp.tracker.service.IpoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * GmpScheduler — three scheduled jobs:
 *
 * 1. WEBSOCKET BROADCAST  (every 45s, always running)
 *    Pushes latest data from MongoDB to all WebSocket clients.
 *    When API is disabled, also runs mock GMP simulation.
 *    Does NOT call external API — zero quota usage per broadcast.
 *
 * 2. IPOALERTS API SYNC  (cron: 7am, 3pm, 11pm IST = 1:30, 9:30, 17:30 UTC)
 *    Fetches currently open IPOs from ipoalerts.in and upserts into MongoDB.
 *    Free plan budget: 25 calls/day, 1 IPO/call, 6 calls/min rate limit.
 *    Safe budget: 3 runs x 8 IPOs = 24 calls/day (under 25 limit).
 *    Skipped automatically when ipoalerts.api.enabled=false.
 *
 * 3. MIDNIGHT COUNTER RESET  (daily at 00:00)
 *    Resets the daily API quota counter for the new day.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GmpScheduler {

    private final IpoService     ipoService;
    private final GmpDataService gmpDataService;

    // ─── 1. WebSocket broadcast every 45s ────────────────────────────
    @Scheduled(
        fixedRateString    = "${app.gmp.refresh-interval-ms:45000}",
        initialDelayString = "${app.gmp.initial-delay-ms:10000}"
    )
    public void broadcastOrMockRefresh() {
        if (gmpDataService.isApiEnabled()) {
            // LIVE MODE: push cached DB data to WebSocket clients (no API call)
            log.debug("📡 [Scheduler] Broadcasting cached IPO data to WebSocket clients");
            ipoService.broadcastAllIpos();
        } else {
            // MOCK MODE: simulate GMP movement + broadcast
            log.info("📊 [Scheduler] Running mock GMP refresh cycle...");
            try {
                gmpDataService.refreshAllGmpData();
            } catch (Exception e) {
                log.error("❌ [Scheduler] Mock refresh failed: {}", e.getMessage(), e);
            }
        }
    }

    // ─── 2. ipoalerts.in API sync — 3x per day ───────────────────────
    // Cron times in UTC: 01:30, 09:30, 17:30  =  IST: 7:00, 15:00, 23:00
    // Override via env var: IPOALERTS_SYNC_CRON
    @Scheduled(cron = "${ipoalerts.api.sync-cron:0 30 1,9,17 * * *}")
    public void syncFromIpoAlerts() {
        if (!gmpDataService.isApiEnabled()) {
            log.debug("⏭️  [Scheduler] API sync skipped — mock mode active");
            return;
        }
        log.info("🌐 [Scheduler] ipoalerts API sync triggered");
        try {
            int synced = gmpDataService.syncFromIpoAlertsApi();
            log.info("🌐 [Scheduler] Sync complete — {} IPOs updated. Quota used today: {}/{}",
                    synced, gmpDataService.getDailyCallsUsed(), gmpDataService.getDailyLimit());
        } catch (Exception e) {
            log.error("❌ [Scheduler] ipoalerts sync failed: {}", e.getMessage(), e);
        }
    }

    // ─── 3. Midnight counter reset ────────────────────────────────────
    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyApiCounter() {
        gmpDataService.resetDailyCounter();
    }
}
