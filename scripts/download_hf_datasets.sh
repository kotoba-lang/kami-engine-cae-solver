#!/usr/bin/env bash
set -euo pipefail

# Clean-room data acquisition: downloads are kept outside the source tree.
# Requires: `huggingface-cli` (or `hf`) and, for large files, Git LFS.
ROOT="${HF_DATASETS_DIR:-$PWD/.cache/huggingface-datasets}"
mkdir -p "$ROOT"

download() {
  local repo="$1"
  local target="$ROOT/${repo//\//__}"
  if command -v hf >/dev/null 2>&1; then
    hf download "$repo" --repo-type dataset --local-dir "$target"
  elif command -v huggingface-cli >/dev/null 2>&1; then
    huggingface-cli download "$repo" --repo-type dataset --local-dir "$target"
  else
    echo "Install huggingface_hub CLI first: pip install -U huggingface_hub" >&2
    exit 2
  fi
}

download "${1:-ashiq24/FSI-pde-dataset}"
