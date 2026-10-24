$WshShell = New-Object -ComObject WScript.Shell
$StartupPath = "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup"
$ShortcutPath = Join-Path $StartupPath "ASR_Server_Background.lnk"

# PowerShell command to run Python server and Cloudflare tunnel via auto-restart batch files hidden
$TargetCmd = "Start-Process 'cmd.exe' -ArgumentList '/c d:\ai-interpreter-deepseek\scripts\run_server.bat' -WindowStyle Hidden; Start-Process 'cmd.exe' -ArgumentList '/c d:\ai-interpreter-deepseek\scripts\run_tunnel.bat' -WindowStyle Hidden"

$Shortcut = $WshShell.CreateShortcut($ShortcutPath)
$Shortcut.TargetPath = "powershell.exe"
$Shortcut.Arguments = "-NoProfile -WindowStyle Hidden -Command `"$TargetCmd`""
$Shortcut.WorkingDirectory = "d:\ai-interpreter-deepseek"
$Shortcut.Description = "Start ASR Server and Cloudflare Tunnel in background with Auto-Restart protection"
$Shortcut.Save()

Write-Host "Auto-restart Startup Shortcut updated successfully!"
