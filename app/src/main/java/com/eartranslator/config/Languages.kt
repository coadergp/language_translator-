package com.eartranslator.config

/**
 * Supported languages. Each carries:
 *  - [code]      : Whisper / opus-mt short code (e.g. "en"),
 *  - [display]   : human-readable name for the Spinner,
 *  - [piperVoice]: the Piper voice file name to load for TTS in that language.
 *
 * TRANSLATION USES AN ENGLISH PIVOT (see [com.eartranslator.nlp.OpusMTTranslator]):
 * any pair X→Y is done as X→en→Y unless one side is already English. This means you only
 * need the opus-mt `en-<code>` and `<code>-en` model folders per language — NOT every
 * pairwise combination. English ([ENGLISH]) is the hub and must always be present.
 *
 * ⚠️ VERIFY ASSETS PER LANGUAGE before shipping one:
 *   - opus-mt: confirm `Helsinki-NLP/opus-mt-en-<code>` and `opus-mt-<code>-en` exist.
 *   - Piper: confirm the exact `piperVoice` file name on rhasspy/piper-voices (names and
 *     availability vary; some languages have no official voice yet → ASR+MT will work but
 *     TTS won't until you add one).
 */
enum class Language(
    val code: String,
    val display: String,
    val piperVoice: String
) {
    // English is the pivot hub — keep it first.
    ENGLISH("en", "English", "en_US-lessac-medium"),

    SPANISH("es", "Spanish", "es_ES-mls_10246-medium"),
    FRENCH("fr", "French", "fr_FR-siwis-medium"),
    GERMAN("de", "German", "de_DE-thorsten-medium"),
    ITALIAN("it", "Italian", "it_IT-riccardo-x_low"),
    PORTUGUESE("pt", "Portuguese", "pt_BR-faber-medium"),
    DUTCH("nl", "Dutch", "nl_BE-nathalie-medium"),
    RUSSIAN("ru", "Russian", "ru_RU-irina-medium"),
    POLISH("pl", "Polish", "pl_PL-darkman-medium"),
    UKRAINIAN("uk", "Ukrainian", "uk_UA-ukrainian_tts-medium"),
    CZECH("cs", "Czech", "cs_CZ-jirka-medium"),
    SLOVAK("sk", "Slovak", "sk_SK-lili-medium"),
    ROMANIAN("ro", "Romanian", "ro_RO-mihai-medium"),
    HUNGARIAN("hu", "Hungarian", "hu_HU-anna-medium"),
    GREEK("el", "Greek", "el_GR-rapunzelina-low"),
    SWEDISH("sv", "Swedish", "sv_SE-nst-medium"),
    DANISH("da", "Danish", "da_DK-talesyntese-medium"),
    FINNISH("fi", "Finnish", "fi_FI-harri-medium"),
    NORWEGIAN("no", "Norwegian", "no_NO-talesyntese-medium"),
    TURKISH("tr", "Turkish", "tr_TR-dfki-medium"),
    CATALAN("ca", "Catalan", "ca_ES-upc_ona-medium"),
    VIETNAMESE("vi", "Vietnamese", "vi_VN-vais1000-medium"),
    CHINESE("zh", "Chinese", "zh_CN-huayan-medium"),
    ARABIC("ar", "Arabic", "ar_JO-kareem-medium");

    companion object {
        /** The pivot/hub language code; all translation routes through it. */
        const val PIVOT = "en"

        fun displayNames(): Array<String> = entries.map { it.display }.toTypedArray()
        fun byDisplay(name: String): Language = entries.first { it.display == name }
        fun byCode(code: String): Language = entries.first { it.code == code }
    }
}
