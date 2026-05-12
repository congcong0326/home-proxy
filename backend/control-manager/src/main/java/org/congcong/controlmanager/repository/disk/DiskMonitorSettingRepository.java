package org.congcong.controlmanager.repository.disk;

import org.congcong.controlmanager.entity.disk.DiskMonitorSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiskMonitorSettingRepository extends JpaRepository<DiskMonitorSettingEntity, String> {
}
