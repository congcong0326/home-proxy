package org.congcong.controlmanager.entity.disk;

import java.time.Instant;

public record DiskHost(
        String hostId,
        String hostName,
        Instant lastSeenAt,
        String lastSourceIp
) {}
