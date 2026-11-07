// Adapted from gradium-ai/xn-ptts android/tts-engine (MIT OR Apache-2.0), commit e5110afd, with
// catalog voices, reloading and a preferred voice added.
package io.github.enoky.androidtts

import android.content.Context
import android.system.Os
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The installed checkpoint and the model loaded from it, shared by the service and the settings
 * screen, which run in one process. The model is loaded once, off the main thread, and kept until
 * its files change.
 */
object Model {
    private const val TAG = "PhononModel"

    /** The languages the runtime normalizes text for. */
    val SUPPORTED = listOf(
        "en", "fr", "de", "es", "pt", "ar", "hi", "ko", "pl", "sv", "fi", "no", "it", "nl", "ja", "zh",
        "da", "tr",
    )

    /** Loaded models. Calls into one must hold its lock: the library takes one call at a time. */
    class Loaded(val tts: PhononTTS, val voices: VoiceSet, val languages: List<String>)

    sealed class State {
        object NotInstalled : State()
        object Loading : State()
        class Ready(val model: Loaded) : State()
        class Failed(val message: String) : State()
    }

    @Volatile var state: State = State.NotInstalled
        private set

    private val loader = Executors.newSingleThreadExecutor()
    private var pending: Future<*>? = null

    fun dir(context: Context) = File(context.filesDir, "model")

    /** Whether a checkpoint folder is in place. It may still fail to load. */
    fun installed(context: Context): Boolean {
        val dir = dir(context)
        return File(dir, "config.json").isFile && File(dir, "tokenizer.json").isFile &&
            (File(dir, "model.q8.gguf").isFile || File(dir, "model.safetensors").isFile)
    }

    /**
     * The languages the installed checkpoint speaks, read from its config without loading it.
     * A config that lists none is taken to speak them all.
     */
    fun languages(context: Context): List<String> {
        if (!installed(context)) return emptyList()
        val listed = try {
            JSONObject(File(dir(context), "config.json").readText()).optJSONArray("languages")
        } catch (e: Exception) {
            null
        }
        val names = (0 until (listed?.length() ?: 0)).map { listed!!.getString(it) }
        return if (names.isEmpty()) SUPPORTED else SUPPORTED.filter { it in names }
    }

    /** Start loading the installed checkpoint, unless it is loaded or loading already. */
    @Synchronized
    fun load(context: Context): Future<*>? {
        if (state is State.Ready || pending?.isDone == false) return pending
        if (!installed(context)) {
            state = State.NotInstalled
            return null
        }
        val dir = dir(context)
        val languages = languages(context)
        state = State.Loading
        pending = loader.submit {
            state = try {
                // Before the first load: the library's thread pool reads it once. One thread per
                // core is too many on phones that mix fast and slow cores (the Galaxy S21 has
                // 1 + 3 fast cores and 4 slow ones). A value already set wins.
                Os.setenv("RAYON_NUM_THREADS", THREADS.toString(), false)
                // Text is normalized per request with setLanguage; this is only the first one.
                val tts = PhononTTS(dir.path, languages.firstOrNull() ?: "none")
                State.Ready(Loaded(tts, VoiceSet(tts.voices, VoiceStore.read(dir)), languages))
            } catch (t: Throwable) {
                Log.e(TAG, "loading $dir failed", t)
                State.Failed(t.message ?: t.toString())
            }
        }
        return pending
    }

    /** The loaded model, waiting for a load in progress. Null when none could be loaded. */
    fun await(context: Context): Loaded? {
        try {
            load(context)?.get(LOAD_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "waiting for the model failed", e)
        }
        return (state as? State.Ready)?.model
    }

    /**
     * Replace the installed checkpoint with what [fill] writes into an empty folder, then load
     * it. [fill] runs on the calling thread, which must not be the main one. This is the one way
     * in: the folder import (SettingsActivity) and the download (ModelDownload) are both a [fill].
     * If [fill] throws, the installed checkpoint is left as it was. Downloaded catalog voices
     * move to the new checkpoint, unless it has voices of the same names.
     */
    fun install(context: Context, fill: (File) -> Unit) {
        val staging = File(context.filesDir, "model.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            fill(staging)
            for (name in listOf("config.json", "tokenizer.json")) {
                require(File(staging, name).isFile) { "the folder has no $name" }
            }
            require(File(staging, "model.q8.gguf").isFile || File(staging, "model.safetensors").isFile) {
                "the folder has no model.q8.gguf or model.safetensors"
            }
            // Held across the swap, so a load() from the service cannot start on the old folder
            // between unloading it and replacing it.
            synchronized(this) {
                unload()
                VoiceStore.carryOver(dir(context), staging)
                dir(context).deleteRecursively()
                check(staging.renameTo(dir(context))) { "could not move the checkpoint into place" }
            }
        } finally {
            staging.deleteRecursively()
        }
        load(context)
    }

    /**
     * Install the catalog voices [entries], whose files are in [staging] as `<name>.safetensors`,
     * and reload the model with them. The library loads every voice file with the model, so one
     * it cannot read would stop the engine: if the model fails to load with the new voices, they
     * are removed again, the model goes back to the voices it had, and this throws.
     */
    fun addCatalogVoices(context: Context, staging: File, entries: List<VoiceCatalog.Entry>): List<VoiceCatalog.Entry> {
        val dir = dir(context)
        val added = synchronized(this) {
            unload()
            try {
                VoiceStore.add(dir, staging, entries)
            } finally {
                load(context)
            }
        }
        // Decided on the load's outcome, however long it takes: await() gives up after a minute,
        // and a slow load that then failed would leave the engine broken with the new voices in.
        if (settled(context)) return added
        val error = (state as? State.Failed)?.message ?: "it did not load"
        synchronized(this) {
            unload()
            VoiceStore.remove(dir, added.map { it.name })
            load(context)
        }
        await(context)
        throw IOException("the model did not load with the new voices ($error), so they were removed")
    }

    /** Wait for the load in progress to end, with no time limit, and say whether it succeeded. */
    private fun settled(context: Context): Boolean {
        try {
            load(context)?.get()
        } catch (e: Exception) {
            Log.w(TAG, "waiting for the model failed", e)
        }
        return state is State.Ready
    }

    /** Delete every downloaded catalog voice and reload. Returns how many there were. */
    fun removeCatalogVoices(context: Context): Int {
        val dir = dir(context)
        val count = synchronized(this) {
            unload()
            val names = VoiceStore.read(dir).keys
            VoiceStore.remove(dir, names)
            load(context)
            names.size
        }
        await(context)
        return count
    }

    /** Free the loaded model, once whatever is speaking with it has finished. */
    @Synchronized
    private fun unload() {
        pending?.get()
        (state as? State.Ready)?.model?.let { synchronized(it) { it.tts.close() } }
        state = State.NotInstalled
        pending = null
    }

    /** The voice chosen in the app, spoken when a request names none and it speaks the language. */
    fun preferredVoice(context: Context): String? = prefs(context).getString(PREF_VOICE, null)

    fun setPreferredVoice(context: Context, name: String) {
        prefs(context).edit().putString(PREF_VOICE, name).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A language as Android names it, two or three letters, to one of [SUPPORTED], or null. */
    fun code(language: String?): String? {
        if (language.isNullOrEmpty()) return null
        val iso3 = when (language.lowercase(Locale.ROOT)) {
            "fre" -> "fra"
            "ger" -> "deu"
            else -> try {
                Locale.forLanguageTag(language).isO3Language
            } catch (e: Exception) {
                return null
            }
        }
        return SUPPORTED.firstOrNull { Locale.forLanguageTag(it).isO3Language == iso3 }
    }

    /** Upstream's measured default for phones. Measure on the phone before raising it. */
    private const val THREADS = 2
    private const val LOAD_TIMEOUT_S = 60L
    private const val PREFS = "settings"
    private const val PREF_VOICE = "default_voice"
}
