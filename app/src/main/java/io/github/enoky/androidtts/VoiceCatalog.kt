package io.github.enoky.androidtts

import org.json.JSONObject
import java.util.Locale

/**
 * Gradium's voice catalog, [`gradium/phonon-voices`](https://huggingface.co/gradium/phonon-voices):
 * a `voices.jsonl` with one voice per line (`name`, `filename`, and optionally `language`,
 * `locale`, `description` and `voice_id`), each voice a safetensors file that works with every
 * Phonon checkpoint. Pinned to [REVISION], the commit upstream's runtime uses
 * (`DEFAULT_VOICE_CATALOG` in xn-ptts `ptts/src/voice_catalog.rs`).
 */
object VoiceCatalog {
    const val REPO = "gradium/phonon-voices"
    const val REVISION = "da6c9ec0d47be8df5c87c730a3aa97187f3229c3"
    const val FILE = "voices.jsonl"

    /**
     * One voice of the catalog. [filename] is the voice file's path in the catalog repo. The app
     * records the voice files the user imports the same way, with [imported] set and [filename]
     * the name the file had.
     */
    data class Entry(
        val name: String,
        val filename: String,
        val language: String? = null,
        val locale: String? = null,
        val description: String? = null,
        val voiceId: String? = null,
        val imported: Boolean = false,
    )

    /** The catalog's voices in file order. A line that is not a voice is skipped, not fatal. */
    internal fun parse(jsonl: String): List<Entry> = jsonl.lineSequence().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        try {
            val o = JSONObject(line)
            Entry(
                name = o.getString("name"),
                filename = o.getString("filename"),
                language = o.text("language"),
                locale = o.text("locale"),
                description = o.text("description"),
                voiceId = o.text("voice_id"),
            )
        } catch (e: Exception) {
            null
        }
    }.toList()

    /**
     * The entries to install for a checkpoint that speaks [languages] and has the voices
     * [builtIn] of its own: the voices in one of [languages] and those with no language, as
     * upstream's servers pick them. Each comes back with its language as a two-letter code and
     * under the name it is installed as, which becomes a file name:
     *
     * - a voice the checkpoint has already, by name in any case, is left out, since it wins;
     * - two catalog voices of one name in different languages are told apart as `Name (fr)`;
     * - a name that cannot be a file name, or a file outside the catalog, is left out.
     */
    internal fun select(entries: List<Entry>, languages: List<String>, builtIn: Collection<String>): List<Entry> {
        val own = builtIn.map { it.lowercase(Locale.ROOT) }.toSet()
        val used = mutableSetOf<String>()
        val out = mutableListOf<Entry>()
        for (entry in entries) {
            val lang = entry.language?.let(::language)
            if (lang != null && lang !in languages) continue
            if (!isInside(entry.filename) || !entry.filename.endsWith(".safetensors")) continue
            val base = entry.name.trim()
            if (base.lowercase(Locale.ROOT) in own) continue
            val name = listOfNotNull(base, lang?.let { "$base ($it)" })
                .firstOrNull { isSafeName(it) && it.lowercase(Locale.ROOT) !in used && it.lowercase(Locale.ROOT) !in own }
                ?: continue
            used += name.lowercase(Locale.ROOT)
            out += entry.copy(name = name, language = lang)
        }
        return out
    }

    /** A language tag such as `en`, `en-US` or `pt_BR`, as the two- or three-letter language alone. */
    internal fun language(tag: String): String? =
        tag.trim().lowercase(Locale.ROOT).split('-', '_').first().takeIf { it.isNotEmpty() }

    /**
     * Whether [name] can be a voice's file name: no path, no hidden file, no control characters,
     * and short enough that `<name>.safetensors.part` stays within the 255-byte file name limit.
     */
    internal fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name.toByteArray(Charsets.UTF_8).size <= 200 && !name.startsWith('.') &&
            name.none { it == '/' || it == '\\' || it < ' ' || it == '\u007f' }

    /** Whether [path] is a plain relative path that stays inside the folder it is joined to. */
    internal fun isInside(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith('/') && '\\' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
}
