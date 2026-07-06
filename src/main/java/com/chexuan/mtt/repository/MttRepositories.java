package com.chexuan.mtt.repository;

import com.chexuan.mtt.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 仓储集合（表少且查询简单，集中一个文件便于维护）
 */
public class MttRepositories {

    @Repository
    public interface MttMatchRepository extends JpaRepository<MttMatch, Long> {
        List<MttMatch> findByStatusIn(List<Integer> statuses);
        List<MttMatch> findByClubIdOrderByStartTimeDesc(Long clubId);
        List<MttMatch> findAllByOrderByStartTimeDesc();
    }

    @Repository
    public interface MttRegistrationRepository extends JpaRepository<MttRegistration, Long> {
        Optional<MttRegistration> findByMatchIdAndUserId(Long matchId, Long userId);
        List<MttRegistration> findByMatchIdAndStatus(Long matchId, Integer status);
        long countByMatchIdAndStatus(Long matchId, Integer status);
        List<MttRegistration> findByUserIdAndStatus(Long userId, Integer status);
    }

    @Repository
    public interface MttCompetitorRepository extends JpaRepository<MttCompetitor, Long> {
        List<MttCompetitor> findByMatchId(Long matchId);
        Optional<MttCompetitor> findByMatchIdAndUserId(Long matchId, Long userId);
        List<MttCompetitor> findByMatchIdAndStatus(Long matchId, Integer status);
        long countByMatchIdAndStatus(Long matchId, Integer status);
    }

    @Repository
    public interface MttHandReportRepository extends JpaRepository<MttHandReport, Long> {
        Optional<MttHandReport> findByMatchIdAndRoomIdAndHandNo(Long matchId, Long roomId, Integer handNo);
    }

    @Repository
    public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
        Optional<LedgerEntry> findByIdempotencyKey(String key);
        List<LedgerEntry> findByStatusIn(List<String> statuses);
        List<LedgerEntry> findByMatchIdOrderByIdAsc(Long matchId);
    }

    @Repository
    public interface MttPayoutBatchRepository extends JpaRepository<MttPayoutBatch, Long> {
        Optional<MttPayoutBatch> findByMatchId(Long matchId);
        List<MttPayoutBatch> findByStatusIn(List<String> statuses);
    }

    @Repository
    public interface MttPrizeGrantRepository extends JpaRepository<MttPrizeGrant, Long> {
        List<MttPrizeGrant> findByMatchId(Long matchId);
    }
}
