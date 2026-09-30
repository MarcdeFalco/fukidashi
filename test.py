import manga_ocr
from ultralytics import YOLO

model = YOLO("comic-speech-bubble-detector.pt")

results = model("/Users/marc/Dropbox/test_ocr.jpg")

print(results)
