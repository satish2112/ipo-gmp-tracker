package com.ipogmp.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * IPO GMP Tracker — Main Application Entry Point
 * Tracks Grey Market Premiums for active IPOs in real-time.
 */
@SpringBootApplication
@EnableScheduling
public class IpoGmpTrackerApplication {
    public static void main(String[] args) {
        SpringApplication.run(IpoGmpTrackerApplication.class, args);
    }
}
