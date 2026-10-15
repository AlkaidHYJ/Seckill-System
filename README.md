# Flash-sale-system

## V1 范围

V1 是单体 Spring Boot 3 应用，使用 MySQL 8 和 Flyway，暂不接入 Redis、RabbitMQ、Nginx 或 HTTPS。
当前骨架只提供启动与部署基础设施；登录、商品、秒杀下单、订单查询和模拟支付业务代码尚未实现。

## 已知简化

- 商品详情中的 `rule` 不落独立字段或规则表，由应用层根据现有限购和支付规则生成固定文案。
- 当前规则文案为“每人限购 1 件，支付截止时间为下单后 15 分钟”；规则变更需要修改应用配置或文档。

## 启动顺序

1. 准备 MySQL 8，并创建可连接的数据库账号。
2. 设置环境变量，至少包括 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`。
3. Spring Boot 启动后，Flyway 自动执行 `src/main/resources/db/migration` 下的迁移。
4. 应用启动完成后访问 `http://localhost:8080/actuator/health`，确认返回 `UP`。

## 本地运行

前置条件：JDK 17、Maven 3.9+、MySQL 8。

PowerShell 示例：

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:DB_URL = "jdbc:mysql://localhost:3306/seckill?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$env:DB_USERNAME = "seckill"
$env:DB_PASSWORD = "change-me"
mvn spring-boot:run
```

也可以复制 `.env.example` 为本地环境变量来源后，再在终端中加载这些变量。Spring Boot 不会自动读取 `.env` 文件。

## Docker 运行

前置条件：Docker Desktop。

```powershell
Copy-Item .env.example .env
docker compose up --build
```

Compose 会先启动 MySQL，等待健康检查通过后再启动应用。MySQL 数据持久化到 `mysql_data` 卷，应用日志持久化到 `app_logs` 卷。

停止服务：

```powershell
docker compose down
```

## 环境变量

| 变量 | 用途 | 示例 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Spring 环境 | `dev` / `prod` |
| `SERVER_PORT` | 应用端口 | `8080` |
| `DB_URL` | JDBC 连接串 | `jdbc:mysql://localhost:3306/seckill?...` |
| `DB_USERNAME` | 应用数据库用户 | `seckill` |
| `DB_PASSWORD` | 应用数据库密码 | `change-me` |
| `DB_POOL_MAX_SIZE` | Hikari 最大连接数 | `10` |
| `DB_POOL_MIN_IDLE` | Hikari 最小空闲连接 | `2` |
| `DB_CONNECTION_TIMEOUT_MS` | 获取连接超时 | `30000` |
| `DB_VALIDATION_TIMEOUT_MS` | 连接校验超时 | `5000` |
| `MYSQL_DATABASE` | Docker MySQL 数据库 | `seckill` |
| `MYSQL_USER` | Docker MySQL 普通用户 | `seckill` |
| `MYSQL_PASSWORD` | Docker MySQL 普通用户密码 | `change-me` |
| `MYSQL_ROOT_PASSWORD` | Docker MySQL root 密码 | `change-root-password` |
| `MYSQL_PORT` | 宿主机映射端口 | `3306` |
| `LOG_DIR` | 日志目录 | `./logs` |

## 拿不准的 3 个点

1. Maven 坐标暂定为 `com.example:flash-sale-system`，正式包名和组织名尚未确定。
2. 本地开发默认使用 `dev`，Docker 默认使用 `prod`；是否需要统一为同一 profile 尚未确定。
3. Docker 镜像目前使用 Maven 在线下载依赖；是否需要公司/个人私有 Maven 镜像源尚未确定。