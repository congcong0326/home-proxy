package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.dto.rewrite.RewriteMergePreview;
import org.congcong.controlmanager.dto.rewrite.RewriteMergeRequest;
import org.congcong.controlmanager.dto.rewrite.RewriteTableImportRequest;
import org.congcong.controlmanager.dto.rewrite.RewriteTableRequest;
import org.congcong.controlmanager.dto.rewrite.RewriteTableView;
import org.congcong.controlmanager.service.RewriteMergeService;
import org.congcong.controlmanager.service.RewriteTableService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 改写表控制器
 */
@RestController
@RequestMapping("/api/rewrite-tables")
@RequiredArgsConstructor
public class RewriteTableController {

    private final RewriteTableService rewriteTableService;
    private final RewriteMergeService rewriteMergeService;

    @GetMapping
    public ResponseEntity<List<RewriteTableView>> getTables() {
        return ResponseEntity.ok(rewriteTableService.getTables());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RewriteTableView> getTable(@PathVariable Long id) {
        return ResponseEntity.ok(rewriteTableService.getTable(id));
    }

    @PostMapping
    public ResponseEntity<RewriteTableView> createTable(@Valid @RequestBody RewriteTableRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rewriteTableService.createTable(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RewriteTableView> updateTable(@PathVariable Long id, @Valid @RequestBody RewriteTableRequest request) {
        return ResponseEntity.ok(rewriteTableService.updateTable(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTable(@PathVariable Long id) {
        rewriteTableService.deleteTable(id);
        return ResponseEntity.noContent().build();
    }

    /** 按行导入：域名模式 host:port [备注]。 */
    @PostMapping("/{id}/import")
    public ResponseEntity<RewriteTableView> importEntries(@PathVariable Long id,
                                                         @Valid @RequestBody RewriteTableImportRequest request) {
        return ResponseEntity.ok(rewriteTableService.importEntries(id, request));
    }

    /** 把多条固定目标改写路由合并为改写表；dryRun=true 时只返回预览。 */
    @PostMapping("/merge-from-routes")
    public ResponseEntity<RewriteMergePreview> mergeFromRoutes(@Valid @RequestBody RewriteMergeRequest request) {
        return ResponseEntity.ok(rewriteMergeService.merge(request));
    }

    @GetMapping(value = "/{id}/export", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> exportEntries(@PathVariable Long id) {
        return ResponseEntity.ok(rewriteTableService.exportEntries(id));
    }
}
