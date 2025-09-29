package org.congcong.controlmanager.dto;

import lombok.Data;

import java.util.List;

@Data
public class UserResponse {
    private Long id;
    private String username;
    private List<String> roles;
    private boolean mustChangePassword;

    public UserResponse() {}
    public UserResponse(Long id, String username, List<String> roles, boolean mustChangePassword) {
        this.id = id;
        this.username = username;
        this.roles = roles;
        this.mustChangePassword = mustChangePassword;
    }

}