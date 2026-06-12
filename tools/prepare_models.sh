#!/usr/bin/env bash
#
# prepare_models.sh — download + quantize every model EarTranslator needs, for a chosen
# set of languages, into app/src/main/assets/models/.
#
# Translation uses an ENGLISH PIVOT, so only en-X and X-en opus-mt models are fetched.
#
# USAGE:
#   tools/prepare_models.sh es fr            # English + Spanish + French
#   tools/prepare_models.sh                  # default: es fr
#
# REQUIREMENTS (install first):
#   python3, pip, curl
#   pip install "optimum[exporters,onnxruntime]" onnx onnxruntime transformers sentencepiece huggingface_hub
#
# WINDOWS: run under WSL or Git Bash (this is a POSIX shell script; the ML tools are
# easiest on Linux/macOS/WSL).
#
# NOTE: English ("en") is always included as the pivot. Start with ONE extra language to
# validate the whole pipeline before fetching all of them — the downloads are large.

set -uo pipefail

ASSETS="app/src/main/assets/models"
LANGS=("$@")
if [ ${#LANGS[@]} -eq 0 ]; then LANGS=(es fr); fi

# Piper voice per language code (mirror of config/Languages.kt — VERIFY/adjust as needed).
declare -A PIPER_VOICE=(
  [en]="en_US-lessac-medium"
  [es]="es_ES-mls_10246-medium"
  [fr]="fr_FR-siwis-medium"
  [de]="de_DE-thorsten-medium"
  [it]="it_IT-riccardo-x_low"
  [pt]="pt_BR-faber-medium"
  [nl]="nl_BE-nathalie-medium"
  [ru]="ru_RU-irina-medium"
  [pl]="pl_PL-darkman-medium"
  [uk]="uk_UA-ukrainian_tts-medium"
  [cs]="cs_CZ-jirka-medium"
  [sk]="sk_SK-lili-medium"
  [ro]="ro_RO-mihai-medium"
  [hu]="hu_HU-anna-medium"
  [el]="el_GR-rapunzelina-low"
  [sv]="sv_SE-nst-medium"
  [da]="da_DK-talesyntese-medium"
  [fi]="fi_FI-harri-medium"
  [no]="no_NO-talesyntese-medium"
  [tr]="tr_TR-dfki-medium"
  [ca]="ca_ES-upc_ona-medium"
  [vi]="vi_VN-vais1000-medium"
  [zh]="zh_CN-huayan-medium"
  [ar]="ar_JO-kareem-medium"
)

mkdir -p "$ASSETS"
echo "==> Target: $ASSETS"
echo "==> Languages: en (pivot) ${LANGS[*]}"

quantize() {  # quantize <file.onnx> in place
  python3 - "$1" <<'PY'
import sys
from onnxruntime.quantization import quantize_dynamic, QuantType
p = sys.argv[1]
quantize_dynamic(p, p, weight_type=QuantType.QInt8)
print("    quantized", p)
PY
}

# ---------------------------------------------------------------------------
# 1) Silero VAD
# ---------------------------------------------------------------------------
echo "==> [1/4] Silero VAD"
if [ ! -f "$ASSETS/silero_vad.onnx" ]; then
  curl -L -o "$ASSETS/silero_vad.onnx" \
    https://github.com/snakers4/silero-vad/raw/v4.0/files/silero_vad.onnx
fi

# ---------------------------------------------------------------------------
# 2) Whisper tiny (ASR) — covers all languages
# ---------------------------------------------------------------------------
echo "==> [2/4] Whisper tiny"
if [ ! -f "$ASSETS/whisper/encoder_model.onnx" ]; then
  optimum-cli export onnx --model openai/whisper-tiny \
    --task automatic-speech-recognition "$ASSETS/whisper/"
fi
python3 - "$ASSETS/whisper/vocab.json" <<'PY'
import sys, json
from transformers import WhisperTokenizer
tok = WhisperTokenizer.from_pretrained("openai/whisper-tiny")
json.dump(tok.get_vocab(), open(sys.argv[1], "w", encoding="utf-8"), ensure_ascii=False)
print("    wrote", sys.argv[1])
PY
quantize "$ASSETS/whisper/encoder_model.onnx"
quantize "$ASSETS/whisper/decoder_model.onnx"

# ---------------------------------------------------------------------------
# 3) opus-mt (MT) — English-pivot: en-X and X-en per language
# ---------------------------------------------------------------------------
echo "==> [3/4] opus-mt (English-pivot pairs)"
fetch_pair() {  # fetch_pair <pair like en-es>
  local pair="$1"
  local dir="$ASSETS/opus-mt/$pair"
  if [ -f "$dir/encoder_model.onnx" ]; then echo "    $pair exists, skip"; return 0; fi
  echo "    exporting $pair ..."
  if ! optimum-cli export onnx --model "Helsinki-NLP/opus-mt-$pair" \
        --task text2text-generation "$dir/"; then
    echo "    WARN: opus-mt-$pair not available on the hub — skipping ($pair)"
    return 0
  fi
  python3 - "$pair" "$dir" <<'PY'
import sys, os, shutil
from huggingface_hub import hf_hub_download
pair, dir = sys.argv[1], sys.argv[2]
repo = f"Helsinki-NLP/opus-mt-{pair}"
for name in ("source.spm", "target.spm"):
    try:
        shutil.copy(hf_hub_download(repo, name), os.path.join(dir, name))
    except Exception as e:
        print("    WARN: could not fetch", name, "for", pair, e)
PY
  quantize "$dir/encoder_model.onnx"
  quantize "$dir/decoder_model.onnx"
}

for c in "${LANGS[@]}"; do
  [ "$c" = "en" ] && continue
  fetch_pair "en-$c"
  fetch_pair "$c-en"
done

# ---------------------------------------------------------------------------
# 4) Piper TTS voices — one per language (incl. English)
# ---------------------------------------------------------------------------
echo "==> [4/4] Piper voices"
PIPER_BASE="https://huggingface.co/rhasspy/piper-voices/resolve/main"
fetch_voice() {  # fetch_voice <langcode>
  local lang="$1"
  local voice="${PIPER_VOICE[$lang]:-}"
  if [ -z "$voice" ]; then echo "    WARN: no Piper voice mapped for '$lang' — skipping"; return 0; fi
  # voice = <locale>-<name>-<quality>, e.g. es_ES-mls_10246-medium
  local locale name quality family
  locale="${voice%%-*}"                # es_ES
  quality="${voice##*-}"               # medium
  name="${voice#${locale}-}"; name="${name%-${quality}}"  # mls_10246
  family="${locale%%_*}"               # es
  local dir="$ASSETS/piper/$lang"
  mkdir -p "$dir"
  local url="$PIPER_BASE/$family/$locale/$name/$quality/$voice"
  if [ ! -f "$dir/$voice.onnx" ]; then
    curl -fL -o "$dir/$voice.onnx"      "$url.onnx" \
      || echo "    WARN: voice .onnx not found at $url.onnx (verify on piper-voices repo)"
    curl -fL -o "$dir/$voice.onnx.json" "$url.onnx.json" \
      || echo "    WARN: voice .onnx.json not found"
  fi
}

fetch_voice en
for c in "${LANGS[@]}"; do fetch_voice "$c"; done

echo
echo "==> Done. Review any WARN lines above (some languages may lack a hub model or voice)."
echo "    Sizes will be large — for Play, see README 'Model delivery' (Asset Delivery / download)."
