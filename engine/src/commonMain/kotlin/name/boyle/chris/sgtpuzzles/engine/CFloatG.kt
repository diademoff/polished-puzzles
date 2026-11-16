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

/**
 * C `atof`: the longest numeric prefix of [text], converted to
 * `double` and then narrowed to `float` as `me->elapsed = atof(val)`
 * does. Trailing text is ignored (`3.5junk` is 3.5), an empty prefix is
 * zero, and an out-of-range value is an infinity or a zero.
 *
 * The live forms are the ones `%g` writes (decimal, optional sign,
 * optional exponent) plus the C99 forms `atof` accepts in a
 * hand-written save: `inf`, `infinity`, `nan`, and a hexadecimal float
 * such as `0x1.8p1`.
 */
internal fun cAtof(text: String): Float {
    var i = 0
    while (i < text.length && cIsSpace(text[i])) i++

    var negative = false
    if (i < text.length && (text[i] == '+' || text[i] == '-')) {
        negative = text[i] == '-'
        i++
    }

    val sign = if (negative) -1.0 else 1.0

    if (cStartsWithIgnoreCase(text, i, "infinity")) {
        return (sign * Double.POSITIVE_INFINITY).toFloat()
    }
    if (cStartsWithIgnoreCase(text, i, "inf")) {
        return (sign * Double.POSITIVE_INFINITY).toFloat()
    }
    if (cStartsWithIgnoreCase(text, i, "nan")) {
        return Float.NaN
    }

    if (i + 1 < text.length && text[i] == '0' && (text[i + 1] == 'x' || text[i + 1] == 'X')) {
        return (sign * hexMantissa(text, i + 2)).toFloat()
    }

    val start = i
    while (i < text.length && text[i] in '0'..'9') i++
    val integerDigits = i - start
    var fractionDigits = 0
    if (i < text.length && text[i] == '.') {
        val fractionStart = i
        i++
        while (i < text.length && text[i] in '0'..'9') i++
        fractionDigits = i - fractionStart - 1
    }
    if (integerDigits == 0 && fractionDigits == 0) return 0f

    // An exponent without at least one digit is not part of the number.
    var end = i
    if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
        var j = i + 1
        if (j < text.length && (text[j] == '+' || text[j] == '-')) j++
        val exponentStart = j
        while (j < text.length && text[j] in '0'..'9') j++
        if (j > exponentStart) end = j
    }

    val number = text.substring(start, end).toDoubleOrNull() ?: 0.0
    return (sign * number).toFloat()
}

/**
 * The value of a C99 hexadecimal float after its `0x` prefix. The
 * exponent is `p<N>` and is base two; a prefix without a hex digit
 * reads as the `0` that `strtod` consumes before it stops.
 */
private fun hexMantissa(text: String, start: Int): Double {
    var i = start
    var value = 0.0
    var digits = 0
    while (i < text.length && hexDigitValue(text[i]) >= 0) {
        value = value * 16.0 + hexDigitValue(text[i])
        i++
        digits++
    }
    if (i < text.length && text[i] == '.') {
        i++
        var scale = 1.0 / 16.0
        while (i < text.length && hexDigitValue(text[i]) >= 0) {
            value += hexDigitValue(text[i]) * scale
            scale /= 16.0
            i++
            digits++
        }
    }
    if (digits == 0) return 0.0

    if (i < text.length && (text[i] == 'p' || text[i] == 'P')) {
        var j = i + 1
        var exponentNegative = false
        if (j < text.length && (text[j] == '+' || text[j] == '-')) {
            exponentNegative = text[j] == '-'
            j++
        }
        val exponentStart = j
        var exponent = 0L
        while (j < text.length && text[j] in '0'..'9') {
            if (exponent < 1_000_000L) exponent = exponent * 10 + (text[j] - '0')
            j++
        }
        if (j > exponentStart) {
            if (exponentNegative) exponent = -exponent
            value *= 2.0.pow(exponent.coerceIn(-1_000_000L, 1_000_000L).toDouble())
        }
    }
    return value
}

private fun hexDigitValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

private fun cStartsWithIgnoreCase(text: String, start: Int, prefix: String): Boolean {
    if (start + prefix.length > text.length) return false
    for (k in prefix.indices) {
        if (text[start + k].lowercaseChar() != prefix[k]) return false
    }
    return true
}

private fun cIsSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
