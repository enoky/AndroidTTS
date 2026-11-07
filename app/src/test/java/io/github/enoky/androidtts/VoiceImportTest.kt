package io.github.enoky.androidtts

import io.github.enoky.androidtts.VoiceImport.NameProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceImportTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A safetensors file: little-endian header length, the JSON header, then the data. */
    private fun safetensors(header: String, data: ByteArray = ByteArray(16)): File {
        val json = header.toByteArray()
        val prefix = ByteArray(8) { i -> (json.size.toLong() shr (8 * i)).toByte() }
        return tmp.newFile().apply { writeBytes(prefix + json + data) }
    }

    @Test
    fun suggestsTheFileNameWithoutItsExtension() {
        assertEquals("Me", VoiceImport.suggestName("Me.safetensors"))
        assertEquals("a1b2c3", VoiceImport.suggestName("a1b2c3.safetensors"))
        assertEquals("Grandpa reading", VoiceImport.suggestName("Grandpa reading"))
        assertEquals("My voice", VoiceImport.suggestName(null))
        assertEquals("My voice", VoiceImport.suggestName(".safetensors"))
        assertEquals("My voice", VoiceImport.suggestName(".hidden.safetensors"))
    }

    @Test
    fun refusesNamesInUseInAnyCaseAndUnsafeNames() {
        val existing = listOf("default", "Zoey")
        assertNull(VoiceImport.check("Me", existing))
        assertEquals(NameProblem.TAKEN, VoiceImport.check("zoey", existing))
        assertEquals(NameProblem.TAKEN, VoiceImport.check("Default", existing))
        assertEquals(NameProblem.INVALID, VoiceImport.check("", existing))
        assertEquals(NameProblem.INVALID, VoiceImport.check("../me", existing))
        assertEquals(NameProblem.INVALID, VoiceImport.check(".me", existing))
    }

    @Test
    fun recognizesAVoiceFile() {
        // The two layouts the library reads: an embedding, or speaker latents.
        assertTrue(VoiceImport.isSafetensors(safetensors("""{"emb":{"dtype":"F32","shape":[1,4],"data_offsets":[0,16]}}""")))
        assertTrue(
            VoiceImport.isSafetensors(
                safetensors("""{"__metadata__":{"format":"pt"},"speaker_wavs":{"dtype":"F32","shape":[4],"data_offsets":[0,16]}}"""),
            ),
        )
    }

    @Test
    fun refusesWhatIsNotAVoiceFile() {
        val wav = tmp.newFile("me.wav").apply { writeBytes("RIFF$\u0000\u0000\u0000WAVEfmt ".toByteArray() + ByteArray(64)) }
        assertFalse(VoiceImport.isSafetensors(wav))
        assertFalse(VoiceImport.isSafetensors(tmp.newFile().apply { writeText("short") }))
        // Only metadata, no tensor.
        assertFalse(VoiceImport.isSafetensors(safetensors("""{"__metadata__":{"format":"pt"}}""")))
        // A header that is not JSON.
        assertFalse(VoiceImport.isSafetensors(safetensors("not json at all")))
        // A header length past the end of the file.
        val truncated = tmp.newFile().apply { writeBytes(byteArrayOf(0x7f, 0, 0, 0, 0, 0, 0, 0) + "{}".toByteArray()) }
        assertFalse(VoiceImport.isSafetensors(truncated))
    }
}
