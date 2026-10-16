# run_sql.ps1
# 在 Docker MySQL 容器里执行 docs/loadtest 下的 SQL（密码从 .env 读取，不打印）
# 用法:
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs\loadtest\run_sql.ps1 -Sql prepare
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs\loadtest\run_sql.ps1 -Sql verify
param(
    [ValidateSet('prepare', 'verify', 'custom')]
    [string] $Sql       = 'prepare',
    [string] $CustomFile = '',
    [string] $Container  = 'flash-sale-mysql',
    [string] $Database   = 'seckill',
    [string] $User       = 'root'
)

$ErrorActionPreference = 'Continue'
# docs/loadtest/../.. = 项目根目录（.env 所在地）
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path $envFile)) { Write-Host "缺少 .env: $envFile"; exit 2 }

$file = switch ($Sql) {
    'prepare' { Join-Path $PSScriptRoot 'prepare.sql' }
    'verify'  { Join-Path $PSScriptRoot 'verify.sql' }
    'custom'  { $CustomFile }
}
if (-not $file -or -not (Test-Path $file)) { Write-Host "SQL 文件不存在: $file"; exit 2 }

# root 用 MYSQL_ROOT_PASSWORD，其他业务账号用 MYSQL_PASSWORD
$pattern = if ($User -eq 'root') { '^MYSQL_ROOT_PASSWORD=(.+)$' } else { '^MYSQL_PASSWORD=(.+)$' }
$match = Select-String -Path $envFile -Pattern $pattern | Select-Object -First 1
if (-not $match) { Write-Host ".env 中找不到 $pattern"; exit 2 }
$pwd_ = $match.Matches[0].Groups[1].Value

Write-Host "=== executing $file against container $Container (db=$Database, user=$User) ==="
# 把 mysql 命令行拼成单一字符串交给容器里的 sh 执行，避免 PowerShell 参数拆分问题
$inner = "mysql -t -u$User $Database"
Get-Content $file -Raw |
  docker exec -i -e "MYSQL_PWD=$pwd_" $Container sh -c $inner 2>&1 |
  Where-Object { $_ -notmatch 'Using a password on the command line' }
