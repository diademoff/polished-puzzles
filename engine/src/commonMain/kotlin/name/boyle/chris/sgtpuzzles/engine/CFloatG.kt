package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/**
 * C `%g` with the default precision of six significant digits.
 *
 * The function is shared by the `b` field of a full parameter text
 * (`Net.kt`) and by the `TIME` record of a timed game, because both
 * call `sprintf` with `%g` in C. The value is a float promoted to
 * double, as C passes it to `sprintf`.
 */
internal fun cFloatG(value: Float): String {
    if (value != value) return "nan"
    if (value == Float.POSITIVE_INFINITY) return "inf"
    if (value == Float.NEGATIVE_INFINITY) return "-inf"
    if (value == 0.0f) return "0"

    val negative = value < 0.0f
    val absolute = abs(value.toDouble())
    var exponent = floor(log10(absolute)).toInt()
    val scaled = absolute / 10.0.pow(exponent.toDouble())
    var mantissa = round(scaled * 100000.0).toLong()
    if (mantissa >= 1000000L) {
        mantissa /= 10L
        exponent++
    }
    val digits = mantissa.toString().padStart(6, '0')

    val body = if (exponent < -4 || exponent >= 6) {
        val fraction = digits.substring(1).trimEnd('0')
        val mantissaText = if (fraction.isEmpty()) {
            digits.substring(0, 1)
        } else {
            digits.substring(0, 1) + "." + fraction
        }
        val sign = if (exponent < 0) '-' else '+'
        val expText = abs(exponent).toString().padStart(2, '0')
        "${mantissaText}e$sign$expText"
    } else if (exponent >= 0) {
        val integerPart = digits.substring(0, exponent + 1)
        val fraction = digits.substring(exponent + 1).trimEnd('0')
        if (fraction.isEmpty()) integerPart else "$integerPart.$fraction"
    } else {
        val fraction = ("0".repeat(-exponent - 1) + digits).trimEnd('0')
        if (fraction.isEmpty()) "0" else "0.$fraction"
    }

    return if (negative) "-$body" else body
}
