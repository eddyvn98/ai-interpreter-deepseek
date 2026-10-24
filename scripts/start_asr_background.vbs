Set WshShell = CreateObject("WScript.Shell")
WshShell.Run "cmd /c d:\ai-interpreter-deepseek\scripts\run_background.bat > d:\ai-interpreter-deepseek\scripts\bat_log.txt 2>&1", 0, False
