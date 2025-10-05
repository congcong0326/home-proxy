package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.RateLimitDTO;
import org.congcong.common.enums.RateLimitScopeType;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.dto.RateLimitCreateRequest;
import org.congcong.controlmanager.dto.RateLimitUpdateRequest;
import org.congcong.controlmanager.entity.RateLimit;
import org.congcong.controlmanager.repository.RateLimitRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * 限流服务层
 */
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RateLimitRepository rateLimitRepository;

    /**
     * 分页查询限流策略列表
     */
    public PageResponse<RateLimitDTO> getRateLimits(Pageable pageable, RateLimitScopeType scopeType, Boolean enabled) {
        Page<RateLimit> rateLimitPage = rateLimitRepository.findByConditions(scopeType, enabled, pageable);
        return convertToPageResponse(rateLimitPage);
    }

    /**
     * 根据ID查询限流策略详情
     */
    public Optional<RateLimitDTO> getRateLimitById(Long id) {
        return rateLimitRepository.findById(id)
                .map(this::convertToDTO);
    }

    /**
     * 创建限流策略
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RateLimitDTO createRateLimit(RateLimitCreateRequest request) {
        RateLimit rateLimit = convertFromCreateRequest(request);
        
        // 业务校验
        validateRateLimit(rateLimit);
        
        RateLimit savedRateLimit = rateLimitRepository.save(rateLimit);

        return convertToDTO(savedRateLimit);
    }

    /**
     * 更新限流策略
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RateLimitDTO updateRateLimit(Long id, RateLimitUpdateRequest request) {
        RateLimit existingRateLimit = rateLimitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "限流策略不存在: " + id));

        // 从请求DTO更新实体
        updateRateLimitFromRequest(existingRateLimit, request);
        
        // 业务校验
        validateRateLimit(existingRateLimit);

        RateLimit savedRateLimit = rateLimitRepository.save(existingRateLimit);

        return convertToDTO(savedRateLimit);
    }

    /**
     * 删除限流策略
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public void deleteRateLimit(Long id) {
        if (!rateLimitRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "限流策略不存在: " + id);
        }
        rateLimitRepository.deleteById(id);
    }

    /**
     * 根据范围类型查询限流策略
     */
    public List<RateLimitDTO> getRateLimitsByScopeType(RateLimitScopeType scopeType) {
        List<RateLimit> rateLimits = rateLimitRepository.findByScopeType(scopeType);
        return rateLimits.stream()
                .map(this::convertToDTO)
                .toList();
    }

    /**
     * 根据启用状态查询限流策略
     */
    public List<RateLimitDTO> getRateLimitsByEnabled(Boolean enabled) {
        List<RateLimit> rateLimits = rateLimitRepository.findByEnabled(enabled);
        return rateLimits.stream()
                .map(this::convertToDTO)
                .toList();
    }



    /**
     * 业务校验
     */
    private void validateRateLimit(RateLimit rateLimit) {
        // 校验范围类型和用户ID的一致性
        if (rateLimit.getScopeType() == RateLimitScopeType.USERS) {
            if (rateLimit.getUserIds() == null || rateLimit.getUserIds().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指定用户范围时，用户ID列表不能为空");
            }
        } else if (rateLimit.getScopeType() == RateLimitScopeType.GLOBAL) {
            // 全局范围时，用户ID列表应为空
            if (rateLimit.getUserIds() != null && !rateLimit.getUserIds().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "全局范围时，用户ID列表应为空");
            }
        }

        // 校验带宽限制值
        if (rateLimit.getUplinkLimitBps() != null && rateLimit.getUplinkLimitBps() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上行带宽限制必须大于0");
        }
        if (rateLimit.getDownlinkLimitBps() != null && rateLimit.getDownlinkLimitBps() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "下行带宽限制必须大于0");
        }
        if (rateLimit.getBurstBytes() != null && rateLimit.getBurstBytes() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "突发字节数必须大于0");
        }

        // 校验时间范围
        if (rateLimit.getEffectiveTimeStart() != null && rateLimit.getEffectiveTimeEnd() != null) {
            if (rateLimit.getEffectiveTimeStart().isAfter(rateLimit.getEffectiveTimeEnd())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生效开始时间不能晚于结束时间");
            }
        }

        // 校验日期范围
        if (rateLimit.getEffectiveFrom() != null && rateLimit.getEffectiveTo() != null) {
            if (rateLimit.getEffectiveFrom().isAfter(rateLimit.getEffectiveTo())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生效开始日期不能晚于结束日期");
            }
        }
    }

    /**
     * 将创建请求DTO转换为RateLimit实体
     */
    private RateLimit convertFromCreateRequest(RateLimitCreateRequest request) {
        RateLimit rateLimit = new RateLimit();
        BeanUtils.copyProperties(request, rateLimit);
        return rateLimit;
    }

    /**
     * 从更新请求DTO更新RateLimit实体
     */
    private void updateRateLimitFromRequest(RateLimit rateLimit, RateLimitUpdateRequest request) {
        rateLimit.setScopeType(request.getScopeType());
        rateLimit.setUserIds(request.getUserIds());
        rateLimit.setUplinkLimitBps(request.getUplinkLimitBps());
        rateLimit.setDownlinkLimitBps(request.getDownlinkLimitBps());
        rateLimit.setBurstBytes(request.getBurstBytes());
        rateLimit.setEnabled(request.getEnabled());
        rateLimit.setEffectiveTimeStart(request.getEffectiveTimeStart());
        rateLimit.setEffectiveTimeEnd(request.getEffectiveTimeEnd());
        rateLimit.setEffectiveFrom(request.getEffectiveFrom());
        rateLimit.setEffectiveTo(request.getEffectiveTo());
    }

    /**
     * 将RateLimit实体转换为RateLimitDTO
     */
    private RateLimitDTO convertToDTO(RateLimit rateLimit) {
        RateLimitDTO dto = new RateLimitDTO();
        BeanUtils.copyProperties(rateLimit, dto);
        return dto;
    }

    /**
     * 将分页RateLimit实体转换为分页RateLimitDTO
     */
    private PageResponse<RateLimitDTO> convertToPageResponse(Page<RateLimit> rateLimitPage) {
        List<RateLimitDTO> rateLimitDTOs = rateLimitPage.getContent().stream()
                .map(this::convertToDTO)
                .toList();

        return new PageResponse<>(
                rateLimitDTOs,
                rateLimitPage.getTotalElements(),
                rateLimitPage.getNumber() + 1, // Spring Data JPA页码从0开始，转换为从1开始
                rateLimitPage.getSize()
        );
    }
}