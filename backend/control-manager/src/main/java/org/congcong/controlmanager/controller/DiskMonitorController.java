package org.congcong.controlmanager.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.entity.disk.DiskDetail;
import org.congcong.controlmanager.entity.disk.DiskInfo;
import org.congcong.controlmanager.service.DiskService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated

public class DiskMonitorController {

    private final DiskService diskService;

    @GetMapping("/disk/list")
    public List<DiskInfo> getAllDisks() {
        return diskService.getAllDisks();
    }

    @GetMapping("/disk/detail/{device}")
    public DiskDetail getDiskDetail(@PathVariable String device) {
        return diskService.getDiskDetail(device.replace("dev/", ""));
    }

}
