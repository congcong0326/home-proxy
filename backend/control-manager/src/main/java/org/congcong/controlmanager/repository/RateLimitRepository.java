package org.congcong.controlmanager.repository;

import org.congcong.common.enums.RateLimitScopeType;
import org.congcong.controlmanager.entity.RateLimit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface RateLimitRepository extends JpaRepository<RateLimit, Long> {

    List<RateLimit> findByEnabled(Boolean enabled);

    List<RateLimit> findByScopeType(RateLimitScopeType scopeType);

    /**
     * 根据启用状态查询（分页）
     */
    Page<RateLimit> findByEnabled(Boolean enabled, Pageable pageable);

    /**
     * 根据范围类型查询（分页）
     */
    Page<RateLimit> findByScopeType(RateLimitScopeType scopeType, Pageable pageable);

    /**
     * 复合条件查询（分页）
     */
    @Query("SELECT r FROM RateLimit r WHERE " +
           "(:scopeType IS NULL OR r.scopeType = :scopeType) AND " +
           "(:enabled IS NULL OR r.enabled = :enabled)")
    Page<RateLimit> findByConditions(@Param("scopeType") RateLimitScopeType scopeType,
                                   @Param("enabled") Boolean enabled,
                                   Pageable pageable);



}