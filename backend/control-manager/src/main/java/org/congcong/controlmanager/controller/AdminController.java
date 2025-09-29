package org.congcong.controlmanager.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.dto.ChangePasswordRequest;
import org.congcong.controlmanager.dto.CreateAdminRequest;
import org.congcong.controlmanager.dto.DeleteAdminRequest;
import org.congcong.controlmanager.dto.DisableAdminRequest;
import org.congcong.controlmanager.dto.LoginRequest;
import org.congcong.controlmanager.dto.LoginResponse;
import org.congcong.controlmanager.dto.UserResponse;
import org.congcong.controlmanager.entity.AdminUser;
import org.congcong.controlmanager.service.AdminAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
    private final AdminAuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");
        return ResponseEntity.ok(authService.login(req, ip, ua));
    }

    @PostMapping("/change-password")
    public ResponseEntity<LoginResponse> changePassword(Authentication authentication, @Valid @RequestBody ChangePasswordRequest req) {
        AdminUser user = (AdminUser) authentication.getPrincipal();
        return ResponseEntity.ok(authService.changePassword(user, req));
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(Authentication authentication) {
        AdminUser user = (AdminUser) authentication.getPrincipal();
        List<String> roles = user.getRoles() == null ? List.of() : java.util.Arrays.stream(user.getRoles().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return ResponseEntity.ok(new UserResponse(user.getId(), user.getUsername(), roles, user.isMustChangePassword()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            authService.logout(token);
        }
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }

    /**
     * 创建管理员 - 只有SUPER_ADMIN角色可以访问
     */
    @PostMapping("/create")
    public ResponseEntity<UserResponse> createAdmin(Authentication authentication, @Valid @RequestBody CreateAdminRequest req) {
        AdminUser currentUser = (AdminUser) authentication.getPrincipal();
        UserResponse newUser = authService.createAdmin(currentUser, req);
        return ResponseEntity.ok(newUser);
    }

    /**
     * 禁用管理员 - 只有SUPER_ADMIN角色可以访问
     */
    @PostMapping("/disable")
    public ResponseEntity<?> disableAdmin(Authentication authentication, @Valid @RequestBody DisableAdminRequest req) {
        AdminUser currentUser = (AdminUser) authentication.getPrincipal();
        authService.disableAdmin(currentUser, req);
        return ResponseEntity.ok(java.util.Map.of("ok", true, "message", "管理员已禁用"));
    }

    /**
     * 删除管理员 - 只有SUPER_ADMIN角色可以访问
     */
    @PostMapping("/delete")
    public ResponseEntity<?> deleteAdmin(Authentication authentication, @Valid @RequestBody DeleteAdminRequest req) {
        AdminUser currentUser = (AdminUser) authentication.getPrincipal();
        authService.deleteAdmin(currentUser, req);
        return ResponseEntity.ok(java.util.Map.of("ok", true, "message", "管理员已删除"));
    }
}