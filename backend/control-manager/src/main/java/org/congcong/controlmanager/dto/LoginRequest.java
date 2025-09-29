package org.congcong.controlmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank
    @Size(min = 1, max = 64)
    @Pattern(regexp = "[A-Za-z0-9_]+")
    private String username;

    @NotBlank
    @Size(min = 8, max = 64)
    private String password;
}