package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.sqrt

/**
 * `game_mkhighlight` from upstream `drawing.c`.
 *
 * The highlight and lowlight colours are part of the palette contract,
 * so the float arithmetic is the same order as C.
 */
internal object Colour {

    fun mkHighlight(ret: FloatArray, background: Int, highlight: Int, lowlight: Int) {
        val black = floatArrayOf(0.0f, 0.0f, 0.0f)
        val white = floatArrayOf(1.0f, 1.0f, 1.0f)
        val k = (sqrt(3.0) / 6.0).toFloat()

        var db = 0.0f
        if (lowlight >= 0) {
            db = distance(ret, background, black)
            if (db < k) {
                for (i in 0 until 3) ret[lowlight * 3 + i] = black[i]
                if (db == 0.0f) {
                    mix(black, white, k / sqrt(3.0).toFloat(), ret, background)
                } else {
                    mix(black, ret.copyOfRange(background * 3, background * 3 + 3), k / db, ret, background)
                }
            } else {
                mix(
                    ret.copyOfRange(background * 3, background * 3 + 3),
                    black,
                    k / db,
                    ret,
                    lowlight,
                )
            }
        }

        if (highlight < 0) return
        val dw = distance(ret, background, white)
        if (dw < k) {
            for (i in 0 until 3) ret[highlight * 3 + i] = white[i]
            if (dw == 0.0f) {
                mix(white, black, k / sqrt(3.0).toFloat(), ret, background)
            } else {
                mix(white, ret.copyOfRange(background * 3, background * 3 + 3), k / dw, ret, background)
            }
            if (lowlight >= 0) {
                mix(
                    ret.copyOfRange(background * 3, background * 3 + 3),
                    black,
                    k / db,
                    ret,
                    lowlight,
                )
            }
        } else {
            mix(
                ret.copyOfRange(background * 3, background * 3 + 3),
                white,
                k / dw,
                ret,
                highlight,
            )
        }
    }

    private fun distance(colours: FloatArray, index: Int, other: FloatArray): Float {
        var sum = 0.0f
        for (i in 0 until 3) {
            val delta = colours[index * 3 + i] - other[i]
            sum += delta * delta
        }
        return sqrt(sum.toDouble()).toFloat()
    }

    private fun mix(a: FloatArray, b: FloatArray, p: Float, out: FloatArray, index: Int) {
        for (i in 0 until 3) {
            out[index * 3 + i] = a[i] * (1.0f - p) + b[i] * p
        }
    }
}
