package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.config.WolConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WolConfigRepository extends JpaRepository<WolConfig, Long> {
    
    /**
     * 根据IP地址查找WOL配置
     */
    Optional<WolConfig> findByIpAddress(String ipAddress);
    
    /**
     * 根据MAC地址查找WOL配置
     */
    Optional<WolConfig> findByMacAddress(String macAddress);
    
    /**
     * 根据设备名称查找WOL配置
     */
    Optional<WolConfig> findByName(String name);
    
    /**
     * 查找所有启用的WOL配置
     */
    List<WolConfig> findByStatus(Integer status);
    
    /**
     * 查找所有启用的WOL配置
     */
    @Query("SELECT w FROM WolConfig w WHERE w.status = 1")
    List<WolConfig> findAllEnabled();
    
    /**
     * 检查IP地址是否已存在（排除指定ID）
     */
    boolean existsByIpAddressAndIdNot(String ipAddress, Long id);
    
    /**
     * 检查MAC地址是否已存在（排除指定ID）
     */
    boolean existsByMacAddressAndIdNot(String macAddress, Long id);
    
    /**
     * 检查设备名称是否已存在（排除指定ID）
     */
    boolean existsByNameAndIdNot(String name, Long id);
    
    /**
     * 检查IP地址是否已存在
     */
    boolean existsByIpAddress(String ipAddress);
    
    /**
     * 检查MAC地址是否已存在
     */
    boolean existsByMacAddress(String macAddress);
    
    /**
     * 检查设备名称是否已存在
     */
    boolean existsByName(String name);
}