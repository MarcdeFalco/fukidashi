"""Convertit les modèles (détecteur de bulles YOLO + manga-ocr) en ONNX pour Android.

Usage : .venv/bin/python export/export_models.py
Sortie : export/out/
"""
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "export" / "out"
OUT.mkdir(parents=True, exist_ok=True)


def export_detector():
    from ultralytics import YOLO

    model = YOLO(ROOT / "comic-speech-bubble-detector.pt")
    # NMS non inclus : fait en Kotlin, plus portable avec ONNX Runtime Mobile
    path = model.export(format="onnx", imgsz=1024, opset=17, simplify=True, dynamic=False)
    shutil.move(path, OUT / "bubble_detector.onnx")
    print("-> bubble_detector.onnx")


def export_ocr():
    # Encodeur + décodeur séparés (décodeur BERT : pas de cache KV possible)
    subprocess.run(
        [
            str(Path(sys.executable).parent / "optimum-cli"), "export", "onnx",
            "--model", "kha-white/manga-ocr-base",
            "--task", "image-to-text",
            "--opset", "17",
            str(OUT / "manga_ocr"),
        ],
        check=True,
    )
    print("-> manga_ocr/")


def quantize():
    """Poids en int8 (quantification dynamique) : ~4x plus petit."""
    from onnxruntime.quantization import QuantType, quantize_dynamic

    src, dst = OUT / "manga_ocr", OUT / "manga_ocr_int8"
    dst.mkdir(exist_ok=True)
    for f in src.iterdir():
        if f.suffix == ".onnx":
            quantize_dynamic(f, dst / f.name, weight_type=QuantType.QUInt8)
        else:
            shutil.copy(f, dst / f.name)
    quantize_dynamic(OUT / "bubble_detector.onnx", OUT / "bubble_detector_int8.onnx",
                     weight_type=QuantType.QUInt8)
    print("-> manga_ocr_int8/, bubble_detector_int8.onnx")


if __name__ == "__main__":
    what = sys.argv[1:] or ["detector", "ocr", "quantize"]
    if "detector" in what:
        export_detector()
    if "ocr" in what:
        export_ocr()
    if "quantize" in what:
        quantize()
