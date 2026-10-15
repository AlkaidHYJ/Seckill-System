package com.example.flashsale.vo;

import lombok.Data;

@Data
public class LoginResponse {

    private String accessToken;
    private String tokenType;
    private long expiresIn;
    private Long userId;
    private String username;

    public LoginResponse(String accessToken, String tokenType, long expiresIn,
                         Long userId, String username) {
        this.accessToken = accessToken;
        this.tokenType = tokenType;
        this.expiresIn = expiresIn;
        this.userId = userId;
        this.username = username;
    }
}