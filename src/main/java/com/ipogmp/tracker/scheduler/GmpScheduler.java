package com.ipogmp.tracker.scheduler;

import com.ipogmp.tracker.service.GmpDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task that periodically refreshes GMP data and pushes updates
 * to all connected WebSocket clients.
 *
 * Interval: every 45 seconds (configurable via app.gmp.refresh-interval-ms)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GmpScheduler {

    private final GmpDataService gmpDataService;

    /**
     * Fixed-rate refresh: runs every 45 seconds after application startup.
     * Use @Scheduled(cron = "0 * * * * *") for minute-based cron instead.
     */
    @Scheduled(fixedRateString = "${app.gmp.refresh-interval-ms:45000}",
               initialDelayString = "${app.gmp.initial-delay-ms:10000}")
    public void refreshGmpData() {
        log.info("📊 [Scheduler] Running GMP refresh cycle...");
        try {
            gmpDataService.refreshAllGmpData();
        } catch (Exception e) {
            log.error("❌ [Scheduler] GMP refresh failed: {}", e.getMessage(), e);
        }
    }
}
