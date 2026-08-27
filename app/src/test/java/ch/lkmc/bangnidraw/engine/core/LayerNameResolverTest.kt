package ch.lkmc.bangnidraw.engine.core

import kotlin.test.assertEquals
import org.junit.Test

class LayerNameResolverTest {
    @Test
    fun `resolves the closed generated-name grammar`() {
        assertEquals("Flattened", resolve(LayerStack.FLATTENED_NAME))
        assertEquals("Layer 12", resolve("${LayerStack.DEFAULT_NAME_KEY} 12"))
        assertEquals("Ink copy", resolve("Ink ${LayerStack.COPY_SUFFIX_KEY}"))
        assertEquals(
            "Ink copy copy",
            resolve("Ink ${LayerStack.COPY_SUFFIX_KEY} ${LayerStack.COPY_SUFFIX_KEY}"),
        )
    }

    @Test
    fun `keeps malformed and unknown resource forms literal`() {
        val malformed = "${LayerStack.DEFAULT_NAME_KEY} twelve"
        val unknown = "@string/app_name"

        assertEquals(malformed, resolve(malformed))
        assertEquals(unknown, resolve(unknown))
    }

    private fun resolve(stored: String): String = LayerNameResolver.resolve(
        stored = stored,
        defaultName = { "Layer $it" },
        flattenedName = "Flattened",
        copySuffix = " copy",
    )
}
