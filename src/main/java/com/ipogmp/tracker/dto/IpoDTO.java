package com.ipogmp.tracker.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ipogmp.tracker.model.Ipo;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Data Transfer Object for IPO — used in REST API requests/responses.
 * Decouples the API contract from the MongoDB document structure.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IpoDTO {

    private String id;

    @NotBlank(message = "IPO name is required")
    @Size(min = 2, max = 150, message = "Name must be between 2 and 150 characters")
    private String name;

    @NotNull(message = "GMP is required")
    @DecimalMin(value = "-9999", message = "GMP cannot be less than -9999")
    @DecimalMax(value = "99999", message = "GMP cannot exceed 99999")
    private Double gmp;

    private Double kostakRate;

    private Double subjectToSauda;

    @NotNull(message = "Issue price is required")
    @DecimalMin(value = "1.0", message = "Issue price must be positive")
    private Double issuePrice;

    // Derived fields — read-only (set by server)
    private Double expectedListingPrice;
    private Double gmpPercentage;

    private LocalDateTime openDate;
    private LocalDateTime closeDate;
    private LocalDateTime listingDate;

    private Ipo.IpoStatus status;

    @Min(value = 1, message = "Lot size must be at least 1")
    private Integer lotSize;

    private Double issueSize;

    private String registrar;

    private LocalDateTime lastUpdated;

    private Double previousGmp;

    /** Direction of GMP change: UP, DOWN, NEUTRAL */
    private String gmpTrend;

    /**
     * Factory: convert Ipo document → IpoDTO
     */
    public static IpoDTO fromIpo(Ipo ipo) {
        if (ipo == null) return null;

        String trend = "NEUTRAL";
        if (ipo.getPreviousGmp() != null && ipo.getGmp() != null) {
            if (ipo.getGmp() > ipo.getPreviousGmp()) trend = "UP";
            else if (ipo.getGmp() < ipo.getPreviousGmp()) trend = "DOWN";
        }

        return IpoDTO.builder()
                .id(ipo.getId())
                .name(ipo.getName())
                .gmp(ipo.getGmp())
                .kostakRate(ipo.getKostakRate())
                .subjectToSauda(ipo.getSubjectToSauda())
                .issuePrice(ipo.getIssuePrice())
                .expectedListingPrice(ipo.getExpectedListingPrice())
                .gmpPercentage(ipo.getGmpPercentage())
                .openDate(ipo.getOpenDate())
                .closeDate(ipo.getCloseDate())
                .listingDate(ipo.getListingDate())
                .status(ipo.getStatus())
                .lotSize(ipo.getLotSize())
                .issueSize(ipo.getIssueSize())
                .registrar(ipo.getRegistrar())
                .lastUpdated(ipo.getLastUpdated())
                .previousGmp(ipo.getPreviousGmp())
                .gmpTrend(trend)
                .build();
    }

    /**
     * Factory: convert IpoDTO → Ipo document (for create/update)
     */
    public Ipo toIpo() {
        return Ipo.builder()
                .id(this.id)
                .name(this.name)
                .gmp(this.gmp)
                .kostakRate(this.kostakRate)
                .subjectToSauda(this.subjectToSauda)
                .issuePrice(this.issuePrice)
                .openDate(this.openDate)
                .closeDate(this.closeDate)
                .listingDate(this.listingDate)
                .status(this.status != null ? this.status : Ipo.IpoStatus.UPCOMING)
                .lotSize(this.lotSize)
                .issueSize(this.issueSize)
                .registrar(this.registrar)
                .lastUpdated(LocalDateTime.now())
                .build();
    }
}
