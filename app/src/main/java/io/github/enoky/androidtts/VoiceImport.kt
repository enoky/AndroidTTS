package io.github.enoky.androidtts

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A voice file the user imports, such as their own voice cloned in Gradium Studio ("Download for
 * Phonon") or with upstream's `scripts/clone-voice.py`. Voice files work with every Phonon
 * checkpoint. It is copied into the app, checked, and installed with [Model.addVoices], so a
 * file the library cannot load is rolled back like a bad catalog voice.
 */
object VoiceImport {
    /** Far more than a voice file needs: a voice is an embedding, not a model. */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** The largest safetensors header read: real headers are a few hundred bytes. */
    private const val MAX_HEADER = 1L * 1024 * 1024

    enum class NameProblem { INVALID, TAKEN }

    /** The voice name to suggest for a file called [fileName]: its name without the extension. */
    fun suggestName(fileName: String?): String {
        val stem = fileName?.substringAfterLast('/')?.substringBeforeLast(".safetensors")?.trim().orEmpty()
        return stem.takeIf { VoiceCatalog.isSafeName(it) } ?: "My voice"
    }

    /** Why [name] cannot name a new voice beside the voices [existing], or null when it can. */
    fun check(name: String, existing: Collection<String>): NameProblem? = when {
        !VoiceCatalog.isSafeName(name) -> NameProblem.INVALID
        existing.any { it.equals(name, ignoreCase = true) } -> NameProblem.TAKEN
        else -> null
    }

    /**
     * Whether [file] is laid out as safetensors: an 8-byte little-endian header length, then a
     * JSON header naming at least one tensor with a shape. Catches a recording or a document
     * picked by mistake; what the library makes of the tensors is left to the load.
     */
    internal fun isSafetensors(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { f ->
            if (f.length() < 10) return false
            val prefix = ByteArray(8).also { f.readFully(it) }
            var n = 0L
            for (i in 7 downTo 0) n = (n shl 8) or (prefix[i].toLong() and 0xff)
            if (n < 2 || n > MAX_HEADER || n > f.length() - 8) return false
            val header = ByteArray(n.toInt()).also { f.readFully(it) }
            val json = JSONObject(header.toString(Charsets.UTF_8))
            json.keys().asSequence().any { it != "__metadata__" && json.optJSONObject(it)?.has("shape") == true }
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Copy the file at [uri] into the app as the voice [name] and reload the model with it.
     * [fileName] is what the file was called, kept in the voice's record. Returns what happened,
     * for the user.
     */
    fun run(context: Context, uri: Uri, fileName: String?, name: String, report: (detail: String?, done: Long, total: Long?) -> Unit): String {
        val model = Model.await(context) ?: throw IOException(context.getString(R.string.voices_need_model))
        if (check(name, model.voices.names) == NameProblem.TAKEN) {
            throw IOException(context.getString(R.string.voice_name_taken, name))
        }
        val staging = File(context.filesDir, "voice.import")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val to = File(staging, "$name.safetensors")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException(context.getString(R.string.voice_file_unreadable))
            var done = 0L
            input.use { from ->
                to.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = from.read(buffer)
                        if (n < 0) break
                        done += n
                        if (done > MAX_BYTES) throw IOException(context.getString(R.string.voice_file_too_big))
                        out.write(buffer, 0, n)
                        report(name, done, null)
                    }
                }
            }
            if (!isSafetensors(to)) throw IOException(context.getString(R.string.voice_file_invalid))
            val entry = VoiceCatalog.Entry(name = name, filename = fileName ?: "$name.safetensors", imported = true)
            Model.addVoices(context, staging, listOf(entry))
            return context.getString(R.string.voice_imported, name)
        } finally {
            staging.deleteRecursively()
        }
    }
}
