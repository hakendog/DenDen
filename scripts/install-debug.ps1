$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$package = "com.tensal.denden"

function Start-DenDen {
    & adb -d shell am start --user 0 -n "$package/.LauncherActivity" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "App installed but could not be launched." }
}

Push-Location $root
try {
    & .\gradlew.bat :app:assembleDebug --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Debug build failed." }

    & adb -d install --user 0 -r $apk
    if ($LASTEXITCODE -ne 0) {
        throw "Install failed. Existing App data was preserved; do not uninstall it to bypass a signing mismatch."
    }

    Start-DenDen
    Write-Output "DenDen debug APK 已安裝並開啟；請在 App 掃描本次設定產生的 QR Code。"
} finally {
    Pop-Location
}
