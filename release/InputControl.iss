#define AppName "Input_Control"
#define Receiver "input-control-receiver.exe"

[Setup]
AppId={{7B72E361-C502-40C1-A839-CC5C2B756531}
AppName={#AppName}
AppVersion={#ReleaseVersion}
AppPublisher=M. Chung
AppPublisherURL=https://github.com/minsmakes/Input_Control
AppSupportURL=https://github.com/minsmakes/Input_Control/issues
AppUpdatesURL=https://github.com/minsmakes/Input_Control/releases
DefaultDirName={localappdata}\Programs\{#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64os
ArchitecturesInstallIn64BitMode=x64os
MinVersion=10.0.22000
SetupIconFile=..\windows-receiver\assets\input-control.ico
UninstallDisplayIcon={app}\{#Receiver}
VersionInfoVersion={#ReleaseVersion}
VersionInfoCompany=M. Chung
VersionInfoDescription=Input_Control Windows Setup
OutputDir={#PackageDir}
OutputBaseFilename=InputControl-v{#ReleaseVersion}-windows-x64-setup
WizardStyle=modern
CloseApplications=yes
CloseApplicationsFilter={#Receiver}
RestartApplications=no
#ifdef Production
SignedUninstaller=yes
SignedUninstallerDir={#UninstallerDir}
#endif

[Files]
Source: "{#ReceiverDir}\{#Receiver}"; DestDir: "{app}"; Flags: ignoreversion

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked

[Icons]
Name: "{userprograms}\{#AppName}"; Filename: "{app}\{#Receiver}"
Name: "{userdesktop}\{#AppName}"; Filename: "{app}\{#Receiver}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#Receiver}"; Description: "Open Input_Control"; Flags: nowait postinstall skipifsilent

[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  RunKey, StartupCommand, InstalledCommand: String;
begin
  if CurUninstallStep = usPostUninstall then begin
    RunKey := 'Software\Microsoft\Windows\CurrentVersion\Run';
    InstalledCommand := '"' + ExpandConstant('{app}\{#Receiver}') + '"';
    if RegQueryStringValue(HKCU, RunKey, 'Input Control', StartupCommand) and
       (CompareText(StartupCommand, InstalledCommand) = 0) then
      RegDeleteValue(HKCU, RunKey, 'Input Control');
  end;
end;
