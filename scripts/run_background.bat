@echo off
cd /d d:\ai-interpreter-deepseek
start "" d:\ai-interpreter-deepseek\scripts\venv\Scripts\python.exe d:\ai-interpreter-deepseek\scripts\asr_server.py --port 8000
start "" "C:\Program Files (x86)\cloudflared\cloudflared.exe" --config d:\ai-interpreter-deepseek\scripts\blank_config.yml tunnel run --url http://localhost:8000 dich-vivutrade
