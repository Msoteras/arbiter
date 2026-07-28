FROM python:3.11-slim

WORKDIR /app

# CPU-only torch + torchvision keeps the image ~5x smaller than the default CUDA
# build. Both must come from the same index or their versions mismatch.
RUN pip install --no-cache-dir torch torchvision --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Bake the CLIP weights into the image so startup doesn't depend on network
RUN python -c "import open_clip; open_clip.create_model_and_transforms('ViT-B-32', pretrained='openai')"

COPY app.py .

EXPOSE 8000

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
