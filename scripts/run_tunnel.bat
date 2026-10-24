@echo off
:loop
"C:\Program Files (x86)\cloudflared\cloudflared.exe" --config d:\ai-interpreter-deepseek\scripts\blank_config.yml tunnel run --url http://localhost:8000 dich-vivutrade
echo Tunnel crashed or exited. Restarting in 5 seconds...
timeout /t 5 >nul
goto loop
