package com.example.flashsale.common;

import lombok.Getter;

@Getter
public enum ErrorCode {
    SUCCESS(0, "OK"),

    INVALID_PARAMETER(1001, "请求参数格式或取值非法"),
    MISSING_PARAMETER(1002, "缺少必填参数或 Header"),
    INVALID_IDEMPOTENCY_KEY(1003, "幂等键格式非法"),
    INVALID_REQUEST_BODY(1004, "请求体格式非法"),
    UNAUTHORIZED(1005, "未认证：缺少或无效的 JWT 访问令牌"),

    FORBIDDEN(2001, "账号被封禁或无权访问资源"),
    ACTIVITY_NOT_FOUND(2002, "活动不存在"),
    ACTIVITY_NOT_STARTED(2003, "活动未开始"),
    ACTIVITY_ENDED(2004, "活动已结束"),
    ACTIVITY_PAUSED(2005, "活动已暂停"),
    INVALID_SECKILL_TOKEN(2006, "秒杀 token/path 无效、已使用或已过期"),
    PURCHASE_LIMIT_EXCEEDED(2007, "已超过限购"),
    INSUFFICIENT_STOCK(2008, "库存不足"),
    ACTIVITY_UNAVAILABLE(2009, "活动当前不可下单"),
    ORDER_ALREADY_EXISTS(2010, "当前用户已存在该活动订单"),
    ORDER_TIMEOUT(2011, "订单已超时关闭"),
    ORDER_NOT_PAYABLE(2012, "订单当前状态不可支付"),
    TOO_MANY_REQUESTS(2013, "请求过于频繁，请稍后重试"),
    ORDER_NOT_FOUND(2014, "订单不存在"),

    SYSTEM_ERROR(5001, "未分类系统异常"),
    REDIS_UNAVAILABLE(5002, "Redis 不可用，秒杀链路 fail-close"),
    MQ_PUBLISH_FAILED(5003, "MQ 发布确认失败或超时"),
    DATABASE_ERROR(5004, "数据库访问或事务失败"),
    DEPENDENCY_TIMEOUT(5005, "依赖服务超时");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}