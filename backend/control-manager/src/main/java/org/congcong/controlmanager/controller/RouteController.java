package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.dto.route.RouteRequest;
import org.congcong.controlmanager.dto.route.RouteView;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.service.RouteService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 路由控制器
 */
@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
@Validated
public class RouteController {

    private final RouteService routeService;

    /**
     * 分页查询路由列表
     * GET /api/routes
     */
    @GetMapping
    public ResponseEntity<PageResponse<RouteView>> getRoutes(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) int pageSize,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) RoutePolicy policy,
            @RequestParam(required = false) Integer status) {
        
        pageSize = Math.min(pageSize, 200);
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(sortDir) ?
            Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page - 1, pageSize, Sort.by(sortDirection, sortBy));
        
        PageResponse<RouteView> response = routeService.getRoutes(pageable, name, policy, status);
        return ResponseEntity.ok(response);
    }

    /**
     * 获取路由详情
     * GET /api/routes/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<RouteView> getRouteById(@PathVariable Long id) {
        RouteView route = routeService.getRouteById(id);
        return ResponseEntity.ok(route);
    }

    /**
     * 创建路由
     * POST /api/routes
     */
    @PostMapping
    public ResponseEntity<RouteView> createRoute(@Valid @RequestBody RouteRequest request) {
        RouteView route = routeService.createRoute(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(route);
    }

    /**
     * 更新路由
     * PUT /api/routes/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<RouteView> updateRoute(@PathVariable Long id, 
                                               @Valid @RequestBody RouteRequest request) {
        RouteView route = routeService.updateRoute(id, request);
        return ResponseEntity.ok(route);
    }

    /**
     * 删除路由
     * DELETE /api/routes/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRoute(@PathVariable Long id) {
        routeService.deleteRoute(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 获取所有启用的路由（用于下拉选择）
     * GET /api/routes/enabled
     */
    @GetMapping("/enabled")
    public ResponseEntity<List<RouteView>> getEnabledRoutes() {
        List<RouteView> routes = routeService.getEnabledRoutes();
        return ResponseEntity.ok(routes);
    }
}
