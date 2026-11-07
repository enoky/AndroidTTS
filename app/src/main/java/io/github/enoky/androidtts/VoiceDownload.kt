package io.github.enoky.androidtts

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Fetches the catalog voices the installed model speaks and installs them with
 * [Model.addVoices]. Built to be stopped and started again:
 *
 * - voices installed already, with the size the Hub lists, are not fetched again;
 * - each fetched file is checked against the Hub's size and SHA-256 and kept in a staging folder
 *   until all are in, so a run that is stopped or killed resumes from the files it checked;
 * - a voice that cannot be fetched is skipped and reported, as upstream's servers do, rather
 *   than failing the rest;
 * - the model reloads once, at the end, and goes back to its old voices if it cannot load.
 */
object VoiceDownload {
    private const val TAG = "VoiceDownload"

    /** Run the download, reporting each step; returns what happened, for the user. */
    fun run(context: Context, cancelled: () -> Boolean, report: (detail: String?, done: Long, total: Long?) -> Unit): String {
        val res = context.resources
        val model = Model.await(context) ?: throw IOException(context.getString(R.string.voices_need_model))
        val dir = Model.dir(context)
        val recorded = VoiceStore.read(dir)

        report(context.getString(R.string.voices_reading_catalog), 0, null)
        val catalog = Hub.text(Hub.fileUrl(VoiceCatalog.REPO, VoiceCatalog.REVISION, VoiceCatalog.FILE), cancelled)
        val (commit, files) = Hub.list(VoiceCatalog.REPO, VoiceCatalog.REVISION, cancelled)
        val remote = files.associateBy { it.path }
        val wanted = VoiceCatalog.select(VoiceCatalog.parse(catalog), model.languages, reserved(model.voices.names, recorded))
        if (wanted.isEmpty()) {
            return context.getString(R.string.voices_none_in_catalog, model.languages.joinToString(", "))
        }
        val missing = wanted.filter { e ->
            val installed = VoiceStore.file(dir, e.name)
            val size = remote[e.filename]?.size
            !(recorded[e.name]?.imported == false && installed.isFile && (size == null || installed.length() == size))
        }
        if (missing.isEmpty()) return res.getQuantityString(R.plurals.voices_all_installed, wanted.size, wanted.size)

        val staging = File(context.filesDir, "voices.staging").apply { mkdirs() }
        val total = missing.sumOf { remote[it.filename]?.size ?: 0L }
        Storage.require(staging, total)
        var done = 0L
        val fetched = mutableListOf<VoiceCatalog.Entry>()
        val failed = mutableListOf<String>()
        for ((i, entry) in missing.withIndex()) {
            if (cancelled()) throw Hub.Cancelled()
            val file = remote[entry.filename]
            val to = File(staging, "${entry.name}.safetensors")
            val step = context.getString(R.string.voices_step, i + 1, missing.size, entry.name)
            report(step, done, total)
            try {
                // Fetched and checked by a run that was stopped before installing it.
                if (file?.sha256 != null && Hub.matches(to, file.size, file.sha256)) {
                    done += to.length()
                } else {
                    val url = Hub.fileUrl(VoiceCatalog.REPO, commit, entry.filename)
                    Hub.download(url, to, file?.size, file?.sha256, cancelled) { n ->
                        done += n
                        report(step, done, total)
                    }
                }
                fetched += entry
            } catch (e: Hub.Cancelled) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "voice ${entry.name} (${entry.filename}) failed", e)
                failed += entry.name
            }
        }

        report(context.getString(R.string.voices_installing), done, total)
        val added = if (fetched.isEmpty()) emptyList() else Model.addVoices(context, staging, fetched)
        staging.deleteRecursively()

        val bytes = added.sumOf { remote[it.filename]?.size ?: 0L }
        val lines = mutableListOf<String>()
        if (added.isNotEmpty()) lines += res.getQuantityString(R.plurals.voices_added, added.size, added.size, Storage.mb(bytes))
        if (failed.isNotEmpty()) {
            val names = failed.take(5).joinToString(", ") + if (failed.size > 5) ", …" else ""
            lines += res.getQuantityString(R.plurals.voices_failed, failed.size, failed.size, names)
        }
        return lines.joinToString("\n")
    }

    /**
     * The voice names of [loaded] that no catalog voice may take: the checkpoint's own and the
     * ones the user imported. Only voices the catalog itself installed can be replaced by it.
     */
    internal fun reserved(loaded: List<String>, recorded: Map<String, VoiceCatalog.Entry>): List<String> =
        loaded.filter { recorded[it]?.imported != false }
}
