"""Visuels du Play Store : icône 512 et image de présentation 1024x500 (anglais et français).

Formes identiques à android/app/src/main/res/drawable/ic_launcher_foreground.xml.
Usage : .venv/bin/python export/store_graphics.py   (sortie : docs/store/)
Nécessite macOS (polices Hiragino et Avenir Next).
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "store"
NAVY, WHITE, RED, LIGHT = (30, 42, 74), (255, 255, 255), (229, 57, 53), (200, 208, 230)
SS = 4  # suréchantillonnage pour l'anticrénelage
TAGLINES = {"en": ("Read Japanese,", "bubble by bubble."), "fr": ("Lisez le japonais,", "bulle par bulle.")}


def draw_icon(d, ox, oy, s):
    """Bulle, texte vertical, furigana et sceau (viewport 108), en (ox, oy) avec l'échelle s."""
    P = lambda x, y: (ox + x * s, oy + y * s)
    d.rounded_rectangle([P(30, 31), P(80, 73)], radius=12 * s, fill=WHITE)
    d.polygon([P(42, 72), P(52, 72), P(38, 83)], fill=WHITE)
    for x0, y1 in [(62, 66.5), (52.5, 60.5), (43, 52.5)]:
        d.rounded_rectangle([P(x0, 36.5), P(x0 + 5, y1)], radius=2.5 * s, fill=NAVY)
    for cy in (41, 46):
        d.ellipse([P(71 - 1.3, cy - 1.3), P(71 + 1.3, cy + 1.3)], fill=NAVY)
    d.ellipse([P(66, 60), P(78, 72)], fill=RED)


def icon():
    n = 512 * SS
    img = Image.new("RGB", (n, n), NAVY)
    s = n / 72  # zone 18..90 du viewport, plein cadre (Google applique le masque)
    draw_icon(ImageDraw.Draw(img), -18 * s, -20 * s, s)
    img.resize((512, 512), Image.LANCZOS).save(OUT / "icon-512.png")


def feature_graphic(lang):
    w, h = 1024 * SS, 500 * SS
    img = Image.new("RGB", (w, h), NAVY)
    d = ImageDraw.Draw(img)
    s = h / 90
    draw_icon(d, 20 * SS - 18 * s, -2 * s, s)
    jp = ImageFont.truetype("/System/Library/Fonts/ヒラギノ角ゴシック W6.ttc", 96 * SS)
    latin = ImageFont.truetype("/System/Library/Fonts/Avenir Next.ttc", 76 * SS, index=0)
    sub = ImageFont.truetype("/System/Library/Fonts/Avenir Next.ttc", 34 * SS, index=0)
    x = 470 * SS
    d.text((x, 95 * SS), "吹き出し", font=jp, fill=WHITE)
    d.text((x, 225 * SS), "Fukidashi", font=latin, fill=WHITE)
    line1, line2 = TAGLINES[lang]
    d.text((x, 335 * SS), line1, font=sub, fill=LIGHT)
    d.text((x, 380 * SS), line2, font=sub, fill=LIGHT)
    img.resize((1024, 500), Image.LANCZOS).save(OUT / lang / "feature-graphic.png")


if __name__ == "__main__":
    icon()
    for lang in TAGLINES:
        (OUT / lang).mkdir(parents=True, exist_ok=True)
        feature_graphic(lang)
    print(f"-> {OUT}")
