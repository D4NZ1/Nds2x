#!/usr/bin/env bash
# Coloca o nucleo melonDS (libretro, Android arm64) em player/src/main/jniLibs/arm64-v8a/libretro_core.so.
# Prioridade: cores/libretro_core.so (local) -> download do buildbot do libretro.
# Variaveis: CORE_NAMES="melonds melondsds" (ordem de tentativa), CORE_BASE_URL.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/player/src/main/jniLibs/arm64-v8a"
mkdir -p "$dest"

if [ -f "$root/cores/libretro_core.so" ]; then
  cp "$root/cores/libretro_core.so" "$dest/libretro_core.so"
  echo "Usando o nucleo local cores/libretro_core.so"
else
  base="${CORE_BASE_URL:-https://buildbot.libretro.com/nightly/android/latest/arm64-v8a}"
  tmp="$(mktemp -d)"
  found=""
  for name in ${CORE_NAMES:-melonds melondsds}; do
    url="$base/${name}_libretro_android.so.zip"
    echo "Baixando $url"
    if curl -fL --retry 3 --retry-delay 2 -o "$tmp/core.zip" "$url"; then
      unzip -o -q "$tmp/core.zip" -d "$tmp"
      so="$(find "$tmp" -name '*.so' | head -n1)"
      if [ -n "$so" ]; then
        cp "$so" "$dest/libretro_core.so"
        found="$name"
        break
      fi
    fi
    echo "Nao deu certo com $name, tentando o proximo..."
  done
  rm -rf "$tmp"
  if [ -z "$found" ]; then
    echo "ERRO: nao consegui baixar o nucleo. Coloque um libretro_core.so (arm64) em cores/." >&2
    exit 1
  fi
  echo "Nucleo baixado: $found"
fi

# confere dependencias dinamicas
core="$dest/libretro_core.so"
readelf_bin="$(command -v readelf || command -v llvm-readelf || true)"
if [ -z "$readelf_bin" ] && [ -n "${ANDROID_NDK_ROOT:-}" ]; then
  readelf_bin="$(find "$ANDROID_NDK_ROOT" -name llvm-readelf -type f | head -n1 || true)"
fi
if [ -n "$readelf_bin" ]; then
  echo "Arquitetura/dependencias do nucleo:"
  "$readelf_bin" -h "$core" | grep -E 'Machine|Class' || true
  if [ "${SKIP_ARCH_CHECK:-0}" != "1" ] && ! "$readelf_bin" -h "$core" | grep -q 'AArch64'; then
    echo "ERRO: o nucleo nao e arm64 (AArch64)." >&2
    exit 1
  fi
  needed="$("$readelf_bin" -d "$core" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')"
  echo "$needed" | sed 's/^/  NEEDED: /'
  if echo "$needed" | grep -q 'libc++_shared.so'; then
    ndk="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
    if [ -z "$ndk" ]; then ndk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/ndk"; fi
    stl=""
    if [ -d "$ndk" ]; then
      stl="$(find "$ndk" -path '*aarch64-linux-android*' -name libc++_shared.so 2>/dev/null | head -n1 || true)"
    fi
    if [ -n "$stl" ]; then
      cp "$stl" "$dest/libc++_shared.so"
      echo "libc++_shared.so incluida a partir do NDK"
    else
      echo "AVISO: o nucleo precisa de libc++_shared.so e nao achei no NDK" >&2
    fi
  fi
  echo "$needed" | grep -Ev '^(libc\.so|libm\.so|libdl\.so|liblog\.so|libandroid\.so|libEGL\.so|libGLESv[123]\.so|libOpenSLES\.so|libz\.so|libc\+\+_shared\.so|libstdc\+\+\.so|libaaudio\.so|libmediandk\.so|)$' \
    | sed 's/^/AVISO: dependencia inesperada: /' >&2 || true
else
  echo "readelf indisponivel; pulando a conferencia do nucleo"
fi
ls -la "$dest"
