package io.github.enoky.androidtts

import io.github.enoky.androidtts.VoiceCatalog.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceSetTest {
    private val voices = VoiceSet(
        listOf("Zoey", "default", "Kauã", "ava", "Anyone"),
        mapOf(
            "Zoey" to Entry("Zoey", "voices/a.safetensors", "en", "en-us", "Bright"),
            "Kauã" to Entry("Kauã", "voices/b.safetensors", "pt", "pt-BR"),
            "ava" to Entry("ava", "voices/c.safetensors", "en", "en_GB"),
            "Anyone" to Entry("Anyone", "voices/d.safetensors"),
        ),
    )

    @Test
    fun offersEachLanguageItsVoicesWithTheBuiltInDefaultFirst() {
        assertEquals(listOf("default", "Anyone", "ava", "Zoey"), voices.forLanguage("en"))
        assertEquals(listOf("default", "Anyone", "Kauã"), voices.forLanguage("pt"))
        assertEquals(listOf("default", "Anyone"), voices.forLanguage("fr"))
    }

    @Test
    fun prefersTheChosenVoiceWhereItSpeaks() {
        assertEquals("Zoey", voices.defaultFor("en", "Zoey"))
        assertEquals("default", voices.defaultFor("pt", "Zoey"))
        assertEquals("default", voices.defaultFor("en", null))
        assertEquals("default", voices.defaultFor("en", "Removed since"))
    }

    @Test
    fun fallsBackToTheFirstVoiceWithoutABuiltInDefault() {
        val set = VoiceSet(listOf("Zoey", "Bea"), emptyMap())
        assertEquals("Bea", set.defaultFor("en", null))
        assertNull(VoiceSet(emptyList(), emptyMap()).defaultFor("en", null))
    }

    @Test
    fun tellsImportedVoicesFromCatalogOnes() {
        val set = VoiceSet(
            listOf("default", "Me", "Zoey"),
            mapOf(
                "Me" to Entry("Me", "me.safetensors", imported = true),
                "Zoey" to Entry("Zoey", "voices/a.safetensors", "en"),
            ),
        )
        assertTrue(set.isImported("Me"))
        assertFalse(set.isCatalog("Me"))
        assertTrue(set.isCatalog("Zoey"))
        assertFalse(set.isImported("default"))
        assertFalse(set.isCatalog("default"))
        assertEquals(1, set.catalogCount)
        // A cloned voice has no language in the catalog's sense: it is offered in every one.
        assertEquals(listOf("default", "Me"), set.forLanguage("fr"))
        assertEquals("Me", set.defaultFor("fr", "Me"))
    }

    @Test
    fun givesTheCatalogsLocaleWhenItIsTheLanguagesOwn() {
        assertEquals(Locale.forLanguageTag("en-US"), voices.locale("en", "Zoey"))
        assertEquals(Locale.forLanguageTag("en-GB"), voices.locale("en", "ava"))
        assertEquals(Locale.forLanguageTag("pt-BR"), voices.locale("pt", "Kauã"))
        assertEquals(Locale.forLanguageTag("en"), voices.locale("en", "default"))
        // A French model speaking with Zoey: she is offered as French, not American.
        assertEquals(Locale.forLanguageTag("fr"), voices.locale("fr", "Zoey"))
    }
}
