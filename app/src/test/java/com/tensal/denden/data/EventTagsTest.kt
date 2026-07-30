package com.tensal.denden.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EventTagsTest {
    @Test
    fun tagsAreSafeTrimmedDeduplicatedAndUnicodePreserving() {
        assertEquals(
            listOf("urgent", "維運"),
            parseEventTags("[\" urgent \",\"維運\",\"urgent\",\"\"]")
        )
        assertEquals(emptyList<String>(), parseEventTags("{broken"))
    }
}
