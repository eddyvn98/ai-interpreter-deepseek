@echo off
:loop
d:\ai-interpreter-deepseek\scripts\venv\Scripts\python.exe d:\ai-interpreter-deepseek\scripts\asr_server.py --port 8000
echo Server crashed or exited. Restarting in 3 seconds...
timeout /t 3 >nul
goto loop
