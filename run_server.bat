@echo off
title ASR Server
echo ===================================================
echo               KHOI DONG ASR SERVER
echo ===================================================
echo.
cd /d "%~dp0"
if exist ".\scripts\venv\Scripts\python.exe" (
    echo Dang khoi chay server tu virtual environment...
    .\scripts\venv\Scripts\python.exe scripts\asr_server.py --host 0.0.0.0 --port 8000
) else (
    echo [ERROR] Khong tim thay Python venv tai .\scripts\venv\
    echo Vui long kiem tra lai thu muc du an.
)
echo.
echo Server da dung hoac gap loi.
pause
