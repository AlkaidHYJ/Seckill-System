# v1 收尾 Git 操作清单

> 仅命令清单，**尚未执行**。按顺序逐条确认后再敲。

## 0. 先排除敏感/大文件

`users.csv` 里是真实 JWT（等同短期凭证），`results/` 是压测原始结果，都不建议入库。

当前仓库里的 `docs/loadtest/users.csv` **已被真实 JWT 覆盖**（200 行有效 token，7200s 过期），必须排除入库；`results/` 是压测原始结果，同样不入库。

```powershell
# 必须执行（现在 users.csv 含真实凭证）
Add-Content -Path .gitignore -Value "docs/loadtest/users.csv"
Add-Content -Path .gitignore -Value "docs/loadtest/user_ids.csv"
Add-Content -Path .gitignore -Value "results/"
Add-Content -Path .gitignore -Value "*.jtl"
git status          # 确认 users.csv 不再出现在待提交列表
git rm --cached docs/loadtest/users.csv   # 若之前已被跟踪，执行这一条取消跟踪
```

要保留一份可入库的模板：手工建 `docs/loadtest/users.csv.example`（内容为 `userId,jwt` + 几行占位），真实文件保持被忽略。`gen_users.ps1` / `run_sql.ps1` / `prepare.sql` / `verify.sql` / `seckill.jmx` / `generate-jwt.md` 应该入库（不含密码与密钥）。

## 1. 检查待提交内容

```bash
git status
git diff --stat
git diff                # 逐行确认没有密码、密钥、真实 token
```

## 2. add

```bash
# 本次压测与复盘产物
git add docs/loadtest/ docs/v1-压测报告.md docs/v1-复盘.md docs/v1-git.md
git add .gitignore

# 或全量（确认 git status 干净后）
git add -A
```

## 3. commit

建议拆两个 commit：一个是压测工装，一个是报告。

```bash
git commit -m "test(loadtest): add JMeter plan, data prep and reconciliation SQL for v1 seckill" -m "- users.csv template + JWT batch guide- prepare.sql: reset stock 100, 200 loadtest users (BCrypt 12)- seckill.jmx: 200 threads, ramp-up 1s, sync timer 200- verify.sql: oversell/duplicate/negative-stock reconciliation"

git commit -m "docs: add v1 load-test report and retrospective templates"
```

单条版本（PowerShell 用反引号或多次 `-m` 避免换行问题）：

```bash
git commit -m "docs(loadtest): add v1 load-test toolkit and report templates"
```

commit message 建议：

| 场景 | 建议 message |
| --- | --- |
| 压测工装 | `test(loadtest): add JMeter plan, prepare/verify SQL and JWT toolkit for v1` |
| 报告模板 | `docs: add v1 load-test report and retrospective templates` |
| 合并一次 | `docs(loadtest): add v1 load-test toolkit and report templates` |
| 若同时改了配置 | `perf(config): raise hikari pool size for load test (if applicable)` |

规范：`type(scope): summary`，第一行 ≤ 72 字符，正文说明「为什么」和「如何复现」。

## 4. tag

```bash
# 附注 tag（推荐，带说明）
git tag -a v1.0.0 -m "v1: MySQL conditional stock deduction + unique index, no Redis/MQ. 200-thread burst load test, oversell check via verify.sql"

# 查看/删除
git tag -n9
git tag -d v1.0.0
```

版本号候选：功能收尾用 `v1.0.0`；若报告数据尚未回填、仅工装入库，可先打 `v1.0.0-rc1`。

## 5. push

```bash
git remote -v
git branch --show-current

git push origin main              # 或 git push origin <你的分支>
git push origin v1.0.0            # tag 不会随普通 push 上传，需单独推
git push origin --tags            # 推所有本地 tag（确认无脏 tag 再用）
```

若需要先推送分支到远端并建立跟踪：

```bash
git push -u origin main
```

## 6. 收尾核对

```bash
git log --oneline -5
git show --stat HEAD
git status                        # 期望 nothing to commit（除被忽略的 users.csv/results）
```

## 注意事项

- **不要**把 `JWT_SECRET`、真实 DB 密码、生产 token 提交进仓库；`.env` 已在 `.gitignore` 中。
- **禁止** `git push --force` 到主干；若需要改写历史，先与协作者确认。
- 压测产生的订单数据留在本地/容器数据库即可，不要 `git add docs/*.sql` 之外的数据导出文件。
