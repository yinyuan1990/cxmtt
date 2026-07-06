package com.chexuan.mtt.repository;

import com.chexuan.mtt.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    Optional<LedgerEntry> findByIdempotencyKey(String key);
    List<LedgerEntry> findByStatusIn(List<String> statuses);
    List<LedgerEntry> findByMatchIdOrderByIdAsc(Long matchId);
}
