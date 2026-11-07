package io.github.enoky.androidtts

/** What to say to show off the engine or one of its voices. */
object Samples {
    /** A sentence in [lang], one of [Model.SUPPORTED], or null for another language. */
    fun sentence(lang: String?): String? = SENTENCES[lang]

    /** A voice saying its own name, in English; another language gets its sample sentence. */
    fun introduce(lang: String, voice: String): String {
        if (lang != "en") return SENTENCES[lang] ?: SENTENCES.getValue("en")
        if (voice == VoiceSet.DEFAULT) return "Hello! This is the default voice."
        // "Zoey (fr)" is the catalog's French Zoey: she is still just Zoey.
        return "Hello! My name is ${voice.substringBefore(" (")}, and this is how I sound."
    }

    private val SENTENCES = mapOf(
        "en" to "This is an example of speech synthesis in English.",
        "fr" to "Ceci est un exemple de synthèse vocale en français.",
        "de" to "Dies ist ein Beispiel für Sprachsynthese auf Deutsch.",
        "es" to "Este es un ejemplo de síntesis de voz en español.",
        "pt" to "Este é um exemplo de síntese de voz em português.",
        "ar" to "هذا مثال على تركيب الكلام باللغة العربية.",
        "hi" to "यह हिंदी में वाक् संश्लेषण का एक उदाहरण है।",
        "ko" to "이것은 한국어 음성 합성의 예입니다.",
        "pl" to "To jest przykład syntezy mowy w języku polskim.",
        "sv" to "Detta är ett exempel på talsyntes på svenska.",
        "fi" to "Tämä on esimerkki puhesynteesistä suomeksi.",
        "no" to "Dette er et eksempel på talesyntese på norsk.",
        "it" to "Questo è un esempio di sintesi vocale in italiano.",
        "nl" to "Dit is een voorbeeld van spraaksynthese in het Nederlands.",
        "ja" to "これは日本語の音声合成の例です。",
        "zh" to "这是一个中文语音合成的示例。",
        "da" to "Dette er et eksempel på talesyntese på dansk.",
        "tr" to "Bu, Türkçe konuşma sentezinin bir örneğidir.",
    )
}
