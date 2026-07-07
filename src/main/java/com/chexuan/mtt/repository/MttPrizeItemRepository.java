package com.chexuan.mtt.repository;

import com.chexuan.mtt.entity.MttPrizeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MttPrizeItemRepository extends JpaRepository<MttPrizeItem, Long> {
    List<MttPrizeItem> findByStatusOrderByIdDesc(Integer status);
    List<MttPrizeItem> findAllByOrderByIdDesc();
}
