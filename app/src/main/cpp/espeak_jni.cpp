// JNI bridge: text -> IPA phonemes via eSpeak-ng, for Piper TTS.
//
// Links against a prebuilt libespeak-ng.so (you supply it under src/main/jniLibs/<abi>/)
// and its headers (under cpp/include/espeak-ng/). Exposes three natives matching
// com.eartranslator.nlp.EspeakPhonemizer.
//
// LICENSE NOTE: eSpeak-ng is GPLv3. Linking it into this app imposes GPLv3 obligations on
// the distributed binary — see README and THIRD_PARTY_NOTICES.md before publishing.

#include <jni.h>
#include <string>
#include <android/log.h>
#include <espeak-ng/speak_lib.h>

#define LOG_TAG "EspeakJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" {

// Returns the sample rate (>0) on success, or <=0 on failure.
// dataPath must be the directory that CONTAINS the "espeak-ng-data" folder.
JNIEXPORT jint JNICALL
Java_com_eartranslator_nlp_EspeakPhonemizer_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jstring dataPath) {
    const char* path = env->GetStringUTFChars(dataPath, nullptr);
    // AUDIO_OUTPUT_SYNCHRONOUS: we never synthesize audio here, only phonemize.
    int sampleRate = espeak_Initialize(
            AUDIO_OUTPUT_SYNCHRONOUS, /*buflength=*/0, path, espeakINITIALIZE_DONT_EXIT);
    env->ReleaseStringUTFChars(dataPath, path);
    if (sampleRate <= 0) LOGE("espeak_Initialize failed (%d)", sampleRate);
    return sampleRate;
}

// Selects an eSpeak voice/language, e.g. "en-us", "fr", "de". Returns 0 on success.
JNIEXPORT jint JNICALL
Java_com_eartranslator_nlp_EspeakPhonemizer_nativeSetVoice(
        JNIEnv* env, jobject /*thiz*/, jstring voice) {
    const char* v = env->GetStringUTFChars(voice, nullptr);
    espeak_ERROR err = espeak_SetVoiceByName(v);
    if (err != EE_OK) LOGE("espeak_SetVoiceByName(%s) failed (%d)", v, err);
    env->ReleaseStringUTFChars(voice, v);
    return static_cast<jint>(err);
}

// Converts UTF-8 text to an IPA phoneme string (clauses concatenated).
JNIEXPORT jstring JNICALL
Java_com_eartranslator_nlp_EspeakPhonemizer_nativeTextToPhonemes(
        JNIEnv* env, jobject /*thiz*/, jstring text) {
    const char* t = env->GetStringUTFChars(text, nullptr);
    const void* textPtr = static_cast<const void*>(t);

    // textmode = UTF-8 input. phonememode bit 1 (0x02) = output IPA.
    const int textmode = espeakCHARS_UTF8;
    const int phonememode = 0x02;

    std::string out;
    // espeak_TextToPhonemes advances textPtr clause-by-clause until it reaches the end.
    while (textPtr != nullptr) {
        const char* phonemes = espeak_TextToPhonemes(&textPtr, textmode, phonememode);
        if (phonemes != nullptr) out += phonemes;
    }

    env->ReleaseStringUTFChars(text, t);
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
