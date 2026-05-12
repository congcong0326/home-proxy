package org.congcong.controlmanager.repository.disk;

import org.congcong.controlmanager.entity.disk.DiskMonitorSampleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DiskMonitorSampleRepository extends JpaRepository<DiskMonitorSampleEntity, Long> {

    @Query("""
            SELECT s FROM DiskMonitorSampleEntity s
            WHERE s.hostId = :hostId
              AND s.sampledAt = (
                SELECT MAX(s2.sampledAt) FROM DiskMonitorSampleEntity s2
                WHERE s2.hostId = s.hostId AND s2.device = s.device
              )
            ORDER BY s.device ASC
            """)
    List<DiskMonitorSampleEntity> findLatestSamplesByHostId(@Param("hostId") String hostId);

    @Query("""
            SELECT s FROM DiskMonitorSampleEntity s
            WHERE s.sampledAt = (
                SELECT MAX(s2.sampledAt) FROM DiskMonitorSampleEntity s2
                WHERE s2.hostId = s.hostId AND s2.device = s.device
            )
            ORDER BY s.hostId ASC, s.device ASC
            """)
    List<DiskMonitorSampleEntity> findLatestSamples();

    Optional<DiskMonitorSampleEntity> findTopByHostIdAndDeviceOrderBySampledAtDesc(String hostId, String device);

    Optional<DiskMonitorSampleEntity> findTopByDeviceOrderBySampledAtDesc(String device);

    List<DiskMonitorSampleEntity> findByHostIdAndDeviceAndSampledAtBetweenOrderBySampledAtAsc(
            String hostId, String device, Instant start, Instant end);

    void deleteBySampledAtBefore(Instant threshold);
}
