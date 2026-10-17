[Setup]
AppId={{AC73FE87-9243-44BD-97F9-75BDC963AABB}
AppName=Agent Bridge
AppVersion=0.2.0
AppPublisher=Agent Bridge
DefaultDirName={localappdata}\Programs\AgentBridge
DefaultGroupName=Agent Bridge
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir=..\dist
OutputBaseFilename=AgentBridge-Setup-0.2.0-x64
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\AgentBridge.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes

[Files]
Source: "..\dist\AgentBridge\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Tasks]
Name: desktopicon; Description: "Create a desktop shortcut"; Flags: unchecked

[Icons]
Name: "{group}\Agent Bridge"; Filename: "{app}\AgentBridge.exe"
Name: "{autodesktop}\Agent Bridge"; Filename: "{app}\AgentBridge.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\AgentBridge.exe"; Description: "Launch Agent Bridge"; Flags: nowait postinstall skipifsilent
