package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.RuleSetDTO;
import org.congcong.common.dto.RuleSetSummaryDTO;
import org.congcong.common.enums.RuleSetCategory;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.dto.ruleset.RuleSetBatchSyncRequest;
import org.congcong.controlmanager.dto.ruleset.CreateRuleSetRequest;
import org.congcong.controlmanager.dto.ruleset.RuleSetSyncResultDTO;
import org.congcong.controlmanager.dto.ruleset.UpdateRuleSetRequest;
import org.congcong.controlmanager.service.RuleSetService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rule-sets")
@RequiredArgsConstructor
public class RuleSetController {

    private final RuleSetService ruleSetService;

    @GetMapping
    public ResponseEntity<PageResponse<RuleSetSummaryDTO>> getRuleSets(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) RuleSetCategory category,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean published) {
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(sortDirection, sort));
        return ResponseEntity.ok(ruleSetService.getRuleSets(pageable, name, category, enabled, published));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RuleSetDTO> getRuleSet(@PathVariable Long id) {
        return ResponseEntity.ok(ruleSetService.getRuleSetById(id));
    }

    @PostMapping
    public ResponseEntity<RuleSetDTO> createRuleSet(@Valid @RequestBody CreateRuleSetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleSetService.createRuleSet(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RuleSetDTO> updateRuleSet(@PathVariable Long id,
                                                    @Valid @RequestBody UpdateRuleSetRequest request) {
        return ResponseEntity.ok(ruleSetService.updateRuleSet(id, request));
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<RuleSetDTO> syncRuleSet(@PathVariable Long id) {
        return ResponseEntity.ok(ruleSetService.syncRuleSet(id));
    }

    @PostMapping("/sync-all")
    public ResponseEntity<List<RuleSetSyncResultDTO>> syncRuleSets(@RequestBody(required = false) RuleSetBatchSyncRequest request) {
        return ResponseEntity.ok(ruleSetService.syncRuleSets(request == null ? new RuleSetBatchSyncRequest() : request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRuleSet(@PathVariable Long id) {
        ruleSetService.deleteRuleSet(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/published")
    public ResponseEntity<List<RuleSetDTO>> getPublishedRuleSets() {
        return ResponseEntity.ok(ruleSetService.getPublishedRuleSets());
    }
}
