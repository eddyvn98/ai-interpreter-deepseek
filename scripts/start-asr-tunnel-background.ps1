# PowerShell script to start the local ASR Server and the Cloudflare Tunnel in the background (hidden)

$BaseDir = "D:\ai-interpreter-deepseek"

Write-Output "Starting ASR Server in the background..."
Start-Process -FilePath "$BaseDir\scripts\venv\Scripts\python.exe" `
              -ArgumentList "$BaseDir\scripts\asr_server.py --port 8000" `
              -WorkingDirectory $BaseDir `
              -WindowStyle Hidden `
              -RedirectStandardOutput "$BaseDir\scripts\server_out.log" `
              -RedirectStandardError "$BaseDir\scripts\server_err.log"

Write-Output "Starting Cloudflare Tunnel in the background..."
Start-Process -FilePath "C:\Program Files (x86)\cloudflared\cloudflared.exe" `
              -ArgumentList "--config `"$BaseDir\scripts\blank_config.yml`" tunnel run --url http://localhost:8000 dich-vivutrade" `
              -WorkingDirectory $BaseDir `
              -WindowStyle Hidden `
              -RedirectStandardOutput "$BaseDir\scripts\tunnel_dich.log" `
              -RedirectStandardError "$BaseDir\scripts\tunnel_dich_err.log"

Write-Output "Both services started in the background."
