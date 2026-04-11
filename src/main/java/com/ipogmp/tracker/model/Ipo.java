package com.ipogmp.tracker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.LocalDateTime;

/**
 * MongoDB document representing an IPO with GMP data.
 * Collection: ipos
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ipos")
public class Ipo {

    @Id
    private String id;

    /** IPO company name */
    @Indexed(unique = true)
    @Field("name")
    private String name;

    /** Grey Market Premium in ₹ (can be negative) */
    @Field("gmp")
    private Double gmp;

    /** Kostak rate — price to buy/sell IPO application */
    @Field("kostak_rate")
    private Double kostakRate;

    /** Subject to Sauda rate */
    @Field("subject_to_sauda")
    private Double subjectToSauda;

    /** Official issue / subscription price */
    @Field("issue_price")
    private Double issuePrice;

    /** IPO open date */
    @Field("open_date")
    private LocalDateTime openDate;

    /** IPO close date */
    @Field("close_date")
    private LocalDateTime closeDate;

    /** Expected listing date */
    @Field("listing_date")
    private LocalDateTime listingDate;

    /** IPO status: UPCOMING, OPEN, CLOSED, LISTED */
    @Field("status")
    private IpoStatus status;

    /** Lot size for one application */
    @Field("lot_size")
    private Integer lotSize;

    /** Total issue size in ₹ crores */
    @Field("issue_size")
    private Double issueSize;

    /** Registrar name */
    @Field("registrar")
    private String registrar;

    /** Last time GMP data was refreshed */
    @Field("last_updated")
    private LocalDateTime lastUpdated;

    /** Previous GMP value — used for change animation on frontend */
    @Field("previous_gmp")
    private Double previousGmp;

    /**
     * Derived: Expected listing price = issuePrice + gmp
     * Not stored in DB; calculated on the fly.
     */
    public Double getExpectedListingPrice() {
        if (issuePrice != null && gmp != null) {
            return issuePrice + gmp;
        }
        return null;
    }

    /**
     * Derived: GMP % = (gmp / issuePrice) * 100
     */
    public Double getGmpPercentage() {
        if (issuePrice != null && issuePrice > 0 && gmp != null) {
            return Math.round((gmp / issuePrice) * 10000.0) / 100.0;
        }
        return null;
    }

    public enum IpoStatus {
        UPCOMING, OPEN, CLOSED, LISTED
    }
}
