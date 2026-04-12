package com.ipogmp.tracker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * MongoDB document representing one IPO with its latest GMP data.
 *
 * Data flow:
 *  - API/scraper fetches GMP → GmpDataService compares with stored value
 *  - If first of day → saves as OPEN, sets dailyOpenGmp
 *  - If changed intraday → saves as UPDATE, preserves previousGmp
 *  - If unchanged → no write, client reads existing MongoDB value
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ipos")
public class Ipo {

    @Id
    private String id;

    @Indexed(unique = true)
    @Field("name")
    private String name;

    /** Current (latest) GMP in ₹ */
    @Field("gmp")
    private Double gmp;

    /** GMP from the previous save event (used for trend arrows and flash) */
    @Field("previous_gmp")
    private Double previousGmp;

    /** First GMP recorded today — set once per calendar day */
    @Field("daily_open_gmp")
    private Double dailyOpenGmp;

    /**
     * The calendar date for which dailyOpenGmp was set.
     * Used to detect "new day" → trigger an OPEN entry.
     */
    @Field("gmp_recorded_date")
    private LocalDate gmpRecordedDate;

    @Field("kostak_rate")
    private Double kostakRate;

    @Field("subject_to_sauda")
    private Double subjectToSauda;

    @Field("issue_price")
    private Double issuePrice;

    @Field("open_date")
    private LocalDateTime openDate;

    @Field("close_date")
    private LocalDateTime closeDate;

    @Field("listing_date")
    private LocalDateTime listingDate;

    @Field("status")
    private IpoStatus status;

    @Field("lot_size")
    private Integer lotSize;

    @Field("issue_size")
    private Double issueSize;

    @Field("registrar")
    private String registrar;

    /** Timestamp of last MongoDB write */
    @Field("last_updated")
    private LocalDateTime lastUpdated;

    // ── Derived (not stored) ───────────────────────────────────────────────

    public Double getExpectedListingPrice() {
        return (issuePrice != null && gmp != null) ? issuePrice + gmp : null;
    }

    public Double getGmpPercentage() {
        if (issuePrice != null && issuePrice > 0 && gmp != null) {
            return Math.round((gmp / issuePrice) * 10000.0) / 100.0;
        }
        return null;
    }

    /** Intraday GMP change = current - dailyOpen */
    public Double getDailyGmpChange() {
        if (gmp != null && dailyOpenGmp != null) {
            return Math.round((gmp - dailyOpenGmp) * 100.0) / 100.0;
        }
        return null;
    }

    public enum IpoStatus {
        UPCOMING, OPEN, CLOSED, LISTED
    }
}
