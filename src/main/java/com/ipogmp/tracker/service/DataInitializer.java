package com.ipogmp.tracker.service;

import com.ipogmp.tracker.model.Ipo;
import com.ipogmp.tracker.repository.IpoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the MongoDB database with sample IPO data on first startup.
 * Skips seeding if data already exists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final IpoRepository ipoRepository;
    private final GmpDataService gmpDataService;

    @Override
    public void run(String... args) {
        long count = ipoRepository.count();
        if (count == 0) {
            log.info("🌱 No data found — seeding database with sample IPOs...");
            List<Ipo> samples = gmpDataService.getSampleIpos();
            ipoRepository.saveAll(samples);
            log.info("✅ Seeded {} IPOs into MongoDB", samples.size());
        } else {
            log.info("📦 Found {} existing IPOs — skipping seed.", count);
        }
    }
}
