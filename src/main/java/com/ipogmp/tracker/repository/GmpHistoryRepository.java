package com.ipogmp.tracker.repository;

import com.ipogmp.tracker.model.GmpHistory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for GMP history records.
 */
@Repository
public interface GmpHistoryRepository extends MongoRepository<GmpHistory, String> {

    /**
     * Check if an OPEN entry already exists for this IPO today.
     * If yes → we're intraday. If no → this is the first save of the day.
     */
    boolean existsByIpoIdAndTradeDateAndEntryType(
        String ipoId,
        LocalDate tradeDate,
        GmpHistory.EntryType entryType
    );

    /** Get the last recorded history entry for an IPO on a given day */
    Optional<GmpHistory> findTopByIpoIdAndTradeDateOrderByRecordedAtDesc(
        String ipoId,
        LocalDate tradeDate
    );

    /** All history entries for one IPO ordered newest first */
    List<GmpHistory> findByIpoIdOrderByRecordedAtDesc(String ipoId);

    /** History entries for one IPO on a specific day */
    List<GmpHistory> findByIpoIdAndTradeDateOrderByRecordedAtAsc(String ipoId, LocalDate tradeDate);

    /** Last N days of history for one IPO */
    List<GmpHistory> findByIpoIdAndTradeDateGreaterThanEqualOrderByRecordedAtAsc(
        String ipoId,
        LocalDate since
    );
}
