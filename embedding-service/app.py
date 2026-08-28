"""CLIP embedding sidecar for duplicate image detection.

Exposes a single endpoint that turns an image into a deterministic
512-dim vector using CLIP ViT-B-32. The same image always produces
the same vector, which is what pgvector similarity search needs.
"""
import base64
import io

import open_clip
import torch
from fastapi import FastAPI, HTTPException
from PIL import Image
from pydantic import BaseModel

MODEL_NAME = "ViT-B-32"
PRETRAINED = "openai"

model, _, preprocess = open_clip.create_model_and_transforms(MODEL_NAME, pretrained=PRETRAINED)
model.eval()

app = FastAPI(title="Arbiter CLIP Embedding Service")


class EmbedRequest(BaseModel):
    image_base64: str


@app.get("/health")
def health():
    return {"status": "ok", "model": f"clip-{MODEL_NAME.lower()}-{PRETRAINED}"}


@app.post("/embed")
def embed(request: EmbedRequest):
    try:
        image_bytes = base64.b64decode(request.image_base64)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid base64: {e}")

    print(f"[CLIP] Received {len(image_bytes)} image bytes", flush=True)
    if not image_bytes:
        raise HTTPException(status_code=400, detail="Empty image payload")

    try:
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    except Exception as e:
        raise HTTPException(
            status_code=400,
            detail=f"Cannot decode image (format not supported? PIL needs JPEG/PNG/WebP/BMP/GIF): {e}",
        )

    tensor = preprocess(image).unsqueeze(0)
    with torch.no_grad():
        features = model.encode_image(tensor)
        features = features / features.norm(dim=-1, keepdim=True)

    vector = features[0].tolist()
    return {
        "embedding": vector,
        "dims": len(vector),
        "model": f"clip-{MODEL_NAME.lower()}-{PRETRAINED}",
    }
