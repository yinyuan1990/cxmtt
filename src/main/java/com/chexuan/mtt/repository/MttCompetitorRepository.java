package com.chexuan.mtt.repository;

import com.chexuan.mtt.entity.MttCompetitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MttCompetitorRepository extends JpaRepository<MttCompetitor, Long> {
    List<MttCompetitor> findByMatchId(Long matchId);
    Optional<MttCompetitor> findByMatchIdAndUserId(Long matchId, Long userId);
    List<MttCompetitor> findByMatchIdAndStatus(Long matchId, Integer status);
    long countByMatchIdAndStatus(Long matchId, Integer status);
}
