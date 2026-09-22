#!/bin/sh
# A C compiler for Triton, on a box with no system toolchain.
#
# Triton JIT-compiles its CUDA driver shim at the first forward pass, so a
# training run needs `cc` on PATH. Installing build-essential needs root, which
# this setup does not have, so the `ziglang` wheel supplies a hermetic clang
# instead and this script presents it as `cc`.
#
# One translation is needed. Triton links CUDA as `-l:libcuda.so.1` — GNU ld's
# "exact filename" form — which zig's lld does not accept. Under WSL the stub
# lives in /usr/lib/wsl/lib and there is no `libcuda.so` symlink to fall back on,
# so each `-l:NAME` is resolved against the usual library directories and passed
# as a plain path.
#
# Install:
#   cp tools/zig-cc.sh ~/.local/shim/cc && ln -sf ~/.local/shim/cc ~/.local/shim/gcc
#   export PATH="$HOME/.local/shim:$PATH"

PY="${PRAXIS_PYTHON:-$HOME/.venvs/praxis-train/bin/python}"

ARGS=""
for a in "$@"; do
    case "$a" in
        -l:*)
            lib="${a#-l:}"
            for d in /usr/lib/wsl/lib /usr/lib/x86_64-linux-gnu /lib/x86_64-linux-gnu /usr/local/cuda/lib64; do
                if [ -f "$d/$lib" ]; then
                    a="$d/$lib"
                    break
                fi
            done
            ;;
    esac
    ARGS="$ARGS \"$a\""
done

eval exec "\"$PY\"" -m ziglang cc $ARGS
