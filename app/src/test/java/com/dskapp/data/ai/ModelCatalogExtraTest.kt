package com.dskapp.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelCatalogExtraTest {

    @Test
    fun outputLimitLargeRequestedAllowsBelow() {
        // numbers >= requested are excluded; 4096 < 16384 so kept
        assertEquals(4096, ModelCatalog.outputLimitFrom("max_output_tokens (4096) is the cap", 16384))
    }

    @Test
    fun outputLimitNoneBelowThreshold() {
        assertNull(ModelCatalog.outputLimitFrom("max_tokens must be between 1 and 255; max_output_tokens", 8192))
    }

    @Test
    fun emptyPayloadBareArray() {
        assertEquals(emptyList<ModelEntry>(), ModelCatalog.parse("[]"))
    }

    @Test
    fun emptyPayloadDataObject() {
        assertEquals(emptyList<ModelEntry>(), ModelCatalog.parse("""{"data":[]}"""))
    }
}
