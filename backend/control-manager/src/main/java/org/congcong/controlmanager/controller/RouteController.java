package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.dto.route.CreateRouteRequest;
import org.congcong.controlmanager.dto.route.RouteDTO;
import org.congcong.controlmanager.dto.route.UpdateRouteRequest;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.service.RouteService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 路由控制器
 */
@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;

    /**
     * 分页查询路由列表
     * GET /api/routes
     */
    @GetMapping
    public ResponseEntity<PageResponse<RouteDTO>> getRoutes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) RoutePolicy policy,
            @RequestParam(required = false) Integer status) {
        
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction) ? 
            Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(sortDirection, sort));
        
        PageResponse<RouteDTO> response = routeService.getRoutes(pageable, name, policy, status);
        return ResponseEntity.ok(response);
    }

    /**
     * 获取路由详情
     * GET /api/routes/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<RouteDTO> getRouteById(@PathVariable Long id) {
        RouteDTO route = routeService.getRouteById(id);
        return ResponseEntity.ok(route);
    }

    /**
     * 创建路由
     * POST /api/routes
     */
    @PostMapping
    public ResponseEntity<RouteDTO> createRoute(@Valid @RequestBody CreateRouteRequest request) {
        RouteDTO route = routeService.createRoute(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(route);
    }

    /**
     * 更新路由
     * PUT /api/routes/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<RouteDTO> updateRoute(@PathVariable Long id, 
                                               @Valid @RequestBody UpdateRouteRequest request) {
        RouteDTO route = routeService.updateRoute(id, request);
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
    public ResponseEntity<List<RouteDTO>> getEnabledRoutes() {
        List<RouteDTO> routes = routeService.getEnabledRoutes();
        return ResponseEntity.ok(routes);
    }
}