package org.congcong.controlmanager.service;

import org.congcong.common.dto.UserDTO;
import org.congcong.common.dto.UserDtoWithCredential;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.entity.User;
import org.congcong.controlmanager.repository.UserRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    /**
     * 分页查询用户列表
     * @param pageable 分页参数
     * @return 用户分页数据
     */
    public PageResponse<UserDTO> findAll(Pageable pageable) {
        Page<User> userPage = userRepository.findAll(pageable);
        return convertToPageResponse(userPage);
    }

    /**
     * 根据用户名模糊搜索用户
     * @param username 用户名关键字
     * @param pageable 分页参数
     * @return 用户分页数据
     */
    public PageResponse<UserDTO> findByUsernameContaining(String username, Pageable pageable) {
        Page<User> userPage = userRepository.findByUsernameContaining(username, pageable);
        return convertToPageResponse(userPage);
    }

    /**
     * 根据状态和用户名搜索用户
     * @param status 用户状态
     * @param username 用户名关键字
     * @param pageable 分页参数
     * @return 用户分页数据
     */
    public PageResponse<UserDTO> findByStatusAndUsernameContaining(Integer status, String username, Pageable pageable) {
        Page<User> userPage = userRepository.findByStatusAndUsernameContaining(status, username, pageable);
        return convertToPageResponse(userPage);
    }

    /**
     * 根据状态查询用户
     * @param status 用户状态
     * @return 用户列表
     */
    public List<UserDTO> findByStatus(Integer status) {
        List<User> users = userRepository.findByStatus(status);
        return users.stream()
                .map(this::convertToDTO)
                .toList();
    }

    public List<UserDtoWithCredential> findUserDTOWithCredentialByStatus(Integer status) {
        List<User> users = userRepository.findByStatus(status);
        return users.stream()
                .map(this::convertToDTOWithCredential)
                .toList();
    }

    /**
     * 根据ID查询用户详情
     * @param id 用户ID
     * @return 用户信息
     */
    public Optional<UserDTO> findById(Long id) {
        Optional<User> userOpt = userRepository.findById(id);
        return userOpt.map(this::convertToDTO);
    }

    /**
     * 根据用户名查询用户
     * @param username 用户名
     * @return 用户信息
     */
    public Optional<UserDTO> findByUsername(String username) {
        Optional<User> userOpt = userRepository.findByUsername(username);
        return userOpt.map(this::convertToDTO);
    }

    /**
     * 创建用户
     * @param user 用户信息
     * @return 创建的用户
     * @throws ResponseStatusException 如果用户名已存在
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public UserDTO createUser(User user) {
        if (userRepository.existsByUsername(user.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在: " + user.getUsername());
        }
        // 检查IP地址唯一性（可选字段）
        if (user.getIpAddress() != null && userRepository.existsByIpAddress(user.getIpAddress())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IP地址已存在: " + user.getIpAddress());
        }
        User savedUser = userRepository.save(user);

        return convertToDTO(savedUser);
    }

    /**
     * 更新用户信息
     * @param id 用户ID
     * @param updatedUser 更新的用户信息
     * @return 更新后的用户
     * @throws ResponseStatusException 如果用户不存在或用户名冲突
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public UserDTO updateUser(Long id, User updatedUser) {
        User existingUser = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在: " + id));

        // 检查用户名是否与其他用户冲突
        if (!existingUser.getUsername().equals(updatedUser.getUsername()) &&
                userRepository.existsByUsername(updatedUser.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在: " + updatedUser.getUsername());
        }

        // 检查IP地址是否与其他用户冲突（仅当有修改且非空）
        String newIp = updatedUser.getIpAddress();
        String oldIp = existingUser.getIpAddress();
        if (newIp != null && (oldIp == null || !oldIp.equals(newIp)) && userRepository.existsByIpAddress(newIp)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IP地址已存在: " + newIp);
        }

        // 更新字段
        if (updatedUser.getUsername() != null) {
            existingUser.setUsername(updatedUser.getUsername());
        }
        if (updatedUser.getCredential() != null) {
            existingUser.setCredential(updatedUser.getCredential());
        }
        if (updatedUser.getIpAddress() != null) {
            existingUser.setIpAddress(updatedUser.getIpAddress());
        }
        if (updatedUser.getStatus() != null) {
            existingUser.setStatus(updatedUser.getStatus());
        }
        if (updatedUser.getRemark() != null) {
            existingUser.setRemark(updatedUser.getRemark());
        }
        User savedUser = userRepository.save(existingUser);

        return convertToDTO(savedUser);
    }

    /**
     * 删除用户
     * @param id 用户ID
     * @throws ResponseStatusException 如果用户不存在或用户被入站配置引用
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在: " + id));

        // TODO: 检查用户是否被入站配置引用
        // 这里需要查询 inbound_configs 表的 allowed_user_ids 字段
        // 如果被引用，应该抛出 ResponseStatusException(HttpStatus.CONFLICT, "用户被入站配置引用，无法删除")

        userRepository.delete(user);
    }

    /**
     * 检查用户名是否存在
     * @param username 用户名
     * @return 是否存在
     */
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    /**
     * 重置用户凭据
     * @param id 用户ID
     * @param newCredential 新凭据
     * @return 更新后的用户
     * @throws ResponseStatusException 如果用户不存在
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public UserDTO resetCredential(Long id, String newCredential) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在: " + id));

        user.setCredential(newCredential);
        User savedUser = userRepository.save(user);
        
        // 刷新聚合配置缓存
        return convertToDTO(savedUser);
    }

    /**
     * 启用/禁用用户
     * @param id 用户ID
     * @param status 状态 (1=enabled, 0=disabled)
     * @return 更新后的用户
     * @throws ResponseStatusException 如果用户不存在
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public UserDTO updateStatus(Long id, Integer status) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在: " + id));

        user.setStatus(status);
        User savedUser = userRepository.save(user);
        return convertToDTO(savedUser);
    }

    /**
     * 将User实体转换为UserDTO
     */
    private UserDTO convertToDTO(User user) {
        UserDTO dto = new UserDTO();
        BeanUtils.copyProperties(user, dto);
        return dto;
    }

    private UserDtoWithCredential convertToDTOWithCredential(User user) {
        UserDtoWithCredential dto = new UserDtoWithCredential();
        BeanUtils.copyProperties(user, dto);
        return dto;
    }

    /**
     * 将分页User实体转换为分页UserDTO
     */
    private PageResponse<UserDTO> convertToPageResponse(Page<User> userPage) {
        List<UserDTO> userDTOs = userPage.getContent().stream()
                .map(this::convertToDTO)
                .toList();
        
        return new PageResponse<>(
                userDTOs,
                userPage.getNumber() + 1, // Spring Data JPA页码从0开始，转换为从1开始
                userPage.getSize(),
                userPage.getTotalElements()
        );
    }


    public void ensureDefaultAnonymousUserExists() {
        if (userRepository.findByUsername("Anonymous user").isEmpty()) {
            User user = new User();
            user.setUsername("Anonymous user");
            user.setCredential("Anonymous user");
            user.setIpAddress("127.0.0.1");
            user.setStatus(1);
            user.setRemark("匿名访问用户，透明代理的兜底用户，误删除");
            userRepository.save(user);
        }
    }
}