import os
os.environ["USE_MODELSCOPE_HUB"] = "0"

import argparse
import asyncio
import io
import re
import sys
import tempfile
import wave

def resolve_cache_path(path_suffix: str) -> str:
    primary_user_dir = "C:/Users/hatha"
    if os.path.exists(primary_user_dir):
        resolved = os.path.join(primary_user_dir, path_suffix.replace("~/", "").replace("~\\", ""))
        if os.path.exists(resolved):
            return resolved
    return os.path.expanduser(path_suffix)

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

def get_deepseek_api_key() -> str:
    key = os.getenv("DEEPSEEK_API_KEY", "").strip() or os.getenv("DEEPSEEK_KEY", "").strip()
    if not key:
        emiri_env = Path("D:/Emiri-Rina/.env")
        if emiri_env.exists():
            try:
                for line in emiri_env.read_text(encoding="utf-8").splitlines():
                    line = line.strip()
                    if line.startswith("DEEPSEEK_API_KEY=") or line.startswith("DEEPSEEK_KEY="):
                        val = line.split("=", 1)[1].strip().strip('"').strip("'")
                        if val:
                            key = val
                            break
            except Exception:
                pass
    return key

def translate_deepseek(text: str, sl: str = "vi", tl: str = "en") -> str:
    clean = (text or "").strip()
    if not clean or len(clean) <= 1:
        return ""
    api_key = get_deepseek_api_key()
    if not api_key:
        return ""
    base_url = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1").rstrip("/")
    url = f"{base_url}/chat/completions"
    model = os.getenv("DEEPSEEK_MODEL", "deepseek-flash").strip() or "deepseek-flash"

    if sl == "vi" and tl == "en":
        system_prompt = (
            "You are a real-time speech translator. "
            "Translate spoken Vietnamese into natural, everyday conversational American English. "
            "Use common colloquial American expressions, natural rhythm, and everyday phrasing (e.g. natural contractions like 'can't', 'it's', 'I'm'). "
            "Avoid stiff, formal, or textbook phrasing. "
            "Output ONLY the translated spoken English. Do not add quotes, brackets, or notes."
        )
    elif sl in ("en", "auto") and tl == "vi":
        system_prompt = (
            "You are a real-time speech translator. "
            "Translate spoken English into natural, everyday conversational Vietnamese (văn nói tự nhiên, đời thường, thông dụng, dễ hiểu). "
            "Output ONLY the translated spoken Vietnamese. Do not add quotes, brackets, or notes."
        )
    else:
        sl_name = "Vietnamese" if sl == "vi" else ("English" if sl == "en" else sl)
        tl_name = "American English" if tl == "en" else ("Vietnamese" if tl == "vi" else tl)
        system_prompt = f"Translate spoken text directly from {sl_name} to natural conversational {tl_name}. Output ONLY direct translation."

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": clean}
        ],
        "thinking": {"type": "disabled"},
        "temperature": 0.2,
        "max_tokens": 300
    }
    try:
        req_data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(url, data=req_data, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=5.0) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            content = data["choices"][0]["message"]["content"].strip()
            if content.startswith('"') and content.endswith('"') and len(content) > 2:
                content = content[1:-1].strip()
            print(f"[Translation DeepSeek] ({sl}->{tl}): '{clean}' -> '{content}'")
            return content
    except Exception as e:
        print(f"[Translation DeepSeek Error] ({sl}->{tl}): {e}")
        return ""

def translate_text(text: str, sl: str, tl: str) -> str:
    clean = (text or "").strip()
    if not clean or len(clean) <= 1:
        return ""
        
    # Tier 1: Google Translate GTX (Ưu tiên hàng đầu cho tốc độ)
    try:
        encoded_text = urllib.parse.quote(clean)
        url = f"https://translate.googleapis.com/translate_a/single?client=gtx&sl={sl}&tl={tl}&dt=t&q={encoded_text}"
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
        )
        with urllib.request.urlopen(req, timeout=3.0) as response:
            body = response.read().decode("utf-8")
            data = json.loads(body)
            parts = data[0]
            translated_text = "".join([part[0] for part in parts if part[0]])
            if translated_text and translated_text.strip():
                print(f"[Translation Google] ({sl}->{tl}): '{clean}' -> '{translated_text.strip()}'")
                return translated_text.strip()
    except Exception as e:
        print(f"[Translation Google Error] ({sl}->{tl}): {e}")
        
    # Tier 2: DeepSeek-Flash Fallback (Văn nói đàm thoại tự nhiên, chuẩn tiếng Anh Mỹ / tiếng Việt)
    ds_result = translate_deepseek(clean, sl=sl, tl=tl)
    if ds_result:
        return ds_result

    return ""

def translate_vi_to_en(text: str) -> str:
    return translate_text(text, sl="vi", tl="en") 

import librosa
import numpy as np

active_windows_connections = []
active_audio_forward_connections = []

piper_voice = None

def get_piper_voice():
    global piper_voice
    if piper_voice is None:
        try:
            from piper.voice import PiperVoice
            model_name = os.getenv("PIPER_VOICE_MODEL", "en_US-joe-medium.onnx").strip()
            model_path = os.path.join(os.path.dirname(__file__), "piper_models", model_name)
            if os.path.exists(model_path):
                print(f"[Piper] Loading model from {model_path}...")
                piper_voice = PiperVoice.load(model_path)
                print("[Piper] Model loaded successfully.")
            else:
                print(f"[Piper] Warning: Model file not found at {model_path}")
        except Exception as e:
            print(f"[Piper] Error loading voice model: {e}")
    return piper_voice


async def broadcast_to_windows(text: str):
    if not text or not text.strip():
        return
    print(f"[Broadcast] Sending to {len(active_windows_connections)} Windows clients: {text}")
    
    # Generate audio using Piper
    audio_base64 = None
    voice = get_piper_voice()
    if voice:
        try:
            import base64
            import time
            from piper.config import SynthesisConfig
            
            # Read speed from env (1.2 means 1.2x faster, so length_scale = 1.0/1.2)
            speed = float(os.getenv("PIPER_SPEED", "1.2"))
            length_scale = 1.0 / speed if speed > 0 else 1.0
            syn_config = SynthesisConfig(length_scale=length_scale)
            
            tts_start = time.time()
            def _synthesize():
                fd, path = tempfile.mkstemp(suffix=".wav")
                os.close(fd)
                try:
                    with wave.open(path, "wb") as wav_file:
                        voice.synthesize_wav(text, wav_file, syn_config=syn_config)
                    with open(path, "rb") as f:
                        data = f.read()
                    return base64.b64encode(data).decode("utf-8")
                finally:
                    if os.path.exists(path):
                        try:
                            os.remove(path)
                        except Exception:
                            pass
            
            loop = asyncio.get_event_loop()
            audio_base64 = await loop.run_in_executor(None, _synthesize)
            print(f"[Latency] Piper TTS took {time.time() - tts_start:.3f} seconds to generate audio.")
            print(f"[Piper] Synthesis complete for text: '{text[:30]}...'")
        except Exception as tts_err:
            print(f"[Piper] Synthesis failed: {tts_err}")

    disconnected = []
    for connection in active_windows_connections:
        try:
            if audio_base64:
                await connection.send_json({
                    "event": "play_audio", 
                    "text": text.strip(), 
                    "audio_base64": audio_base64
                })
            else:
                await connection.send_json({
                    "event": "speak", 
                    "text": text.strip()
                })
        except Exception as e:
            print(f"[Broadcast] Failed to send to client: {e}")
            disconnected.append(connection)
    for conn in disconnected:
        if conn in active_windows_connections:
            active_windows_connections.remove(conn)

# Speaker Diarization Cache & Utils
speaker_sessions_cache = {}

def get_speaker_embedding(audio_path: str) -> np.ndarray:
    y, sr = librosa.load(audio_path, sr=16000)
    # Extract 20 MFCC coefficients
    mfcc = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=20)
    mfcc_mean = np.mean(mfcc, axis=1)
    mfcc_std = np.std(mfcc, axis=1)
    # Form a 40-dimensional feature vector
    embedding = np.concatenate((mfcc_mean, mfcc_std))
    norm = np.linalg.norm(embedding)
    if norm > 0:
        embedding = embedding / norm
    return embedding

def identify_speaker(session_id: str, audio_path: str) -> str:
    if not session_id:
        return "Speaker Unknown"
        
    try:
        new_emb = get_speaker_embedding(audio_path)
    except Exception as e:
        print(f"[Speaker ID] Error extracting embedding: {e}")
        return "Speaker Unknown"
        
    if session_id not in speaker_sessions_cache:
        speaker_sessions_cache[session_id] = []
        
    sessions = speaker_sessions_cache[session_id]
    
    best_sim = -1.0
    best_label = None
    best_index = -1
    
    for idx, spk in enumerate(sessions):
        # Cosine similarity
        sim = np.dot(new_emb, spk["embedding"]) / (np.linalg.norm(new_emb) * np.linalg.norm(spk["embedding"]) + 1e-9)
        if sim > best_sim:
            best_sim = sim
            best_label = spk["label"]
            best_index = idx
            
    threshold = 0.75 # Threshold for speaker verification
    
    if best_sim >= threshold:
        # Match found, update embedding with moving average
        old_emb = sessions[best_index]["embedding"]
        sessions[best_index]["embedding"] = old_emb * 0.8 + new_emb * 0.2
        norm = np.linalg.norm(sessions[best_index]["embedding"])
        if norm > 0:
            sessions[best_index]["embedding"] /= norm
        print(f"[Speaker ID] Match: {best_label} (Sim: {best_sim:.4f})")
        return best_label
    else:
        # New speaker detected
        new_label = f"Speaker {len(sessions) + 1}"
        sessions.append({
            "label": new_label,
            "embedding": new_emb
        })
        print(f"[Speaker ID] New speaker: {new_label} (Max Sim: {best_sim:.4f})")
        return new_label

app = FastAPI()


app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.get("/test_broadcast")
async def test_broadcast(text: str = "This is a quick test of the broadcast audio system"):
    await broadcast_to_windows(text)
    return {"status": "broadcasted", "text": text}


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


@app.websocket("/v1/windows/audio_stream")
@app.websocket("/windows/audio_stream")
async def windows_audio_stream_endpoint(websocket: WebSocket):
    await websocket.accept()
    active_audio_forward_connections.append(websocket)
    print(f"[WS Audio Forward] Client connected: {websocket.client}")
    try:
        while True:
            data = await websocket.receive_text()
            if data == "ping":
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        print(f"[WS Audio Forward] Client disconnected: {websocket.client}")
    except Exception as e:
        print(f"[WS Audio Forward] Error: {e}")
    finally:
        if websocket in active_audio_forward_connections:
            active_audio_forward_connections.remove(websocket)




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
        # On GTX 1050 Ti (compute capability 6.1), float16 is not supported; int8_float32 is optimal
        compute_type = "int8_float32" if device == "cuda" else "int8"
        print(f"Loading Faster-Whisper model '{model_size_or_path}' on {device} (compute_type={compute_type})...")
        try:
            self.model = WhisperModel(model_size_or_path, device=device, compute_type=compute_type)
        except Exception as e:
            print(f"Failed with compute_type={compute_type} ({e}), falling back to float32...")
            self.model = WhisperModel(model_size_or_path, device=device, compute_type="float32")

    def generate(self, input: str, language: str = None, **kwargs):
        lang_param = None if (not language or language.lower() == "auto") else language.lower()
        try:
            segments, info = self.model.transcribe(
                input,
                beam_size=1,
                language=lang_param,
                temperature=0.0,
                vad_filter=True,
            )
            text = "".join([segment.text for segment in segments])
            return [{"text": text}]
        except Exception as e:
            print(f"[FasterWhisper] Generate error: {e}")
            return [{"text": ""}]


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
                local_path = resolve_cache_path(f".cache/modelscope/hub/models/{key}")
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
    session_id: str = Form(None),
    segment_index: int = Form(None),
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
        result = await asyncio.to_thread(asr_model.generate, input=tmp_path, language=run_lang)
        text = extract_text(result)
        print(f"[{task}] {text}")
        
        # Identify speaker if session_id is provided
        speaker = None
        if text.strip() and session_id:
            speaker = await asyncio.to_thread(identify_speaker, session_id, tmp_path)
            print(f"[ASR Segment] session={session_id}, index={segment_index}, speaker={speaker}, text={text}")
            
        # Auto translate and broadcast to Windows clients
        if text.strip():
            en_text = await asyncio.to_thread(translate_vi_to_en, text)
            if en_text:
                asyncio.create_task(broadcast_to_windows(en_text))
                
        return {"text": text, "speaker": speaker}
    except Exception as e:
        print(f"Transcription error: {e}")
        return JSONResponse(status_code=500, content={"error": str(e)})
    finally:
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except Exception:
                pass


clone_tts_model = None

def get_clone_tts_model():
    global clone_tts_model
    if clone_tts_model is None:
        import os
        import torch
        from transformers import AutoModelForCausalLM
        
        # Save old environment variable
        old_use_hub = os.environ.get("USE_MODELSCOPE_HUB")
        os.environ["USE_MODELSCOPE_HUB"] = "0"
        
        try:
            # Append MOSS-TTS-Nano to sys.path so dynamic imports inside HF work if they refer to local modules
            moss_path = os.path.join(os.path.dirname(__file__), "MOSS-TTS-Nano")
            if moss_path not in sys.path:
                sys.path.append(moss_path)
                
            checkpoint = resolve_cache_path(".cache/huggingface/hub/models--OpenMOSS-Team--MOSS-TTS-Nano/snapshots/44502f80dbf9743528fa921cc544d662c685ebec")
            
            # Register the tokenizer class with AutoTokenizer so it can be resolved during model.inference()
            try:
                if checkpoint not in sys.path:
                    sys.path.append(checkpoint)
                from tokenization_moss_tts_nano import MossTTSNanoSentencePieceTokenizer
                from transformers import AutoTokenizer
                # Register globally under AutoTokenizer
                MossTTSNanoSentencePieceTokenizer.register_for_auto_class("AutoTokenizer")
                
                # Also register for model type
                AutoTokenizer.register("moss_tts_nano", slow_tokenizer_class=MossTTSNanoSentencePieceTokenizer)
                print("[Tokenizer Reg] Successfully registered MossTTSNanoSentencePieceTokenizer with AutoTokenizer.")
            except Exception as reg_err:
                print(f"[Tokenizer Reg] Failed to register: {reg_err}")
                
            print("Loading MOSS-TTS-Nano model on CPU...")
            device = torch.device("cpu")
            dtype = torch.float32
            
            # Load using AutoModelForCausalLM
            model = AutoModelForCausalLM.from_pretrained(
                checkpoint,
                trust_remote_code=True,
            )
            model.to(device=device, dtype=dtype)
            model._set_attention_implementation("sdpa")
            model.eval()
            clone_tts_model = model
        finally:
            # Restore environment variable
            if old_use_hub is not None:
                os.environ["USE_MODELSCOPE_HUB"] = old_use_hub
            else:
                os.environ.pop("USE_MODELSCOPE_HUB", None)
                
    return clone_tts_model

import base64

@app.post("/v1/audio/synthesize_clone")
@app.post("/audio/synthesize_clone")
async def synthesize_clone(
    text: str = Form(...),
):
    if not text or not text.strip():
        return JSONResponse(status_code=400, content={"error": "Text must not be empty"})
        
    try:
        model = get_clone_tts_model()
        
        # Path to reference voice
        ref_path = os.path.join(os.path.dirname(__file__), "reference_voice.wav")
        if not os.path.exists(ref_path):
            ref_path = os.path.join(os.path.dirname(__file__), "MOSS-TTS-Nano", "assets", "audio", "en_2.wav")
            print(f"[Synthesize Clone] user reference voice not found. Falling back to default: {ref_path}")
        else:
            print(f"[Synthesize Clone] using user reference voice: {ref_path}")
            
        suffix = ".wav"
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
            tmp_path = tmp.name
            
        try:
            import torch
            device = torch.device("cpu")
            
            # Normalize text
            moss_path = os.path.join(os.path.dirname(__file__), "MOSS-TTS-Nano")
            if moss_path not in sys.path:
                sys.path.append(moss_path)
            from tts_robust_normalizer_single_script import normalize_tts_text
            
            normalized_text = normalize_tts_text(text)
            print(f"[Synthesize Clone] Text: '{text}' -> Normalized: '{normalized_text}'")
            
            # Run inference in threadpool
            def _run_inference():
                return model.inference(
                    text=normalized_text,
                    output_audio_path=tmp_path,
                    mode="voice_clone",
                    prompt_text=None,
                    prompt_audio_path=ref_path,
                    audio_tokenizer_type="moss-audio-tokenizer-nano",
                    audio_tokenizer_pretrained_name_or_path=resolve_cache_path(".cache/huggingface/hub/models--OpenMOSS-Team--MOSS-Audio-Tokenizer-Nano/snapshots/6aa02b01e445cc585582cf0ba480bc3ea6c8dd68"),
                    device=device,
                    use_kv_cache=True,
                    do_sample=True,
                    text_temperature=1.0,
                    text_top_p=1.0,
                    text_top_k=50,
                    audio_temperature=0.8,
                    audio_top_p=0.95,
                    audio_top_k=25,
                    audio_repetition_penalty=1.2,
                )
                
            result = await asyncio.to_thread(_run_inference)
            
            # Convert 32-bit float stereo WAV to 16-bit mono WAV to support Android playbacks and reduce size
            try:
                import soundfile as sf
                data, samplerate = sf.read(tmp_path)
                if len(data.shape) > 1:
                    data = data.mean(axis=1)
                sf.write(tmp_path, data, samplerate, subtype='PCM_16')
                print(f"[Synthesize Clone] Converted to 16-bit mono WAV. New size: {os.path.getsize(tmp_path)} bytes")
            except Exception as conv_err:
                print(f"[Synthesize Clone] Warning: Failed to convert output to 16-bit PCM: {conv_err}")
            
            # Read output and encode to base64
            with open(tmp_path, "rb") as f:
                audio_bytes = f.read()
                
            audio_base64 = base64.b64encode(audio_bytes).decode("utf-8")
            
            return {
                "success": True,
                "audio_base64": audio_base64,
                "format": "wav",
                "sample_rate": result.get("sample_rate", 48000)
            }
            
        finally:
            if os.path.exists(tmp_path):
                try:
                    os.remove(tmp_path)
                except Exception:
                    pass
                    
    except Exception as e:
        import traceback
        traceback.print_exc()
        print(f"Clone synthesis error: {e}")
        return JSONResponse(status_code=500, content={"error": str(e)})


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
            if websocket.client_state.name != "CONNECTED":
                break

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
                    result = await asyncio.to_thread(model_instance.generate, input=tmp_path, language=run_lang)
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

                # Filter silence hallucinations (e.g. 'y', '.', 'you', 'uh' produced on pure silence)
                if is_audio_silent and (len(norm_clean) <= 2 or norm_clean in ("y", "the", "you", "uh", "um", "ah")):
                    norm_text = ""
                    norm_clean = ""

                if norm_clean:
                    if norm_clean == last_clean or is_audio_silent:
                        unchanged_count += 1
                    else:
                        unchanged_count = 0
                        last_text = norm_text
                        print(f"ASR partial: {norm_text} (RMS: {rms:.5f})")
                        try:
                            await websocket.send_json({
                                "event": "result",
                                "text": norm_text,
                                "is_final": False,
                            })
                        except Exception:
                            break
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
                    clean_final = (finalized_text or "").strip()
                    is_valid_speech = len(clean_final) > 1 and not (is_audio_silent and len(clean_final.split()) <= 1 and clean_final.lower() in ("y", "the", "you", "uh", "um", "ah"))
                    
                    if clean_final and is_valid_speech:
                        print(f"ASR final ({finalize_reason}): {clean_final}")
                        # Auto translate based on input language
                        sl = language if language else "vi"
                        tl = "vi" if sl == "en" else "en"
                        translated_text = await asyncio.to_thread(translate_text, clean_final, sl, tl)
                        if translated_text and client != "windows":
                            asyncio.create_task(broadcast_to_windows(translated_text))
                            
                        try:
                            await websocket.send_json({
                                "event": "result",
                                "text": translated_text if translated_text else clean_final,
                                "is_final": True,
                                "vi_text": clean_final,
                            })
                        except Exception:
                            break
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
                        # Entire buffer was silent or hallucinated noise, discard it completely
                        async with lock:
                            audio_buffer = []
                            
                    last_text = ""
                    unchanged_count = 0

            except Exception as e:
                print(f"ASR WS transcribe error: {e}")

    transcribe_task = None
    if model_name.lower() != "none":
        transcribe_task = asyncio.create_task(transcribe_loop())

    try:
        while True:
            data = await websocket.receive_bytes()
            if not data:
                continue

            # Forward raw bytes to Windows audio clients
            if active_audio_forward_connections:
                print(f"[WS Audio Forward] Forwarding {len(data)} bytes to {len(active_audio_forward_connections)} clients")
                disconnected = []
                for conn in active_audio_forward_connections:
                    try:
                        await conn.send_bytes(data)
                    except Exception as e:
                        print(f"[WS Audio Forward] Failed to send: {e}")
                        disconnected.append(conn)
                for conn in disconnected:
                    if conn in active_audio_forward_connections:
                        active_audio_forward_connections.remove(conn)

            shorts = np.frombuffer(data, dtype=np.int16)
            floats = shorts.astype(np.float32) / 32768.0
            async with lock:
                audio_buffer.extend(floats.tolist())
    except WebSocketDisconnect:
        print("ASR Client disconnected")
    except Exception as e:
        print(f"ASR WS error: {e}")
    finally:
        if transcribe_task:
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
        print(f"Warming up default model {args.model}...")
        get_model(args.model)
        try:
            print("Warming up Whisper model (medium -> small)...")
            whisper_m = get_model("medium")
            dummy = np.zeros(16000, dtype=np.float32)
            tmp_warm = write_wav_file(dummy)
            try:
                whisper_m.generate(tmp_warm, language="vi")
            finally:
                if os.path.exists(tmp_warm):
                    os.remove(tmp_warm)
            print("Whisper warmed up successfully.")
        except Exception as wex:
            print(f"Whisper warmup failed: {wex}")
        try:
            print("Warming up MOSS-TTS-Nano model...")
            get_clone_tts_model()
        except Exception as ex:
            print(f"MOSS-TTS-Nano Warm up failed: {ex}")
    except Exception as e:
        print(f"Warm up failed: {e}")

    print(f"Starting ASR Server on http://{args.host}:{args.port}")
    uvicorn.run(app, host=args.host, port=args.port)
