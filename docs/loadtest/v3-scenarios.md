# v3 压测方案

## 1. 测试目标

验证 v3 在 v2 Redis Lua 预扣 + MQ 异步落库链路上增加 token/path 和 Redis 限流后：

- 无有效 token/path 的脚本不能进入有效下单链路；
- 同一个 path 只能成功消费一次；
- 用户/IP 超过阈值返回 `2013`，不扣库存；
- 正常用户每人先获取 token，再使用 path 下单一次；
- QPS 不低于 v2 基线 393.7；
- 超卖为 0、重复订单为 0、Redis/MySQL 库存 diff 为 0。

## 2. 测试前置

1. 启动 MySQL、Redis、RabbitMQ 和当前应用，确认 `/actuator/health` 为 UP。
2. 以 activity 2 为测试活动，状态为 `ONGOING`，准备 200 个有效用户和 JWT：`docs/loadtest/users.csv`。
3. 每轮开始前执行库存/订单准备脚本，确保 Redis 库存、MySQL `available_stock`、用户限购标记、`seckill:creating` 和订单队列状态可记录。
4. 设置 v3 阈值：正常用户压测时不能因阈值误杀；攻击场景单独降低阈值或使用独立测试 key。
5. JMeter 场景文件：`docs/loadtest/v3.jmx`。每个线程读取一个用户，先请求 token，再请求 buy/path。

## 3. 场景定义

### 场景 1：正常用户带 token 下单

- 并发：200 用户，每用户 1 次。
- 请求顺序：
  1. `POST /api/auth/login` 或使用预生成 JWT；
  2. `POST /api/seckill/activities/2/token`；
  3. 从响应提取 `seckillToken`、`path`、`expiresAt`；
  4. `POST /api/seckill/activities/2/buy/{path}`，Header 携带 `X-Seckill-Token`。
- 预期：token 为 200；buy 在未触发限流时为 200/业务成功，返回 `CREATING`；随后订单落库为 `UNPAID`。
- 校验：每个 path 只消费一次；MySQL 订单数、Redis 用户标记、Redis 库存和 MQ 消费结果一致。

### 场景 2：无 token 直接调用 `/buy`

- 请求：携带有效 JWT，但不携带 `X-Seckill-Token`，或直接使用伪造 path。
- 预期：
  - 缺少 Header：业务码 `1002`；
  - 带伪造/无效 token/path：业务码 `2006`；
  - 若 JWT 也缺失：HTTP `401`、业务码 `1005`。
- 校验：库存不减少，不产生订单，不写 CREATING。

### 场景 3：复用同一个 path

- 第一次：使用合法 token/path 请求 buy。
- 第二次：完全复用相同 JWT、token、path 请求 buy。
- 预期：第一次最多成功一次；第二次 HTTP `400`、业务码 `2006`。
- 校验：订单数不增加，库存不重复扣减，token Hash 的 `status=USED`。

### 场景 4：超过限流阈值

- 用户维度：同一 `userId + activityId` 在 1 秒内发送超过 `user-per-second` 次请求。
- IP 维度：同一 `remoteAddr` 在 1 秒内发送超过 `ip-per-second` 次请求。
- 默认阈值：用户 5 次/秒，IP 20 次/秒，可由环境变量覆盖。
- 预期：超限 HTTP `429`、业务码 `2013`。
- 校验：超限请求不进入 Controller/SeckillService，不执行 `seckill_full.lua`，库存、订单和 `seckill:creating` 不变化。

## 4. 压测轮次

| 轮次 | 用途 | 用户数 | 库存 | 阈值 | 结果文件 |
|---|---|---:|---:|---|---|
| r0 | 预热 | 200 | 100 | 正常阈值 | `results/v3_r0.jtl` |
| r1 | 预热 | 200 | 100 | 正常阈值 | `results/v3_r1.jtl` |
| r2 | 正式 | 200 | 100 | 正常阈值 | `results/v3_r2.jtl` |
| r3 | 正式 | 200 | 100 | 正常阈值 | `results/v3_r3.jtl` |
| r4 | 正式 | 200 | 100 | 正常阈值 | `results/v3_r4.jtl` |
| attack | 防刷 | 1 或多 IP | 不参与 | 降低/单独配置 | `results/v3_attack.jtl` |

正式结果取 r2/r3/r4 中位数。正常场景和攻击场景不要混在同一统计样本中。

## 5. 结果判定

- 性能：正式轮 QPS 不低于 v2 的 393.7；P99 记录并与 v2 501 ms 对比。
- 防刷：无 token/path 全部被拒；path 重放成功数为 0；超限请求全部为 2013。
- 正确性：超卖数为 0、重复订单数为 0、`total_stock - available_stock - valid_orders = 0`。
- 一致性：RabbitMQ 队列清空后，`CREATING=0`、Redis 库存与 MySQL 可用库存一致、订单状态为预期。
- 任何 5xx、连接失败、库存负数、重复订单或 diff 非 0 均判定为失败。

## 6. 建议命令

```powershell
# 检查基础设施
 docker compose ps
Invoke-RestMethod http://localhost:8080/actuator/health

# 非 GUI 执行正常 v3 场景
jmeter -n -t docs/loadtest/v3.jmx `
  -JHOST=localhost -JPORT=8080 -JACTIVITY_ID=2 `
  -JTHREADS=200 -JRAMP_UP=1 -JLOOPS=1 `
  -l results/v3_r2.jtl -e -o results/html_v3_r2

# 汇总 JTL
python docs/loadtest/analyze_jtl.py results/v3_r2.jtl
```

攻击场景建议使用单独的 JMX 或脚本，专门统计 `2006` 和 `2013`，不要把业务拒绝率混入正常用户 QPS。
