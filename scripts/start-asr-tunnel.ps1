# PowerShell script to start the local ASR Server and the Cloudflare Tunnel for dich.vivutrade.io.vn

Write-Host "Starting ASR Server on port 8000 (visible cmd window)..."
Start-Process cmd -ArgumentList "/k cd /d D:\ai-interpreter-deepseek && scripts\venv\Scripts\python.exe scripts\asr_server.py --port 8000" -WindowStyle Normal

Write-Host "Starting Cloudflare Tunnel for dich.vivutrade.io.vn (visible cmd window)..."
Start-Process cmd -ArgumentList "/k cd /d D:\ai-interpreter-deepseek && `"C:\Program Files (x86)\cloudflared\cloudflared.exe`" tunnel run --url http://localhost:8000 dich-vivutrade" -WindowStyle Normal

Write-Host "Done! Please keep the command windows open while using the app."
