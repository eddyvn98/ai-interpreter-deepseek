<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/8b029082-164b-4bae-adcd-a4e75f6a867e

## Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device

For the local ASR server, install `funasr` and start `scripts/asr_server.py`. The app now targets the FunASR-based WebSocket/HTTP ASR backend by default.

Recommended setup:

```powershell
.\scripts\venv\Scripts\python.exe -m pip install -r scripts\requirements-funasr.txt
.\scripts\venv\Scripts\python.exe scripts\asr_server.py --host 0.0.0.0 --port 8000
```

## Build Quickly (PowerShell)

Use this command from repo root:

```powershell
.\scripts\build-local.ps1
```

The script automatically finds a local JDK (Android Studio JBR / JDK 21), sets `JAVA_HOME` for the current shell, then runs:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

If `JAVA_HOME` is missing, this is the fastest way to build without manual setup every time.
