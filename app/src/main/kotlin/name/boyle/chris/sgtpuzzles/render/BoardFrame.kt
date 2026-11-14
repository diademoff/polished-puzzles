package name.boyle.chris.sgtpuzzles.render

/**
 * One drawing primitive produced by the C engine.
 *
 * The model is the Kotlin side of `GameEngine.ViewCallbacks`. It is
 * deliberately free of Android and Compose types so it can be tested on
 * the JVM against the oracle traces.
 */
sealed interface DrawOp {
    data class FillRect(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val colour: Int,
    ) : DrawOp

    data class Line(
        val thickness: Float,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val colour: Int,
    ) : DrawOp

    data class Poly(
        val thickness: Float,
        val points: List<Int>,
        val ox: Int,
        val oy: Int,
        val line: Int,
        val fill: Int,
    ) : DrawOp

    data class Circle(
        val thickness: Float,
        val x: Float,
        val y: Float,
        val r: Float,
        val line: Int,
        val fill: Int,
    ) : DrawOp

    data class Text(
        val x: Int,
        val y: Int,
        val flags: Int,
        val size: Int,
        val colour: Int,
        val text: String,
    ) : DrawOp

    /** Replaces the current clip with this rectangle. */
    data class Clip(val x: Int, val y: Int, val w: Int, val h: Int) : DrawOp

    /** Restores the clip to the whole board. */
    data class Unclip(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) : DrawOp

    /** Replays a saved blitter region at a new position. */
    data class BlitterLoad(val id: Int, val x: Int, val y: Int) : DrawOp
}

/**
 * The pixels captured by `blitter_save`, kept as the operations that
 * produced them. A load replays the operations inside the saved region,
 * so the renderer does not need a mutable bitmap.
 */
data class BlitterSnapshot(
    val id: Int,
    val w: Int,
    val h: Int,
    val x: Int,
    val y: Int,
    val ops: List<DrawOp>,
)

/** One complete drawing pass. */
data class BoardFrame(
    val ops: List<DrawOp>,
    val blitters: Map<Int, BlitterSnapshot>,
)
