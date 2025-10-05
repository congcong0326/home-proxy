package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.RouteDTO;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.dto.route.CreateRouteRequest;
import org.congcong.controlmanager.dto.route.UpdateRouteRequest;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.repository.RouteRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * 路由服务层
 */
@Service
@RequiredArgsConstructor
public class RouteService {

    private final RouteRepository routeRepository;

    /**
     * 分页查询路由列表
     */
    public PageResponse<RouteDTO> getRoutes(Pageable pageable, String name, RoutePolicy policy, Integer status) {
        Page<Route> routePage;
        
        // 使用复合条件查询
        routePage = routeRepository.findByConditions(name, policy, status, pageable);
        
        List<RouteDTO> routeDTOs = routePage.getContent().stream()
                .map(this::convertToDTO)
                .toList();
        
        return new PageResponse<>(
                routeDTOs,
                routePage.getTotalElements(),
                routePage.getNumber(),
                routePage.getSize()
        );
    }

    /**
     * 根据ID获取路由详情
     */
    public RouteDTO getRouteById(Long id) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "路由不存在"));
        return convertToDTO(route);
    }

    /**
     * 创建路由
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RouteDTO createRoute(CreateRouteRequest request) {
        // 检查名称是否已存在
        if (routeRepository.existsByName(request.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "路由名称已存在");
        }

        Route route = new Route();
        route.setName(request.getName());
        route.setRules(request.getRules());
        route.setPolicy(request.getPolicy());
        route.setOutboundTag(request.getOutboundTag());
        route.setOutboundProxyType(request.getOutboundProxyType());
        route.setOutboundProxyHost(request.getOutboundProxyHost());
        route.setOutboundProxyPort(request.getOutboundProxyPort());
        route.setOutboundProxyUsername(request.getOutboundProxyUsername());
        route.setOutboundProxyPassword(request.getOutboundProxyPassword());
        route.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        route.setNotes(request.getNotes());

        Route savedRoute = routeRepository.save(route);

        return convertToDTO(savedRoute);
    }

    /**
     * 更新路由
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RouteDTO updateRoute(Long id, UpdateRouteRequest request) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "路由不存在"));

        // 检查名称是否与其他路由冲突
        if (request.getName() != null && !request.getName().equals(route.getName())) {
            if (routeRepository.existsByNameAndIdNot(request.getName(), id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "路由名称已存在");
            }
            route.setName(request.getName());
        }

        // 更新其他字段
        if (request.getRules() != null) {
            route.setRules(request.getRules());
        }
        if (request.getPolicy() != null) {
            route.setPolicy(request.getPolicy());
        }
        if (request.getOutboundTag() != null) {
            route.setOutboundTag(request.getOutboundTag());
        }
        if (request.getOutboundProxyType() != null) {
            route.setOutboundProxyType(request.getOutboundProxyType());
        }
        if (request.getOutboundProxyHost() != null) {
            route.setOutboundProxyHost(request.getOutboundProxyHost());
        }
        if (request.getOutboundProxyPort() != null) {
            route.setOutboundProxyPort(request.getOutboundProxyPort());
        }
        if (request.getOutboundProxyUsername() != null) {
            route.setOutboundProxyUsername(request.getOutboundProxyUsername());
        }
        if (request.getOutboundProxyPassword() != null) {
            route.setOutboundProxyPassword(request.getOutboundProxyPassword());
        }
        if (request.getStatus() != null) {
            route.setStatus(request.getStatus());
        }
        if (request.getNotes() != null) {
            route.setNotes(request.getNotes());
        }

        Route savedRoute = routeRepository.save(route);

        return convertToDTO(savedRoute);
    }

    /**
     * 删除路由
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public void deleteRoute(Long id) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "路由不存在"));

        // TODO: 检查是否被入站配置引用，如果被引用则不能删除
        // if (isRouteReferenced(id)) {
        //     throw new ResponseStatusException(HttpStatus.CONFLICT, "路由正在被使用，无法删除");
        // }

        routeRepository.delete(route);
    }

    /**
     * 获取所有启用的路由（用于下拉选择）
     */
    public List<RouteDTO> getEnabledRoutes() {
        List<Route> routes = routeRepository.findByStatus(1);
        return routes.stream()
                .map(this::convertToDTO)
                .toList();
    }

    /**
     * 转换为DTO
     */
    private RouteDTO convertToDTO(Route route) {
        RouteDTO dto = new RouteDTO();
        dto.setId(route.getId());
        dto.setName(route.getName());
        dto.setRules(route.getRules());
        dto.setPolicy(route.getPolicy());
        dto.setOutboundTag(route.getOutboundTag());
        dto.setOutboundProxyType(route.getOutboundProxyType());
        dto.setOutboundProxyHost(route.getOutboundProxyHost());
        dto.setOutboundProxyPort(route.getOutboundProxyPort());
        dto.setOutboundProxyUsername(route.getOutboundProxyUsername());
        dto.setOutboundProxyPassword(route.getOutboundProxyPassword());
        dto.setStatus(route.getStatus());
        dto.setNotes(route.getNotes());
        dto.setCreatedAt(route.getCreatedAt());
        dto.setUpdatedAt(route.getUpdatedAt());
        return dto;
    }
}