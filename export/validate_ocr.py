"""Vérifie que manga-ocr en ONNX lit comme l'original, sans PyTorch.

Le pipeline ici (prétraitement, décodage glouton, détokenisation, post-traitement)
est la référence que l'appli Kotlin doit reproduire à l'identique.

Usage : .venv/bin/python export/validate_ocr.py [dossier_modeles]
"""
import json
import re
import sys
import time
from pathlib import Path

import jaconv
import numpy as np
import onnxruntime as ort
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
MODEL_DIR = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "export" / "out" / "manga_ocr"
TEST_DIR = Path.home() / "dev" / "manga-ocr" / "tests" / "data"

START, EOS = 2, 3
MAX_LEN = 300
NO_REPEAT_NGRAM = 3


def preprocess(img: Image.Image) -> np.ndarray:
    img = img.convert("L").convert("RGB").resize((224, 224), Image.BILINEAR)
    x = np.asarray(img, dtype=np.float32) / 255.0
    x = (x - 0.5) / 0.5
    return x.transpose(2, 0, 1)[None]  # NCHW


def banned_tokens(ids: list[int]) -> set[int]:
    """Tokens qui recréeraient un n-gramme déjà vu (no_repeat_ngram_size=3)."""
    n = NO_REPEAT_NGRAM
    if len(ids) < n:
        return set()
    prefix = tuple(ids[-(n - 1):])
    return {ids[i + n - 1] for i in range(len(ids) - n + 1) if tuple(ids[i:i + n - 1]) == prefix}


def greedy_decode(enc, dec, pixel_values) -> list[int]:
    hidden = enc.run(None, {"pixel_values": pixel_values})[0]
    ids = [START]
    for _ in range(MAX_LEN - 1):
        logits = dec.run(None, {"input_ids": np.array([ids], dtype=np.int64),
                                "encoder_hidden_states": hidden})[0][0, -1]
        for t in banned_tokens(ids):
            logits[t] = -np.inf
        nxt = int(logits.argmax())
        if nxt == EOS:
            break
        ids.append(nxt)
    return ids[1:]


def log_softmax(x: np.ndarray) -> np.ndarray:
    x = x - x.max(axis=-1, keepdims=True)
    return x - np.log(np.exp(x).sum(axis=-1, keepdims=True))


def beam_decode(enc, dec, pixel_values, num_beams=4, length_penalty=2.0) -> list[int]:
    """Recherche en faisceau comme HF generate (early_stopping=True).

    Les faisceaux actifs passent dans le décodeur en un seul batch.
    """
    hidden = enc.run(None, {"pixel_values": pixel_values})[0]
    beams = [([START], 0.0)]  # (tokens, somme des log-probas)
    finished = []  # (score normalisé, tokens)
    for _ in range(MAX_LEN - 1):
        ids = np.array([b[0] for b in beams], dtype=np.int64)
        h = np.repeat(hidden, len(beams), axis=0)
        logp = log_softmax(dec.run(None, {"input_ids": ids, "encoder_hidden_states": h})[0][:, -1])
        for i, (toks, _) in enumerate(beams):
            for t in banned_tokens(toks):
                logp[i, t] = -np.inf
        total = logp + np.array([b[1] for b in beams])[:, None]
        # 2*num_beams candidats, comme HF, pour garder num_beams faisceaux non terminés
        flat = np.argsort(total, axis=None)[::-1][: 2 * num_beams]
        new_beams = []
        for rank, idx in enumerate(flat):
            bi, tok = divmod(int(idx), total.shape[1])
            score = float(total[bi, tok])
            if tok == EOS:
                if rank < num_beams:  # HF ignore un EOS hors des num_beams meilleurs
                    toks = beams[bi][0]
                    finished.append((score / (len(toks) ** length_penalty), toks))
            else:
                new_beams.append((beams[bi][0] + [tok], score))
            if len(new_beams) == num_beams:
                break
        beams = new_beams
        if len(finished) >= num_beams:  # early_stopping=True
            break
    if not finished:
        finished = [(s / (len(t) ** length_penalty), t) for t, s in beams]
    return max(finished)[1][1:]


def detokenize(ids: list[int], vocab: list[str]) -> str:
    # 0..14 = [PAD] [UNK] [CLS] [SEP] [MASK] <unused0..9> : ignorés comme skip_special_tokens
    return "".join(vocab[i] for i in ids if i >= 15)


def post_process(text: str) -> str:
    text = "".join(text.split())
    text = text.replace("…", "...")
    text = re.sub("[・.]{2,}", lambda m: (m.end() - m.start()) * ".", text)
    return jaconv.h2z(text, ascii=True, digit=True)


def main():
    vocab = (MODEL_DIR / "vocab.txt").read_text(encoding="utf-8").splitlines()
    enc = ort.InferenceSession(str(next(MODEL_DIR.glob("encoder_model*.onnx"))))
    dec = ort.InferenceSession(str(next(MODEL_DIR.glob("decoder_model*.onnx"))))
    expected = json.loads((TEST_DIR / "expected_results.json").read_text(encoding="utf-8"))

    ok = 0
    t0 = time.time()
    for item in expected:
        img = Image.open(TEST_DIR / "images" / item["filename"])
        text = post_process(detokenize(beam_decode(enc, dec, preprocess(img)), vocab))
        match = text == item["result"]
        ok += match
        print(f"{'✅' if match else '❌'} {item['filename']}: {text}" + ("" if match else f"\n   attendu : {item['result']}"))
    print(f"\n{ok}/{len(expected)} identiques — {(time.time() - t0) / len(expected):.2f} s/image (CPU Mac)")


if __name__ == "__main__":
    main()
