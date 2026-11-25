---
name: vasyan-ops
description: Operational runbooks for deploying liner-worker, querying Cloudflare D1, building and installing liner-android via Wi-Fi ADB, inspecting adb logcat, packaging liner-desktop, and testing Worker API endpoints safely in Windows PowerShell 5.1.
---

# `vasyan-ops` — Операционные runbook'и

Используй этот навык при сборке, деплое, отладке на устройстве или работе с D1 в репозитории `c:\liner_mod`.

---

## 1. `liner-worker`: проверка, деплой и D1

Рабочая директория: `c:\liner_mod\liner-worker`.

```powershell
# Typecheck + Deploy
npx tsc --noEmit; npx wrangler deploy
```

### Работа с D1 (`liner-db`)
```powershell
# Проверка пользователей
npx wrangler d1 execute liner-db --remote --command "SELECT id, username, created_at FROM users;"

# Подсчёт треков и плейлистов
npx wrangler d1 execute liner-db --remote --command "SELECT COUNT(*) AS likes FROM liked_tracks; SELECT COUNT(*) AS playlists FROM playlists;"
```

### Тестирование Worker API в PowerShell 5.1
Избегай `curl.exe -d '{...}'` из-за экранирования кавычек в PowerShell. Используй `Invoke-RestMethod`:
```powershell
$body = @{ username = "test"; password = "secret" } | ConvertTo-Json
Invoke-RestMethod -Uri "https://liner-worker.vasyan.workers.dev/v1/auth/login" -Method Post -ContentType "application/json" -Body $body
```
Для сложных проверок создавай временный скрипт в `<Artifact Directory>\scratch\check.js` и запускай `node <path>`.

---

## 2. `liner-android`: сборка, Wi-Fi ADB и Logcat

Рабочая директория: `c:\liner_mod\liner-android`.
Путь к `adb`: `C:\Users\svyat\AppData\Local\Android\Sdk\platform-tools\adb.exe`.

### Подключение и установка APK
При включённой «Отладке по Wi-Fi» на `192.168.1.231` телефон обычно **сам находится по mDNS** (`adb-..._adb-tls-connect._tcp`) без ввода порта. Сначала дай `adb` 2 секунды на mDNS-поиск и проверь `adb devices`:
```powershell
$adb = "C:\Users\svyat\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb start-server | Out-Null; Start-Sleep -Seconds 2
$dev = (& $adb devices | Select-String "\tdevice$" | Select-Object -First 1).ToString().Split("`t")[0]
# Только если $dev пустой (mDNS не сработал), подключайся вручную по порту:
# & $adb connect 192.168.1.231:<PORT>
```

Сборка и установка:
```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
& $adb -s $dev install -r app\build\outputs\apk\debug\app-debug.apk
```

### Снятие логов (`adb logcat`)
```powershell
$adb = "C:\Users\svyat\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb -s 192.168.1.231:<PORT> logcat -d -t 200 | Select-String -Pattern "Liner|Media3|ExoPlayer|LanConnect|OkHttp|AndroidRuntime"
```

---

## 3. `liner-desktop`: сборка и упаковка Electron

Рабочая директория: `c:\liner_mod\liner-desktop`.

```powershell
# Проверка типов и сборка Vite
npx --yes pnpm exec tsc -b; npx --yes pnpm exec vite build
```

Перед упаковкой установщика обязательно закрой запущенный `liner.exe` (включая иконку в трее), иначе `electron-builder --win` упадёт с `EBUSY`:
```powershell
Get-Process -Name "liner" -ErrorAction SilentlyContinue | Stop-Process -Force
npx --yes pnpm electron:build:win
```
Готовый установщик: `release\vasyan-Setup-0.2.12.exe`, распакованная версия: `release\win-unpacked\liner.exe`.
