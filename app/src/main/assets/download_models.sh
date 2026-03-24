#!/bin/bash
# Run once to download the three openWakeWord ONNX model files into assets.
# Required before building:
#   - melspectrogram.onnx   (audio → mel spectrogram)
#   - embedding_model.onnx  (mel frames → speech embeddings)
#   - hey_jarvis_v0.1.onnx  (embeddings → wake word score)

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_URL="https://huggingface.co/davidscripka/openwakeword/resolve/main"

for MODEL in melspectrogram.onnx embedding_model.onnx hey_jarvis_v0.1.onnx; do
    if [ -f "$SCRIPT_DIR/$MODEL" ]; then
        echo "$MODEL already present, skipping."
    else
        echo "Downloading $MODEL..."
        curl -L "$BASE_URL/$MODEL" -o "$SCRIPT_DIR/$MODEL"
    fi
done

echo "All models ready in app/src/main/assets/"
