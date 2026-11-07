package io.github.enoky.androidtts

import java.util.Locale

/**
 * The voices of a loaded model, and which of them to offer in each language. [names] are the
 * voices the library loaded; [added] records the ones that came from the catalog or that the
 * user imported. The checkpoint's own voices, and imported ones, speak every language the model
 * does; a catalog voice speaks its own language, or every one when the catalog gives none.
 */
class VoiceSet(val names: List<String>, val added: Map<String, VoiceCatalog.Entry>) {
    /** The voices to offer in [lang]: the built-in `default` first, then by name. */
    fun forLanguage(lang: String): List<String> =
        names.filter { name -> added[name]?.language.let { it == null || it == lang } }
            .sortedWith(compareBy<String>({ it != DEFAULT }, { it.lowercase(Locale.ROOT) }))

    /**
     * The voice that speaks [lang] when a request names none: [preferred] when it speaks [lang],
     * else the checkpoint's `default`, else the first voice. Null when the model has none.
     */
    fun defaultFor(lang: String, preferred: String?): String? {
        val offered = forLanguage(lang)
        return preferred?.takeIf { it in offered } ?: DEFAULT.takeIf { it in offered } ?: offered.firstOrNull()
    }

    /** The voice's locale in [lang]: the catalog's, such as en-GB, when it is a [lang] one. */
    fun locale(lang: String, name: String): Locale {
        val tag = added[name]?.locale?.replace('_', '-')
        val locale = tag?.let { Locale.forLanguageTag(it) }
        return if (locale != null && locale.language == lang) locale else Locale.forLanguageTag(lang)
    }

    fun description(name: String): String? = added[name]?.description

    fun isCatalog(name: String) = added[name]?.imported == false

    fun isImported(name: String) = added[name]?.imported == true

    /** How many catalog voices are installed. */
    val catalogCount: Int get() = added.values.count { !it.imported }

    companion object {
        /** The name the library gives a checkpoint's own `default-voice.safetensors`. */
        const val DEFAULT = "default"
    }
}
