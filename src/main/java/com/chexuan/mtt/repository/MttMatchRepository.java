package com.chexuan.mtt.repository;

import com.chexuan.mtt.entity.MttMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MttMatchRepository extends JpaRepository<MttMatch, Long> {
    List<MttMatch> findByStatusIn(List<Integer> statuses);
    List<MttMatch> findByClubIdOrderByStartTimeDesc(Long clubId);
    List<MttMatch> findAllByOrderByStartTimeDesc();
    long countByClubIdAndStatus(Long clubId, Integer status);
    List<MttMatch> findByClubIdAndStatusOrderByStartTimeDesc(Long clubId, Integer status);
}
