package org.congcong.controlmanager.repository;

import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.entity.Route;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RouteRepository extends JpaRepository<Route, Long> {
    
    List<Route> findByStatus(Integer status);
    
    List<Route> findByPolicy(RoutePolicy policy);
    
    boolean existsByName(String name);
    
    boolean existsByNameAndIdNot(String name, Long id);
    
    /**
     * 根据名称模糊查询（分页）
     */
    Page<Route> findByNameContainingIgnoreCase(String name, Pageable pageable);
    
    /**
     * 根据策略查询（分页）
     */
    Page<Route> findByPolicy(RoutePolicy policy, Pageable pageable);
    
    /**
     * 根据状态查询（分页）
     */
    Page<Route> findByStatus(Integer status, Pageable pageable);
    
    /**
     * 复合条件查询（分页）
     */
    @Query("SELECT r FROM Route r WHERE " +
           "(:name IS NULL OR LOWER(r.name) LIKE LOWER(CONCAT('%', :name, '%'))) AND " +
           "(:policy IS NULL OR r.policy = :policy) AND " +
           "(:status IS NULL OR r.status = :status)")
    Page<Route> findByConditions(@Param("name") String name, 
                                @Param("policy") RoutePolicy policy, 
                                @Param("status") Integer status, 
                                Pageable pageable);
}