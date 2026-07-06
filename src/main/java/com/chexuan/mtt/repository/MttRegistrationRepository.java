package com.chexuan.mtt.repository;

import com.chexuan.mtt.entity.MttRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MttRegistrationRepository extends JpaRepository<MttRegistration, Long> {
    Optional<MttRegistration> findByMatchIdAndUserId(Long matchId, Long userId);
    List<MttRegistration> findByMatchIdAndStatus(Long matchId, Integer status);
    long countByMatchIdAndStatus(Long matchId, Integer status);
    List<MttRegistration> findByUserIdAndStatus(Long userId, Integer status);
}
