package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.utils.sanitizeFileName
import org.junit.Assert.assertEquals
import org.junit.Test

class JxlUtilsTest {

    @Test
    fun sanitizeFileName_replacesIllegalCharacters() {
        assertEquals("WH-1000XM4", sanitizeFileName("WH-1000XM4"))
        assertEquals("Buds_Pro_2", sanitizeFileName(" Buds Pro 2 "))
        assertEquals("a_b_c", sanitizeFileName("a/b:c"))
        assertEquals("", sanitizeFileName("   "))
    }
}
