# ADR-018：秒杀接口 Redis 限流方案

- 状态：已采纳
- 日期：2026-10-19
- 范围：v3 `/buy/{path}` 请求限流
- 不包含：验证码、黑名单、分布式锁

## 1. 背景

token/path 能阻止无凭证和重放请求，但持有合法凭证的脚本仍可能在短时间内高频调用购买接口。限流必须发生在库存 Lua 之前，并且超限请求不能产生库存副作用。

## 2. 决策

采用 Redis ZSET 滑动窗口，由 `SeckillRateLimitInterceptor` 在 JWT 鉴权之后、Controller 之前执行。一次 Lua 调用完成两个维度的清理、计数、判断和写入：

- 用户维度：`seckill:limit:user:{userId}:{activityId}`，默认每秒 5 次。
- IP 维度：`seckill:limit:ip:{ip}`，默认每秒 20 次。
- 商品维度：本批不启用；活动 ID 已包含在用户维度，商品全局限流留待后续评估。

用户和 IP 任一超限都返回业务码 `2013`、HTTP `429`，不会调用 `SeckillService`、`seckill_full.lua` 或 MQ。

## 3. 令牌桶与滑动窗口选择

选择滑动窗口：实现只依赖 Redis ZSET，能够直观保留请求时间点，边界行为容易观测和压测验证；用户/IP两个维度可在同一个 Lua 中原子判断，避免一个维度已写入而另一个维度失败。

令牌桶的优点是允许平滑突发、内存更低，但需要维护 token 数量和补充时间，参数解释及边界测试更复杂。当前 v3 主要目标是保护 `/buy` 和快速拒绝脚本流量，因此选择可审计性更强的滑动窗口。

## 4. 原子性与故障策略

Lua 先删除窗口外成员，再分别检查用户/IP计数；只有两个计数都未超限时才写入两个 ZSET。超限时不写入、不扣库存。Redis 不可用时 fail-close，返回 `5002`，不允许绕过限流进入秒杀链路。

## 5. 配置

```yaml
seckill:
  rate-limit:
    user-per-second: ${SECKILL_RATE_LIMIT_USER_PER_SECOND:5}
    ip-per-second: ${SECKILL_RATE_LIMIT_IP_PER_SECOND:20}
```

## 6. 拿不准的 3 个点

1. `X-Forwarded-For` 只有在网关覆盖并可信时才能作为真实 IP；当前实现取第一个地址，直连部署应改为只使用 `remoteAddr`。
2. IP 全局窗口可能把共享 NAT 下的正常用户一起限流；后续需要基于压测分布确认阈值，或增加设备/网段维度。
3. 当前默认阈值是保护性初始值，不代表最终容量；应结合合法用户并发量、Redis QPS 和误杀率重新标定。