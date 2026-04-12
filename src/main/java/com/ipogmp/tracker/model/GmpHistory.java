package com.ipogmp.tracker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Tracks GMP save events per IPO per day.
 *
 * Save rules:
 *  - OPEN   : first value recorded for the calendar day
 *  - UPDATE : GMP changed from previous stored value during the same day
 *  - CLOSE  : optional end-of-day snapshot (can be set by a separate scheduler)
 *
 * This lets you reconstruct: "what was the opening GMP today, did it move, by how much?"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "gmp_history")
@CompoundIndexes({
    @CompoundIndex(name = "ipo_date_idx", def = "{'ipo_id': 1, 'trade_date': 1}"),
    @CompoundIndex(name = "ipo_date_type_idx", def = "{'ipo_id': 1, 'trade_date': 1, 'entry_type': 1}")
})
public class GmpHistory {

    @Id
    private String id;

    /** Reference to the parent Ipo document */
    @Field("ipo_id")
    private String ipoId;

    /** Denormalised name for easy querying without a join */
    @Field("ipo_name")
    private String ipoName;

    /** Calendar date (date only, no time) */
    @Field("trade_date")
    private LocalDate tradeDate;

    /** GMP value at this point in time */
    @Field("gmp")
    private Double gmp;

    /** GMP at the previous save event (null for OPEN entries) */
    @Field("previous_gmp")
    private Double previousGmp;

    /** Change in GMP from previous entry */
    @Field("gmp_change")
    private Double gmpChange;

    /**
     * Why this record was created:
     *  OPEN   = first save of the day
     *  UPDATE = intraday GMP change detected
     *  CLOSE  = end-of-day snapshot
     */
    @Field("entry_type")
    private EntryType entryType;

    /** Exact timestamp of this record */
    @Field("recorded_at")
    private LocalDateTime recordedAt;

    /** Source of the data: MOCK, API, SCRAPER, MANUAL */
    @Field("source")
    private String source;

    public enum EntryType {
        OPEN, UPDATE, CLOSE
    }
}
