package org.congcong.controlmanager.repository.disk;

import org.congcong.controlmanager.entity.disk.DiskMonitorHostEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiskMonitorHostRepository extends JpaRepository<DiskMonitorHostEntity, Long> {

    Optional<DiskMonitorHostEntity> findByHostId(String hostId);

    List<DiskMonitorHostEntity> findAllByOrderByLastSeenAtDesc();
}
