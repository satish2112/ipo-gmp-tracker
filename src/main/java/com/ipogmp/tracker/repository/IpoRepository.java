package com.ipogmp.tracker.repository;

import com.ipogmp.tracker.model.Ipo;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data MongoDB repository for IPO documents.
 */
@Repository
public interface IpoRepository extends MongoRepository<Ipo, String> {

    Optional<Ipo> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Ipo> findByStatus(Ipo.IpoStatus status);

    List<Ipo> findByStatusIn(List<Ipo.IpoStatus> statuses);

    /** Find IPOs where GMP is above a threshold */
    @Query("{ 'gmp': { $gt: ?0 } }")
    List<Ipo> findByGmpGreaterThan(Double threshold);

    /** Find IPOs ordered by GMP descending */
    List<Ipo> findAllByOrderByGmpDesc();

    /** Find IPOs ordered by last updated descending */
    List<Ipo> findAllByOrderByLastUpdatedDesc();
}
