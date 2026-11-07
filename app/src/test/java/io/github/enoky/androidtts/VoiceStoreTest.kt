package io.github.enoky.androidtts

import io.github.enoky.androidtts.VoiceCatalog.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zoey = Entry("Zoey", "voices/a1.safetensors", "en", "en-us", "Bright", "a1")
    private val ava = Entry("Ava", "voices/b2.safetensors", "en")

    private fun stage(dir: File, vararg names: String) = names.forEach {
        File(dir, "$it.safetensors").apply { parentFile?.mkdirs(); writeText("voice $it") }
    }

    @Test
    fun addsVoicesBesideTheCheckpointsOwnAndRecordsThem() {
        val model = tmp.newFolder("model")
        val staging = tmp.newFolder("staging")
        stage(File(model, "voices"), "builtin")
        stage(staging, "Zoey", "Ava")
        val added = VoiceStore.add(model, staging, listOf(zoey, ava, Entry("NotStaged", "voices/x.safetensors")))
        assertEquals(listOf("Zoey", "Ava"), added.map { it.name })
        assertEquals("voice Zoey", VoiceStore.file(model, "Zoey").readText())
        assertFalse(File(staging, "Zoey.safetensors").exists())
        assertEquals(mapOf("Ava" to ava, "Zoey" to zoey), VoiceStore.read(model))
        assertTrue(VoiceStore.file(model, "builtin").isFile)
    }

    @Test
    fun replacesAVoiceOfTheSameName() {
        val model = tmp.newFolder("model")
        val staging = tmp.newFolder("staging")
        stage(staging, "Zoey")
        VoiceStore.add(model, staging, listOf(zoey))
        File(staging, "Zoey.safetensors").writeText("newer")
        VoiceStore.add(model, staging, listOf(zoey.copy(description = "Brighter")))
        assertEquals("newer", VoiceStore.file(model, "Zoey").readText())
        assertEquals("Brighter", VoiceStore.read(model).getValue("Zoey").description)
    }

    @Test
    fun forgetsRecordsWhoseFileIsGone() {
        val model = tmp.newFolder("model")
        val staging = tmp.newFolder("staging")
        stage(staging, "Zoey", "Ava")
        VoiceStore.add(model, staging, listOf(zoey, ava))
        VoiceStore.file(model, "Ava").delete()
        assertEquals(setOf("Zoey"), VoiceStore.read(model).keys)
    }

    @Test
    fun removesOnlyCatalogVoices() {
        val model = tmp.newFolder("model")
        val staging = tmp.newFolder("staging")
        stage(File(model, "voices"), "builtin")
        stage(staging, "Zoey", "Ava")
        VoiceStore.add(model, staging, listOf(zoey, ava))
        VoiceStore.remove(model, listOf("Zoey", "builtin"))
        assertFalse(VoiceStore.file(model, "Zoey").exists())
        assertTrue(VoiceStore.file(model, "builtin").isFile)
        assertEquals(setOf("Ava"), VoiceStore.read(model).keys)
        VoiceStore.remove(model, listOf("Ava"))
        assertFalse(File(model, VoiceStore.METADATA).exists())
    }

    @Test
    fun carriesCatalogVoicesToANewCheckpointUnlessItHasThem() {
        val old = tmp.newFolder("old")
        val new = tmp.newFolder("new")
        val staging = tmp.newFolder("staging")
        stage(File(old, "voices"), "builtin")
        stage(staging, "Zoey", "Ava")
        VoiceStore.add(old, staging, listOf(zoey, ava))
        stage(File(new, "voices"), "Ava")
        VoiceStore.carryOver(old, new)
        assertEquals("voice Zoey", VoiceStore.file(new, "Zoey").readText())
        assertEquals(setOf("Zoey"), VoiceStore.read(new).keys)
        // The new checkpoint's own Ava stays its own, and the old checkpoint's voices stay behind.
        assertEquals("voice Ava", VoiceStore.file(new, "Ava").readText())
        assertFalse(VoiceStore.file(new, "builtin").exists())
    }

    @Test
    fun recordsImportedVoicesApartAndCarriesThemOver() {
        val model = tmp.newFolder("model")
        val staging = tmp.newFolder("staging")
        val me = Entry("Me", "a1b2c3.safetensors", imported = true)
        stage(staging, "Me", "Zoey")
        VoiceStore.add(model, staging, listOf(me, zoey))
        assertEquals(me, VoiceStore.read(model).getValue("Me"))
        assertFalse(VoiceStore.read(model).getValue("Zoey").imported)

        val new = tmp.newFolder("new")
        VoiceStore.carryOver(model, new)
        assertEquals(me, VoiceStore.read(new).getValue("Me"))
        VoiceStore.remove(new, listOf("Me"))
        assertEquals(setOf("Zoey"), VoiceStore.read(new).keys)
    }

    @Test
    fun readsNothingFromABrokenRecord() {
        val model = tmp.newFolder("model")
        File(model, VoiceStore.METADATA).writeText("{not json")
        assertEquals(emptyMap<String, Entry>(), VoiceStore.read(model))
    }
}
