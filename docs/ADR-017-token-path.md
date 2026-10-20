# ADR-017：秒杀 token/path 设计方案

- 状态：已采纳
- 日期：2026-10-19
- 范围：v3 秒杀凭证、防重放；不包含限流、对账、超时取消、验证码和分布式锁

## 1. 背景

v2 使用 JWT 直接调用购买接口。接口路径和活动 ID 可被提前探测，脚本可以绕过正常页面反复提交；仅依赖 HTTP 层校验也无法保证并发重放下的一次性消费。

## 2. 决策

1. 新增 `POST /api/seckill/activities/{activityId}/token`。JWT 拦截器取得 userId，服务端校验活动存在且状态为 `ONGOING`。
2. 使用 `SecureRandom` 生成 32 字节 URL-safe `seckillToken` 和 24 字节 URL-safe `path`。path 不包含 `/`，可直接作为路径变量。
3. Redis 保存 `seckill:token:{SHA-256(path)}` Hash：`token`、`userId`、`activityId`、`expiresAt`、`status=READY`，TTL 固定 60 秒。Redis 不保存明文 path。
4. 购买接口改为 `POST /api/seckill/activities/{activityId}/buy/{path}`，通过 `X-Seckill-Token` 传 token。token、path、JWT userId、URL activityId 必须互相匹配。
5. `seckill_full.lua` 在同一次执行中完成凭证存在性、token、用户、活动、状态和过期校验；通过后将 status 改为 `USED`，再执行 v2 的限购/库存预扣和 CREATING 状态写入。
6. 凭证失败统一返回业务码 `2006`，不区分不存在、篡改、跨用户、跨活动、已使用和过期，避免泄露凭证状态。
7. 不改 v2 的 MQ Producer、Consumer、订单消息格式和异步落库流程；本批不加入限流。

## 3. path 生成算法与防碰撞

path 是 24 字节密码学随机数，经 RFC 4648 URL-safe Base64 编码并去除 padding，长度为 32 个 ASCII 字符，熵为 192 bit。碰撞概率按生日问题近似为：

$$P \approx \frac{n(n-1)}{2 \cdot 2^{192}}$$

即使生成 $n=10^9$ 个 path，碰撞概率约为 $8.0 \times 10^{-40}$。Redis key 使用 SHA-256(path)，摘要为 256 bit；理论摘要碰撞概率更低。签发时 Redis Hash 写入使用随机 path 对应 key，发生极端碰撞时可检测已有 key 后重试；当前实现依赖 192 bit 随机空间，工程上已足够。

## 4. 原子性与防重放

path 的状态变更、库存预扣、用户占用标记和订单 CREATING 写入都在同一个 Redis Lua 调用中完成。Lua 先检查 `status=READY`，并在成功分支写入 `USED`；Redis 单线程执行保证同一 path 的并发请求不会同时通过。过期 key 由 TTL 清理，Lua 同时比较 Redis server time 和 `expiresAt`。

库存不足时脚本在标记 USED 前返回售罄，允许客户端在凭证有效期内按既有业务语义重新尝试；一旦完成成功占用，path 永久不可重放，直到 Hash TTL 清理。

## 5. 被拒绝的方案

- JWT 直接作为秒杀凭证：有效期和复用范围过大，不能防止接口脚本化调用。
- 仅使用动态 path、不绑定 token：path 泄露后可被直接重放或跨用户使用。
- Java 先 GET 校验、再调用旧 Lua：存在并发 TOCTOU 窗口，无法保证一次性消费。
- 分布式锁：增加热点锁竞争和故障释放复杂度，Lua 已能覆盖本批原子性需求。
- 本批加入限流：属于下一批独立能力，会混淆 token/path 防重放验收边界。

## 6. 拿不准的 3 个点

1. **库存不足是否消耗 path**：当前决策为不消耗，便于有效凭证在库存状态短暂变化时重试；若产品要求严格“一次请求一次机会”，应把 USED 标记前移。
2. **token 是否需要 JWT 化**：当前采用随机 opaque token + Redis 绑定，便于服务端即时撤销和一次性消费；如果未来需要无状态校验，再评估短期 JWT。
3. **path 是否需要主动撤销同用户旧凭证**：当前重复获取不会撤销旧凭证，所有凭证各自 60 秒且只能成功一次；若要限制每用户同时持有一个凭证，需要额外索引和撤销语义。

## 7. 验收

- token/path 缺失、伪造、跨用户、跨活动、过期和重复使用均返回 2006。
- 同一 path 并发请求最多一个成功进入 v2 原预扣和 MQ 链路。
- 合法请求仍返回 `orderNo + CREATING`，MQ/Consumer 行为与 v2 相同。
- Redis token key TTL 为 60 秒，path 明文不作为 Redis key。
