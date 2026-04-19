package com.ipogmp.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

/**
 * IPO GMP Tracker — Main Application Entry Point
 * Tracks Grey Market Premiums for active IPOs in real-time.
 */
@SpringBootApplication
@EnableScheduling
public class IpoGmpTrackerApplication {

    public static void main(String[] args) {
        // Fix for Microsoft JDK 17 + MongoDB Atlas M0:
        // Java 17 defaults to TLS 1.3 which causes SSLException: internal_error
        // with Atlas M0 free tier clusters. Force TLS 1.2 to resolve this.
        System.setProperty("jdk.tls.client.protocols", "TLSv1.2");

        SpringApplication.run(IpoGmpTrackerApplication.class, args);
    }

    /**
     * RestTemplate bean used by GmpDataService to call the ipoalerts.in API.
     * Declared here so Spring manages the lifecycle and it can be injected/mocked in tests.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
