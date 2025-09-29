package org.congcong.controlmanager.enums;

/**
 * 管理员角色枚举
 */
public enum AdminRole {
    /**
     * 超级管理员 - 拥有所有权限，包括创建和禁用其他管理员
     */
    SUPER_ADMIN("SUPER_ADMIN", "超级管理员"),
    
    /**
     * 普通管理员 - 拥有基本管理权限，但不能管理其他管理员
     */
    ADMIN("ADMIN", "普通管理员");
    
    private final String code;
    private final String description;
    
    AdminRole(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 根据代码获取角色枚举
     */
    public static AdminRole fromCode(String code) {
        for (AdminRole role : values()) {
            if (role.code.equals(code)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown role code: " + code);
    }
    
    /**
     * 检查是否为超级管理员
     */
    public boolean isSuperAdmin() {
        return this == SUPER_ADMIN;
    }
}