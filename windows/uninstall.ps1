#Requires -Version 5.1
$ErrorActionPreference = "SilentlyContinue"

$InstallDir = Join-Path $env:LOCALAPPDATA "Portal Drop"
$StartMenu  = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Portal Drop.lnk"
$Startup    = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Startup\Portal Drop Service.lnk"

Get-Process -Name "portal-drop-desktop","portal-drop" | Stop-Process -Force
Start-Sleep -Milliseconds 300

Remove-Item -Force $StartMenu, $Startup
Remove-Item -Recurse -Force $InstallDir

$UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($UserPath) {
    $Filtered = ($UserPath -split ';' | Where-Object { $_ -and $_ -ne $InstallDir }) -join ';'
    [Environment]::SetEnvironmentVariable("Path", $Filtered, "User")
}

Write-Host "Portal Drop uninstalled. Your downloaded files in Downloads\Portal Drop were kept."
