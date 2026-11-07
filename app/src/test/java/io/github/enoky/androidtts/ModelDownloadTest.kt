package io.github.enoky.androidtts

import io.github.enoky.androidtts.ModelDownload.RepoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

class ModelDownloadTest {
    private fun file(path: String) = RepoFile(path, 1, null)
    private fun paths(files: List<RepoFile>) = files.map { it.path }

    @Test
    fun picksTheCheckpointFilesAndPrefersTheQ8Weights() {
        val repo = listOf(
            ".gitattributes", "README.md", "config.json", "tokenizer.json", "model.safetensors",
            "model.q8.gguf", "default-voice.safetensors",
        ).map(::file)
        assertEquals(
            listOf("config.json", "tokenizer.json", "model.q8.gguf", "default-voice.safetensors"),
            paths(ModelDownload.pick(repo)),
        )
    }

    @Test
    fun fallsBackToTheF32Weights() {
        val repo = listOf("config.json", "tokenizer.json", "model.safetensors").map(::file)
        assertEquals(listOf("config.json", "tokenizer.json", "model.safetensors"), paths(ModelDownload.pick(repo)))
    }

    @Test
    fun takesOneFilePerVoiceAndNothingNested() {
        val repo = listOf(
            "config.json", "tokenizer.json", "model.q8.gguf",
            "embeddings/zoey.safetensors", "embeddings/max.safetensors", "voices/zoey.safetensors",
            "voices/nested/deep.safetensors", "voices/notes.txt", "voices/.safetensors",
        ).map(::file)
        assertEquals(
            listOf("config.json", "tokenizer.json", "model.q8.gguf", "voices/zoey.safetensors", "embeddings/max.safetensors"),
            paths(ModelDownload.pick(repo)),
        )
    }

    @Test
    fun skipsTheDefaultVoiceFileWhenAVoiceIsCalledDefault() {
        val repo = listOf(
            "config.json", "tokenizer.json", "model.q8.gguf", "voices/default.safetensors", "default-voice.safetensors",
        ).map(::file)
        assertEquals(
            listOf("config.json", "tokenizer.json", "model.q8.gguf", "voices/default.safetensors"),
            paths(ModelDownload.pick(repo)),
        )
    }

    @Test
    fun saysWhatIsMissing() {
        val e = assertThrows(IOException::class.java) { ModelDownload.pick(listOf(file("config.json"))) }
        assertEquals(
            "gradium/phonon-31m-en has no tokenizer.json, weights (model.q8.gguf or model.safetensors)",
            e.message,
        )
    }

    @Test
    fun parsesTheHubAnswer() {
        // The shape of /api/models/{repo}/revision/{revision}?blobs=true.
        val json = """
            {"id": "gradium/phonon-31m-en", "sha": "8c64b55ec301728e2d1695bfea87a08a267d0c2d",
             "siblings": [
               {"rfilename": "config.json", "blobId": "aa", "size": 1234},
               {"rfilename": "model.q8.gguf", "blobId": "bb", "size": 35000000,
                "lfs": {"sha256": "ABCDEF", "size": 35000000, "pointerSize": 134}},
               {"rfilename": "tokenizer.json"}
             ]}
        """.trimIndent()
        val (commit, files) = ModelDownload.parse(json)
        assertEquals("8c64b55ec301728e2d1695bfea87a08a267d0c2d", commit)
        assertEquals(listOf("config.json", "model.q8.gguf", "tokenizer.json"), paths(files))
        assertEquals(1234L, files[0].size)
        assertNull(files[0].sha256)
        assertEquals(35000000L, files[1].size)
        assertEquals("ABCDEF", files[1].sha256)
        assertNull(files[2].size)
    }

    @Test
    fun usesThePinnedRevisionWhenTheHubNamesNoCommit() {
        val (commit, _) = ModelDownload.parse("""{"siblings": []}""")
        assertEquals(ModelDownload.REVISION, commit)
    }

    @Test
    fun formatsDigestsAsTheHubDoes() {
        val digest = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelDownload.hex(digest))
    }
}
