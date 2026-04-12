package com.ipogmp.tracker.repository;

import com.ipogmp.tracker.model.Ipo;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IpoRepository extends MongoRepository<Ipo, String> {

    Optional<Ipo> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Ipo> findByStatus(Ipo.IpoStatus status);

    List<Ipo> findByStatusIn(List<Ipo.IpoStatus> statuses);

    List<Ipo> findAllByOrderByGmpDesc();

    List<Ipo> findAllByOrderByLastUpdatedDesc();

    @Query("{ 'gmp': { $gt: ?0 } }")
    List<Ipo> findByGmpGreaterThan(Double threshold);
}
