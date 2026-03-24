#!/bin/bash
# Run once to download the three openWakeWord ONNX model files into assets.
# Required before building:
#   - melspectrogram.onnx   (audio → mel spectrogram)
#   - embedding_model.onnx  (mel frames → speech embeddings)
#   - hey_jarvis_v0.1.onnx  (embeddings → wake word score)

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_URL="https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"

for MODEL in melspectrogram.onnx embedding_model.onnx hey_jarvis_v0.1.onnx; do
    echo "Downloading $MODEL..."
    curl -L "$BASE_URL/$MODEL" -o "$SCRIPT_DIR/$MODEL"
    SIZE=$(wc -c < "$SCRIPT_DIR/$MODEL")
    if [ "$SIZE" -lt 1024 ]; then
        echo "ERROR: $MODEL is only $SIZE bytes — download likely failed"
        exit 1
    fi
    echo "$MODEL OK (${SIZE} bytes)"
done

echo "All models ready in app/src/main/assets/"
