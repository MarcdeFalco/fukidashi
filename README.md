# 吹き出し Fukidashi

Application Android pour lire le japonais — mangas, livres — bulle par bulle, **hors ligne**.

Ouvrez une page (image, archive CBZ, capture partagée) ou photographiez un livre papier :
les bulles sont détectées, et toucher une bulle ouvre un écran d'étude avec les furigana,
les mots cliquables (lecture, forme du dictionnaire, conjugaison décomposée, définitions,
kanji) et les points de grammaire expliqués en français.

Tout tourne sur le téléphone : sur le NPU des puces Snapdragon (ONNX Runtime + Qualcomm QNN),
sinon sur le processeur.

## Organisation

| Dossier | Contenu |
|---|---|
| `export/` | Scripts Python : conversion des modèles en ONNX, versions de référence des traitements, construction du dictionnaire |
| `android/core/` | Traitements en Kotlin pur (détection, OCR, analyse du japonais, grammaire), testés sur JVM |
| `android/app/` | Application (Jetpack Compose, CameraX) |
| `android/models/` | Pack de ressources Play (modèles + dictionnaire) |
| `docs/` | Politique de confidentialité, fiche Play Store |

## Construire

Les modèles et le dictionnaire ne sont pas versionnés ; il faut les générer :

```bash
python3.12 -m venv .venv && .venv/bin/pip install -r export/requirements.txt
# Détecteur de bulles YOLOv8 (comic-speech-bubble-detector.pt) à placer à la racine
.venv/bin/python export/export_models.py
# JMdict / KANJIDIC2 de jmdict-simplified dans export/data/ (voir build_dictionary.py)
.venv/bin/python export/build_dictionary.py
```

Puis, avec le SDK Android :

```bash
cd android
./gradlew :core:test          # tests du pipeline
./gradlew :app:assembleDebug  # APK de développement (modèles inclus)
./gradlew :app:bundleRelease  # AAB Play Store (modèles dans le pack :models)
```

`android/bench.sh` mesure les performances sur un téléphone branché.

## Licences

Code sous **GNU AGPL-3.0** (voir `LICENSE`), notamment parce que le détecteur de bulles est
un modèle YOLOv8 (Ultralytics, AGPL-3.0).

Composants et données tiers :
- [manga-ocr](https://github.com/kha-white/manga-ocr) (Maciej Budyś) — Apache 2.0
- [JMdict / KANJIDIC2](https://www.edrdg.org/edrdg/licence.html) (EDRDG), via
  [jmdict-simplified](https://github.com/scriptin/jmdict-simplified) — CC BY-SA 4.0
- [Kuromoji](https://github.com/atilika/kuromoji) + IPADIC — Apache 2.0 / licence IPADIC
- [ONNX Runtime](https://onnxruntime.ai) — MIT ; Qualcomm AI Engine Direct (QNN) — licence Qualcomm
- Jetpack Compose, CameraX — Apache 2.0
