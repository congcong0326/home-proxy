package org.congcong.controlmanager.service;

import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.config.AdminAuthProperties;
import org.congcong.controlmanager.dto.ChangePasswordRequest;
import org.congcong.controlmanager.dto.CreateAdminRequest;
import org.congcong.controlmanager.dto.DeleteAdminRequest;
import org.congcong.controlmanager.dto.DisableAdminRequest;
import org.congcong.controlmanager.dto.LoginRequest;
import org.congcong.controlmanager.dto.LoginResponse;
import org.congcong.controlmanager.dto.UserResponse;
import org.congcong.controlmanager.entity.AdminLoginHistory;
import org.congcong.controlmanager.entity.AdminTokenBlacklist;
import org.congcong.controlmanager.entity.AdminUser;
import org.congcong.controlmanager.enums.AdminRole;
import org.congcong.controlmanager.repository.AdminLoginHistoryRepository;
import org.congcong.controlmanager.repository.AdminTokenBlacklistRepository;
import org.congcong.controlmanager.repository.AdminUserRepository;
import org.congcong.controlmanager.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
public class AdminAuthService {
    private final AdminUserRepository userRepo;
    private final AdminLoginHistoryRepository historyRepo;
    private final AdminTokenBlacklistRepository blacklistRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;
    private final AdminAuthProperties props;

    public LoginResponse login(LoginRequest req, String ip, String ua) {
        AdminUser user = userRepo.findByUsername(req.getUsername()).orElse(null);
        if (user == null || user.getStatus() != 1 || !encoder.matches(req.getPassword(), user.getPasswordHash())) {
            recordLogin(user == null ? null : user.getId(), req.getUsername(), false, ip, ua, "bad_credentials");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad credentials");
        }
        String token = jwtService.issue(user);
        long expiresIn = props.getJwt().getTtlDays() * 24L * 3600L;
        UserResponse ur = new UserResponse(user.getId(), user.getUsername(), roles(user), user.isMustChangePassword());
        recordLogin(user.getId(), user.getUsername(), true, ip, ua, null);
        return new LoginResponse(token, user.isMustChangePassword(), expiresIn, ur);
    }

    public LoginResponse changePassword(AdminUser authedUser, ChangePasswordRequest req) {
        AdminUser user = userRepo.findById(authedUser.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        String old = req.getOldPassword();
        String nw = req.getNewPassword();
        if (nw == null || nw.length() < 8 || nw.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid new password");
        }
        if (old != null && old.equals(nw)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "old and new are same");
        }
        if (!user.isMustChangePassword()) {
            if (old == null || !encoder.matches(old, user.getPasswordHash())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "old password incorrect");
            }
        }
        user.setPasswordHash(encoder.encode(nw));
        user.setMustChangePassword(false);
        user.setVer(user.getVer() + 1);
        userRepo.save(user);
        String token = jwtService.issue(user);
        long expiresIn = props.getJwt().getTtlDays() * 24L * 3600L;
        UserResponse ur = new UserResponse(user.getId(), user.getUsername(), roles(user), user.isMustChangePassword());
        return new LoginResponse(token, false, expiresIn, ur);
    }

    public void logout(String token) {
        DecodedJWT jwt = jwtService.verify(token);
        AdminTokenBlacklist bl = new AdminTokenBlacklist();
        bl.setJti(jwt.getId());
        bl.setExpiresAt(LocalDateTime.ofEpochSecond(jwt.getExpiresAt().toInstant().getEpochSecond(), 0, java.time.ZoneOffset.UTC));
        blacklistRepo.save(bl);
    }

    private void recordLogin(Long userId, String username, boolean success, String ip, String ua, String reason) {
        AdminLoginHistory h = new AdminLoginHistory();
        h.setUserId(userId);
        h.setUsername(username);
        h.setSuccess(success);
        h.setIp(ip);
        h.setUa(ua);
        h.setReason(reason);
        historyRepo.save(h);
    }

    private java.util.List<String> roles(AdminUser user) {
        if (user.getRoles() == null || user.getRoles().isEmpty()) return java.util.List.of();
        return Arrays.stream(user.getRoles().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * 创建管理员 - 只有SUPER_ADMIN可以调用
     */
    public UserResponse createAdmin(AdminUser currentUser, CreateAdminRequest req) {
        // 检查当前用户是否为SUPER_ADMIN
        if (!isSuperAdmin(currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有超级管理员可以创建管理员");
        }
        
        // 检查用户名是否已存在
        if (userRepo.findByUsername(req.getUsername()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名已存在");
        }
        
        // 创建新管理员
        AdminUser newUser = new AdminUser();
        newUser.setUsername(req.getUsername());
        newUser.setPasswordHash(encoder.encode(req.getPassword()));
        
        // 设置角色，如果未指定则默认为ADMIN
        String roles = req.getRoles();
        if (roles == null || roles.trim().isEmpty()) {
            roles = AdminRole.ADMIN.getCode();
        }
        newUser.setRoles(roles);
        
        newUser.setMustChangePassword(true); // 新建用户必须修改密码
        newUser.setVer(1);
        newUser.setStatus(1); // 启用状态
        newUser.setCreatedAt(LocalDateTime.now());
        newUser.setUpdatedAt(LocalDateTime.now());
        
        AdminUser savedUser = userRepo.save(newUser);
        return new UserResponse(savedUser.getId(), savedUser.getUsername(), roles(savedUser), savedUser.isMustChangePassword());
    }
    
    /**
     * 禁用管理员 - 只有SUPER_ADMIN可以调用
     */
    public void disableAdmin(AdminUser currentUser, DisableAdminRequest req) {
        // 检查当前用户是否为SUPER_ADMIN
        if (!isSuperAdmin(currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有超级管理员可以禁用管理员");
        }
        
        // 不能禁用自己
        if (currentUser.getId().equals(req.getUserId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能禁用自己");
        }
        
        AdminUser targetUser = userRepo.findById(req.getUserId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        
        // 如果目标用户也是SUPER_ADMIN，需要额外检查
        if (isSuperAdmin(targetUser)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能禁用超级管理员");
        }
        
        targetUser.setStatus(0); // 设置为禁用状态
        targetUser.setUpdatedAt(LocalDateTime.now());
        userRepo.save(targetUser);
    }
    
    /**
     * 删除管理员
     */
    public void deleteAdmin(AdminUser currentUser, DeleteAdminRequest req) {
        // 检查当前用户是否为SUPER_ADMIN
        if (!isSuperAdmin(currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有超级管理员可以删除管理员");
        }
        
        // 不能删除自己
        if (currentUser.getId().equals(req.getUserId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能删除自己");
        }
        
        AdminUser targetUser = userRepo.findById(req.getUserId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        
        // 如果目标用户也是SUPER_ADMIN，需要额外检查
        if (isSuperAdmin(targetUser)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能删除超级管理员");
        }
        
        // 删除用户
        userRepo.delete(targetUser);
    }
    
    /**
     * 检查并创建默认管理员账号
     * 在应用启动时调用，如果没有管理员账号则创建默认的admin账号
     */
    public void ensureDefaultAdminExists() {
        // 检查是否已存在管理员账号
        long adminCount = userRepo.count();
        if (adminCount > 0) {
            return; // 已有管理员，无需创建
        }
        
        // 创建默认管理员账号
        AdminUser defaultAdmin = new AdminUser();
        defaultAdmin.setUsername("admin");
        // 默认密码为 "admin123"，用户首次登录时必须修改
        defaultAdmin.setPasswordHash(encoder.encode("admin123"));
        defaultAdmin.setRoles(AdminRole.SUPER_ADMIN.getCode());
        defaultAdmin.setMustChangePassword(true);
        defaultAdmin.setVer(1);
        defaultAdmin.setStatus(1);
        defaultAdmin.setCreatedAt(LocalDateTime.now());
        defaultAdmin.setUpdatedAt(LocalDateTime.now());
        
        userRepo.save(defaultAdmin);
    }

    /**
     * 检查用户是否为超级管理员
     */
    private boolean isSuperAdmin(AdminUser user) {
        if (user.getRoles() == null || user.getRoles().isEmpty()) {
            return false;
        }
        return Arrays.stream(user.getRoles().split(","))
            .map(String::trim)
            .anyMatch(role -> AdminRole.SUPER_ADMIN.getCode().equals(role));
    }
}