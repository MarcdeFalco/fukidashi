import os
import cv2
from PIL import Image
from ultralytics import YOLO
from manga_ocr import MangaOcr

# ==== CONFIG ====
img_path = "test_ocr.jpg"
output_dir = "debug_bulles"
os.makedirs(output_dir, exist_ok=True)

# ==== CHARGEMENT DES MODÈLES ====
model = YOLO("comic-speech-bubble-detector.pt")
mocr = MangaOcr()

# ==== LECTURE IMAGE ====
img = cv2.imread(img_path)

# ==== DÉTECTION ====
results = model(img_path, conf=0.1)

# ==== TRAITEMENT DES BULLES ====
for idx, result in enumerate(results):
    boxes = result.boxes.xyxy.cpu().numpy()
    scores = result.boxes.conf.cpu().numpy()

    for i, (box, score) in enumerate(zip(boxes, scores)):
        x1, y1, x2, y2 = map(int, box)
        crop = img[y1:y2, x1:x2]

        # OpenCV -> PIL (RGB)
        crop_pil = Image.fromarray(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB))

        # OCR
        text = mocr(crop_pil)
        print(f"[{score:.2f}] Texte détecté : {text}")

        # Sauvegarde de la bulle seule
        safe_text = text.replace("\n", " ").replace("/", "_").replace("\\", "_")
        if len(safe_text) > 30:
            safe_text = safe_text[:30] + "..."
        filename = f"bubble_{idx}_{i}_{score:.2f}_{safe_text}.png"
        cv2.imwrite(os.path.join(output_dir, filename), crop)

        # Dessin rectangle + texte sur l'image originale
        cv2.rectangle(img, (x1, y1), (x2, y2), (0, 0, 255), 2)
        display_text = f"{score:.2f} | {text}"
        if len(display_text) > 40:
            display_text = display_text[:40] + "..."
        cv2.putText(img, display_text, (x1, max(y1 - 10, 15)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1, cv2.LINE_AA)

# ==== SAUVEGARDE IMAGE DEBUG ====
debug_img_path = "test_ocr_debug.jpg"
cv2.imwrite(debug_img_path, img)

print(f"\n✅ Sauvegarde terminée :\n- Image globale : {debug_img_path}\n- Bulles : {output_dir}")
