package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.InboundConfigDTO;
import org.congcong.common.enums.ProtocolType;
import org.congcong.controlmanager.dto.InboundConfigCreateRequest;
import org.congcong.controlmanager.dto.InboundConfigUpdateRequest;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.repository.InboundConfigRepository;
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
 * 入站配置服务层
 */
@Service
@RequiredArgsConstructor
public class InboundConfigService {

    private final InboundConfigRepository inboundConfigRepository;

    /**
     * 分页查询入站配置列表
     */
    public PageResponse<InboundConfigDTO> getInboundConfigs(Pageable pageable, ProtocolType protocol,
                                                            Integer port, Boolean tlsEnabled, Integer status) {
        Page<InboundConfig> inboundPage = inboundConfigRepository.findByConditions(protocol, port, tlsEnabled, status, pageable);
        return convertToPageResponse(inboundPage);
    }

    /**
     * 根据ID查询入站配置详情
     */
    public Optional<InboundConfigDTO> getInboundConfigById(Long id) {
        return inboundConfigRepository.findById(id)
                .map(this::convertToDTO);
    }

    /**
     * 创建入站配置
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public InboundConfigDTO createInboundConfig(InboundConfigCreateRequest request) {
        // 业务校验
        validateInboundConfig(request);
        
        InboundConfig inboundConfig = convertFromCreateRequest(request);
        
        // 业务校验
        validateInboundConfigEntity(inboundConfig);
        
        InboundConfig savedInboundConfig = inboundConfigRepository.save(inboundConfig);

        return convertToDTO(savedInboundConfig);
    }

    /**
     * 更新入站配置
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public InboundConfigDTO updateInboundConfig(Long id, InboundConfigUpdateRequest request) {
        InboundConfig existingInboundConfig = inboundConfigRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "入站配置不存在: " + id));

        // 从请求DTO更新实体
        updateInboundConfigFromRequest(existingInboundConfig, request);
        
        // 业务校验
        validateInboundConfigEntity(existingInboundConfig);

        InboundConfig savedInboundConfig = inboundConfigRepository.save(existingInboundConfig);

        return convertToDTO(savedInboundConfig);
    }

    /**
     * 删除入站配置
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public void deleteInboundConfig(Long id) {
        if (!inboundConfigRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "入站配置不存在: " + id);
        }
        inboundConfigRepository.deleteById(id);
    }

    /**
     * 根据协议类型查询入站配置
     */
    public List<InboundConfigDTO> getInboundConfigsByProtocol(ProtocolType protocol) {
        List<InboundConfig> inboundConfigs = inboundConfigRepository.findByProtocol(protocol);
        return inboundConfigs.stream()
                .map(this::convertToDTO)
                .toList();
    }

    /**
     * 根据状态查询入站配置
     */
    public List<InboundConfigDTO> getInboundConfigsByStatus(Integer status) {
        List<InboundConfig> inboundConfigs = inboundConfigRepository.findByStatus(status);
        return inboundConfigs.stream()
                .map(this::convertToDTO)
                .toList();
    }

    /**
     * 业务校验 - 创建请求
     */
    private void validateInboundConfig(InboundConfigCreateRequest request) {
        // 检查端口是否已被占用
        if (inboundConfigRepository.existsByListenIpAndPort(request.getListenIp(), request.getPort())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, 
                    "端口已被占用: " + request.getListenIp() + ":" + request.getPort());
        }
    }

    /**
     * 业务校验 - 实体
     */
    private void validateInboundConfigEntity(InboundConfig inboundConfig) {
        // 校验协议与TLS的组合
        if (inboundConfig.getProtocol() == ProtocolType.SOCKS5_HTTPS && inboundConfig.getTlsEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                    "SOCKS5_HTTPS协议不支持TLS，请关闭TLS或选择其他协议");
        }

        // 校验Shadowsocks协议的特殊要求
        if (inboundConfig.getProtocol() == ProtocolType.SHADOW_SOCKS) {
            if (inboundConfig.getSsMethod() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                        "Shadowsocks协议必须指定加密方法");
            }
            
            if (inboundConfig.getAllowedUserIds() == null || inboundConfig.getAllowedUserIds().size() != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                        "Shadowsocks协议必须且仅能绑定一个用户");
            }
        }

        // 校验端口冲突（更新时）
        if (inboundConfig.getId() != null) {
            if (inboundConfigRepository.existsByListenIpAndPortAndIdNot(
                    inboundConfig.getListenIp(), inboundConfig.getPort(), inboundConfig.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, 
                        "端口已被其他配置占用: " + inboundConfig.getListenIp() + ":" + inboundConfig.getPort());
            }
        }
    }

    /**
     * 将创建请求DTO转换为InboundConfig实体
     */
    private InboundConfig convertFromCreateRequest(InboundConfigCreateRequest request) {
        InboundConfig inboundConfig = new InboundConfig();
        BeanUtils.copyProperties(request, inboundConfig);
        return inboundConfig;
    }

    /**
     * 从更新请求DTO更新InboundConfig实体
     */
    private void updateInboundConfigFromRequest(InboundConfig inboundConfig, InboundConfigUpdateRequest request) {
        inboundConfig.setName(request.getName());
        inboundConfig.setProtocol(request.getProtocol());
        inboundConfig.setListenIp(request.getListenIp());
        inboundConfig.setPort(request.getPort());
        inboundConfig.setTlsEnabled(request.getTlsEnabled());
        inboundConfig.setSniffEnabled(request.getSniffEnabled());
        inboundConfig.setSsMethod(request.getSsMethod());
        inboundConfig.setAllowedUserIds(request.getAllowedUserIds());
        inboundConfig.setRouteIds(request.getRouteIds());
        inboundConfig.setStatus(request.getStatus());
        inboundConfig.setNotes(request.getNotes());
        inboundConfig.setInboundRouteBindings(request.getInboundRouteBindings());
    }

    /**
     * 将InboundConfig实体转换为InboundConfigDTO
     */
    private InboundConfigDTO convertToDTO(InboundConfig inboundConfig) {
        InboundConfigDTO dto = new InboundConfigDTO();
        BeanUtils.copyProperties(inboundConfig, dto);
        
        // 设置扩展字段
        if (inboundConfig.getAllowedUserIds() != null) {
            dto.setUserCount(inboundConfig.getAllowedUserIds().size());
        } else {
            dto.setUserCount(0);
        }
        
        return dto;
    }

    /**
     * 将分页InboundConfig实体转换为分页InboundConfigDTO
     */
    private PageResponse<InboundConfigDTO> convertToPageResponse(Page<InboundConfig> inboundPage) {
        List<InboundConfigDTO> inboundDTOs = inboundPage.getContent().stream()
                .map(this::convertToDTO)
                .toList();

        return new PageResponse<>(
                inboundDTOs,
                inboundPage.getNumber() + 1, // Spring Data JPA页码从0开始，转换为从1开始
                inboundPage.getSize(),
                inboundPage.getTotalElements()
        );
    }
}