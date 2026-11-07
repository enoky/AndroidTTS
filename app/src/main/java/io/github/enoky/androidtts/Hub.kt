package io.github.enoky.androidtts

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Hugging Face Hub access shared by the model and voice downloads: a repo's file listing, and
 * downloads checked against the size and SHA-256 the Hub lists, retried when the network fails.
 */
object Hub {
    private const val HUB = "https://huggingface.co"

    /** Attempts per request: the first, then retries after 2 and 4 seconds. */
    private const val ATTEMPTS = 3

    /** A file in a repo, with the size and SHA-256 the Hub lists, when it lists them. */
    class RepoFile(val path: String, val size: Long?, val sha256: String?)

    /** An HTTP answer other than 200. */
    class HttpStatus(val code: Int, message: String) : IOException(message)

    /** Thrown once the caller asks to stop. */
    class Cancelled : IOException("cancelled")

    /** The commit [revision] of [repo] points at, and the files the repo has there. */
    fun list(repo: String, revision: String, cancelled: () -> Boolean): Pair<String, List<RepoFile>> =
        parseListing(text("$HUB/api/models/$repo/revision/$revision?blobs=true", cancelled), revision)

    /** Where to download [path] of [repo] at [commit]. */
    fun fileUrl(repo: String, commit: String, path: String) = "$HUB/$repo/resolve/$commit/${encode(path)}"

    /**
     * The commit and files from the Hub's `/api/models/{repo}/revision/{revision}?blobs=true`
     * answer. A missing commit is taken to be [revision].
     */
    internal fun parseListing(json: String, revision: String): Pair<String, List<RepoFile>> {
        val info = JSONObject(json)
        val siblings = info.optJSONArray("siblings") ?: throw IOException("the Hub listed no files")
        val files = (0 until siblings.length()).map { i ->
            val entry = siblings.getJSONObject(i)
            val lfs = entry.optJSONObject("lfs")
            val size = entry.optLong("size", -1).takeIf { it >= 0 } ?: lfs?.optLong("size", -1)?.takeIf { it >= 0 }
            RepoFile(entry.getString("rfilename"), size, lfs?.optString("sha256")?.takeIf { it.isNotEmpty() })
        }
        return info.optString("sha").ifEmpty { revision } to files
    }

    /** The body of [url] as UTF-8 text. */
    fun text(url: String, cancelled: () -> Boolean): String = retrying(cancelled) {
        val connection = open(url)
        try {
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Download [url] to [to], checked against [size] and [sha256] when they are known. The bytes
     * go to a `.part` file that replaces [to] only once checked, so [to] is never left half
     * written. [progress] gets each step's byte count, negative when a failed attempt is undone.
     */
    fun download(
        url: String,
        to: File,
        size: Long?,
        sha256: String?,
        cancelled: () -> Boolean,
        progress: (Long) -> Unit,
    ) {
        to.parentFile?.mkdirs()
        val part = File(to.path + ".part")
        retrying(cancelled) {
            var written = 0L
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val connection = open(url)
                try {
                    connection.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                if (cancelled()) throw Cancelled()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                written += n
                                progress(n.toLong())
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                if (size != null && written != size) throw IOException("${to.name} is $written bytes, expected $size")
                if (sha256 != null && hex(digest.digest()) != sha256.lowercase()) {
                    throw IOException("${to.name} failed its SHA-256 check")
                }
                if (!part.renameTo(to)) throw IOException("could not save ${to.name}")
            } catch (e: IOException) {
                part.delete()
                progress(-written)
                throw e
            }
        }
    }

    /** Whether [file] is there with the [size] and [sha256] given, each checked when known. */
    fun matches(file: File, size: Long?, sha256: String?): Boolean {
        if (!file.isFile || (size != null && file.length() != size)) return false
        return sha256 == null || sha256(file) == sha256.lowercase()
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun encode(path: String) =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /**
     * [block], tried again after a network failure, a server error or a failed check. A refusal
     * (4xx other than 408 and 429) is final, and so is [Cancelled].
     */
    private fun <T> retrying(cancelled: () -> Boolean, block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (e is Cancelled || cancelled()) throw Cancelled()
                if (e is HttpStatus && e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                if (attempt >= ATTEMPTS) throw e
                pause(1000L shl attempt, cancelled)
                attempt++
            }
        }
    }

    private fun pause(ms: Long, cancelled: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (cancelled()) throw Cancelled()
            Thread.sleep(100)
        }
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
            val where = connection.url.let { it.host + it.path }
            connection.disconnect()
            throw HttpStatus(code, "HTTP $code from $where")
        }
        return connection
    }
}
