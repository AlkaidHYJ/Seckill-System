package com.example.flashsale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank
    @Size(max = 64)
    private String username;

    @NotBlank
    @Size(min = 8, max = 72)
    private String password;
}