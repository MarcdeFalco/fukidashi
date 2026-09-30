"""Export de manga-ocr optimisé pour le téléphone, avec cache.

Le décodeur BERT d'origine (optimum) recalcule à chaque caractère toute la phrase
et l'attention sur l'image (projections K/V des 197 patchs), pour chaque faisceau.
Ici, deux graphes, avec les mêmes poids :

- encoder_kv.onnx : image -> K/V de l'attention croisée des 2 couches (taille fixe,
  une fois par bulle ; compatible NPU).
- decoder_step.onnx : un token par faisceau + cache K/V de l'auto-attention
  -> logits du dernier token + cache mis à jour.

Usage : .venv/bin/python export/export_cached.py
Sortie : export/out/manga_ocr_cached/ (fp32) et manga_ocr_cached_int8/
"""
import math
import shutil
from pathlib import Path

import torch
from torch import nn
from transformers import VisionEncoderDecoderModel

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "export" / "out" / "manga_ocr_cached"
SRC = ROOT / "export" / "out" / "manga_ocr"  # vocab.txt de l'export optimum

HEADS, HEAD_DIM = 12, 64


def split_heads(x):  # [B, L, 768] -> [B, 12, L, 64]
    b, l, _ = x.shape
    return x.view(b, l, HEADS, HEAD_DIM).transpose(1, 2)


def merge_heads(x):  # [B, 12, L, 64] -> [B, L, 768]
    b, _, l, _ = x.shape
    return x.transpose(1, 2).reshape(b, l, HEADS * HEAD_DIM)


class EncoderKV(nn.Module):
    """ViT + projections K/V de l'attention croisée de chaque couche du décodeur."""

    def __init__(self, model):
        super().__init__()
        self.encoder = model.encoder
        self.layers = model.decoder.bert.encoder.layer

    def forward(self, pixel_values):
        hidden = self.encoder(pixel_values=pixel_values).last_hidden_state  # [1, 197, 768]
        out = []
        for layer in self.layers:
            att = layer.crossattention.self
            out += [split_heads(att.key(hidden)), split_heads(att.value(hidden))]
        return tuple(out)


class DecoderStep(nn.Module):
    """Un pas du décodeur BERT avec cache, réécrit à l'identique de HF (eval, sans dropout)."""

    def __init__(self, model):
        super().__init__()
        bert = model.decoder.bert
        self.emb = bert.embeddings
        self.layers = bert.encoder.layer
        self.head = model.decoder.cls.predictions

    @staticmethod
    def attention(q, k, v):
        scores = q @ k.transpose(-1, -2) / math.sqrt(HEAD_DIM)
        return torch.softmax(scores, dim=-1) @ v

    def forward(self, input_ids, position, past_k0, past_v0, past_k1, past_v1, cross_k0, cross_v0, cross_k1, cross_v1):
        # input_ids [B, 1], position [1] ; past [B, 12, P, 64] ; cross [1, 12, 197, 64]
        e = self.emb
        x = e.word_embeddings(input_ids) + e.position_embeddings(position)[None] + e.token_type_embeddings.weight[0]
        x = e.LayerNorm(x)
        pasts = [(past_k0, past_v0), (past_k1, past_v1)]
        crosses = [(cross_k0, cross_v0), (cross_k1, cross_v1)]
        new_cache = []
        for layer, (pk, pv), (ck, cv) in zip(self.layers, pasts, crosses):
            # Auto-attention : le nouveau token voit tous les précédents (causal par construction)
            sa = layer.attention.self
            k = torch.cat([pk, split_heads(sa.key(x))], dim=2)
            v = torch.cat([pv, split_heads(sa.value(x))], dim=2)
            new_cache += [k, v]
            ctx = merge_heads(self.attention(split_heads(sa.query(x)), k, v))
            so = layer.attention.output
            x = so.LayerNorm(so.dense(ctx) + x)
            # Attention croisée sur l'image, K/V précalculés
            ca = layer.crossattention
            ctx = merge_heads(self.attention(split_heads(ca.self.query(x)), ck, cv))
            x = ca.output.LayerNorm(ca.output.dense(ctx) + x)
            # Feed-forward
            h = layer.intermediate(x)
            x = layer.output.LayerNorm(layer.output.dense(h) + x)
        logits = self.head(x)[:, -1, :]  # [B, 6144]
        return (logits, *new_cache)


def verify(model, enc_kv, step):
    """Compare aux logits HF sur une séquence de test."""
    torch.manual_seed(0)
    pixels = torch.randn(1, 3, 224, 224)
    ids = torch.tensor([[2, 150, 3000, 42, 999, 7]]).repeat(4, 1)
    ids[1:, 3:] = torch.randint(15, 6144, (3, 3))
    with torch.no_grad():
        enc_hidden = model.encoder(pixel_values=pixels).last_hidden_state
        ref = model.decoder(input_ids=ids, encoder_hidden_states=enc_hidden.repeat(4, 1, 1)).logits
        cross = enc_kv(pixels)
        past = [torch.zeros(4, HEADS, 0, HEAD_DIM)] * 4
        for t in range(ids.shape[1]):
            logits, *past = step(ids[:, t:t + 1], torch.tensor([t]), *past, *cross)
            err = (logits - ref[:, t]).abs().max().item()
            assert err < 1e-3, f"écart {err} au pas {t}"
    print(f"✅ décodeur avec cache identique à HF (écart max {err:.1e})")


def export(model):
    OUT.mkdir(parents=True, exist_ok=True)
    enc_kv, step = EncoderKV(model).eval(), DecoderStep(model).eval()
    verify(model, enc_kv, step)

    kv_names = ["cross_k0", "cross_v0", "cross_k1", "cross_v1"]
    torch.onnx.export(
        enc_kv, (torch.randn(1, 3, 224, 224),), OUT / "encoder_kv.onnx",
        input_names=["pixel_values"], output_names=kv_names, opset_version=17, dynamo=False,
    )

    past_names = ["past_k0", "past_v0", "past_k1", "past_v1"]
    new_names = ["new_k0", "new_v0", "new_k1", "new_v1"]
    past = torch.zeros(4, HEADS, 3, HEAD_DIM)
    cross = torch.zeros(1, HEADS, 197, HEAD_DIM)
    dyn = {"input_ids": {0: "beams"}, "logits": {0: "beams"}}
    for n in past_names + new_names:
        dyn[n] = {0: "beams", 2: "length"}
    torch.onnx.export(
        step,
        (torch.ones(4, 1, dtype=torch.long), torch.tensor([3]), past, past, past, past, cross, cross, cross, cross),
        OUT / "decoder_step.onnx",
        input_names=["input_ids", "position", *past_names, *kv_names],
        output_names=["logits", *new_names],
        dynamic_axes=dyn, opset_version=17, dynamo=False,
    )
    shutil.copy(SRC / "vocab.txt", OUT / "vocab.txt")
    print(f"-> {OUT}")


def quantize():
    from onnxruntime.quantization import QuantType, quantize_dynamic

    dst = OUT.parent / "manga_ocr_cached_int8"
    dst.mkdir(exist_ok=True)
    quantize_dynamic(OUT / "decoder_step.onnx", dst / "decoder_step.onnx", weight_type=QuantType.QUInt8)
    quantize_dynamic(OUT / "encoder_kv.onnx", dst / "encoder_kv.onnx", weight_type=QuantType.QUInt8)
    shutil.copy(OUT / "vocab.txt", dst / "vocab.txt")
    print(f"-> {dst}")


if __name__ == "__main__":
    m = VisionEncoderDecoderModel.from_pretrained("kha-white/manga-ocr-base").eval()
    export(m)
    quantize()
