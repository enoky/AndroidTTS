package io.github.enoky.androidtts

import java.io.File
import java.io.IOException

/**
 * Phonon 31m (English, Apache 2.0) on Hugging Face: which files make its checkpoint folder, and
 * downloading them into one. [Downloads] runs it and installs the result with [Model.install].
 *
 * The repo is pinned to [REVISION], the commit upstream's own runtime loads by default
 * (`DEFAULT_MODEL` in xn-ptts `ptts/src/checkpoint.rs`), so every install gets the same files.
 * Files are picked by the same rules as upstream's Hub loader (`ptts-wasm/js/hub.js`).
 */
object ModelDownload {
    const val REPO = "gradium/phonon-31m-en"
    const val REVISION = "8c64b55ec301728e2d1695bfea87a08a267d0c2d"

    /** Weights by preference: the q8 GGUF (fast on ARMv8.2 phones), else the f32 safetensors. */
    private val WEIGHTS = listOf("model.q8.gguf", "model.safetensors")
    private val VOICE_DIRS = listOf("voices", "embeddings")
    private const val DEFAULT_VOICE = "default-voice.safetensors"

    /** Download the checkpoint into [into], reporting each file and the bytes done so far. */
    fun fetch(into: File, cancelled: () -> Boolean, report: (file: String?, done: Long, total: Long?) -> Unit) {
        report(null, 0, null)
        val (commit, listed) = Hub.list(REPO, REVISION, cancelled)
        val files = pick(listed)
        val total = files.map { it.size }.takeIf { sizes -> sizes.all { it != null } }?.sumOf { it!! }
        total?.let { Storage.require(into, it) }
        var done = 0L
        for (file in files) {
            report(file.path, done, total)
            Hub.download(Hub.fileUrl(REPO, commit, file.path), File(into, file.path), file.size, file.sha256, cancelled) { n ->
                done += n
                report(file.path, done, total)
            }
        }
    }

    /** The files a checkpoint folder needs, out of everything in the repo. */
    internal fun pick(files: List<Hub.RepoFile>): List<Hub.RepoFile> {
        val byPath = files.associateBy { it.path }
        val chosen = mutableListOf<Hub.RepoFile>()
        val missing = mutableListOf<String>()
        for (name in listOf("config.json", "tokenizer.json")) {
            byPath[name]?.let { chosen += it } ?: run { missing += name }
        }
        WEIGHTS.firstNotNullOfOrNull { byPath[it] }?.let { chosen += it }
            ?: run { missing += "weights (${WEIGHTS.joinToString(" or ")})" }
        if (missing.isNotEmpty()) throw IOException("$REPO has no ${missing.joinToString(", ")}")

        // One file per voice name, `voices/` winning over `embeddings/`, and nothing nested: the
        // name ends up as a path inside the app's storage.
        val voices = LinkedHashMap<String, Hub.RepoFile>()
        for (dir in VOICE_DIRS) {
            for (file in files.sortedBy { it.path }) {
                if (!file.path.startsWith("$dir/") || !file.path.endsWith(".safetensors")) continue
                val name = file.path.removePrefix("$dir/").removeSuffix(".safetensors")
                if (name.isEmpty() || '/' in name || name in voices) continue
                voices[name] = file
            }
        }
        chosen += voices.values
        if ("default" !in voices) byPath[DEFAULT_VOICE]?.let { chosen += it }
        return chosen
    }
}

/** Free-space checks before a download, so a full phone fails early with a clear message. */
object Storage {
    /** Throws unless [dir]'s file system has room for [bytes] and a 10% margin. */
    fun require(dir: File, bytes: Long) {
        dir.mkdirs()
        val needed = bytes + bytes / 10
        val free = dir.usableSpace
        if (free in 1 until needed) {
            throw IOException("not enough free space: needs ${mb(needed)}, ${mb(free)} free")
        }
    }

    fun mb(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1e6)
}
