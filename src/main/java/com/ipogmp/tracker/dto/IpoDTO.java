package com.ipogmp.tracker.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ipogmp.tracker.model.Ipo;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IpoDTO {

    private String id;

    @NotBlank(message = "IPO name is required")
    @Size(min = 2, max = 150)
    private String name;

    @NotNull(message = "GMP is required")
    private Double gmp;

    private Double previousGmp;
    private Double dailyOpenGmp;
    private Double dailyGmpChange;
    private LocalDate gmpRecordedDate;
    private Double kostakRate;
    private Double subjectToSauda;

    @NotNull(message = "Issue price is required")
    @DecimalMin("1.0")
    private Double issuePrice;

    // Derived (computed server-side, not stored)
    private Double expectedListingPrice;
    private Double gmpPercentage;

    private LocalDateTime openDate;
    private LocalDateTime closeDate;
    private LocalDateTime listingDate;
    private Ipo.IpoStatus status;

    @Min(1)
    private Integer lotSize;
    private Double issueSize;
    private String registrar;
    private LocalDateTime lastUpdated;

    /** UP / DOWN / NEUTRAL — derived from gmp vs previousGmp */
    private String gmpTrend;

    // ─── Factory: Ipo → DTO ───────────────────────────────────────────

    public static IpoDTO fromIpo(Ipo ipo) {
        if (ipo == null) return null;
        String trend = "NEUTRAL";
        if (ipo.getPreviousGmp() != null && ipo.getGmp() != null) {
            if (ipo.getGmp() > ipo.getPreviousGmp())      trend = "UP";
            else if (ipo.getGmp() < ipo.getPreviousGmp()) trend = "DOWN";
        }
        return IpoDTO.builder()
            .id(ipo.getId())
            .name(ipo.getName())
            .gmp(ipo.getGmp())
            .previousGmp(ipo.getPreviousGmp())
            .dailyOpenGmp(ipo.getDailyOpenGmp())
            .dailyGmpChange(ipo.getDailyGmpChange())
            .gmpRecordedDate(ipo.getGmpRecordedDate())
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
            .gmpTrend(trend)
            .build();
    }

    // ─── Factory: DTO → Ipo ───────────────────────────────────────────

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
