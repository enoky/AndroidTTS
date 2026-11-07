package io.github.enoky.androidtts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class HubTest {
    @get:Rule val tmp = TemporaryFolder()

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
        val (commit, files) = Hub.parseListing(json, "main")
        assertEquals("8c64b55ec301728e2d1695bfea87a08a267d0c2d", commit)
        assertEquals(listOf("config.json", "model.q8.gguf", "tokenizer.json"), files.map { it.path })
        assertEquals(1234L, files[0].size)
        assertNull(files[0].sha256)
        assertEquals(35000000L, files[1].size)
        assertEquals("ABCDEF", files[1].sha256)
        assertNull(files[2].size)
    }

    @Test
    fun usesTheRevisionWhenTheHubNamesNoCommit() {
        assertEquals("abc123", Hub.parseListing("""{"siblings": []}""", "abc123").first)
    }

    @Test
    fun formatsDigestsAsTheHubDoes() {
        val digest = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hub.hex(digest))
    }

    @Test
    fun checksAFileAgainstItsSizeAndDigest() {
        val file = tmp.newFile("voice.safetensors").apply { writeText("abc") }
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(Hub.matches(file, 3, sha))
        assertTrue(Hub.matches(file, 3, sha.uppercase()))
        assertTrue(Hub.matches(file, null, null))
        assertFalse(Hub.matches(file, 4, sha))
        assertFalse(Hub.matches(file, 3, "0".repeat(64)))
        assertFalse(Hub.matches(tmp.root.resolve("missing"), null, null))
    }

    @Test
    fun encodesFilePathsInUrls() {
        assertEquals(
            "https://huggingface.co/gradium/phonon-voices/resolve/abc/voices/Kau%C3%A3%20Sol.safetensors",
            Hub.fileUrl("gradium/phonon-voices", "abc", "voices/Kauã Sol.safetensors"),
        )
    }
}
