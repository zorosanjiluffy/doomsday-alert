# Makes the watcher start automatically (minimized) every time you log in to Windows.
# Run:    powershell -ExecutionPolicy Bypass -File .\install-autostart.ps1
# Undo:   powershell -ExecutionPolicy Bypass -File .\install-autostart.ps1 -Remove
param([switch]$Remove)

$startup = [Environment]::GetFolderPath('Startup')
$lnk = Join-Path $startup 'Doomsday Ticket Watcher.lnk'

if ($Remove) {
    if (Test-Path $lnk) { Remove-Item $lnk -Confirm:$false; 'Auto-start removed.' } else { 'Auto-start was not installed.' }
    return
}

$shell = New-Object -ComObject WScript.Shell
$s = $shell.CreateShortcut($lnk)
$s.TargetPath = Join-Path $PSScriptRoot 'start-watcher.bat'
$s.WorkingDirectory = $PSScriptRoot
$s.WindowStyle = 7   # minimized
$s.Save()
"Auto-start installed: $lnk"
