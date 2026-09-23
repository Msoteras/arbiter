# Build context: project root, like every other image in the repo (the hosting platform needs the
# Dockerfile path and the build context to share the same root).
# docker build -t clip-embedding-img -f embedding-service/Dockerfile .

FROM python:3.11-slim

WORKDIR /app

# CPU-only torch + torchvision keeps the image ~5x smaller than the default CUDA
# build. Both must come from the same index or their versions mismatch.
RUN pip install --no-cache-dir torch torchvision --index-url https://download.pytorch.org/whl/cpu

COPY embedding-service/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Bake the CLIP weights into the image so startup doesn't depend on network
RUN python -c "import open_clip; open_clip.create_model_and_transforms('ViT-B-32', pretrained='openai')"

COPY embedding-service/app.py .

# After baking the weights, so pip installs and the model download still run as root.
RUN useradd --create-home --shell /bin/false arbiter && chown -R arbiter:arbiter /app
USER arbiter

EXPOSE 8000

# Shell form on purpose: ${PORT} has to expand at runtime, and the exec form does no expansion.
CMD uvicorn app:app --host 0.0.0.0 --port ${PORT:-8000}
