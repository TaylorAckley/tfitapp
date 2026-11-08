<#
.SYNOPSIS
    Boots the tfit emulator if it is not already running, installs the debug build, and opens the app.

.DESCRIPTION
    Safe to run repeatedly: a running emulator is reused, a booted one is not waited on,
    and installDebug over the same build is a no-op. Nothing here touches keys or settings.

.PARAMETER SkipInstall
    Only bring the emulator up; do not build or install.

.PARAMETER Tests
    After installing, run the JVM tests and both instrumented suites. Note that the
    instrumented run uninstalls the app, so the script reinstalls and relaunches afterwards.

.EXAMPLE
    .\start.ps1
    .\start.ps1 -Tests
#>
[CmdletBinding()]
param(
    [switch] $SkipInstall,
    [switch] $Tests
)

$ErrorActionPreference = "Stop"

$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
$avd = "tfit"
$jdk = "C:\Program Files\Android\openjdk\jdk-21.0.8"
$package = "com.tfitapp"
$activity = "$package/.ui.MainActivity"

foreach ($tool in @($adb, $emulator, $jdk)) {
    if (-not (Test-Path $tool)) { throw "Not found: $tool. Install Android Studio's SDK / JDK 21, or edit the paths at the top of this script." }
}
$env:JAVA_HOME = $jdk

function Get-EmulatorSerial {
    (& $adb devices) | Where-Object { $_ -match '^(emulator-\d+)\s+device$' } | ForEach-Object { $Matches[1] } | Select-Object -First 1
}

function Wait-ForBoot([string] $serial) {
    Write-Host "Waiting for $serial to boot" -NoNewline
    for ($i = 0; $i -lt 90; $i++) {
        $booted = (& $adb -s $serial shell getprop sys.boot_completed 2>$null) -join ""
        if ($booted.Trim() -eq "1") { Write-Host " ready."; return }
        Write-Host "." -NoNewline
        Start-Sleep -Seconds 2
    }
    throw "Emulator did not finish booting in 3 minutes."
}

# 1. Emulator: reuse or boot. A headless one (booted for the test suites with -no-window)
#    would take the install and show nothing, so it is replaced with a visible one.
$serial = Get-EmulatorSerial
$headless = Get-CimInstance Win32_Process -Filter "Name = 'emulator.exe'" | Where-Object { $_.CommandLine -match '-no-window' }
if ($serial -and $headless) {
    Write-Host "Emulator $serial is running headless; restarting it with a window."
    & $adb -s $serial emu kill | Out-Null
    for ($i = 0; $i -lt 15 -and (Get-EmulatorSerial); $i++) { Start-Sleep -Seconds 1 }
    $serial = $null
}
if ($serial) {
    Write-Host "Emulator already running as $serial."
} else {
    if (-not ((& $emulator -list-avds) -contains $avd)) { throw "AVD '$avd' does not exist. Create it in Android Studio > Device Manager (API 36, x86_64)." }
    Write-Host "Booting AVD '$avd'..."
    Start-Process -FilePath $emulator -ArgumentList @("-avd", $avd) -WindowStyle Minimized | Out-Null
    for ($i = 0; $i -lt 30 -and -not $serial; $i++) { Start-Sleep -Seconds 2; $serial = Get-EmulatorSerial }
    if (-not $serial) { throw "adb never saw the emulator. Check the emulator window for an error." }
}
Wait-ForBoot $serial

if ($SkipInstall) { return }

# 2. Build and install. installDebug is incremental; an unchanged build installs nothing.
Push-Location $PSScriptRoot
try {
    if ($Tests) {
        Write-Host "JVM tests..."
        & .\gradlew.bat test
        if ($LASTEXITCODE -ne 0) { throw "JVM tests failed." }
        Write-Host "Instrumented tests (Room, then app E2E)..."
        # leaveApksInstalledAfterRun keeps the app, and so its data, on the device: pasted API keys survive a test run.
        & .\gradlew.bat :infrastructure:room:connectedDebugAndroidTest :app:connectedDebugAndroidTest "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"
        if ($LASTEXITCODE -ne 0) { throw "Instrumented tests failed. Report: app\build\reports\androidTests\connected\debug\index.html" }
    }

    Write-Host "Installing..."
    & .\gradlew.bat installDebug -q
    if ($LASTEXITCODE -ne 0) { throw "installDebug failed." }
} finally {
    Pop-Location
}

# 3. Launch.
& $adb -s $serial shell am start -n $activity | Out-Null
Write-Host "TFitApp is up on $serial."
