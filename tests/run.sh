#!/usr/bin/env bash
# Testes sem Android: AXML, ROM NDS e montagem do APK. Uso: tests/run.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
src="$here/../generator/src/main/java/com/ndspack/generator"
python3 "$here/make_fixture.py" "$work/fx"
mkdir -p "$work/classes"
javac -d "$work/classes" "$src/AxmlPatcher.java" "$src/NdsRom.java" "$src/ApkBuilder.java" "$here/BuilderTest.java"
java -cp "$work/classes" BuilderTest "$work/fx"
python3 "$here/verify.py" "$work/fx"
rm -rf "$work"
