package org.congcong.controlmanager.entity.disk;

public record DiskInfo(
    String device,
    String model,
    String serial,
    String size,
    String status,
    int temperature
) {}