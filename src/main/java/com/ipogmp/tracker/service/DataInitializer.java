package com.ipogmp.tracker.service;

import com.ipogmp.tracker.model.GmpHistory;
import com.ipogmp.tracker.model.Ipo;
import com.ipogmp.tracker.repository.GmpHistoryRepository;
import com.ipogmp.tracker.repository.IpoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Seeds MongoDB with sample IPOs on first startup and writes their
 * OPEN history entries for today.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final IpoRepository        ipoRepository;
    private final GmpHistoryRepository historyRepository;
    private final GmpDataService       gmpDataService;

    @Override
    public void run(String... args) {
        long count = ipoRepository.count();
        if (count == 0) {
            log.info("🌱 Seeding database with sample IPOs...");
            List<Ipo> samples = gmpDataService.getSampleIpos();
            List<Ipo> saved   = ipoRepository.saveAll(samples);
            log.info("✅ Saved {} IPOs to MongoDB", saved.size());

            // Write OPEN history entries for each seeded IPO
            LocalDate today = LocalDate.now();
            for (Ipo ipo : saved) {
                if (ipo.getGmp() == null) continue;
                GmpHistory openEntry = GmpHistory.builder()
                    .ipoId(ipo.getId())
                    .ipoName(ipo.getName())
                    .tradeDate(today)
                    .gmp(ipo.getGmp())
                    .previousGmp(null)
                    .gmpChange(null)
                    .entryType(GmpHistory.EntryType.OPEN)
                    .recordedAt(LocalDateTime.now())
                    .source("SEED")
                    .build();
                historyRepository.save(openEntry);
            }
            log.info("📖 OPEN history entries written for {} IPOs", saved.size());
        } else {
            log.info("📦 Found {} existing IPOs — skipping seed.", count);
        }
    }
}
