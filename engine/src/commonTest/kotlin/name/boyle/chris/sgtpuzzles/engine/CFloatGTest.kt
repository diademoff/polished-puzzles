package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Level: unit. The shared C `%g` formatter. The cases are the same as
 * `NetslideTest` pins for its private copy, so the extracted helper
 * cannot change the full net parameter text, and they include the
 * values a timed save uses for `TIME`.
 */
class CFloatGTest {

    @Test
    fun `clock values keep the C six digit form`() {
        assertEquals("0", cFloatG(0.0f))
        assertEquals("1", cFloatG(1.0f))
        assertEquals("0.5", cFloatG(0.5f))
        assertEquals("0.1", cFloatG(0.1f))
        assertEquals("0.9999", cFloatG(0.9999f))
        assertEquals("0.25", cFloatG(0.25f))
        assertEquals("12.5", cFloatG(12.5f))
        assertEquals("65", cFloatG(65.0f))
    }

    @Test
    fun `exponents and edge values match C`() {
        assertEquals("1e-05", cFloatG(1.0e-5f))
        assertEquals("1e+06", cFloatG(1000000.0f))
        assertEquals("-1.5", cFloatG(-1.5f))
        assertEquals("nan", cFloatG(Float.NaN))
        assertEquals("inf", cFloatG(Float.POSITIVE_INFINITY))
        assertEquals("-inf", cFloatG(Float.NEGATIVE_INFINITY))
    }
}
