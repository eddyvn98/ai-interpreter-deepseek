import os
import sys

# Windows DLL loading support
if sys.platform == "win32":
    import site
    for sp in site.getsitepackages():
        cublas_path = os.path.join(sp, "nvidia", "cublas", "bin")
        cudnn_path = os.path.join(sp, "nvidia", "cudnn", "bin")
        if os.path.exists(cublas_path):
            print(f"Adding DLL directory to PATH: {cublas_path}")
            os.environ["PATH"] = cublas_path + os.pathsep + os.environ["PATH"]
            if hasattr(os, "add_dll_directory"):
                os.add_dll_directory(cublas_path)
        if os.path.exists(cudnn_path):
            print(f"Adding DLL directory to PATH: {cudnn_path}")
            os.environ["PATH"] = cudnn_path + os.pathsep + os.environ["PATH"]
            if hasattr(os, "add_dll_directory"):
                os.add_dll_directory(cudnn_path)

from faster_whisper import WhisperModel

print("Attempting to load model on CUDA with int8...")
try:
    model = WhisperModel("base.en", device="cuda", compute_type="int8")
    print("CUDA int8 loaded successfully!")
except Exception as e:
    print(f"CUDA int8 load failed: {e}")

print("\nAttempting to load model on CPU with int8...")
try:
    model_cpu = WhisperModel("base.en", device="cpu", compute_type="int8")
    print("CPU int8 loaded successfully!")
except Exception as e:
    print(f"CPU int8 load failed: {e}")
