package io.github.enoky.androidtts

import io.github.enoky.androidtts.VoiceCatalog.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCatalogTest {
    @Test
    fun parsesTheCatalogAndSkipsWhatIsNotAVoice() {
        // Upstream's own test catalog (ptts/src/voice_catalog.rs), plus lines a robust reader skips.
        val jsonl = """
            {"filename": "voices/a1.safetensors", "name": "Zoey", "voice_id": "a1", "language": "en", "locale": "en-us"}

            {"filename": "voices/b2.safetensors", "name": "Kauã", "voice_id": "b2", "language": "pt", "description": "Calm"}
            {"filename": "voices/c3.safetensors", "name": "Lucía-Sol"}
            not json
            {"name": "No file"}
            {"filename": "voices/d4.safetensors", "name": "Null fields", "language": null, "description": "  "}
        """.trimIndent()
        val voices = VoiceCatalog.parse(jsonl)
        assertEquals(listOf("Zoey", "Kauã", "Lucía-Sol", "Null fields"), voices.map { it.name })
        assertEquals(Entry("Zoey", "voices/a1.safetensors", "en", "en-us", null, "a1"), voices[0])
        assertEquals("Calm", voices[1].description)
        assertNull(voices[2].language)
        assertNull(voices[3].language)
        assertNull(voices[3].description)
    }

    @Test
    fun selectsTheVoicesInTheModelsLanguagesAndThoseWithNone() {
        val entries = listOf(
            Entry("Zoey", "voices/a.safetensors", "en"),
            Entry("Kauã", "voices/b.safetensors", "pt"),
            Entry("Ava", "voices/c.safetensors", "en-GB"),
            Entry("Anyone", "voices/d.safetensors"),
        )
        val chosen = VoiceCatalog.select(entries, listOf("en"), emptyList())
        assertEquals(listOf("Zoey", "Ava", "Anyone"), chosen.map { it.name })
        assertEquals(listOf("en", "en", null), chosen.map { it.language })
    }

    @Test
    fun leavesOutVoicesTheCheckpointHasInAnyCase() {
        val entries = listOf(Entry("Default", "voices/a.safetensors", "en"), Entry("Zoey", "voices/b.safetensors", "en"))
        assertEquals(listOf("Zoey"), VoiceCatalog.select(entries, listOf("en"), listOf("default")).map { it.name })
    }

    @Test
    fun neverTakesTheNameOfAnImportedVoice() {
        val recorded = mapOf(
            "Zoey" to Entry("Zoey", "zoey-clone.safetensors", imported = true),
            "Ava" to Entry("Ava", "voices/b.safetensors", "en"),
        )
        val reserved = VoiceDownload.reserved(listOf("default", "Zoey", "Ava"), recorded)
        assertEquals(listOf("default", "Zoey"), reserved)
        val entries = listOf(Entry("Zoey", "voices/a.safetensors", "en"), Entry("Ava", "voices/b.safetensors", "en"))
        // Ava came from the catalog, so it may be fetched again; the user's Zoey stays theirs.
        assertEquals(listOf("Ava"), VoiceCatalog.select(entries, listOf("en"), reserved).map { it.name })
    }

    @Test
    fun tellsApartOneNameInTwoLanguages() {
        val entries = listOf(
            Entry("Alex", "voices/a.safetensors", "en"),
            Entry("alex", "voices/b.safetensors", "fr"),
            Entry("Alex", "voices/c.safetensors", "fr"),
            Entry("Alex", "voices/d.safetensors"),
        )
        val chosen = VoiceCatalog.select(entries, listOf("en", "fr"), emptyList())
        assertEquals(listOf("Alex", "alex (fr)"), chosen.map { it.name })
        assertEquals(listOf("voices/a.safetensors", "voices/b.safetensors"), chosen.map { it.filename })
    }

    @Test
    fun refusesNamesAndPathsThatEscape() {
        val entries = listOf(
            Entry("../evil", "voices/a.safetensors", "en"),
            Entry(".hidden", "voices/b.safetensors", "en"),
            Entry("Outside", "../secret.safetensors", "en"),
            Entry("Absolute", "/etc/voice.safetensors", "en"),
            Entry("NotAVoice", "voices/e.json", "en"),
            Entry("Long" + "x".repeat(300), "voices/f.safetensors", "en"),
            Entry("Fine", "voices/g.safetensors", "en"),
        )
        assertEquals(listOf("Fine"), VoiceCatalog.select(entries, listOf("en"), emptyList()).map { it.name })
    }

    @Test
    fun checksNamesAndPaths() {
        assertTrue(VoiceCatalog.isSafeName("Lucía-Sol"))
        assertTrue(VoiceCatalog.isSafeName("Zoey (fr)"))
        assertFalse(VoiceCatalog.isSafeName(""))
        assertFalse(VoiceCatalog.isSafeName("a/b"))
        assertFalse(VoiceCatalog.isSafeName("a\\b"))
        assertFalse(VoiceCatalog.isSafeName("tab\there"))
        assertFalse(VoiceCatalog.isSafeName("声".repeat(70)))
        assertTrue(VoiceCatalog.isInside("voices/a1.safetensors"))
        assertFalse(VoiceCatalog.isInside("voices/../a.safetensors"))
        assertFalse(VoiceCatalog.isInside("voices//a.safetensors"))
        assertFalse(VoiceCatalog.isInside("./a.safetensors"))
        assertEquals("pt", VoiceCatalog.language("pt_BR"))
        assertEquals("en", VoiceCatalog.language(" EN-us "))
    }
}
