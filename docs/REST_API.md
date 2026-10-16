# 秒杀系统 REST API 接口契约

> 版本：v1.0 ｜ 适用：Java 17 + Spring Boot 3
>
> 本文只定义 HTTP 接口契约，不定义 Controller、Java 类或实现代码。

## 1. 通用约定

### 1.1 基础信息

- Base URL：`/api`
- Content-Type：请求和成功响应均为 `application/json`
- 时间：ISO-8601，带时区，例如 `2026-10-11T12:30:00+08:00`；服务端 Jackson 使用 `Asia/Shanghai` 时区且不序列化为时间戳
- 金额：JSON 字符串，保留两位小数，例如 `"19.90"`
- ID：JSON 数字，类型为 `int64`；客户端不应依赖其精度时按字符串处理
- 认证：需要登录的接口携带 `Authorization: Bearer <accessToken>`；未携带或令牌无效/过期返回 HTTP `401` + 业务码 `1005`，已认证但无权（账号封禁、访问他人订单）返回 HTTP `403` + 业务码 `2001`
- 请求追踪：客户端可携带 `X-Request-Id`；服务端响应 `X-Trace-Id`

### 1.2 统一返回结构

```json
{
  "code": 0,
  "message": "OK",
  "data": {},
  "traceId": "01J..."
}
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `code` | `int32` | `0` 表示成功，非 0 表示失败 |
| `message` | `string` | 面向调用方的稳定提示，不承载堆栈信息 |
| `data` | `object\|array\|null` | 成功或失败时的业务数据；无数据时为 `null` |
| `traceId` | `string` | 本次请求的全链路追踪标识 |

HTTP 状态码与业务码同时返回：参数错误使用 `400`，未认证使用 `401`，无权限使用 `403`，资源不存在使用 `404`，限流使用 `429`，系统异常使用 `500`/`503`。

### 1.3 业务状态

| 状态 | 对外含义 | 来源 |
|---|---|---|
| `NOT_STARTED` | 未开始 | 活动 |
| `ONGOING` | 进行中 | 活动 |
| `ENDED` | 已结束 | 活动 |
| `PAUSED` | 已暂停 | 活动 |
| `CREATING` | 已受理，等待落库 | Redis，仅用于轮询 |
| `FAILED` | 下单失败 | Redis，仅用于轮询 |
| `UNPAID` | 已落库，待支付 | MySQL |
| `PAID` | 已支付 | MySQL |
| `CLOSED_TIMEOUT` | 超时关闭 | MySQL |

`COMPENSATING` 是库存补偿记录状态，不是订单状态，不通过订单接口返回。

## 2. 接口清单

| 编号 | 方法 | URL | 鉴权 | 说明 |
|---|---|---|---|---|
| API-01 | `POST` | `/api/auth/login` | 否 | 登录并获取访问令牌 |
| API-02 | `GET` | `/api/seckill/activities` | 否 | 秒杀商品列表 |
| API-03 | `GET` | `/api/seckill/activities/{activityId}` | 否 | 秒杀商品详情 |
| API-04 | `POST` | `/api/seckill/activities/{activityId}/token` | 是 | 获取一次性秒杀凭证和 path |
| API-05 | `POST` | `/api/seckill/activities/{activityId}/buy/{path}` | 是 | 提交秒杀订单 |
| API-06 | `GET` | `/api/orders/{orderNo}` | 是 | 查询订单或轮询下单结果 |
| API-07 | `POST` | `/api/orders/{orderNo}/pay` | 是 | 模拟支付 |

## 3. 接口详情

### API-01 登录

**请求**：`POST /api/auth/login`

| 参数 | 类型 | 必填 | 校验 |
|---|---|---:|---|
| `username` | `string` | 是 | 1~64 个字符；去除首尾空白后非空 |
| `password` | `string` | 是 | 8~72 个字符；按明文传输到 HTTPS，服务端与 BCrypt 校验 |

**返回 `data`**

| 字段 | 类型 | 含义 |
|---|---|---|
| `accessToken` | `string` | JWT 访问令牌 |
| `tokenType` | `string` | 固定为 `Bearer` |
| `expiresIn` | `int64` | 令牌剩余秒数 |
| `userId` | `int64` | 用户 ID |
| `username` | `string` | 登录名 |

可能错误：`1001` 参数格式错误、`1002` 用户名或密码错误、`2001` 账号已封禁、`5001` 系统异常。

幂等：无。鉴权：无需登录；仅允许状态正常的用户登录。

### API-02 商品列表

**请求**：`GET /api/seckill/activities`

无请求参数。可选查询参数 `status=NOT_STARTED|ONGOING|ENDED|PAUSED`；非法值返回 `1001`。

**返回 `data`**：`array<ActivitySummary>`

| 字段 | 类型 | 含义 |
|---|---|---|
| `activityId` | `int64` | 活动/商品 ID |
| `goodsName` | `string` | 商品名称 |
| `goodsImg` | `string\|null` | 商品图片 URL |
| `seckillPrice` | `string` | 秒杀价 |
| `originalPrice` | `string` | 原价 |
| `displayStock` | `int32` | Redis 剩余库存展示值，非 DB 字段；仅供展示 |
| `status` | `string` | 活动状态 |
| `startTime` | `string` | 开抢时间 |
| `endTime` | `string` | 结束时间 |
| `countdownSeconds` | `int64` | 距离开抢或结束的秒数；终态为 `0` |

可能错误：`1001` 查询参数错误、`5001` 系统异常。幂等：无。鉴权：无需登录。

### API-03 商品详情

**请求**：`GET /api/seckill/activities/{activityId}`

| 参数 | 类型 | 必填 | 校验 |
|---|---|---:|---|
| `activityId`（Path） | `int64` | 是 | 正整数 |

**返回 `data`**：`ActivityDetail`

除 API-02 字段外增加：

| 字段 | 类型 | 含义 |
|---|---|---|
| `limitPerUser` | `int32` | 每人限购数量，当前固定为 1 |
| `rule` | `string` | 秒杀规则摘要 |

可能错误：`1001` 活动 ID 无效、`2002` 活动不存在、`5001` 系统异常。幂等：无。鉴权：无需登录。

### API-04 获取秒杀 token

**请求**：`POST /api/seckill/activities/{activityId}/token`

| 参数 | 类型 | 必填 | 校验 |
|---|---|---:|---|
| `activityId`（Path） | `int64` | 是 | 正整数；活动必须存在 |

**返回 `data`**

| 字段 | 类型 | 含义 |
|---|---|---|
| `seckillToken` | `string` | 绑定用户和活动的短期凭证，不替代 JWT |
| `path` | `string` | 下单 URL 中使用的一次性不透明路径片段 |
| `expiresAt` | `string` | 凭证过期时间 |

可能错误：`1001` 活动 ID 无效、`2002` 活动不存在、`2003` 活动未开始、`2004` 活动已结束、`2005` 活动已暂停、`5002` Redis 不可用。

幂等：无。重复获取会生成新的凭证，旧凭证不主动撤销，但每个凭证只能成功下单一次。鉴权：需要 JWT。

### API-05 秒杀下单

**请求**：`POST /api/seckill/activities/{activityId}/buy/{path}`

Header：

| Header | 类型 | 必填 | 校验 |
|---|---|---:|---|
| `Authorization` | `string` | 是 | 有效 JWT |
| `X-Idempotency-Key` | `string` | 是 | 1~64 个 ASCII 字符；同用户、同活动、同 key 重放返回同一结果 |
| `X-Seckill-Token` | `string` | 是 | API-04 返回的凭证 |

Body：无。`quantity` 不开放，由服务端固定为 `1`。

Path 参数：`activityId` 为正整数；`path` 为 API-04 返回的非空不透明字符串，禁止客户端自行构造。

**返回 `data`**

| 字段 | 类型 | 含义 |
|---|---|---|
| `orderNo` | `string` | 业务订单号 |
| `status` | `string` | 固定初始返回 `CREATING` |
| `acceptedAt` | `string` | 请求受理时间 |
| `pollAfterMs` | `int32` | 建议下次轮询间隔 |

可能错误：`1005` 未认证（未携带或令牌无效/过期，HTTP `401`）、`1001` 参数或幂等键错误、`2006` 凭证无效/已使用/已过期、`2007` 已超过限购、`2008` 库存不足、`2009` 活动不可下单、`2010` 已存在该活动订单、`2013` 请求过于频繁、`5002` Redis 不可用、`5003` 消息发布失败、`5004` 数据库访问或事务失败、`5005` 依赖服务超时。

幂等：`X-Idempotency-Key` 只写入 Redis 做短期去重，不落库；一次性 `path` 成功后作废；MySQL `uk_activity_user(activity_id,user_id)` 防止每人每活动多单，`uk_order_no` 防止重复落库。Redis 预扣成功后返回 `CREATING`；消息发布或消费失败最终返回 `FAILED`。

鉴权：需要 JWT、有效秒杀 token 和 path，三者必须绑定同一用户与活动。

### API-06 查询订单状态

**请求**：`GET /api/orders/{orderNo}`

| 参数 | 类型 | 必填 | 校验 |
|---|---|---:|---|
| `orderNo`（Path） | `string` | 是 | 1~32 个字符，符合订单号格式 |

**返回 `data`**

| 字段 | 类型 | 必返 | 含义 |
|---|---|---:|---|
| `orderNo` | `string` | 是 | 订单号 |
| `activityId` | `int64\|null` | 否 | 活动 ID；`CREATING`/`FAILED` 阶段可为空，落库后必返 |
| `goodsName` | `string` | 否 | 商品快照；`CREATING` 时可为空 |
| `quantity` | `int32` | 否 | 固定为 1 |
| `amount` | `string` | 否 | 应付金额 |
| `status` | `string` | 是 | `CREATING`、`FAILED`、`UNPAID`、`PAID`、`CLOSED_TIMEOUT` |
| `payDeadline` | `string\|null` | 否 | 支付截止时间；落库后返回 |
| `payTime` | `string\|null` | 否 | 支付时间 |
| `closeTime` | `string\|null` | 否 | 关闭时间 |
| `closeReason` | `int32\|null` | 否 | `1` 表示超时关闭 |

`CREATING`/`FAILED` 先读 Redis，`UNPAID` 及之后状态以 MySQL 为准。轮询终止条件为 `status != CREATING`。

`FAILED` 只返回通用失败状态，不返回具体失败原因；具体原因通过 `traceId` 查询日志排查。

可能错误：`1005` 未认证（未携带或令牌无效/过期，HTTP `401`）、`1001` 订单号格式错误、`2014` 订单不存在、`2001` 无权访问该订单、`5001` 系统异常。幂等：只读，无副作用。鉴权：需要 JWT，且 `orderNo` 必须属于当前用户。

### API-07 模拟支付

**请求**：`POST /api/orders/{orderNo}/pay`

Body 可选：`{ "paymentMethod": "MOCK" }`。若传入，`paymentMethod` 必须为 `MOCK`。

| 参数 | 类型 | 必填 | 校验 |
|---|---|---:|---|
| `orderNo`（Path） | `string` | 是 | 1~32 个字符，符合订单号格式 |

**返回 `data`**

| 字段 | 类型 | 含义 |
|---|---|---|
| `orderNo` | `string` | 订单号 |
| `status` | `string` | 成功时为 `PAID` |
| `payTime` | `string` | 支付完成时间 |
| `amount` | `string` | 支付金额 |

v1 实现复用 `OrderVO` 返回订单数据，因此除上述字段外可能同时返回 `activityId`、`goodsName`、`quantity`、`payDeadline`、`closeTime` 和 `closeReason`；调用方应忽略未使用字段。v1 不自动执行超时关闭，`CLOSED_TIMEOUT` 仅用于手动构造数据测试。

可能错误：`1005` 未认证（未携带或令牌无效/过期，HTTP `401`）、`1001` 参数错误、`2001` 无权访问订单、`2014` 订单不存在、`2011` 订单已超时关闭、`2012` 订单状态不可支付、`5001` 系统异常。

幂等：使用订单状态 CAS：`UNPAID -> PAID`。重复支付不重复扣款，已为 `PAID` 时返回当前已支付结果；其他状态拒绝。鉴权：需要 JWT，且订单必须属于当前用户。

## 4. 错误码表

### 1xxx：参数与请求错误

| 错误码 | 含义 |
|---:|---|
| `1001` | 请求参数格式或取值非法 |
| `1002` | 缺少必填参数或 Header |
| `1003` | 幂等键格式非法 |
| `1004` | 请求体格式非法 |
| `1005` | 未认证：缺少 `Authorization: Bearer <accessToken>`，或令牌无效/已过期（HTTP `401`） |

### 2xxx：业务错误

| 错误码 | 含义 |
|---:|---|
| `2001` | 账号被封禁或无权访问资源 |
| `2002` | 活动不存在 |
| `2003` | 活动未开始 |
| `2004` | 活动已结束 |
| `2005` | 活动已暂停 |
| `2006` | 秒杀 token/path 无效、已使用或已过期 |
| `2007` | 已超过限购 |
| `2008` | 库存不足 |
| `2009` | 活动当前不可下单 |
| `2010` | 当前用户已存在该活动订单 |
| `2011` | 订单已超时关闭 |
| `2012` | 订单当前状态不可支付 |
| `2013` | 请求过于频繁，请稍后重试 |
| `2014` | 订单不存在 |

### 5xxx：系统错误

| 错误码 | 含义 |
|---:|---|
| `5001` | 未分类系统异常 |
| `5002` | Redis 不可用，秒杀链路 fail-close |
| `5003` | MQ 发布确认失败或超时 |
| `5004` | 数据库访问或事务失败 |
| `5005` | 依赖服务超时 |

## 5. traceId 方案

1. 网关或应用入口读取 `X-Request-Id`；缺失、过长或不符合格式时忽略并生成新的 `traceId`。
2. `traceId` 使用不可预测的短字符串（建议 ULID 或 128-bit 随机值），一次 HTTP 请求全程复用。
3. 放入请求上下文，并写入应用日志、异常日志、MQ 消息 headers（`traceId`）和异步消费者日志。
4. 响应体 `traceId` 与响应头 `X-Trace-Id` 使用同一值。异步 MQ 消费产生新的 `spanId`，但沿用原 `traceId`。
5. 不把用户密码、JWT、秒杀 token 或 path 写入日志；错误响应只返回 `traceId`，便于排查。

## 6. 秒杀 token 与一次性 path

### 6.1 生成

- API-04 先验证 JWT、活动存在性和活动状态，再生成高熵随机 `nonce`。
- `path` 使用 URL-safe Base64 或十六进制编码的随机值，不包含用户 ID、活动 ID、库存等可推断信息。
- Redis 保存凭证记录，例如 `seckill:token:{hash(path)}`，内容至少包含 `userId`、`activityId`、凭证状态、过期时间和关联 `seckillToken`。
- `seckillToken` 建议为短期签名凭证；path 仍以 Redis 记录为最终一次性消费状态。

### 6.2 校验与消费

API-05 必须原子校验：JWT 用户、活动 ID、path、`X-Seckill-Token`、活动状态和过期时间全部匹配；随后在同一 Redis 原子操作中将 path 标记为 `USED`，再执行库存与限购预扣。

推荐顺序是“校验并占用凭证 + 库存预扣”使用同一个 Lua 脚本：任一条件失败整体不生效。MQ 发布失败时由 `CREATE_ROLLBACK` 补偿库存和限购，并将订单置为 `FAILED`；path 不因失败重试而恢复，客户端应重新获取凭证。

### 6.3 过期规则

- 凭证有效期建议为 60 秒，且不得晚于活动结束时间。
- Redis TTL 到期后凭证不可使用；服务端时间为唯一判定依据。
- 一个 path 最多成功受理一个订单；重放统一返回 `2006`，不泄露 path 当前绑定的用户或订单信息。
- JWT 过期、活动暂停、活动结束不会延长 path 有效期。

## 7. 尚需确认的 3 个点

1. **登录是否包含注册**：当前接口清单只有登录，DDL 又没有注册接口；本契约假设用户由初始化数据提供，不新增注册 API。
2. **秒杀 token 的有效期**：本文建议 60 秒；若前端需要更长等待，应明确是否允许刷新凭证，以及刷新是否撤销旧凭证。
3. **商品列表库存展示口径**：Redis 是库存权威源，但列表读取可能看到瞬时值；本文将 `displayStock` 定义为 Redis 展示值，不作为下单承诺，也未承诺强一致。