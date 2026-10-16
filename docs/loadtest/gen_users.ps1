# gen_users.ps1
# 批量调用 /api/auth/login 生成压测用 users.csv（列: userId,jwt）
# 用法（PowerShell，在项目根目录或任意目录均可）:
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs\loadtest\gen_users.ps1
#   可选参数: -Count 200 -Password '12345678' -OutFile docs\loadtest\users.csv
param(
    [string] $Base     = 'http://localhost:8080',
    [int]    $Count    = 200,
    [string] $Prefix   = 'loadtest',
    [string] $Password = '12345678',
    [string] $OutFile  = "$PSScriptRoot\users.csv"
)

$ErrorActionPreference = 'Continue'
$rows = New-Object System.Collections.Generic.List[string]
$rows.Add('userId,jwt')   # 表头必须存在，JMeter CSV Data Set 设了 ignoreFirstLine=true
$failed = @()

for ($i = 1; $i -le $Count; $i++) {
    $username = "$Prefix$i"
    $body = (@{ username = $username; password = $Password } | ConvertTo-Json -Compress)
    try {
        $resp = Invoke-RestMethod -Uri "$Base/api/auth/login" -Method Post `
                -ContentType 'application/json' -Body $body -TimeoutSec 15
        if ($resp.code -eq 0 -and $resp.data.accessToken) {
            $rows.Add("$($resp.data.userId),$($resp.data.accessToken)")
        } else {
            $failed += "$username -> code=$($resp.code) msg=$($resp.message)"
        }
    } catch {
        $failed += "$username -> $($_.Exception.Message)"
    }

    if ($i % 50 -eq 0) { Write-Host "progress: $i/$Count" }
}

# Windows PowerShell 5.1 的 Set-Content -Encoding utf8 会写 BOM，这里显式用无 BOM 的 UTF-8
$target = [System.IO.Path]::GetFullPath($OutFile)
$parent = Split-Path -Parent $target
if (-not (Test-Path $parent)) { Write-Host "输出目录不存在: $parent"; exit 2 }
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($target, $rows, $utf8NoBom)
$OutFile = $target

$total = $rows.Count - 1
Write-Host "written rows : $total (expect $Count)"
Write-Host "output file  : $OutFile"
if ($failed.Count -gt 0) {
    Write-Host "FAILED $($failed.Count):"
    $failed | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host 'OK: all logins succeeded'
