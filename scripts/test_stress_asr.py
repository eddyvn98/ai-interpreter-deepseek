import asyncio
import os
import sys
import time
import numpy as np
import websockets
import json
import wave

# Force UTF-8 stdout/stderr on Windows to prevent UnicodeEncodeError
if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace', write_through=True)
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace', write_through=True)

# Ensure directories exist
SCRATCH_DIR = r"C:\Users\hatha\.gemini\antigravity\scratch"
os.makedirs(SCRATCH_DIR, exist_ok=True)

AUDIO_PATH = os.path.join(SCRATCH_DIR, "lenh_dang_dien_ra.wav")

def add_noise_to_pcm(pcm_data, noise_level=0.1):
    """Adds white noise to 16-bit integer PCM data."""
    shorts = np.frombuffer(pcm_data, dtype=np.int16).astype(np.float32)
    # Generate white noise
    noise = np.random.normal(0, noise_level * 32768, len(shorts))
    noisy_shorts = np.clip(shorts + noise, -32768, 32767).astype(np.int16)
    return noisy_shorts.tobytes()

async def run_websocket_test(uri, pcm_data, test_name="Normal Speech", chunk_size=1024, delay=0.064):
    print(f"\n[START] Starting test case: {test_name}")
    start_time = time.time()
    
    try:
        async with websockets.connect(uri) as websocket:
            print(f"[CONNECTED] Connected to ASR server: {uri}")
            
            results = []
            async def receive_messages():
                try:
                    async for message in websocket:
                        data = json.loads(message)
                        event = data.get("event")
                        text = data.get("text")
                        is_final = data.get("is_final", False)
                        vi_text = data.get("vi_text", "")
                        
                        latency = time.time() - start_time
                        if is_final:
                            print(f"[{latency:.2f}s] [FINAL] \"{text}\" | VI: \"{vi_text}\"")
                        else:
                            print(f"[{latency:.2f}s] [PARTIAL] \"{text}\"")
                        results.append(data)
                except asyncio.CancelledError:
                    pass
                except Exception as e:
                    print(f"[WARN] Receiver error: {e}")

            receiver_task = asyncio.create_task(receive_messages())
            
            # Send chunks to simulate real-time microphone stream
            bytes_sent = 0
            for i in range(0, len(pcm_data), chunk_size * 2):  # 2 bytes per sample (16-bit)
                chunk = pcm_data[i:i + chunk_size * 2]
                if not chunk:
                    break
                await websocket.send(chunk)
                bytes_sent += len(chunk)
                await asyncio.sleep(delay)
            
            # Wait for final transcribing response
            await asyncio.sleep(8.0)
            
            receiver_task.cancel()
            await receiver_task
            
            print(f"[FINISHED] Test case finished. Sent {bytes_sent / 1024:.1f} KB audio.")
            return results
    except Exception as e:
        print(f"[FAIL] WebSocket connection failed: {e}")
        return []

def analyze_audio_file():
    with wave.open(AUDIO_PATH, "rb") as wav:
        params = wav.getparams()
        nchannels, sampwidth, framerate, nframes = params[:4]
        print(f"[METRICS] Audio info: Channels={nchannels}, Rate={framerate}Hz, Width={sampwidth} bytes, TotalFrames={nframes}")
        
        # Ensure 16-bit width
        if sampwidth != 2:
            print(f"[FAIL] Audio sample width is {sampwidth} bytes. Only 16-bit (2 bytes) WAV supported.")
            sys.exit(1)
            
        raw_data = wav.readframes(nframes)
        shorts = np.frombuffer(raw_data, dtype=np.int16)
        
        # Convert stereo to mono
        if nchannels > 1:
            print("[INFO] Converting stereo audio to mono...")
            shorts = shorts.reshape(-1, nchannels).mean(axis=1).astype(np.int16)
            
        # Resample to 16kHz if necessary
        if framerate != 16000:
            print(f"[INFO] Resampling audio from {framerate}Hz to 16000Hz...")
            duration = len(shorts) / framerate
            new_num_samples = int(duration * 16000)
            old_indices = np.linspace(0, len(shorts) - 1, len(shorts))
            new_indices = np.linspace(0, len(shorts) - 1, new_num_samples)
            shorts = np.interp(new_indices, old_indices, shorts).astype(np.int16)
            
        # Limit to first 20 seconds for faster testing if it's too long
        max_samples = 16000 * 20
        if len(shorts) > max_samples:
            print("[INFO] Truncating audio to 20 seconds for rapid testing.")
            shorts = shorts[:max_samples]
            
        return shorts.tobytes()

async def main():
    if not os.path.exists(AUDIO_PATH):
        print(f"[FAIL] Local test file not found at {AUDIO_PATH}")
        sys.exit(1)
        
    pcm_data = analyze_audio_file()
    
    # Use auto language detection to handle English/Vietnamese dynamically
    uri = "ws://localhost:8000/v1/audio/stream?model=medium&language=auto"
    
    # 1. Test case: Tiêu chuẩn (Clean Audio)
    await run_websocket_test(uri, pcm_data, "1. Clean Local Speech Sample")
    
    # 2. Test case: Stress âm thanh hỗn loạn (Medium Noise - 10% Noise)
    noisy_pcm_medium = add_noise_to_pcm(pcm_data, noise_level=0.1)
    await run_websocket_test(uri, noisy_pcm_medium, "2. Chaotic Audio (Medium Noise - SNR ~20dB)")
    
    # 3. Test case: Stress cực mạnh (Extreme Noise - 35% Noise / Hỗn loạn cao)
    noisy_pcm_heavy = add_noise_to_pcm(pcm_data, noise_level=0.35)
    await run_websocket_test(uri, noisy_pcm_heavy, "3. Extreme Noise Stress Test (SNR ~9dB)")
    
    # 4. Test case: Đoạn nhiễu trắng thuần túy (Không có giọng nói - 10 giây)
    # Kiểm tra xem Whisper có bị ảo giác (hallucination) lặp từ vô hạn không
    pure_noise = np.random.normal(0, 0.1 * 32768, 16000 * 10).astype(np.int16).tobytes()
    await run_websocket_test(uri, pure_noise, "4. Pure Noise (No Speech - checking for ASR Hallucinations)")

if __name__ == "__main__":
    asyncio.run(main())
