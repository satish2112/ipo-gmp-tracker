package com.ipogmp.tracker.service;

import com.ipogmp.tracker.model.Ipo;
import com.ipogmp.tracker.model.InvestorGainIpo;
import com.ipogmp.tracker.repository.IpoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvestorGainSyncService {

    private final InvestorGainService investorGainService;
    private final IpoRepository       ipoRepository;

    public void syncFromInvestorGain() {
        log.info("Syncing from InvestorGain.com");
        List<InvestorGainIpo> list = investorGainService.fetchIpoData();
        int updated = 0;
        for (InvestorGainIpo src : list) {
            try {
                if (upsertIpo(src)) updated++;
            } catch (Exception e) {
                log.error("Error upserting IPO: {}", src.getName(), e);
            }
        }
        log.info("InvestorGain sync finished — {}/{} IPOs saved", updated, list.size());
    }

    /** Returns true if the record was saved (new or changed). */
    private boolean upsertIpo(InvestorGainIpo src) {
        if (src.getName() == null || src.getName().isBlank()) {
            log.warn("Skipping blank-name IPO from InvestorGain");
            return false;
        }

        Optional<Ipo> existing = ipoRepository.findByNameIgnoreCase(src.getName());
        Ipo ipo = existing.orElseGet(() -> Ipo.builder().build());
        boolean isNew = existing.isEmpty();

        ipo.setName(src.getName());
        ipo.setLastUpdated(LocalDateTime.now());

        // ── Issue price ──────────────────────────────────────────────────
        if (src.getPrice() != null && !src.getPrice().isBlank()) {
            try {
                ipo.setIssuePrice(Double.parseDouble(src.getPrice().replaceAll("[^0-9.]", "")));
            } catch (NumberFormatException e) {
                log.warn("Cannot parse price '{}' for '{}'", src.getPrice(), src.getName());
            }
        }

        // ── GMP — track previousGmp + dailyOpenGmp before overwriting ───
        if (src.getGmp() != null && !src.getGmp().isBlank()) {
            try {
                double newGmp = Double.parseDouble(src.getGmp().replaceAll("[^0-9.]", ""));
                if (!isNew && ipo.getGmp() != null) {
                    ipo.setPreviousGmp(ipo.getGmp());
                } else {
                    ipo.setPreviousGmp(newGmp);
                }
                // First update of the calendar day → record dailyOpenGmp
                LocalDate today = LocalDate.now();
                if (ipo.getGmpRecordedDate() == null || !ipo.getGmpRecordedDate().equals(today)) {
                    ipo.setDailyOpenGmp(newGmp);
                    ipo.setGmpRecordedDate(today);
                }
                ipo.setGmp(newGmp);
            } catch (NumberFormatException e) {
                log.warn("Cannot parse GMP '{}' for '{}'", src.getGmp(), src.getName());
            }
        }

        // ── Subscription ─────────────────────────────────────────────────
        if (src.getSubscriptionTimes() != null && !src.getSubscriptionTimes().isBlank()) {
            try {
                String s = src.getSubscriptionTimes().replaceAll("[^0-9.]", "");
                if (!s.isBlank()) ipo.setSubscriptionTimes(Double.parseDouble(s));
            } catch (NumberFormatException e) {
                log.warn("Cannot parse subscription '{}' for '{}'", src.getSubscriptionTimes(), src.getName());
            }
        }

        // ── Allotment date ───────────────────────────────────────────────
        if (src.getAllotmentDate() != null && !src.getAllotmentDate().isBlank()) {
            LocalDateTime parsed = parseDate(src.getAllotmentDate().trim());
            if (parsed != null) ipo.setAllotmentDate(parsed);
        }

        // ── Type ─────────────────────────────────────────────────────────
        if (src.getType() != null && !src.getType().isBlank()) {
            ipo.setType(src.getType());
        }

        // ── Status detection ─────────────────────────────────────────────
        // Only override if status is missing or still at default UPCOMING
        if (ipo.getStatus() == null || ipo.getStatus() == Ipo.IpoStatus.UPCOMING) {
            ipo.setStatus(detectStatus(ipo));
        }

        ipoRepository.save(ipo);
        log.debug("Saved [{}] gmp=₹{} status={}", ipo.getName(), ipo.getGmp(), ipo.getStatus());
        return true;
    }

    /**
     * Infer status from date fields.
     * LISTED  → listing date is in the past
     * CLOSED  → allotment date is in the past (but listing not yet / unknown)
     * OPEN    → allotment date is today or within the next 14 days
     * UPCOMING→ no dates, or allotment is far future
     */
    private Ipo.IpoStatus detectStatus(Ipo ipo) {
        LocalDate today = LocalDate.now();

        if (ipo.getListingDate() != null
                && ipo.getListingDate().toLocalDate().isBefore(today)) {
            return Ipo.IpoStatus.LISTED;
        }

        if (ipo.getAllotmentDate() != null) {
            LocalDate allotment = ipo.getAllotmentDate().toLocalDate();
            if (allotment.isBefore(today)) {
                return Ipo.IpoStatus.CLOSED;
            }
            // Allotment within 14 days → subscription is open (or about to close)
            if (!allotment.isAfter(today.plusDays(14))) {
                return Ipo.IpoStatus.OPEN;
            }
        }

        return Ipo.IpoStatus.UPCOMING;
    }

    // ── Date parser ───────────────────────────────────────────────────────────

    private static final DateTimeFormatter[] DATE_FMTS = {
        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd MMM yyyy",  Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
    };

    private LocalDateTime parseDate(String raw) {
        for (DateTimeFormatter fmt : DATE_FMTS) {
            try { return LocalDate.parse(raw, fmt).atStartOfDay(); }
            catch (DateTimeParseException ignored) {}
        }
        log.warn("Could not parse date '{}'", raw);
        return null;
    }
}
