"""Vérifie le détecteur de bulles ONNX contre ultralytics, puis enchaîne l'OCR.

Le pipeline ici (letterbox, décodage de la sortie YOLO, NMS) est la référence
que l'appli Kotlin doit reproduire.

Usage : .venv/bin/python export/validate_detector.py [image] [detecteur.onnx]
"""
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageOps

from validate_ocr import beam_decode, detokenize, post_process, preprocess

ROOT = Path(__file__).resolve().parent.parent
IMG = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "test_ocr.jpg"
DET = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "export/out/bubble_detector_int8.onnx"
OCR_DIR = ROOT / "export/out/manga_ocr_int8"

SIZE = 1024
CONF = 0.3
IOU = 0.45
CLASSES = ["text_bubble", "text_free"]


def letterbox(img: Image.Image):
    """Redimensionne en gardant le ratio, complète en gris (114) jusqu'à 1024x1024."""
    w, h = img.size
    scale = SIZE / max(w, h)
    nw, nh = round(w * scale), round(h * scale)
    pad_x, pad_y = (SIZE - nw) // 2, (SIZE - nh) // 2
    canvas = Image.new("RGB", (SIZE, SIZE), (114, 114, 114))
    canvas.paste(img.convert("RGB").resize((nw, nh), Image.BILINEAR), (pad_x, pad_y))
    x = np.asarray(canvas, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0
    return x, scale, pad_x, pad_y


def iou(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    x1, y1 = np.maximum(a[0], b[:, 0]), np.maximum(a[1], b[:, 1])
    x2, y2 = np.minimum(a[2], b[:, 2]), np.minimum(a[3], b[:, 3])
    inter = np.clip(x2 - x1, 0, None) * np.clip(y2 - y1, 0, None)
    area = lambda r: (r[..., 2] - r[..., 0]) * (r[..., 3] - r[..., 1])
    return inter / (area(a) + area(b) - inter)


def detect(sess, img: Image.Image):
    x, scale, pad_x, pad_y = letterbox(img)
    out = sess.run(None, {"images": x})[0][0].T  # (21504, 4 + nb_classes)
    scores = out[:, 4:].max(axis=1)
    keep = scores >= CONF
    out, scores = out[keep], scores[keep]
    cls = out[:, 4:].argmax(axis=1)
    cx, cy, w, h = out[:, 0], out[:, 1], out[:, 2], out[:, 3]
    boxes = np.stack([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2], axis=1)
    # Retour aux coordonnées de l'image d'origine
    boxes = (boxes - [pad_x, pad_y, pad_x, pad_y]) / scale
    boxes = boxes.clip(0, [img.width, img.height, img.width, img.height])

    # NMS par classe (comme ultralytics par défaut)
    result = []
    for c in np.unique(cls):
        idx = np.where(cls == c)[0]
        idx = idx[np.argsort(-scores[idx])]
        while len(idx):
            best, idx = idx[0], idx[1:]
            result.append((boxes[best], float(scores[best]), int(c)))
            idx = idx[iou(boxes[best], boxes[idx]) < IOU]
    return sorted(result, key=lambda r: -r[1])


def main():
    img = ImageOps.exif_transpose(Image.open(IMG))  # photos : rotation EXIF
    dets = detect(ort.InferenceSession(str(DET)), img)

    from ultralytics import YOLO
    ref = YOLO(ROOT / "comic-speech-bubble-detector.pt")(str(IMG), conf=CONF, verbose=False)[0]
    ref_boxes = ref.boxes.xyxy.cpu().numpy()
    print(f"ONNX : {len(dets)} zones — ultralytics : {len(ref_boxes)} zones")

    vocab = (OCR_DIR / "vocab.txt").read_text(encoding="utf-8").splitlines()
    enc = ort.InferenceSession(str(OCR_DIR / "encoder_model.onnx"))
    dec = ort.InferenceSession(str(OCR_DIR / "decoder_model.onnx"))
    for box, score, c in dets:
        best_iou = iou(box, ref_boxes).max() if len(ref_boxes) else 0
        crop = img.crop(tuple(int(v) for v in box))
        text = post_process(detokenize(beam_decode(enc, dec, preprocess(crop)), vocab))
        print(f"[{score:.2f} {CLASSES[c]:<11} IoU/réf {best_iou:.2f}] {text}")


if __name__ == "__main__":
    main()
