package io.github.enoky.androidtts

import io.github.enoky.androidtts.Hub.RepoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

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
}
