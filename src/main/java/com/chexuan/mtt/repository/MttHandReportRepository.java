package com.chexuan.mtt.repository;

import com.chexuan.mtt.entity.MttHandReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MttHandReportRepository extends JpaRepository<MttHandReport, Long> {
    Optional<MttHandReport> findByMatchIdAndRoomIdAndHandNo(Long matchId, Long roomId, Integer handNo);
}
