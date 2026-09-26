package io.github.youxufkhan.carozerra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClipCatalogTest {

    @Test
    fun catalogListsEveryBundledClipExactlyOnce() {
        val onDisk = File("../../assets/clips")
            .listFiles { f -> f.name.endsWith(".lkd") }!!
            .map { it.name }.sorted()
        assertEquals(83, onDisk.size)
        assertEquals(onDisk, ClipCatalog.all.sorted())
        assertEquals(ClipCatalog.all.size, ClipCatalog.all.toSet().size)
    }

    @Test
    fun everyClipHasACategory() {
        for (name in ClipCatalog.all) {
            assertTrue(name, ClipCatalog.categoryOf(name).isNotEmpty())
        }
    }

    @Test
    fun categoriesMatchTheWebPlayer() {
        assertEquals(
            listOf("Movies", "Backgrounds", "Stills", "Level Meters", "Color"),
            ClipCatalog.categories.map { it.name }
        )
    }
}
