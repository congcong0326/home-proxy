package org.congcong.controlmanager.entity.disk;

import java.util.List;

public record DiskIoStats(
        String device,
        List<Long> readDeltas,
        List<Long> writeDeltas,
        long totalRead,
        long totalWrite
) {}
