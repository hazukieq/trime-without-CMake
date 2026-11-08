#!/usr/bin/env python3
"""Drive the local Qwen-Image-2.1 (ComfyUI) to render a material/3D pass of an
icon.  img2img at low denoise keeps the mark, adds surface treatment.

usage: comfy_icon.py --in icon.png --prompt "..." -o out.png [--denoise .5]
"""
from __future__ import annotations

import argparse
import json
import shutil
import time
import urllib.request
from pathlib import Path

COMFY = Path("/home/hazukie/ComfyUI-parent/ComfyUI")
SERVER = "http://127.0.0.1:8188"
UNET = "qwen-image-2.1-Q4_K_M.gguf"
CLIP = "qwen3vl_8b_int8_convrot.safetensors"
VAE = "qwen_image_2.1_vae_bf16.safetensors"


def post(path, payload):
    req = urllib.request.Request(SERVER + path, data=json.dumps(payload).encode(),
                                headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def get(path):
    with urllib.request.urlopen(SERVER + path, timeout=120) as r:
        return json.loads(r.read())


def stage(src: Path) -> str:
    dst = COMFY / "input" / src.name
    shutil.copy2(src, dst)
    return src.name


def build(img, prompt, negative, seed, steps, denoise, prefix, cfg=2.5,
          resolution=1024):
    return {
        "1": {"class_type": "UnetLoaderGGUF", "inputs": {"unet_name": UNET}},
        "2": {"class_type": "CLIPLoader",
              "inputs": {"clip_name": CLIP, "type": "qwen_image", "device": "default"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": VAE}},
        "4": {"class_type": "LoadImage", "inputs": {"image": img, "upload": "image"}},
        "5": {"class_type": "ImageScaleToTotalPixels",
              "inputs": {"image": ["4", 0], "upscale_method": "lanczos",
                         "megapixels": 1.0, "resolution_steps": 64}},
        "6": {"class_type": "VAEEncode", "inputs": {"pixels": ["5", 0], "vae": ["3", 0]}},
        "7": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["2", 0], "text": prompt}},
        "8": {"class_type": "CLIPTextEncode",
              "inputs": {"clip": ["2", 0], "text": negative}},
        "9": {"class_type": "ModelSamplingAuraFlow",
              "inputs": {"model": ["1", 0], "shift": 3.1}},
        "10": {"class_type": "KSampler",
               "inputs": {"model": ["9", 0], "positive": ["7", 0], "negative": ["8", 0],
                          "latent_image": ["6", 0], "seed": seed, "steps": steps,
                          "cfg": cfg, "sampler_name": "euler", "scheduler": "simple",
                          "denoise": denoise}},
        "11": {"class_type": "VAEDecode", "inputs": {"samples": ["10", 0], "vae": ["3", 0]}},
        "12": {"class_type": "SaveImage",
               "inputs": {"images": ["11", 0], "filename_prefix": prefix}},
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="inp", required=True)
    ap.add_argument("--prompt", required=True)
    ap.add_argument("--negative", default="文字, 中文, 汉字, 水印, 杂乱, 多个物体, 变形")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--denoise", type=float, default=0.5)
    ap.add_argument("--steps", type=int, default=24)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--cfg", type=float, default=2.5)
    a = ap.parse_args()
    img = stage(Path(a.inp))
    out = Path(a.out)
    wf = build(img, a.prompt, a.negative, a.seed, a.steps, a.denoise, out.stem,
               cfg=a.cfg)
    t0 = time.time()
    r = post("/prompt", {"prompt": wf})
    pid = r["prompt_id"]
    print("[submit]", pid, flush=True)
    while True:
        h = get(f"/history/{pid}")
        if pid in h:
            e = h[pid]
            st = e.get("status", {})
            if st.get("status_str") == "error":
                print("[error]", json.dumps(st.get("messages", []), ensure_ascii=False)[:900])
                raise SystemExit(1)
            imgs = []
            for node in e.get("outputs", {}).values():
                imgs.extend(node.get("images", []))
            if imgs:
                f = imgs[-1]
                src = COMFY / "output" / f.get("subfolder", "") / f["filename"]
                out.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, out)
                print(f"[done] {out} ({time.time()-t0:.0f}s)")
                return
            time.sleep(0.5)
        else:
            time.sleep(1.0)


if __name__ == "__main__":
    main()
