package io.github.enoky.androidtts

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Downloads Phonon 31m (English, Apache 2.0) from Hugging Face and installs it with
 * [Model.install], so no computer is needed to get a model onto the phone.
 *
 * The repo is pinned to [REVISION], the commit upstream's own runtime loads by default
 * (`DEFAULT_MODEL` in xn-ptts `ptts/src/checkpoint.rs`), so every install gets the same files.
 * Files are picked by the same rules as upstream's Hub loader (`ptts-wasm/js/hub.js`), and each
 * one is checked against the size and SHA-256 the Hub lists for it.
 */
object ModelDownload {
    const val REPO = "gradium/phonon-31m-en"
    const val REVISION = "8c64b55ec301728e2d1695bfea87a08a267d0c2d"
    private const val HUB = "https://huggingface.co"

    /** Weights by preference: the q8 GGUF (fast on ARMv8.2 phones), else the f32 safetensors. */
    private val WEIGHTS = listOf("model.q8.gguf", "model.safetensors")
    private val VOICE_DIRS = listOf("voices", "embeddings")
    private const val DEFAULT_VOICE = "default-voice.safetensors"

    /** A file in the repo, with the size and SHA-256 the Hub lists, when it lists them. */
    class RepoFile(val path: String, val size: Long?, val sha256: String?)

    sealed class State {
        object Idle : State()
        class Running(val file: String?, val done: Long, val total: Long?) : State()
        class Failed(val message: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set

    @Volatile private var cancelled = false

    val running: Boolean get() = state is State.Running

    /** Download and install the model in the background, unless a download is running. */
    @Synchronized
    fun start(context: Context) {
        if (running) return
        cancelled = false
        state = State.Running(null, 0, null)
        val app = context.applicationContext
        thread(name = "model-download") {
            state = try {
                Model.install(app) { into -> fetch(into) }
                Model.await(app)
                State.Idle
            } catch (t: Throwable) {
                // Throwable, not Exception: anything left uncaught would leave the state Running.
                if (cancelled) State.Idle else State.Failed(t.message ?: t.toString())
            }
        }
    }

    /** Stop a running download. The model installed before, if any, stays. */
    fun cancel() {
        cancelled = true
    }

    private fun fetch(into: File) {
        val (commit, listed) = parse(text("$HUB/api/models/$REPO/revision/$REVISION?blobs=true"))
        val files = pick(listed)
        val total = files.map { it.size }.takeIf { sizes -> sizes.all { it != null } }?.sumOf { it!! }
        var done = 0L
        for (file in files) {
            state = State.Running(file.path, done, total)
            download("$HUB/$REPO/resolve/$commit/${encode(file.path)}", file, File(into, file.path)) { n ->
                done += n
                state = State.Running(file.path, done, total)
            }
        }
    }

    /**
     * The commit the Hub answered for and the files it lists, from its
     * `/api/models/{repo}/revision/{revision}?blobs=true` answer.
     */
    internal fun parse(json: String): Pair<String, List<RepoFile>> {
        val info = JSONObject(json)
        val siblings = info.optJSONArray("siblings") ?: throw IOException("the Hub listed no files for $REPO")
        val files = (0 until siblings.length()).map { i ->
            val entry = siblings.getJSONObject(i)
            val lfs = entry.optJSONObject("lfs")
            val size = entry.optLong("size", -1).takeIf { it >= 0 } ?: lfs?.optLong("size", -1)?.takeIf { it >= 0 }
            RepoFile(entry.getString("rfilename"), size, lfs?.optString("sha256")?.takeIf { it.isNotEmpty() })
        }
        return info.optString("sha").ifEmpty { REVISION } to files
    }

    /** The files a checkpoint folder needs, out of everything in the repo. */
    internal fun pick(files: List<RepoFile>): List<RepoFile> {
        val byPath = files.associateBy { it.path }
        val chosen = mutableListOf<RepoFile>()
        val missing = mutableListOf<String>()
        for (name in listOf("config.json", "tokenizer.json")) {
            byPath[name]?.let { chosen += it } ?: run { missing += name }
        }
        WEIGHTS.firstNotNullOfOrNull { byPath[it] }?.let { chosen += it }
            ?: run { missing += "weights (${WEIGHTS.joinToString(" or ")})" }
        if (missing.isNotEmpty()) throw IOException("$REPO has no ${missing.joinToString(", ")}")

        // One file per voice name, `voices/` winning over `embeddings/`, and nothing nested: the
        // name ends up as a path inside the app's storage.
        val voices = LinkedHashMap<String, RepoFile>()
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

    internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun encode(path: String) =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun text(url: String): String {
        val connection = open(url)
        try {
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, file: RepoFile, to: File, progress: (Long) -> Unit) {
        to.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        val connection = open(url)
        try {
            connection.inputStream.use { input ->
                to.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        if (cancelled) throw IOException("cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        length += n
                        progress(n.toLong())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        file.size?.let { check(length == it) { "${file.path} is $length bytes, expected $it" } }
        file.sha256?.let { check(hex(digest.digest()) == it.lowercase()) { "${file.path} failed its SHA-256 check" } }
    }

    /** A GET that answered 200, following the Hub's redirects to its file storage. */
    private fun open(url: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "AndroidTTS")
        val code = try {
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw IOException("cannot reach ${URI(url).host}: ${e.message}", e)
        }
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IOException(
                when (code) {
                    401, 403, 404 -> "$REPO is missing or not public (HTTP $code)"
                    else -> "HTTP $code from ${connection.url.host}"
                },
            )
        }
        return connection
    }
}
