package org.congcong.controlmanager.dto;

import lombok.Data;


@Data
public class LoginResponse {
    private String token;
    private boolean mustChangePassword;
    private long expiresIn;
    private UserResponse user;

    public LoginResponse() {}
    public LoginResponse(String token, boolean mustChangePassword, long expiresIn, UserResponse user) {
        this.token = token;
        this.mustChangePassword = mustChangePassword;
        this.expiresIn = expiresIn;
        this.user = user;
    }

}