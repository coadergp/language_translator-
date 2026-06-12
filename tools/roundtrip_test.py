"""
End-to-end PC verification of the EarTranslator model pipeline, BOTH directions.
Generates speech with Piper (the app's voices), recognizes it with Whisper-tiny, and
translates with opus-mt — the same models the app uses. Proves the translation core works,
independent of Bluetooth/on-device phonemization.
"""
import os, wave, sys
import librosa
from piper import PiperVoice
from transformers import (WhisperProcessor, WhisperForConditionalGeneration,
                          MarianMTModel, MarianTokenizer)

ROOT = r"g:\Language_Translator\models"
OUT = r"C:\Users\quest\eartools\stage"

def synth(voice_onnx, text, wav_path):
    v = PiperVoice.load(voice_onnx)
    with wave.open(wav_path, "wb") as wf:
        v.synthesize_wav(text, wf)
    return wav_path

print("Loading Whisper-tiny...")
wproc = WhisperProcessor.from_pretrained("openai/whisper-tiny")
wmodel = WhisperForConditionalGeneration.from_pretrained("openai/whisper-tiny")

def asr(wav):
    audio, _ = librosa.load(wav, sr=16000)
    feats = wproc(audio, sampling_rate=16000, return_tensors="pt").input_features
    ids = wmodel.generate(feats)  # auto language detection
    return wproc.batch_decode(ids, skip_special_tokens=True)[0].strip()

def translate(pair, text):
    tok = MarianTokenizer.from_pretrained(f"Helsinki-NLP/opus-mt-{pair}")
    m = MarianMTModel.from_pretrained(f"Helsinki-NLP/opus-mt-{pair}")
    out = m.generate(**tok(text, return_tensors="pt"))
    return tok.batch_decode(out, skip_special_tokens=True)[0].strip()

print("\n================ PERSON A (English speaker) ================")
en_text = "Hello, where is the train station?"
print("Spoken (English):", en_text)
synth(os.path.join(ROOT, "piper", "en", "en_US-lessac-medium.onnx"), en_text, os.path.join(OUT, "en.wav"))
heard = asr(os.path.join(OUT, "en.wav"))
print("Whisper heard   :", heard)
print("Translated (ES) :", translate("en-es", heard))

print("\n================ PERSON B (Spanish speaker) ================")
es_text = "Hola, donde puedo encontrar un taxi?"
print("Spoken (Spanish):", es_text)
synth(os.path.join(ROOT, "piper", "es", "es_ES-davefx-medium.onnx"), es_text, os.path.join(OUT, "es.wav"))
heard2 = asr(os.path.join(OUT, "es.wav"))
print("Whisper heard   :", heard2)
print("Translated (EN) :", translate("es-en", heard2))
print("\nDONE")
