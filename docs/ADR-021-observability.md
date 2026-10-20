# ADR-021：可观测方案（traceId 跨 MQ + 慢 SQL + 关键指标）

- 状态：已采纳
- 日期：2026-10-20
- 范围：v4 可观测三件套——traceId 跨 MQ 贯通（`OrderMessageProducer` / `OrderMessageConsumer`）、慢 SQL 日志（`SlowSqlInterceptor`）、Micrometer 关键指标（`mq.*` / `seckill.request.total` / `seckill.reconciliation.diff.total`）
- 不包含：Prometheus + Grafana 采集展示、ELK 日志归集、SkyWalking/Zipkin 链路追踪、指标告警规则与通知（后续迭代）

## 1. 背景

v4 设计 17.6 已明确可观测目标，但落地层面存在三处断点：

1. **异步链路断链**：`TraceIdFilter` 只在 HTTP 线程写 MDC，`@RabbitListener` 消费线程的 MDC 为空，Producer 与 Consumer 日志无法按同一 traceId 串联，CREATING→UNPAID 收敛时延（指标 #3）无法按 traceId 配对。
2. **DB 层无回归信号**：没有 SQL 耗时观测，索引退化 / 慢批次只能等到压测收尾人工发现。
3. **指标口径不全**：现有 Counter 只覆盖三个 JOB（TimeoutClose / StockCompensation / Reconciliation 轮次），缺少 MQ 收发与请求结果分布，无法从 `/actuator` 自证链路健康度。

本方案在不引入任何新依赖的前提下，用 Spring Boot Actuator 自带的 Micrometer + 已配置的 SLF4J 结构化日志补齐上述三处。

## 2. 决策

### 2.1 traceId 跨 MQ 传播路径

传播载体为 **AMQP message headers（`traceId`）**，**不改消息体协议**：

| 阶段 | 位置 | 动作 |
|---|---|---|
| 入口 | `TraceIdFilter` | HTTP 线程 MDC 写入 traceId（`X-Request-Id` 透传或 UUID），响应回写 `X-Trace-Id` |
| 生产 | `OrderMessageProducer#send` | 发送前 `MDC.get("traceId")`，经 `MessagePostProcessor` 写入 `messageProperties.setHeader("traceId", ...)`；body 仍为 `OrderMessage` JSON |
| 中间 | RabbitMQ broker | traceId 随 header 透传，不参与路由/序列化语义 |
| 消费 | `OrderMessageConsumer#consume` | 先 `rawMessage.getMessageProperties().getHeader("traceId")` 取回并 `MDC.put`，再处理；`finally` 中 `MDC.remove("traceId")` |
| 检索 | 运维 | 任一 orderNo → API 日志取 traceId → `grep traceId` 命中 API / Consumer / 补偿 / 对账 4 类日志（验收 17.8-3） |

- **一致性保证**：Producer 发送时读、Consumer 消费时写均为同一个 `traceId` 键，logback pattern `[traceId=%X{traceId:-N/A}]` 对两端一致渲染。Consumer 无 header（老消息 / 手工投递）时不写 MDC，日志退化为 `N/A`，不伪造。
- **JOB 批次**：JOB-01/02/03 非 MQ 触发，沿用各自批次 traceId（如 `job03-{yyyyMMddHHmmss}`，见 `ReconciliationJob`），不经 header。

### 2.2 慢 SQL 阈值 500 ms 的选择理由

- **量级参照**：v3 buy 链路 P99 = 397 ms（整请求预算）。单条 SQL 若占 500 ms，已超过整请求 P99，必然吞噬 SLO，触发即有明确预警价值。
- **与 200 ms 的关系**：200 ms 会频繁命中 `SUM(quantity)` 聚合与批量扫表等“设计内慢查询”，噪声高；500 ms 只捕获“异常慢”，信噪比更适合作为常态 WARN。
- **远小于事务/连接超时**：Hikari `connection-timeout=30000ms`，500 ms 远在其下，慢查询不会先被超时打断，日志能完整落盘证据。
- **配置化**：`seckill.slow-sql.threshold-ms`（默认 500，环境变量 `SECKILL_SLOW_SQL_THRESHOLD_MS` 可覆盖），不硬编码。

实现约束：`SlowSqlInterceptor` 快速路径只做两次 `System.nanoTime()` 采样；**仅当 `elapsedMs > thresholdMs` 时**才构建 `BoundSql`（取 SQL 与参数）并打一条 WARN，常态开销可忽略。仅对 SELECT（`Executor#query`）与 UPDATE（`Executor#update` 且 `SqlCommandType.UPDATE`）生效，INSERT/DELETE 不计。

### 2.3 指标命名规范

- **统一前缀**：业务自定义指标以 `seckill.` 开头；MQ 收发指标以 `mq.` 开头。
- **下划线分段**：多词用 `.` 分层、词内用 `_`（如 `seckill.reconciliation.diff.total`），与 Micrometer 推荐风格一致，避免驼峰。
- **高维用 tag，不拆名**：结果码、对账侧等可枚举维度以 tag 承载（`code`、`type`），指标名保持稳定，避免指标爆炸。
- **名词 + 量纲**：`*.total` 表累计计数（Counter）；与现有 `.closed/.refunded/.processed` 等同风格。
- **不带单位后缀**：耗时类指标由 Micrometer `Timer` 承载时单位自明，本批全部为计数型。

### 2.4 指标清单与用途

| 指标 | 类型 / tag | 位置 | 用途 |
|---|---|---|---|
| `seckill.request.total` | Counter, `code` | `SeckillController#buy` | buy 受理结果码分布（0 成功 / 2006 凭证 / 2010 重复 / 2013 限流 / 500x 系统），替代“API 结构化日志按 code 计数”（17.6 指标 #8） |
| `mq.send.success` | Counter | `OrderMessageProducer#handleConfirm`（ack=true） | 下单消息确认成功量，链路入口健康度 |
| `mq.send.fail` | Counter | `OrderMessageProducer#send` catch + confirm nack | 发布异常 / 确认失败量，配合 Redis 回滚告警 |
| `mq.consume.success` | Counter | `OrderMessageConsumer#consume` | 落库成功并 ack 量，与 `mq.send.success` 对齐观测落差 |
| `mq.consume.fail` | Counter | `OrderMessageConsumer#consume` catch | 重试耗尽并 nack 量，DLQ 前置信号 |
| `mq.consume.retry` | Counter | `OrderMessageConsumer#retryWithBackoff` | 处理重试次数（不含最终失败那次），链路抖动观测 |
| `seckill.reconciliation.diff.total` | Counter, `type=redis`/`type=mysql` | `ReconciliationJob#reconcileOne` | 双 diff 非零轮次分侧计数（17.6 指标 #7 的可聚合来源） |
| `seckill.timeout.closed` / `seckill.timeout.failed` | Counter | `TimeoutCloseJob` | JOB-01 超时关闭量 / 失败量（已有） |
| `seckill.compensation.refunded` / `.skipped` / `.retried` / `.alerts` | Counter | `StockCompensationJob` | JOB-02 回补量 / 退避重试 / 告警（已有） |
| `seckill.creating.compensation.processed` / `.skipped` / `.failed` / `.alerts` | Counter | `CreatingCompensationJob` | CREATING 清理补偿（已有） |
| `seckill.reconciliation.inconsistent` / `.exempted` / `.redis_missing` / `.failed` | Counter | `ReconciliationJob` | JOB-03 告警轮次 / 豁免 / key 缺失 / 异常（已有） |

> 全部指标仅依赖 Actuator 内置 Micrometer，不引入 APM/Prometheus；未接 Grafana，取数走 `/actuator/metrics`。

### 2.5 `/actuator/metrics` 查询示例

`application.yml` 已放开 `management.endpoints.web.exposure.include: health,info,metrics`。

```bash
# 列出可用指标名
curl -s http://localhost:8080/actuator/metrics | jq '.names'

# 请求结果码分布（按 code 过滤）
curl -s "http://localhost:8080/actuator/metrics/seckill.request.total" | jq
curl -s "http://localhost:8080/actuator/metrics/seckill.request.total?tag=code:2010" | jq '.measurements'

# MQ 收发
curl -s "http://localhost:8080/actuator/metrics/mq.send.success" | jq '.measurements'
curl -s "http://localhost:8080/actuator/metrics/mq.consume.retry" | jq '.measurements'

# 对账双 diff（分侧）
curl -s "http://localhost:8080/actuator/metrics/seckill.reconciliation.diff.total?tag=type:redis" | jq '.measurements'
curl -s "http://localhost:8080/actuator/metrics/seckill.reconciliation.diff.total?tag=type:mysql" | jq '.measurements'
```

PowerShell 等价（Windows 本机）：

```powershell
Invoke-RestMethod "http://localhost:8080/actuator/metrics/seckill.request.total?tag=code:0"
Invoke-RestMethod "http://localhost:8080/actuator/metrics/seckill.reconciliation.diff.total?tag=type:redis"
```

## 3. 备选与取舍

- **traceId 放消息体 vs header** → 选 header：body 协议保持稳定，header 属 AMQP 元数据层，与业务字段解耦，Broker 天然透传。
- **200 ms vs 500 ms 慢 SQL 阈值** → 选 500 ms：以“超整请求 P99”为红线的低噪声口径；200 ms 保留为可配置下探值，环境变量可临时收紧。
- **自建 Prometheus 抓取 vs 仅 Actuator** → 选仅 Actuator：满足“压测取数 + 自证”即可，避免引入采集/展示链路，符合 v4 范围约束（17.8-9）。

## 4. 影响与验证

- **验证 traceId**：任取 3 笔压测订单，`grep traceId` 从 API 到 Consumer 到补偿/对账全串无断链（17.8-3）。
- **验证慢 SQL**：临时把 `seckill.slow-sql.threshold-ms` 调低（如注入 >阈值查询）触发一条 WARN，含 id / sql / elapsedMs / params / traceId（17.8-4，阈值口径改为 500 ms）。
- **验证指标**：`/actuator/metrics` 可查到本 ADR §2.4 全部指标名与 tag。

## 5. 拍板结论与已知限制

1. **慢 SQL `params` 暂不脱敏（已知限制）**：日志直接打印 `boundSql.getParameterObject()`，可能含 userId 等标识值。当前项目参数无密码/密钥类字段，经评估**决定暂不脱敏**并作为已知限制登记；若后续引入敏感参数（证件号、支付凭证等），必须在 `SlowSqlInterceptor` 内补参数过滤器后再上线。
2. **`mq.consume.retry` 口径保持不变**：计“实际重试尝试次数”（`MAX_ATTEMPTS=3` 时最多 +2，不含最终失败那次），**不引入 `attempts` 维度**。理由：当前只观测“链路是否抖动”，简单口径已足够，避免高基数 tag。
3. **`seckill.request.total` 仅埋 buy 端点（已知限制）**：`token` 端点（凭证发放）**不埋**。理由：秒杀核心动作是 `buy`，`token` 属前置准备且 QPS 语义不同；若后续需要全端点 QPS，需扩展为通用 Filter/Interceptor，届时另开 ADR。当前口径已在 §2.4 指标表标注为“buy 受理结果码分布”。