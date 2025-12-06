package org.congcong.controlmanager.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.UserDTO;
import org.congcong.controlmanager.dto.*;
import org.congcong.controlmanager.entity.User;
import org.congcong.controlmanager.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final UserService userService;

    /**
     * 分页查询用户列表
     * GET /api/users
     */
    @GetMapping
    public ResponseEntity<PageResponse<UserDTO>> getUsers(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) int pageSize,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        // 限制最大页面大小
        pageSize = Math.min(pageSize, 200);

        // 创建排序对象
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sort = Sort.by(direction, sortBy);

        // 创建分页对象 (Spring Data JPA 的页码从0开始)
        Pageable pageable = PageRequest.of(page - 1, pageSize, sort);

        PageResponse<UserDTO> response;
        if (q != null && !q.trim().isEmpty()) {
            // 如果有搜索关键字，进行模糊搜索
            response = userService.findByStatusAndUsernameContaining(status, q.trim(), pageable);
        } else if (status != null) {
            // 如果只有状态过滤
            response = userService.findByStatusAndUsernameContaining(status, null, pageable);
        } else {
            // 否则查询所有用户
            response = userService.findAll(pageable);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 根据ID查询用户详情
     * GET /api/users/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserDTO> getUserById(@PathVariable Long id) {
        Optional<UserDTO> userDTO = userService.findById(id);
        if (userDTO.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        return ResponseEntity.ok(userDTO.get());
    }

    /**
     * 创建用户
     * POST /api/users
     */
    @PostMapping
    public ResponseEntity<UserDTO> createUser(@Valid @RequestBody CreateUserRequest request) {
        User user = new User();
        BeanUtils.copyProperties(request, user);

        UserDTO userDTO = userService.createUser(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(userDTO);
    }

    /**
     * 更新用户信息
     * PUT /api/users/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<UserDTO> updateUser(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        User user = new User();
        BeanUtils.copyProperties(request, user);

        UserDTO userDTO = userService.updateUser(id, user);
        return ResponseEntity.ok(userDTO);
    }

    /**
     * 删除用户
     * DELETE /api/users/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 重置用户凭证
     * PUT /api/users/{id}/credential
     */
    @PutMapping("/{id}/credential")
    public ResponseEntity<Void> resetUserCredential(@PathVariable Long id, @RequestBody String credential) {
        userService.resetCredential(id, credential);
        return ResponseEntity.ok().build();
    }

    /**
     * 更新用户状态
     * PUT /api/users/{id}/status
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<Void> updateUserStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest request) {
        userService.updateStatus(id, request.getStatus());
        return ResponseEntity.ok().build();
    }

    // 内部DTO类
    @Data
    public static class ResetCredentialRequest {
        @NotBlank(message = "新凭证不能为空")
        private String newCredential;
    }

    @Data
    public static class UpdateStatusRequest {
        @NotNull(message = "状态不能为空")
        private Integer status;
    }
}