#Requires -Version 5.1
$ErrorActionPreference = "Stop"

$InstallDir = Join-Path $env:LOCALAPPDATA "Portal Drop"
$SoundsDir  = Join-Path $InstallDir "sounds"
$StartMenu  = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"
$StartupDir = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Startup"
$SourceDir  = Split-Path -Parent $MyInvocation.MyCommand.Path

$CliBin = Join-Path $SourceDir "portal-drop.exe"
$GuiBin = Join-Path $SourceDir "portal-drop-desktop.exe"

if (-not (Test-Path $CliBin) -or -not (Test-Path $GuiBin)) {
    Write-Error "Missing portal-drop.exe or portal-drop-desktop.exe next to install.ps1. Build or extract the Windows release zip first."
    exit 1
}

Write-Host "Installing Portal Drop to $InstallDir ..."
Get-Process -Name "portal-drop-desktop","portal-drop" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 300

New-Item -ItemType Directory -Force -Path $InstallDir, $SoundsDir | Out-Null
Copy-Item -Force $CliBin (Join-Path $InstallDir "portal-drop.exe")
Copy-Item -Force $GuiBin (Join-Path $InstallDir "portal-drop-desktop.exe")

$IconSrc = Join-Path $SourceDir "portal-drop.ico"
if (-not (Test-Path $IconSrc)) {
    $IconSrc = Join-Path $SourceDir "assets\portal-drop.ico"
}
$IconDst = Join-Path $InstallDir "portal-drop.ico"
if (Test-Path $IconSrc) {
    Copy-Item -Force $IconSrc $IconDst
}

$SoundsSrc = Join-Path $SourceDir "sounds"
if (-not (Test-Path $SoundsSrc)) {
    $SoundsSrc = Join-Path (Split-Path -Parent $SourceDir) "assets\sounds"
}
if (Test-Path $SoundsSrc) {
    Copy-Item -Force (Join-Path $SoundsSrc "*.wav") $SoundsDir
}

# Add install dir to user PATH if missing
$UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
if (-not ($UserPath -split ';' | Where-Object { $_ -eq $InstallDir })) {
    $NewPath = if ($UserPath) { "$UserPath;$InstallDir" } else { $InstallDir }
    [Environment]::SetEnvironmentVariable("Path", $NewPath, "User")
}

# Create Start Menu & Login Startup shortcuts
$Wsh = New-Object -ComObject WScript.Shell
$AppLink = $Wsh.CreateShortcut((Join-Path $StartMenu "Portal Drop.lnk"))
$AppLink.TargetPath = Join-Path $InstallDir "portal-drop-desktop.exe"
$AppLink.WorkingDirectory = $InstallDir
if (Test-Path $IconDst) { $AppLink.IconLocation = $IconDst }
$AppLink.Description = "Send files and messages between Windows and Android over your LAN"
$AppLink.Save()

$BootLink = $Wsh.CreateShortcut((Join-Path $StartupDir "Portal Drop Service.lnk"))
$BootLink.TargetPath = Join-Path $InstallDir "portal-drop.exe"
$BootLink.Arguments = "serve"
$BootLink.WorkingDirectory = $InstallDir
$BootLink.WindowStyle = 7
$BootLink.Save()

# Add local private firewall rule if running elevated
$IsAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator
)
if ($IsAdmin) {
    Remove-NetFirewallRule -DisplayName "Portal Drop (LAN TCP 53621)" -ErrorAction SilentlyContinue
    New-NetFirewallRule -DisplayName "Portal Drop (LAN TCP 53621)" `
        -Direction Inbound -Protocol TCP -LocalPort 53621 -Profile Private -Action Allow | Out-Null
    Write-Host "Added Windows Firewall rule for Private networks (TCP 53621)."
} else {
    Write-Host "Note: If Windows Defender Firewall prompts when Android connects, allow Private networks (TCP 53621)."
}

Start-Process -FilePath (Join-Path $InstallDir "portal-drop-desktop.exe")
Write-Host "Portal Drop installed and started."
