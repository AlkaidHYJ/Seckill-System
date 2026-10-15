package com.example.flashsale.common;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public Result(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    public static <T> Result<T> success(T data, String traceId) {
        return new Result<>(0, "OK", data, traceId);
    }

    public static <T> Result<T> failure(ErrorCode errorCode, String traceId) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null, traceId);
    }

    public static <T> Result<T> failure(ErrorCode errorCode, String message, String traceId) {
        return new Result<>(errorCode.getCode(), message, null, traceId);
    }
}