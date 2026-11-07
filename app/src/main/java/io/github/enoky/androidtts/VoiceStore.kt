package io.github.enoky.androidtts

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The catalog voices installed in a checkpoint folder: each one's file in `voices/`, named after
 * the voice, which is how the native library finds voices, and what the catalog says about it
 * in [METADATA]. A voice in `voices/` without a record is the checkpoint's own.
 */
object VoiceStore {
    const val METADATA = "catalog-voices.json"

    fun file(modelDir: File, name: String) = File(modelDir, "voices/$name.safetensors")

    /** The recorded catalog voices whose file is in place, by name. */
    fun read(modelDir: File): Map<String, VoiceCatalog.Entry> {
        val text = try {
            File(modelDir, METADATA).takeIf { it.isFile }?.readText() ?: return emptyMap()
        } catch (e: IOException) {
            return emptyMap()
        }
        val list = try {
            JSONObject(text).getJSONArray("voices")
        } catch (e: Exception) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, VoiceCatalog.Entry>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val name = o.optString("name").takeIf { VoiceCatalog.isSafeName(it) } ?: continue
            if (!file(modelDir, name).isFile) continue
            out[name] = VoiceCatalog.Entry(
                name = name,
                filename = o.optString("filename"),
                language = o.text("language"),
                locale = o.text("locale"),
                description = o.text("description"),
                voiceId = o.text("voice_id"),
            )
        }
        return out
    }

    /** Record [entries] as the folder's catalog voices, replacing the record atomically. */
    fun write(modelDir: File, entries: Collection<VoiceCatalog.Entry>) {
        val target = File(modelDir, METADATA)
        if (entries.isEmpty()) {
            target.delete()
            return
        }
        val list = JSONArray()
        for (e in entries.sortedBy { it.name.lowercase(Locale.ROOT) }) {
            list.put(
                JSONObject()
                    .put("name", e.name)
                    .put("filename", e.filename)
                    .putOpt("language", e.language)
                    .putOpt("locale", e.locale)
                    .putOpt("description", e.description)
                    .putOpt("voice_id", e.voiceId),
            )
        }
        val json = JSONObject().put("catalog", "${VoiceCatalog.REPO}@${VoiceCatalog.REVISION}").put("voices", list)
        val tmp = File(modelDir, "$METADATA.tmp")
        tmp.writeText(json.toString(1))
        if (!tmp.renameTo(target)) throw IOException("could not save $METADATA")
    }

    /**
     * Move the voice files in [staging], each named `<name>.safetensors`, into [modelDir] as
     * [entries], replacing voices of the same name, and record them beside the ones already
     * there. Entries without a staged file are left out.
     */
    fun add(modelDir: File, staging: File, entries: Collection<VoiceCatalog.Entry>): List<VoiceCatalog.Entry> {
        val before = read(modelDir).values
        val added = mutableListOf<VoiceCatalog.Entry>()
        File(modelDir, "voices").mkdirs()
        try {
            for (e in entries) {
                val from = File(staging, "${e.name}.safetensors")
                if (!from.isFile) continue
                if (!from.renameTo(file(modelDir, e.name))) throw IOException("could not install the voice ${e.name}")
                added += e
            }
        } finally {
            // Recorded even when a move fails part way, so no catalog voice is ever taken for one
            // of the checkpoint's own.
            write(modelDir, before.filter { old -> added.none { it.name == old.name } } + added)
        }
        return added
    }

    /** Delete the catalog voices [names], files and records. */
    fun remove(modelDir: File, names: Collection<String>) {
        val recorded = read(modelDir)
        for (name in names) if (name in recorded) file(modelDir, name).delete()
        write(modelDir, recorded.values.filter { it.name !in names })
    }

    /**
     * Move the catalog voices of [from], the checkpoint folder being replaced, into [into], the
     * one replacing it, unless it has a voice of that name, so a new checkpoint keeps them.
     */
    fun carryOver(from: File, into: File) {
        val kept = mutableListOf<VoiceCatalog.Entry>()
        for (e in read(from).values) {
            val target = file(into, e.name)
            if (target.exists()) continue
            target.parentFile?.mkdirs()
            if (file(from, e.name).renameTo(target)) kept += e
        }
        if (kept.isNotEmpty()) write(into, kept)
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
}
