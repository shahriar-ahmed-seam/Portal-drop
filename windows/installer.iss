#ifndef MyAppVersion
  #define MyAppVersion "0.6.0"
#endif

[Setup]
AppId={{8C9E4F6A-3B12-4A7D-9F22-51E7B6A29C10}
AppName=Portal Drop
AppVersion={#MyAppVersion}
AppVerName=Portal Drop {#MyAppVersion}
AppPublisher=Portal Drop
AppPublisherURL=https://github.com/shahriar-ahmed-seam/Portal-drop
AppSupportURL=https://github.com/shahriar-ahmed-seam/Portal-drop/issues
DefaultDirName={localappdata}\Portal Drop
DefaultGroupName=Portal Drop
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
DisableProgramGroupPage=yes
SetupIconFile=assets\portal-drop.ico
UninstallDisplayIcon={app}\portal-drop.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
OutputDir=..\dist
OutputBaseFilename=portal-drop-{#MyAppVersion}-windows-x64-setup
CloseApplications=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional shortcuts:"; Flags: unchecked
Name: "autostart"; Description: "Start background sync service when Windows starts"; GroupDescription: "Startup:"

[Files]
Source: "..\linux\target\release\portal-drop.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "target\release\portal-drop-desktop.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "assets\portal-drop.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\assets\sounds\*.wav"; DestDir: "{app}\sounds"; Flags: ignoreversion

[Icons]
Name: "{userprograms}\Portal Drop"; Filename: "{app}\portal-drop-desktop.exe"; WorkingDir: "{app}"; IconFilename: "{app}\portal-drop.ico"; Comment: "Direct LAN messaging and file transfer with Android"
Name: "{userdesktop}\Portal Drop"; Filename: "{app}\portal-drop-desktop.exe"; WorkingDir: "{app}"; IconFilename: "{app}\portal-drop.ico"; Tasks: desktopicon
Name: "{userstartup}\Portal Drop Service"; Filename: "{app}\portal-drop.exe"; Parameters: "serve"; WorkingDir: "{app}"; IconFilename: "{app}\portal-drop.ico"; Flags: runminimized; Tasks: autostart

[Run]
Filename: "{app}\portal-drop-desktop.exe"; Description: "Launch Portal Drop"; Flags: nowait postinstall skipifsilent

[Code]
procedure StopPortalDropProcesses;
var
  ResultCode: Integer;
begin
  Exec('taskkill.exe', '/F /IM portal-drop-desktop.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec('taskkill.exe', '/F /IM portal-drop.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  StopPortalDropProcesses;
  Result := '';
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    StopPortalDropProcesses;
  end;
end;
