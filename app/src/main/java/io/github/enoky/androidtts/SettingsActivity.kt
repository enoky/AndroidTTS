// Adapted from gradium-ai/xn-ptts android/tts-engine (MIT OR Apache-2.0), commit e5110afd, with
// the model download and the sample button added.
package io.github.enoky.androidtts

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.speech.tts.TextToSpeech
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The engine's settings, which the system text-to-speech settings open: what is installed, a
 * download of the Phonon 31m model, a way to install a checkpoint folder from the phone's
 * storage instead, and a sample to hear the engine.
 */
class SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var downloadButton: Button
    private lateinit var sampleButton: Button
    private lateinit var importButton: Button
    @Volatile private var importing: String? = null
    private var sampler: TextToSpeech? = null
    private val poll = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        }
        val subtitle = TextView(this).apply {
            text = getString(R.string.subtitle)
            setPadding(0, pad / 2, 0, pad)
        }
        status = TextView(this).apply { setPadding(0, 0, 0, pad) }
        downloadButton = Button(this).apply {
            setOnClickListener {
                if (ModelDownload.running) ModelDownload.cancel() else ModelDownload.start(this@SettingsActivity)
                refresh()
            }
        }
        sampleButton = Button(this).apply {
            text = getString(R.string.play_sample)
            setOnClickListener { playSample() }
        }
        importButton = Button(this).apply {
            text = getString(R.string.import_folder)
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK) }
        }
        val systemSettings = Button(this).apply {
            text = getString(R.string.system_settings)
            setOnClickListener { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        }
        val help = TextView(this).apply {
            text = getString(R.string.help)
            setPadding(0, pad, 0, 0)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            for (v in listOf(title, subtitle, status, downloadButton, sampleButton, systemSettings, importButton, help)) {
                addView(v, MATCH_PARENT, WRAP_CONTENT)
            }
        }
        // Keeps clear of the status and navigation bars, which the app draws under.
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })
        Model.load(this)
        // The system settings send INSTALL_TTS_DATA when the user asks for the voice data: start
        // the download straight away rather than asking again.
        if (savedInstanceState == null && intent?.action == TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA &&
            !Model.installed(this)
        ) {
            ModelDownload.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        status.removeCallbacks(poll)
    }

    override fun onDestroy() {
        sampler?.shutdown()
        sampler = null
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val tree = data?.data
        if (requestCode != PICK || resultCode != RESULT_OK || tree == null) return
        importing = getString(R.string.importing)
        refresh()
        thread {
            val error = try {
                Model.install(this) { into -> copyCheckpoint(tree, into) }
                Model.await(this)
                null
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            importing = null
            runOnUiThread {
                refresh()
                if (error != null) status.text = getString(R.string.import_failed, error)
            }
        }
    }

    private fun refresh() {
        val download = ModelDownload.state
        val busy = importing != null || download is ModelDownload.State.Running
        downloadButton.text = getString(
            when {
                download is ModelDownload.State.Running -> R.string.cancel_download
                Model.installed(this) -> R.string.download_again
                else -> R.string.download
            },
        )
        downloadButton.isEnabled = importing == null
        importButton.isEnabled = !busy
        sampleButton.isEnabled = !busy && Model.state is Model.State.Ready
        val model = when (val s = Model.state) {
            is Model.State.NotInstalled -> getString(R.string.not_installed)
            is Model.State.Loading -> getString(R.string.loading)
            is Model.State.Failed -> getString(R.string.load_failed, s.message)
            is Model.State.Ready -> getString(
                R.string.ready,
                s.model.languages.joinToString(", "),
                s.model.speakers.joinToString(", ").ifEmpty { "-" },
            )
        }
        status.text = importing ?: when (download) {
            is ModelDownload.State.Running -> progress(download)
            // A failed download leaves the model installed before, if any: say both.
            is ModelDownload.State.Failed -> getString(R.string.download_failed, download.message) + "\n\n" + model
            is ModelDownload.State.Idle -> model
        }
        status.removeCallbacks(poll)
        if (busy || Model.state is Model.State.Loading) status.postDelayed(poll, 500)
    }

    private fun progress(download: ModelDownload.State.Running): String {
        val file = download.file ?: return getString(R.string.downloading_start)
        val mb = { bytes: Long -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1e6) }
        val amount = download.total?.let { "${mb(download.done)} / ${mb(it)}" } ?: mb(download.done)
        return getString(R.string.downloading, file, amount)
    }

    /** Speak a sentence through this very engine, as any app would reach it. */
    private fun playSample() {
        sampler?.let {
            speakSample(it)
            return
        }
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(this, { result ->
            if (result == TextToSpeech.SUCCESS) {
                speakSample(tts)
            } else {
                // Bind afresh on the next tap rather than reuse an engine that never started.
                tts.shutdown()
                if (sampler === tts) sampler = null
                status.text = getString(R.string.sample_failed)
            }
        }, packageName)
        sampler = tts
    }

    private fun speakSample(tts: TextToSpeech) {
        // The model speaks English; the phone's own language may be another.
        tts.setLanguage(Locale.ENGLISH)
        tts.speak(getString(R.string.sample_text), TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    /** Copy the files a checkpoint folder needs from the picked folder into [into]. */
    private fun copyCheckpoint(tree: Uri, into: File) {
        val root = children(tree, DocumentsContract.getTreeDocumentId(tree))
        // The library loads the GGUF when both are there, so the other is not worth the space.
        val skip = if ("model.q8.gguf" in root) "model.safetensors" else null
        for ((name, entry) in root) {
            val (id, isDir) = entry
            when {
                !isDir && name in FILES && name != skip -> copy(tree, id, File(into, name))
                isDir && name in VOICE_DIRS -> for ((voice, child) in children(tree, id)) {
                    // The name comes from the document provider: keep it inside the voices folder.
                    if (!child.second && voice.endsWith(".safetensors") && '/' !in voice) {
                        copy(tree, child.first, File(into, "$name/$voice"))
                    }
                }
            }
        }
    }

    /** A folder's entries by name: each one's document id and whether it is a folder. */
    private fun children(tree: Uri, id: String): Map<String, Pair<String, Boolean>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val out = mutableMapOf<String, Pair<String, Boolean>>()
        contentResolver.query(uri, columns, null, null, null)?.use {
            while (it.moveToNext()) out[it.getString(1)] = it.getString(0) to (it.getString(2) == Document.MIME_TYPE_DIR)
        }
        return out
    }

    private fun copy(tree: Uri, id: String, to: File) {
        importing = getString(R.string.copying, to.name)
        runOnUiThread(::refresh)
        to.parentFile?.mkdirs()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val input = contentResolver.openInputStream(uri) ?: error("cannot read ${to.name}")
        input.use { from -> to.outputStream().use { from.copyTo(it, 1 shl 20) } }
    }

    private companion object {
        const val PICK = 1

        /** What a checkpoint folder holds. */
        val FILES = setOf("config.json", "tokenizer.json", "model.q8.gguf", "model.safetensors", "default-voice.safetensors")
        val VOICE_DIRS = setOf("voices", "embeddings")
    }
}
