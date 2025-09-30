package org.congcong.controlmanager.repository;

import org.congcong.common.enums.ProtocolType;
import org.congcong.controlmanager.entity.InboundConfig;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InboundConfigRepository extends JpaRepository<InboundConfig, Long> {
    
    Optional<InboundConfig> findByListenIpAndPort(String listenIp, Integer port);
    
    List<InboundConfig> findByStatus(Integer status);
    
    List<InboundConfig> findByProtocol(ProtocolType protocol);
    
    boolean existsByListenIpAndPortAndIdNot(String listenIp, Integer port, Long id);
    
    boolean existsByListenIpAndPort(String listenIp, Integer port);

    /**
     * 根据协议类型查询（分页）
     */
    Page<InboundConfig> findByProtocol(ProtocolType protocol, Pageable pageable);

    /**
     * 根据状态查询（分页）
     */
    Page<InboundConfig> findByStatus(Integer status, Pageable pageable);

    /**
     * 根据TLS启用状态查询（分页）
     */
    Page<InboundConfig> findByTlsEnabled(Boolean tlsEnabled, Pageable pageable);

    /**
     * 复合条件查询（分页）
     */
    @Query("SELECT i FROM InboundConfig i WHERE " +
           "(:protocol IS NULL OR i.protocol = :protocol) AND " +
           "(:port IS NULL OR i.port = :port) AND " +
           "(:tlsEnabled IS NULL OR i.tlsEnabled = :tlsEnabled) AND " +
           "(:status IS NULL OR i.status = :status)")
    Page<InboundConfig> findByConditions(@Param("protocol") ProtocolType protocol,
                                       @Param("port") Integer port,
                                       @Param("tlsEnabled") Boolean tlsEnabled,
                                       @Param("status") Integer status,
                                       Pageable pageable);
}