#!/usr/bin/env bash
# Test de vitesse sur le téléphone.
#   ./bench.sh setup                     installe l'APK debug et copie les images de test
#   ./bench.sh run [--es backend npu ...] lance BenchmarkActivity et affiche le résultat
#   ./bench.sh push-models <dossier>     copie des variantes de modèles sur le téléphone
set -euo pipefail
export LC_ALL=en_US.UTF-8
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
SERIAL="${SERIAL:-RZCY12QPR0D}"
PKG=dev.marc.japanesehelper
TMP=/data/local/tmp/jh
# Dossier interne de l'appli (accessible via run-as, version debug seulement)
REMOTE=files
HERE="$(cd "$(dirname "$0")" && pwd)"
adb() { "$ADB" -s "$SERIAL" "$@"; }

case "${1:-}" in
setup)
    adb install -r "$HERE/app/build/outputs/apk/debug/app-debug.apk"
    adb shell mkdir -p "$TMP/bench/ocr"
    adb push "$HERE/../test_ocr.jpg" "$TMP/bench/page.jpg" >/dev/null
    adb push "$HOME"/dev/manga-ocr/tests/data/images/. "$TMP/bench/ocr/" >/dev/null
    adb push "$HOME/dev/manga-ocr/tests/data/expected_results.json" "$TMP/bench/ocr/" >/dev/null
    adb shell run-as $PKG sh -c "'mkdir -p $REMOTE && cp -r $TMP/bench $REMOTE/'"
    echo "OK"
    ;;
push-models)
    name="$(basename "$2")"
    adb shell mkdir -p "$TMP/models_bench"
    adb push "$2" "$TMP/models_bench/" >/dev/null
    adb shell run-as $PKG sh -c "'mkdir -p $REMOTE/models_bench && cp -r $TMP/models_bench/$name $REMOTE/models_bench/'"
    adb shell rm -rf "$TMP/models_bench/$name"
    echo "-> /data/data/$PKG/files/models_bench/$name"
    ;;
run)
    shift
    adb shell am force-stop $PKG
    adb logcat -c
    adb shell am start -W -n $PKG/.BenchmarkActivity "$@" >/dev/null
    # Attend la fin (jusqu'à 10 min : la 1re compilation NPU peut être longue)
    for _ in $(seq 1 600); do
        if adb logcat -d -s Bench:I | grep -q " FIN$"; then break; fi
        sleep 1
    done
    adb logcat -d -s Bench:I | sed -n 's/.*Bench *: //p' | grep -v '^RESULT' || true
    adb shell run-as $PKG cat "$REMOTE/bench/result.json"
    ;;
*)
    sed -n '2,5p' "$0"; exit 1 ;;
esac
