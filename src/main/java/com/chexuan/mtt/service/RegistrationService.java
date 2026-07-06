package com.chexuan.mtt.service;

import com.chexuan.mtt.entity.LedgerEntry;
import com.chexuan.mtt.entity.MttMatch;
import com.chexuan.mtt.entity.MttRegistration;
import com.chexuan.mtt.repository.MttMatchRepository;
import com.chexuan.mtt.repository.MttRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 报名 / 退赛 / 批量退费（规划 §5）
 *
 * 顺序约定：先扣费（ledger 幂等）再写报名记录；报名记录唯一键兜底防重复。
 * 退费同样走 ledger（ENTRY_REFUND），解散/取消批量退费逐笔幂等可重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final MttMatchRepository matchRepository;
    private final MttRegistrationRepository registrationRepository;
    private final LedgerService ledgerService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * 报名。失败抛 IllegalStateException（带用户可读原因）。
     */
    public MttRegistration register(Long matchId, Long userId) {
        MttMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalStateException("比赛不存在"));

        if (match.getStatus() != MttMatch.STATUS_CREATE) {
            throw new IllegalStateException("比赛不在报名期");
        }
        long now = System.currentTimeMillis();
        if (match.getEntryOpenTime() != null && now < match.getEntryOpenTime()) {
            throw new IllegalStateException("报名尚未开放");
        }
        if (now >= match.getStartTime() - 60_000L) {
            throw new IllegalStateException("临近开赛，报名已截止");
        }
        MttRegistration exist = registrationRepository.findByMatchIdAndUserId(matchId, userId).orElse(null);
        if (exist != null && exist.getStatus() == MttRegistration.STATUS_REGISTERED) {
            throw new IllegalStateException("你已报名本比赛");
        }
        long registered = registrationRepository.countByMatchIdAndStatus(matchId, MttRegistration.STATUS_REGISTERED);
        if (registered >= match.getUpperLimit()) {
            throw new IllegalStateException("报名人数已满");
        }

        // 1. 扣报名费（幂等键含报名序次：退赛后重新报名是新一笔）
        String idem = "m" + matchId + ":fee:u" + userId + ":" + (exist == null ? 0 : exist.getId());
        if (match.getEntryFee() > 0) {
            try {
                ledgerService.post(matchId, userId, match.getClubId(),
                        LedgerEntry.TYPE_ENTRY_FEE, match.getEntryCurrency(),
                        -match.getEntryFee(), idem);
            } catch (IllegalStateException e) {
                throw new IllegalStateException("报名费扣除失败：" + e.getMessage());
            }
        }

        // 2. 写报名记录（唯一键防并发双报；撞键则退刚扣的费）
        try {
            if (exist != null) {
                exist.setStatus(MttRegistration.STATUS_REGISTERED);
                exist.setFee(match.getEntryFee());
                return registrationRepository.save(exist);
            }
            MttRegistration reg = new MttRegistration();
            reg.setMatchId(matchId);
            reg.setUserId(userId);
            reg.setFee(match.getEntryFee());
            return registrationRepository.save(reg);
        } catch (DataIntegrityViolationException dup) {
            if (match.getEntryFee() > 0) {
                ledgerService.post(matchId, userId, match.getClubId(),
                        LedgerEntry.TYPE_ENTRY_REFUND, match.getEntryCurrency(),
                        match.getEntryFee(), idem + ":dup-refund");
            }
            throw new IllegalStateException("你已报名本比赛");
        }
    }

    /**
     * 开赛前主动退赛：全额退费。
     */
    public void unregister(Long matchId, Long userId) {
        MttMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalStateException("比赛不存在"));
        if (match.getStatus() != MttMatch.STATUS_CREATE) {
            throw new IllegalStateException("比赛已开赛/结束，无法退赛");
        }
        if (System.currentTimeMillis() >= match.getStartTime() - 60_000L) {
            throw new IllegalStateException("临近开赛已锁定，无法退赛");
        }
        MttRegistration reg = registrationRepository.findByMatchIdAndUserId(matchId, userId)
                .filter(r -> r.getStatus() == MttRegistration.STATUS_REGISTERED)
                .orElseThrow(() -> new IllegalStateException("你未报名本比赛"));

        refundOne(match, reg, "quit");
    }

    /**
     * 批量退费（人数不足解散 / 运营取消）。逐笔幂等，单笔失败不阻塞其余（心跳可重试）。
     *
     * @return 失败笔数
     */
    public int refundAll(MttMatch match, String reasonTag) {
        List<MttRegistration> regs =
                registrationRepository.findByMatchIdAndStatus(match.getId(), MttRegistration.STATUS_REGISTERED);
        int failed = 0;
        for (MttRegistration reg : regs) {
            try {
                refundOne(match, reg, reasonTag);
            } catch (Exception e) {
                failed++;
                log.error("批量退费失败(心跳会重试): match={}, user={}, err={}",
                        match.getId(), reg.getUserId(), e.getMessage());
            }
        }
        log.info("批量退费完成: match={}, total={}, failed={}", match.getId(), regs.size(), failed);
        return failed;
    }

    private void refundOne(MttMatch match, MttRegistration reg, String reasonTag) {
        if (reg.getFee() != null && reg.getFee() > 0) {
            ledgerService.post(match.getId(), reg.getUserId(), match.getClubId(),
                    LedgerEntry.TYPE_ENTRY_REFUND, match.getEntryCurrency(),
                    reg.getFee(), "m" + match.getId() + ":refund:" + reasonTag + ":u" + reg.getUserId() + ":" + reg.getId());
        }
        reg.setStatus(MttRegistration.STATUS_REFUNDED);
        registrationRepository.save(reg);
    }

    // ==================== ⭐ 比赛机器人自动报名 ====================

    /**
     * 自动报名 robotCount 个机器人（开赛前由心跳触发一次）。
     *
     * 规则：
     *   ① 候选=该俱乐部 is_robot=1 的活跃成员；
     *   ② 跨赛去重：已报名其他"报名期/进行中"比赛的机器人不选（对齐德州 sendAi 去重）；
     *   ③ 报名费照付（先补足机器人俱乐部积分=运营垫资,不进比赛 ledger）——
     *      保证奖池公式 participants×entryFee 口径不被机器人破坏；
     *   ④ 机器人牌桌行为由主服 RobotEngine 托管（公平打、不控盘，见主服守卫）。
     *
     * @return 实际报入数
     */
    public int registerRobots(MttMatch match) {
        int want = match.getRobotCount() != null ? match.getRobotCount() : 0;
        if (want <= 0) return 0;

        long already = registrationRepository.countByMatchIdAndStatus(
                match.getId(), MttRegistration.STATUS_REGISTERED);
        int space = (int) Math.min(want, Math.max(0, match.getUpperLimit() - already));
        if (space <= 0) return 0;

        // ① 该俱乐部机器人 ② 排除已报任何活跃比赛的
        List<Long> candidates = jdbcTemplate.queryForList(
                "SELECT cm.user_id FROM club_member cm " +
                "JOIN user u ON u.id = cm.user_id AND u.is_robot = 1 " +
                "WHERE cm.club_id = ? AND cm.status = 1 " +
                "AND cm.user_id NOT IN (" +
                "  SELECT r.user_id FROM mtt_registration r " +
                "  JOIN mtt_match m ON m.id = r.match_id AND m.status IN (1,2) " +
                "  WHERE r.status = 1" +
                ") ORDER BY RAND() LIMIT " + space,
                Long.class, match.getClubId());

        int registered = 0;
        for (Long uid : candidates) {
            try {
                // ③ 垫资：补足报名费（运营给机器人上分，不进比赛 ledger 口径）
                //    金币赛补 user.gold，钻石赛/实物赛补 user.diamond
                if (match.getEntryFee() > 0) {
                    if ("GOLD".equals(match.getEntryCurrency())) {
                        jdbcTemplate.update(
                                "UPDATE user SET gold = GREATEST(gold, ?) WHERE id=?",
                                match.getEntryFee(), uid);
                    } else if ("DIAMOND".equals(match.getEntryCurrency())) {
                        jdbcTemplate.update(
                                "UPDATE user SET diamond = GREATEST(diamond, ?) WHERE id=?",
                                match.getEntryFee(), uid);
                    } else {
                        jdbcTemplate.update(
                                "UPDATE club_member SET score = GREATEST(score, ?) WHERE club_id=? AND user_id=? AND status=1",
                                match.getEntryFee(), match.getClubId(), uid);
                    }
                }
                register(match.getId(), uid);
                registered++;
            } catch (Exception e) {
                log.warn("机器人报名失败(跳过): match={}, robot={}, err={}", match.getId(), uid, e.getMessage());
            }
        }
        log.info("比赛机器人自动报名: match={}, 目标={}, 实报={}", match.getId(), want, registered);
        return registered;
    }
}
