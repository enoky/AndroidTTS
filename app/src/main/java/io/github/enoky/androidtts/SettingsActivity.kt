// Adapted from gradium-ai/xn-ptts android/tts-engine (MIT OR Apache-2.0), commit e5110afd, with
// the model and voice downloads, the voice list and the samples added.
package io.github.enoky.androidtts

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The engine's settings, which the system text-to-speech settings open: what is installed, the
 * model and voice downloads, the voices to hear and choose the default from, and a way to install
 * a checkpoint folder from the phone's storage instead. Android's own settings have no voice
 * picker for an engine, so the default voice is chosen here.
 */
class SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var modelButton: Button
    private lateinit var voicesButton: Button
    private lateinit var sampleButton: Button
    private lateinit var removeVoicesButton: Button
    private lateinit var importButton: Button
    private lateinit var voicesHeader: TextView
    private lateinit var voiceList: LinearLayout
    @Volatile private var importing: String? = null
    @Volatile private var removing = false
    private var sampler: TextToSpeech? = null
    private var samplerReady = false
    private val pendingSamples = mutableListOf<(TextToSpeech) -> Unit>()
    private var shownModel: Model.Loaded? = null
    private var shownPreferred: String? = null
    private val poll = Runnable { refresh() }
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        }
        val subtitle = TextView(this).apply {
            text = getString(R.string.subtitle)
            setPadding(0, pad / 2, 0, pad)
        }
        status = TextView(this).apply { setPadding(0, 0, 0, pad) }
        modelButton = button(null) {
            when (Downloads.running) {
                Downloads.Job.MODEL -> Downloads.cancel()
                null -> Downloads.downloadModel(this)
                else -> {}
            }
        }
        voicesButton = button(null) {
            when (Downloads.running) {
                Downloads.Job.VOICES -> Downloads.cancel()
                null -> Downloads.downloadVoices(this)
                else -> {}
            }
        }
        sampleButton = button(R.string.play_sample) { playSample() }
        val systemSettings = button(R.string.system_settings) { openTtsSettings() }
        voicesHeader = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad * 3 / 2, 0, pad / 4)
        }
        val voicesHint = TextView(this).apply {
            text = getString(R.string.voices_hint)
            setPadding(0, 0, 0, pad / 2)
        }
        voiceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        removeVoicesButton = button(R.string.remove_voices) { confirmRemoveVoices() }
        importButton = button(R.string.import_folder) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK)
        }
        val help = TextView(this).apply {
            text = getString(R.string.help)
            setPadding(0, pad, 0, 0)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            for (v in listOf(
                title, subtitle, status, modelButton, voicesButton, sampleButton, systemSettings,
                voicesHeader, voicesHint, voiceList, removeVoicesButton, importButton, help,
            )) {
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
            Downloads.downloadModel(this)
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
        val app = applicationContext
        thread {
            val error = try {
                Model.install(app) { into -> copyCheckpoint(tree, into) }
                Model.await(app)
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

    private fun button(label: Int?, onClick: () -> Unit) = Button(this).apply {
        label?.let { setText(it) }
        setOnClickListener {
            onClick()
            refresh()
        }
    }

    private fun refresh() {
        val download = Downloads.state
        val job = Downloads.running
        val ready = (Model.state as? Model.State.Ready)?.model
        val installed = Model.installed(this)
        val busy = importing != null || removing || job != null
        val other = importing != null || removing

        modelButton.text = getString(
            when {
                job == Downloads.Job.MODEL -> R.string.cancel_download
                installed -> R.string.download_again
                else -> R.string.download
            },
        )
        modelButton.isEnabled = !other && (job == null || job == Downloads.Job.MODEL)
        voicesButton.text = getString(if (job == Downloads.Job.VOICES) R.string.cancel_download else R.string.download_voices)
        voicesButton.isEnabled = !other && (job == Downloads.Job.VOICES || (job == null && ready != null))
        sampleButton.isEnabled = !busy && ready != null
        importButton.isEnabled = !busy
        removeVoicesButton.visibility = if ((ready?.voices?.catalog?.size ?: 0) > 0) View.VISIBLE else View.GONE
        removeVoicesButton.isEnabled = !busy

        val model = when (val s = Model.state) {
            is Model.State.NotInstalled -> getString(R.string.not_installed)
            is Model.State.Loading -> getString(R.string.loading)
            is Model.State.Failed -> getString(R.string.load_failed, s.message)
            is Model.State.Ready -> getString(R.string.ready, s.model.languages.joinToString(", "), s.model.voices.names.size)
        }
        status.text = when {
            importing != null -> importing
            removing -> getString(R.string.removing_voices)
            download is Downloads.State.Running -> progress(download)
            download is Downloads.State.Done -> download.message + "\n\n" + model
            // A failed download leaves what was installed before: say both.
            download is Downloads.State.Failed -> getString(
                if (download.job == Downloads.Job.MODEL) R.string.download_failed else R.string.voices_failed,
                download.message,
            ) + "\n\n" + model
            else -> model
        }
        showVoices(ready)
        status.removeCallbacks(poll)
        if (busy || Model.state is Model.State.Loading) status.postDelayed(poll, 500)
    }

    private fun progress(download: Downloads.State.Running): String {
        val title = getString(
            if (download.job == Downloads.Job.MODEL) R.string.downloading_model else R.string.downloading_voices,
        )
        val detail = download.detail ?: getString(R.string.downloading_start)
        val amount = when {
            download.total != null && download.total > 0 -> "${Storage.mb(download.done)} / ${Storage.mb(download.total)}"
            download.done > 0 -> Storage.mb(download.done)
            else -> null
        }
        return listOfNotNull(title, detail, amount).joinToString("\n")
    }

    /** List [model]'s voices, unless they are listed already: rebuilt only when they change. */
    private fun showVoices(model: Model.Loaded?) {
        val preferred = Model.preferredVoice(this)
        if (model === shownModel && preferred == shownPreferred) return
        shownModel = model
        shownPreferred = preferred
        voiceList.removeAllViews()
        if (model == null) {
            voicesHeader.text = getString(R.string.voices_header_none)
            return
        }
        voicesHeader.text = getString(R.string.voices_header, model.voices.names.size)
        for (lang in model.languages) {
            val names = model.voices.forLanguage(lang)
            if (names.isEmpty()) continue
            if (model.languages.size > 1) {
                voiceList.addView(TextView(this).apply {
                    text = Locale.forLanguageTag(lang).displayLanguage
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, pad / 2, 0, 0)
                })
            }
            val chosen = model.voices.defaultFor(lang, preferred)
            for (name in names) voiceList.addView(voiceRow(model, lang, name, name == chosen))
        }
    }

    /** One voice: tap it to hear it and make it the default, or ▶ to only hear it. */
    private fun voiceRow(model: Model.Loaded, lang: String, name: String, isDefault: Boolean): View {
        val locale = model.voices.locale(lang, name)
        val details = listOfNotNull(
            if (model.voices.isCatalog(name)) model.voices.description(name) else getString(R.string.voice_built_in),
            locale.displayCountry.takeIf { it.isNotEmpty() },
        )
        val radio = RadioButton(this).apply {
            text = if (details.isEmpty()) name else "$name\n${details.joinToString(" · ")}"
            isChecked = isDefault
            setOnClickListener {
                Model.setPreferredVoice(this@SettingsActivity, name)
                preview(lang, name)
                refresh()
            }
        }
        val play = Button(this).apply {
            text = getString(R.string.play_symbol)
            contentDescription = getString(R.string.play_voice, name)
            setOnClickListener { preview(lang, name) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad / 4, 0, pad / 4)
            addView(radio, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(play, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    /** Speak the sample through this very engine, as any app would, in the default voice. */
    private fun playSample() = withSampler { tts ->
        val lang = Model.languages(this).firstOrNull() ?: "en"
        // setLanguage picks the language's default voice, the one chosen below.
        tts.setLanguage(Locale.forLanguageTag(lang))
        tts.speak(Samples.sentence(lang) ?: getString(R.string.sample_text), TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    private fun preview(lang: String, name: String) = withSampler { tts ->
        val voice = tts.voices?.firstOrNull { it.name == "$lang-$name" }
        if (voice != null) tts.setVoice(voice) else tts.setLanguage(Locale.forLanguageTag(lang))
        tts.speak(Samples.introduce(lang, name), TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    /** Run [action] with a TextToSpeech bound to this engine, binding it on first use. */
    private fun withSampler(action: (TextToSpeech) -> Unit) {
        // The engine runs in this process, so its calls run on this (main) thread: while the
        // model loads they would wait for it, and freeze the screen.
        if (Model.state !is Model.State.Ready) return
        val existing = sampler
        if (existing != null && samplerReady) {
            action(existing)
            return
        }
        pendingSamples += action
        if (existing != null) return
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(this, { result ->
            if (sampler === tts && result == TextToSpeech.SUCCESS) {
                samplerReady = true
                pendingSamples.forEach { it(tts) }
                pendingSamples.clear()
            } else if (sampler === tts) {
                // Bind afresh on the next tap rather than reuse an engine that never started.
                tts.shutdown()
                sampler = null
                pendingSamples.clear()
                status.text = getString(R.string.sample_failed)
            }
        }, packageName)
        sampler = tts
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun confirmRemoveVoices() {
        val count = (Model.state as? Model.State.Ready)?.model?.voices?.catalog?.size ?: return
        AlertDialog.Builder(this)
            .setMessage(resources.getQuantityString(R.plurals.remove_voices_confirm, count, count))
            .setPositiveButton(R.string.remove) { _, _ -> removeVoices() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeVoices() {
        removing = true
        refresh()
        val app = applicationContext
        thread {
            val error = try {
                Model.removeCatalogVoices(app)
                null
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            removing = false
            runOnUiThread {
                refresh()
                if (error != null) status.text = getString(R.string.remove_failed, error)
            }
        }
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
