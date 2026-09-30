import cv2
from ultralytics import YOLO
from manga_ocr import MangaOcr

# Chargement des modèles
model = YOLO("comic-speech-bubble-detector.pt")
mocr = MangaOcr()

# Chargement image originale
img_path = "/Users/marc/Dropbox/test_ocr.jpg"
img = cv2.imread(img_path)

# Détection avec seuil plus bas
results = model(img_path, conf=0.1)

# Itérer sur les détections
for result in results:
    boxes = result.boxes.xyxy.cpu().numpy()  # [x1, y1, x2, y2]
    for (x1, y1, x2, y2) in boxes:
        x1, y1, x2, y2 = map(int, [x1, y1, x2, y2])
        crop = img[y1:y2, x1:x2]
        text = mocr(crop)
        print(f"Texte détecté : {text}")
        
        # Dessiner rectangle + texte sur l'image
        cv2.rectangle(img, (x1, y1), (x2, y2), (0, 0, 255), 2)
        cv2.putText(img, text, (x1, y1 - 10), cv2.FONT_HERSHEY_SIMPLEX, 
                    0.5, (0, 0, 255), 1, cv2.LINE_AA)

# Sauvegarde de l'image de debug
cv2.imwrite("/Users/marc/Dropbox/test_ocr_debug.jpg", img)