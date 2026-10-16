# 批量生成 200 个测试用户 JWT

产出目标：`docs/loadtest/users.csv`

```csv
userId,jwt
1001,eyJhbGciOiJIUzI1NiJ9....
```

- `userId`：`users.id` 真实主键（下单时后端从 JWT subject 取 userId 落库，必须与库中一致）
- `jwt`：`POST /api/auth/login` 返回的 `data.accessToken`，JMeter 中作为 `Authorization: Bearer ${jwt}`

前置：已执行 `prepare.sql`，200 个用户 `loadtest1 ~ loadtest200` 密码统一为 `12345678`（哈希为 prepare.sql 里那个 `$2a$12$x1c/...` 值），应用运行在 `localhost:8080`。

> 密码长度硬约束：`LoginRequest` 上 `@Size(min = 8, max = 72)`，少于 8 位会被 400 拦掉，`12345678` 恰好 8 位可用。

---

## 方案 A（推荐）：循环调用登录接口

优点：userId 与 token 由后端自己产出，签名密钥、过期时间、subject 一定正确，不会因环境配置漂移导致 401。

### A1. Windows PowerShell

> 仓库已有现成脚本，直接跑即可（幂等，会覆盖 `users.csv`）：
>
> ```powershell
> powershell -NoProfile -ExecutionPolicy Bypass -File docs\loadtest\gen_users.ps1
> # 等价于 -Base http://localhost:8080 -Count 200 -Prefix loadtest -Password 12345678 -OutFile docs\loadtest\users.csv
> # 输出 written rows / 失败清单；全部成功时 exit 0
> ```

下面是等价的手工脚本（便于改字段或排查）：

```powershell
$base  = "http://localhost:8080"
$plain = '12345678'
$out   = "docs\loadtest\users.csv"

$rows = New-Object System.Collections.Generic.List[string]
$rows.Add("userId,jwt")

for ($i = 1; $i -le 200; $i++) {
    $body = (@{ username = "loadtest$i"; password = $plain } | ConvertTo-Json -Compress)
    try {
        $resp = Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post `
                -ContentType "application/json" -Body $body
        $rows.Add("$($resp.data.userId),$($resp.data.accessToken)")
    } catch {
        Write-Host "FAILED loadtest$i : $($_.Exception.Message)"
    }
}

$rows | Set-Content -Path $out -Encoding utf8
Write-Host "generated $($rows.Count - 1) rows -> $out"
```

### A2. bash / Git-Bash（依赖 curl + jq）

```bash
#!/usr/bin/env bash
set -euo pipefail
BASE="http://localhost:8080"
PLAIN='12345678'
OUT="docs/loadtest/users.csv"

echo "userId,jwt" > "$OUT"
for i in $(seq 1 200); do
  resp=$(curl -s -X POST "$BASE/api/auth/login" \
          -H 'Content-Type: application/json' \
          -d "{\"username\":\"loadtest${i}\",\"password\":\"${PLAIN}\"}")
  userId=$(echo "$resp" | jq -r '.data.userId')
  token=$(echo "$resp"  | jq -r '.data.accessToken')
  if [ "$token" = "null" ]; then
    echo "FAILED loadtest${i}: $resp" >&2
    continue
  fi
  echo "${userId},${token}" >> "$OUT"
done
echo "rows: $(( $(wc -l < "$OUT") - 1 ))"
```

### A3. 单条手工验证（先确认密码哈希正确再跑批量）

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"loadtest1","password":"12345678"}'
```

期望 `200`，且 `data.accessToken` 非空。若返回 4xx 密码错误，说明 `prepare.sql` 里的 BCrypt 哈希与明文不匹配，重新生成哈希。

登录响应结构（便于改脚本字段）：

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "userId": 1001,
    "username": "loadtest1"
  },
  "traceId": "..."
}
```

### A4. 结果校验（必须两个都过）

```powershell
# 1) 行数：期望 201（1 行表头 + 200 行数据）
(Get-Content docs\loadtest\users.csv).Count
Get-Content docs\loadtest\users.csv | Measure-Object -Line

# 2) 首行必须是表头（JMeter 的 ignoreFirstLine 依赖它），最后一行不为空
Get-Content docs\loadtest\users.csv -TotalCount 1

# 3) token 列不能出现空值或 $null
Select-String -Path docs\loadtest\users.csv -Pattern ',($|null|undefined)' | Measure-Object
```

常见坑：
- `Out-File` / `Set-Content` 默认不会帮你写表头，脚本里必须显式加 `"userId,jwt"` 第一行。
- PowerShell 的 `Out-File -Encoding UTF8` 在 PS 5.1 下带 BOM，JMeter 读 CSV 会把 BOM 归到首列名上；因为表头由 `variableNames` 指定且 `ignoreFirstLine=true`，影响有限，但用 `Set-Content -Encoding utf8`（PS7+ 无 BOM）更干净。
- 登录失败被 `catch` 跳过的行会造成行数 < 201，必须补齐后重跑。

---


## 方案 B：Java 小工具本地签发（离线，不需要应用在线）

只在压测机无法访问 `/api/auth/login`（例如远程环境只放开 8080 的业务接口）时使用。
**风险**：`JWT_SECRET` 必须与被压应用完全一致；`subject` 必须是真实 `users.id`。dev 环境未设置环境变量时默认密钥为 `change-this-development-secret-key-please`。

### B1. 先导出真实 userId

```bash
mysql -uroot -p seckill -e "SELECT id, username FROM users WHERE username LIKE 'loadtest%' ORDER BY id;" \
  > docs/loadtest/user_ids.csv
```

得到两列 `id / username`（可能以 Tab 分隔，脚本按逗号切分时先转成逗号，或改用 `--batch --raw` + `REPLACE`）。

### B2. 工具代码（放在 `docs/loadtest/` 下作为文档片段，不进 `src/main/java`，不参与业务打包）

依赖与项目一致：`io.jsonwebtoken:jjwt-api / jjwt-impl / jjwt-jackson`。

```java
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 批量签发压测 JWT。
 * 用法: java -cp "target/classes:target/dependency/*" JwtBatchGenerator \
 *         docs/loadtest/user_ids.csv docs/loadtest/users.csv
 * 输入: 每行 "userId,username"（顺序无关，自动跳过表头/空行）
 * 输出: users.csv，两列 userId,jwt
 */
public final class JwtBatchGenerator {

    // 必须与 application-{dev|prod}.yml 的 jwt.secret 一致
    private static final String SECRET =
            System.getenv().getOrDefault("JWT_SECRET", "change-this-development-secret-key-please");
    // 与 jwt.expire 一致，压测建议放大到能覆盖整轮（秒）
    private static final long EXPIRE_SECONDS =
            Long.parseLong(System.getenv().getOrDefault("JWT_EXPIRE_SECONDS", "7200"));

    public static void main(String[] args) throws IOException {
        Path in = Path.of(args.length > 0 ? args[0] : "docs/loadtest/user_ids.csv");
        Path out = Path.of(args.length > 1 ? args[1] : "docs/loadtest/users.csv");
        Key key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder("userId,jwt\n");
        List<String> lines = Files.readAllLines(in, StandardCharsets.UTF_8);
        for (String line : lines) {
            String[] parts = line.trim().split("[,\\t]");
            if (parts.length < 2 || !parts[0].matches("\\d+")) {
                continue; // 跳过表头与脏行
            }
            long userId = Long.parseLong(parts[0]);
            String username = parts[1];
            Instant now = Instant.now();
            String token = Jwts.builder()
                    .subject(String.valueOf(userId))
                    .claim("username", username)
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(now.plusSeconds(EXPIRE_SECONDS)))
                    .signWith(key)
                    .compact();
            sb.append(userId).append(',').append(token).append('\n');
        }
        Files.writeString(out, sb, StandardCharsets.UTF_8);
        System.out.println("written: " + out);
    }
}
```

### B3. 临时跑起来（不进主工程，独立目录编译执行）

```bash
# 把 B2 的代码保存为临时文件（不放进 src，不进业务打包）
mkdir -p tmp-jwt
# 手动将上面代码块写入 tmp-jwt/JwtBatchGenerator.java

# jjwt 依赖 jar 从本地 maven 仓库取
mvn -q dependency:copy-dependencies -DoutputDirectory=target/dependency

javac -cp "target/dependency/*" -d tmp-jwt tmp-jwt/JwtBatchGenerator.java
java  -cp "tmp-jwt;target/dependency/*" JwtBatchGenerator docs/loadtest/user_ids.csv docs/loadtest/users.csv
```

（PowerShell / cmd 用 `;` 分隔 classpath，Git-Bash 用 `:`。）

### B4. 自校验

生成后取一行 token，调一次业务接口验证不被拦截：

```bash
TOKEN=$(sed -n '2p' docs/loadtest/users.csv | cut -d, -f2)
curl -i -X POST http://localhost:8080/api/seckill/activities/2/buy \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"activityId":2}'
```

出现 `401/未登录` 即密钥或 subject 不一致，回到方案 A。

---

## 关于 users.csv 与线程数的关系

- 200 线程 × 1 循环，`recycle=true`：200 行 token 与 200 线程刚好一一对应（每线程各取一行）。
- 若行数少于 200，会循环复用同一 token，重复下单会命中 `uk_activity_user` 唯一索引（业务返回"已下单"），不是超卖，但会污染错误率统计。
- 若某行登录失败被跳过，请把行数补齐到 200，或在 JMeter 中把线程数改成与实际行数一致。
- JWT 有效期默认 7200s，压测当轮内无需刷新；隔天复跑请重新生成。

---

## 已拍板的 3 个点

1. **请求体必需**：保留 `{"activityId":2}`（接口签名 `@Valid @RequestBody SeckillRequest` 且校验 body 与路径一致）。若实测报错再删。
2. **错误率口径**：拆开算。业务拒绝（400，code=2008 库存不足 / 2010 已下单）算正常，约 50%；系统错误（5xx）必须 0%。默认 JMeter 把 400 计为 error，用 `.jtl` 的 `responseCode` 列离线区分；或启用 `seckill.jmx` 里那个置为 disabled 的 `Optional - business 400 not an error` JSR223 后置处理器。
3. **连接池 max=10**：v1 不调大，当作真实瓶颈与 v2 对比基线。只有报连接池超时（HikariCP `connection-timeout` / 5xx）时，才用 `DB_POOL_MAX_SIZE=20` 重跑一轮，两版数据都归档到 `docs/v1-压测报告.md`。

## 本次准备执行结果（2026-10-15，环境事实，非压测结果）

| 项 | 结果 |
| --- | --- |
| `prepare.sql` | 已执行：`loadtest_users=200`，`available_stock=100`，`total_stock=100`，`status=ONGOING`，`orders=0` |
| 用户 id 区间 | 259 ~ 458（插入顺序非按名字，所以 `users.csv` 里 userId 与 loadtestN 不是递增对应） |
| BCrypt | `$2a$12$x1c/...`（Python bcrypt 4.0.1，rounds=12、prefix=2a，checkpw 自检通过），明文 `12345678` |
| 单用户登录验证 | `loadtest1` → `code=0`，userId=277，tokenType=Bearer，expiresIn=7200，token 长 159~161 |
| `gen_users.ps1` | 200/200 成功，`users.csv` 201 行（表头 + 200），无空 token，无 BOM |
| token 有效期 | 7200s（约 2h），过期或隔天复跑必须重新生成 |
| 提醒 | `users.csv` 现在是真实凭证，不要 commit（见 `docs/v1-git.md`） |
