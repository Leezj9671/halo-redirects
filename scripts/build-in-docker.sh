#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

docker run --rm \
  -v "${ROOT_DIR}:/workspace" \
  -w /workspace \
  gradle:8.13.0-jdk21 \
  gradle --no-daemon clean test build
