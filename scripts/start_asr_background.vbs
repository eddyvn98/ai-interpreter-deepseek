Set WshShell = CreateObject("WScript.Shell")
WshShell.Run "D:\ai-interpreter-deepseek\scripts\venv\Scripts\python.exe D:\ai-interpreter-deepseek\scripts\asr_server.py --port 8000", 0, False
WshShell.Run """C:\Program Files (x86)\cloudflared\cloudflared.exe"" tunnel run --url http://localhost:8000 dich-vivutrade", 0, False
