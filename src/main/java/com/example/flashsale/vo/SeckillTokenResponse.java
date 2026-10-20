package com.example.flashsale.vo;

import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
public class SeckillTokenResponse {

    private String seckillToken;
    private String path;
    private OffsetDateTime expiresAt;

    public SeckillTokenResponse(String seckillToken, String path, OffsetDateTime expiresAt) {
        this.seckillToken = seckillToken;
        this.path = path;
        this.expiresAt = expiresAt;
    }
}
