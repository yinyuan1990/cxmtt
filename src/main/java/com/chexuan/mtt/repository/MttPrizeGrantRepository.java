package com.chexuan.mtt.repository;

import com.chexuan.mtt.entity.MttPrizeGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MttPrizeGrantRepository extends JpaRepository<MttPrizeGrant, Long> {
    List<MttPrizeGrant> findByMatchId(Long matchId);
    List<MttPrizeGrant> findByUserIdOrderByIdDesc(Long userId);
}
