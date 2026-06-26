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
    private final IpoRepository ipoRepository;

    public void syncFromInvestorGain() {
        log.info("Syncing from InvestorGain.com");
        List<InvestorGainIpo> investorGainIpos = investorGainService.fetchIpoData();
        for (InvestorGainIpo investorGainIpo : investorGainIpos) {
            try {
                upsertIpo(investorGainIpo);
            } catch (Exception e) {
                log.error("Error upserting IPO: {}", investorGainIpo.getName(), e);
            }
        }
        log.info("Finished syncing from InvestorGain.com");
    }

    private void upsertIpo(InvestorGainIpo src) {
        if (src.getName() == null || src.getName().isBlank()) {
            log.warn("Skipping IPO with blank name from InvestorGain");
            return;
        }

        Optional<Ipo> existing = ipoRepository.findByNameIgnoreCase(src.getName());
        Ipo ipo = existing.orElse(Ipo.builder().build());

        ipo.setName(src.getName());
        ipo.setLastUpdated(LocalDateTime.now());

        if (src.getPrice() != null && !src.getPrice().isBlank()) {
            try {
                ipo.setIssuePrice(Double.parseDouble(src.getPrice().replaceAll("[^0-9.]", "")));
            } catch (NumberFormatException e) {
                log.warn("Could not parse price '{}' for IPO '{}'", src.getPrice(), src.getName());
            }
        }

        if (src.getGmp() != null && !src.getGmp().isBlank()) {
            try {
                ipo.setGmp(Double.parseDouble(src.getGmp().replaceAll("[^0-9.]", "")));
            } catch (NumberFormatException e) {
                log.warn("Could not parse GMP '{}' for IPO '{}'", src.getGmp(), src.getName());
            }
        }

        if (src.getSubscriptionTimes() != null && !src.getSubscriptionTimes().isBlank()) {
            try {
                String subStr = src.getSubscriptionTimes().replaceAll("[^0-9.]", "");
                if (!subStr.isBlank()) {
                    ipo.setSubscriptionTimes(Double.parseDouble(subStr));
                }
            } catch (NumberFormatException e) {
                log.warn("Could not parse subscription '{}' for IPO '{}'", src.getSubscriptionTimes(), src.getName());
            }
        }

        if (src.getAllotmentDate() != null && !src.getAllotmentDate().isBlank()) {
            try {
                DateTimeFormatter[] fmts = {
                    DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
                    DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy")
                };
                for (DateTimeFormatter fmt : fmts) {
                    try {
                        LocalDate d = LocalDate.parse(src.getAllotmentDate().trim(), fmt);
                        ipo.setAllotmentDate(d.atStartOfDay());
                        break;
                    } catch (DateTimeParseException ignored) {}
                }
            } catch (Exception e) {
                log.warn("Could not parse allotment date '{}' for IPO '{}'", src.getAllotmentDate(), src.getName());
            }
        }

        ipoRepository.save(ipo);
        log.debug("Upserted IPO metadata [{}] from InvestorGain", src.getName());
    }
}
