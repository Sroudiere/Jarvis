#!/bin/bash
# Run this script once to download the openWakeWord ONNX model into assets.
# The base models (melspectrogram.onnx, embedding_model.onnx) are bundled
# inside the xyz.rementia:openwakeword AAR — you only need the classifier below.

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Downloading hey_jarvis_v0.1.onnx..."
curl -L "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/hey_jarvis_v0.1.onnx" \
     -o "$SCRIPT_DIR/hey_jarvis_v0.1.onnx"

echo "Done. Model saved to app/src/main/assets/hey_jarvis_v0.1.onnx"
