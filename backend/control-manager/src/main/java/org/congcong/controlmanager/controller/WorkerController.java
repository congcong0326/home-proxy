package org.congcong.controlmanager.controller;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.WorkerPollRequest;
import org.congcong.common.dto.WorkerPollResponse;
import org.congcong.controlmanager.dto.WorkerStatusDTO;
import org.congcong.controlmanager.service.WorkerControlService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/worker")
@RequiredArgsConstructor
public class WorkerController {

    private final WorkerControlService workerControlService;

    @PostMapping("/poll")
    public ResponseEntity<WorkerPollResponse> poll(@RequestBody(required = false) WorkerPollRequest request) {
        return ResponseEntity.ok(workerControlService.poll(request));
    }

    @GetMapping("/status")
    public ResponseEntity<WorkerStatusDTO> status() {
        WorkerStatusDTO status = workerControlService.getLatestStatus();
        if (status == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(status);
    }
}
