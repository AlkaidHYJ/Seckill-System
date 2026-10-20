package com.example.flashsale.enums;

public enum SeckillLuaResult {
    SUCCESS(0),
    STOCK_EMPTY(1),
    DUPLICATE(2),
    INVALID_TOKEN(3);

    private final long code;

    SeckillLuaResult(long code) {
        this.code = code;
    }

    public static SeckillLuaResult fromCode(Long code) {
        if (code == null) {
            throw new IllegalStateException("Redis Lua returned null result");
        }
        for (SeckillLuaResult result : values()) {
            if (result.code == code) {
                return result;
            }
        }
        throw new IllegalStateException("Unknown Redis Lua result: " + code);
    }
}
