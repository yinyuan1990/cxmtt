package com.chexuan.mtt.repository;

import com.chexuan.mtt.entity.MttPayoutBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MttPayoutBatchRepository extends JpaRepository<MttPayoutBatch, Long> {
    Optional<MttPayoutBatch> findByMatchId(Long matchId);
    List<MttPayoutBatch> findByStatusIn(List<String> statuses);
}
