import argparse
import asyncio
import io
import os
import re
import sys
import tempfile
import wave

import numpy as np
from fastapi import FastAPI, UploadFile, File, Form, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from funasr import AutoModel
import uvicorn

if sys.platform == "win32":
    import site

    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    for sp in site.getsitepackages():
        cublas_path = os.path.join(sp, "nvidia", "cublas", "bin")
        cudnn_path = os.path.join(sp, "nvidia", "cudnn", "bin")
        if os.path.exists(cublas_path):
            os.environ["PATH"] = cublas_path + os.pathsep + os.environ["PATH"]
            if hasattr(os, "add_dll_directory"):
                os.add_dll_directory(cublas_path)
        if os.path.exists(cudnn_path):
            os.environ["PATH"] = cudnn_path + os.pathsep + os.environ["PATH"]
            if hasattr(os, "add_dll_directory"):
                os.add_dll_directory(cudnn_path)

import urllib.request
import urllib.parse
import json

def translate_vi_to_en(text: str) -> str:
    if not text or not text.strip():
        return ""
    try:
        encoded_text = urllib.parse.quote(text.strip())
        url = f"https://translate.googleapis.com/translate_a/single?client=gtx&sl=vi&tl=en&dt=t&q={encoded_text}"
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
        )
        with urllib.request.urlopen(req, timeout=4.0) as response:
            body = response.read().decode("utf-8")
            data = json.loads(body)
            parts = data[0]
            translated_text = "".join([part[0] for part in parts if part[0]])
            print(f"[Translation] Translated: '{text}' -> '{translated_text}'")
            return translated_text.strip()
    except Exception as e:
        print(f"[Translation] Error translating text '{text}': {e}")
        return ""

active_windows_connections = []

async def broadcast_to_windows(text: str):
    if not text or not text.strip():
        return
    print(f"[Broadcast] Sending to {len(active_windows_connections)} Windows clients: {text}")
    disconnected = []
    for connection in active_windows_connections:
        try:
            await connection.send_json({"event": "speak", "text": text.strip()})
        except Exception as e:
            print(f"[Broadcast] Failed to send to client: {e}")
            disconnected.append(connection)
    for conn in disconnected:
        if conn in active_windows_connections:
            active_windows_connections.remove(conn)

app = FastAPI()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.websocket("/v1/windows/stream")
@app.websocket("/windows/stream")
async def windows_websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    active_windows_connections.append(websocket)
    print(f"[WS Windows] Client connected: {websocket.client}")
    try:
        while True:
            data = await websocket.receive_text()
            if data == "ping":
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        print(f"[WS Windows] Client disconnected: {websocket.client}")
    except Exception as e:
        print(f"[WS Windows] Error: {e}")
    finally:
        if websocket in active_windows_connections:
            active_windows_connections.remove(websocket)



model_cache = {}
DEFAULT_MODEL = "iic/SenseVoiceSmall"


def normalize_model_name(model_name: str) -> str:
    value = (model_name or "").strip().lower()
    if value == "medium":
        return "openai/whisper-medium"
    elif value == "small":
        return "openai/whisper-small"
    elif value == "tiny":
        return "openai/whisper-tiny"
    elif value == "nano":
        return "iic/Fun-ASR-Nano-2512"
    return DEFAULT_MODEL if value == "" else model_name


def clean_text(text: str) -> str:
    # Remove tags like <|en|>, <|Speech|>, <|EMO_UNKNOWN|>, <|woitn|>, etc.
    cleaned = re.sub(r"<\|[^>]*\|>", "", text)
    
    # Common Whisper hallucination phrases (case-insensitive)
    hallucinations = [
        # Catch variations of "subscribe to channel to not miss interesting videos"
        r"(?i)(please\s+)?subscribe\s+(to\s+)?(ghien\s+mi\s+go|la\s+la\s+school|[a-z0-9\s.]+)?\s*channel\s+to\s+not\s+miss\s+interesting\s+videos",
        # Catch variations of "subscribe cho kênh... để không bỏ lỡ những video hấp dẫn"
        r"(?i)(hãy\s+)?subscribe\s+(cho\s+)?(kênh\s+)?(ghiền\s+mì\s+gõ|la\s+la\s+school|[a-z0-9\s.]+)?\s*(để\s+không\s+bỏ\s+lỡ\s+những\s+video\s+hấp\s+dẫn|để\s+không\s+bỏ\s+lỡ\s+các\s+video\s+hấp\s+dẫn|để\s+ủng\s+hộ)",
        r"(?i)ghien\s+mi\s+go",
        r"(?i)ghiền\s+mì\s+gõ",
        r"(?i)ghiền\s+m\.i\.go",
        r"(?i)la\s+la\s+school",
        r"(?i)please\s+subscribe\s+to\s+my\s+channel",
        r"(?i)thank\s+you\s+for\s+watching",
        r"(?i)thank\s+you\s+everyone\s+for\s+watching",
        r"(?i)cảm\s+ơn\s+các\s+bạn\s+đã\s+theo\s+dõi",
        r"(?i)cảm\s+ơn\s+mọi\s+người\s+đã\s+theo\s+dõi",
        r"(?i)hãy\s+đăng\s+ký\s+kênh",
        r"(?i)đăng\s+ký\s+kênh\s+để\s+ủng\s+hộ"
    ]
    for pattern in hallucinations:
        cleaned = re.sub(pattern, "", cleaned)
        
    cleaned = cleaned.strip()
    if re.match(r"^[\s.,!?\-]*$", cleaned):
        return ""
        
    return cleaned


def extract_text(result) -> str:
    if not result:
        return ""
    raw_text = ""
    if isinstance(result, list):
        parts = []
        for item in result:
            if isinstance(item, dict) and item.get("text"):
                parts.append(str(item["text"]))
        raw_text = "".join(parts)
    elif isinstance(result, dict):
        if result.get("text"):
            raw_text = str(result["text"])
        elif isinstance(result.get("result"), list):
            return extract_text(result["result"])
    else:
        raw_text = str(result)
        
    return clean_text(raw_text)


def write_wav_file(samples: np.ndarray, sample_rate: int = 16000) -> str:
    fd, path = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    pcm = np.clip(samples, -1.0, 1.0)
    pcm16 = (pcm * 32767.0).astype(np.int16)
    with wave.open(path, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(pcm16.tobytes())
    return path


class FasterWhisperWrapper:
    def __init__(self, model_size_or_path: str, device: str = "cuda"):
        from faster_whisper import WhisperModel
        print(f"Loading Faster-Whisper model '{model_size_or_path}' on {device} (compute_type=float32)...")
        self.model = WhisperModel(model_size_or_path, device=device, compute_type="float32")

    def generate(self, input: str, language: str = None, **kwargs):
        # Map 'auto' or empty string to None for auto-detection in faster-whisper
        lang_param = None if (not language or language.lower() == "auto") else language.lower()
        segments, info = self.model.transcribe(input, beam_size=5, language=lang_param)
        text = "".join([segment.text for segment in segments])
        return [{"text": text}]


def get_model(model_name: str, device: str = "cuda"):
    key = normalize_model_name(model_name)
    if key not in model_cache:
        try:
            if "whisper" in key.lower():
                model_size = key.split("/")[-1]
                if model_size.startswith("whisper-"):
                    model_size = model_size.replace("whisper-", "")
                if model_size == "medium":
                    print("GPU memory limit (GTX 1050 Ti 4GB): falling back from 'medium' to 'small' to prevent CUDA Out Of Memory.")
                    model_size = "small"
                model_cache[key] = FasterWhisperWrapper(model_size, device=device)
            else:
                print(f"Loading FunASR model '{key}' on {device}...")
                local_path = os.path.expanduser(f"~/.cache/modelscope/hub/models/{key}")
                if os.path.exists(os.path.join(local_path, "model.pt")):
                    print(f"Found local model files at '{local_path}'. Using local path to bypass download.")
                    model_to_load = local_path
                else:
                    model_to_load = key
                    
                model_cache[key] = AutoModel(
                    model=model_to_load,
                    device=device,
                    disable_update=True,
                )
        except Exception as e:
            if device == "cuda":
                print(f"Failed to load model '{key}' on CUDA ({e}). Falling back to CPU...")
                return get_model(model_name, device="cpu")
            else:
                default_key = normalize_model_name(DEFAULT_MODEL)
                if key != default_key:
                    print(f"Failed to load model '{key}' on CPU ({e}). Falling back to default model '{DEFAULT_MODEL}'...")
                    return get_model(DEFAULT_MODEL, device="cuda")
                else:
                    raise e
    return model_cache[key]




@app.post("/v1/audio/transcriptions")
@app.post("/audio/transcriptions")
async def transcribe(
    file: UploadFile = File(...),
    model: str = Form("medium"),
    language: str = Form(None),
    prompt: str = Form(None),
    temperature: float = Form(0.0),
    task: str = Form("transcribe"),
):
    suffix = os.path.splitext(file.filename)[1] if file.filename else ".wav"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        content = await file.read()
        tmp.write(content)
        tmp_path = tmp.name

    try:
        asr_model = get_model(model)
        # SenseVoiceSmall only supports: zh, en, ja, ko, yue
        run_lang = language
        if not isinstance(asr_model, FasterWhisperWrapper):
            supported_langs = ["zh", "en", "ja", "ko", "yue", "auto"]
            if (language or "").lower() not in supported_langs:
                run_lang = "auto"
        result = asr_model.generate(input=tmp_path, language=run_lang)
        text = extract_text(result)
        print(f"[{task}] {text}")
        
        # Auto translate and broadcast to Windows clients
        if text.strip():
            en_text = translate_vi_to_en(text)
            if en_text:
                asyncio.create_task(broadcast_to_windows(en_text))
                
        return {"text": text}
    except Exception as e:
        print(f"Transcription error: {e}")
        return JSONResponse(status_code=500, content={"error": str(e)})
    finally:
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except Exception:
                pass


@app.websocket("/v1/audio/stream")
@app.websocket("/audio/stream")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    model_name = websocket.query_params.get("model", DEFAULT_MODEL)
    language_param = websocket.query_params.get("language", "")
    language = None if (not language_param or language_param.lower() == "auto") else language_param
    client = websocket.query_params.get("client", "android").strip().lower()
    print(f"ASR Client connected: model={model_name}, language={language}, client={client}")

    audio_buffer = []
    lock = asyncio.Lock()
    last_text = ""

    async def transcribe_loop():
        nonlocal audio_buffer, last_text
        try:
            model_instance = get_model(model_name)
        except Exception as e:
            print(f"ASR WS failed to load model '{model_name}': {e}")
            await websocket.close()
            return

        unchanged_count = 0
        last_text = ""

        while True:
            await asyncio.sleep(0.4)

            async with lock:
                if len(audio_buffer) < 8000:
                    continue
                samples = np.array(audio_buffer, dtype=np.float32)

            try:
                t0 = asyncio.get_event_loop().time()
                tmp_path = write_wav_file(samples)
                try:
                    # SenseVoiceSmall only supports: zh, en, ja, ko, yue
                    run_lang = language
                    if not isinstance(model_instance, FasterWhisperWrapper):
                        supported_langs = ["zh", "en", "ja", "ko", "yue", "auto"]
                        if (language or "").lower() not in supported_langs:
                            run_lang = "auto"
                    result = model_instance.generate(input=tmp_path, language=run_lang)
                    text = extract_text(result)
                finally:
                    if os.path.exists(tmp_path):
                        try:
                            os.remove(tmp_path)
                        except Exception:
                            pass
                elapsed = asyncio.get_event_loop().time() - t0
                
                # Check text stability (silence detection)
                norm_text = clean_text(text).strip()
                norm_clean = re.sub(r"[^\w\s]", "", norm_text.lower()).strip()
                last_clean = re.sub(r"[^\w\s]", "", last_text.lower()).strip()
                
                # Calculate RMS of recent audio
                rms = 0.0
                if len(samples) > 0:
                    recent_len = min(len(samples), int(16000 * 0.8))
                    recent_samples = samples[-recent_len:]
                    rms = float(np.sqrt(np.mean(np.square(recent_samples))))
                
                # If RMS is extremely low, we consider it silent
                # For clean digital loopback, silence RMS is typically < 0.001
                # We use 0.003 to be safe but sensitive
                is_audio_silent = (rms < 0.003) if len(samples) >= int(16000 * 0.8) else False

                if norm_clean:
                    if norm_clean == last_clean or is_audio_silent:
                        unchanged_count += 1
                    else:
                        unchanged_count = 0
                        last_text = norm_text
                        print(f"ASR partial: {norm_text} (RMS: {rms:.5f})")
                        await websocket.send_json({
                            "event": "result",
                            "text": norm_text,
                            "is_final": False,
                        })
                else:
                    if last_text:
                        unchanged_count += 1
                # Finalize logic: Silence detection, length limit, or maximum duration limit reached
                words = norm_text.split()
                num_words = len(words)
                
                should_finalize = False
                finalize_reason = ""
                
                # 1. Force finalize if sentence is too long to prevent translation fluctuation
                if num_words >= 15:
                    should_finalize = True
                    finalize_reason = "length_limit"
                # 2. Finalize on silence / text stability
                elif num_words > 0:
                    # For short text, wait for 2 loops (~0.8s) of stability
                    # For longer text (>= 8 words), 1 loop (~0.4s) of stability is enough!
                    required_unchanged = 2 if num_words < 8 else 1
                    if (unchanged_count >= required_unchanged or is_audio_silent) and last_text:
                        should_finalize = True
                        finalize_reason = "silence"
                # 3. Maximum duration safeguard (12 seconds)
                elif len(audio_buffer) > 16000 * 12:
                    should_finalize = True
                    finalize_reason = "max_duration"

                if should_finalize:
                    finalized_text = last_text if last_text else text
                    if finalized_text.strip():
                        print(f"ASR final ({finalize_reason}): {finalized_text}")
                        # Auto translate and broadcast to Windows clients (Skip if source is Windows client to avoid loopback feedback)
                        en_text = translate_vi_to_en(finalized_text)
                        if en_text and client != "windows":
                            asyncio.create_task(broadcast_to_windows(en_text))
                            
                        await websocket.send_json({
                            "event": "result",
                            "text": en_text if en_text else finalized_text,
                            "is_final": True,
                            "vi_text": finalized_text,
                        })
                        async with lock:
                            if finalize_reason in ("silence", "length_limit"):
                                # Keep only the last 0.2s of audio to avoid cutting speech onset and duplicates
                                keep_samples = int(16000 * 0.2)
                            else:
                                # Keep last 3.0s for overlap context
                                keep_samples = int(16000 * 3.0)
                                
                            if len(audio_buffer) > keep_samples:
                                audio_buffer = audio_buffer[-keep_samples:]
                            else:
                                audio_buffer = []
                    else:
                        # Entire buffer was silent, discard it completely
                        async with lock:
                            audio_buffer = []
                            
                    last_text = ""
                    unchanged_count = 0

            except Exception as e:
                print(f"ASR WS transcribe error: {e}")

    transcribe_task = asyncio.create_task(transcribe_loop())

    try:
        while True:
            data = await websocket.receive_bytes()
            if not data:
                continue
            shorts = np.frombuffer(data, dtype=np.int16)
            floats = shorts.astype(np.float32) / 32768.0
            async with lock:
                audio_buffer.extend(floats.tolist())
    except WebSocketDisconnect:
        print("ASR Client disconnected")
    except Exception as e:
        print(f"ASR WS error: {e}")
    finally:
        transcribe_task.cancel()
        try:
            await websocket.close()
        except Exception:
            pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    args = parser.parse_args()

    try:
        print(f"Warming up model {args.model}...")
        get_model(args.model)
    except Exception as e:
        print(f"Warm up failed: {e}")

    print(f"Starting ASR Server on http://{args.host}:{args.port}")
    uvicorn.run(app, host=args.host, port=args.port)
