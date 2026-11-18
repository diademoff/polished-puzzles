package name.boyle.chris.sgtpuzzles.render

import name.boyle.chris.sgtpuzzles.backend.GameEngine

/**
 * Records the drawing primitives of one engine pass into a [BoardFrame].
 *
 * This is the testable, platform-free half of the Compose renderer. It
 * implements the same `ViewCallbacks` seam that the C engine calls, so a
 * JVM test can feed it the oracle trace and compare the result. The
 * Compose half is `BoardCanvas`.
 *
 * A pass may draw only what changed, and the frontend must keep the
 * rest (ADR 0010), so the recorded scene is the passes added together.
 * A pass that paints over the whole window makes the earlier passes
 * invisible, and the engines repaint the window on every redraw, so the
 * scene is normally one pass. Without that trim it grew with every
 * move and every Compose frame replayed the whole session.
 *
 * Blitters are stored as the operations that produced the saved region.
 * A load replays them inside the saved rectangle, which reproduces the
 * pixels without a mutable bitmap.
 */
class BoardRecorder : GameEngine.ViewCallbacks {

    private companion object {
        const val MIN_SCENE_OPS = 20_000
    }

    /** Operations from previous passes; the engine updates incrementally. */
    private val scene = ArrayList<DrawOp>()
    private val current = ArrayList<DrawOp>()
    private val blitters = LinkedHashMap<Int, BlitterSnapshot>()
    private var nextBlitter = 0
    private var baseSize = 0

    /** The area the scene paints, for the trim. */
    private var sceneBounds: Bounds? = null

    /** Called when one drawing pass is complete. */
    var onFrame: (BoardFrame) -> Unit = {}

    /** Called when the engine asks the frontend to repaint. */
    var onInvalidate: () -> Unit = {}

    override fun startDraw() {
        current.clear()
    }

    override fun endDraw() {
        if (paintsOverScene()) {
            scene.clear()
            sceneBounds = null
        }
        val firstOfScene = scene.isEmpty()
        scene.addAll(current)
        for (op in current) sceneBounds = union(sceneBounds, boundsOf(op))
        current.clear()
        if (firstOfScene) baseSize = scene.size
        onFrame(BoardFrame(scene.toList(), LinkedHashMap(blitters)))
    }

    /**
     * True when this pass paints a rectangle of colour over everything
     * the scene has drawn.
     *
     * The engines clear the window on a new drawstate, and the shell
     * forces that before every redraw, so normally every pass clears
     * over the one before it and the scene holds one pass. A pass that
     * draws only a change fails the test and is added, which is what the
     * game expects.
     */
    private fun paintsOverScene(): Boolean {
        val bounds = sceneBounds ?: return false
        return current.any { op ->
            op is DrawOp.FillRect && op.w > 0 && op.h > 0 &&
                op.x <= bounds.left &&
                op.y <= bounds.top &&
                op.x + op.w >= bounds.right &&
                op.y + op.h >= bounds.bottom
        }
    }

    private fun union(a: Bounds?, b: Bounds?): Bounds? = when {
        a == null -> b
        b == null -> a
        else -> Bounds(
            minOf(a.left, b.left),
            minOf(a.top, b.top),
            maxOf(a.right, b.right),
            maxOf(a.bottom, b.bottom),
        )
    }

    /**
     * The area one operation paints. An outline falls half a thickness
     * outside its points and a glyph can reach past its box; a pixel of
     * either at the window edge is redrawn by the next full pass, so
     * both are measured by their geometry. Over-estimating only makes
     * the trim less likely, never wrong.
     */
    private fun boundsOf(op: DrawOp): Bounds? = when (op) {
        is DrawOp.FillRect -> Bounds(
            op.x.toFloat(),
            op.y.toFloat(),
            (op.x + op.w).toFloat(),
            (op.y + op.h).toFloat(),
        )

        is DrawOp.Line -> Bounds(
            minOf(op.x1, op.x2),
            minOf(op.y1, op.y2),
            maxOf(op.x1, op.x2),
            maxOf(op.y1, op.y2),
        )

        is DrawOp.Poly -> {
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            var i = 0
            while (i + 1 < op.points.size) {
                val x = (op.points[i] + op.ox).toFloat()
                val y = (op.points[i + 1] + op.oy).toFloat()
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
                i += 2
            }
            if (left > right) null else Bounds(left, top, right, bottom)
        }

        is DrawOp.Circle -> Bounds(
            op.x - op.r,
            op.y - op.r,
            op.x + op.r,
            op.y + op.r,
        )

        is DrawOp.Text -> {
            val size = op.size.toFloat()
            val width = 0.75f * size * maxOf(1, op.text.length)
            Bounds(
                op.x - width,
                op.y - 1.5f * size,
                op.x + width,
                op.y + 0.75f * size,
            )
        }

        is DrawOp.BlitterLoad -> {
            val snapshot = blitters[op.id]
            if (snapshot == null) null else Bounds(
                op.x.toFloat(),
                op.y.toFloat(),
                (op.x + snapshot.w).toFloat(),
                (op.y + snapshot.h).toFloat(),
            )
        }

        is DrawOp.Clip, is DrawOp.Unclip -> null
    }

    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * True when the scene has grown far beyond one board. The trim
     * leaves one pass, so this is a backstop for a game that redraws
     * neither the window nor its own changes.
     */
    val shouldCompact: Boolean
        get() = scene.size > maxOf(MIN_SCENE_OPS, baseSize * 4)

    /** Drops the accumulated scene, for example after a resize. */
    fun reset() {
        scene.clear()
        current.clear()
        sceneBounds = null
        baseSize = 0
    }

    private fun allOps(): List<DrawOp> = scene + current

    override fun blitterAlloc(w: Int, h: Int): Int {
        val id = nextBlitter++
        allocate(id, w, h)
        return id
    }

    /** Allocates a blitter under an id chosen by the caller. */
    fun allocate(id: Int, w: Int, h: Int) {
        require(w > 0 && h > 0) { "blitter $id has an empty size: $w x $h" }
        blitters[id] = BlitterSnapshot(id, w, h, 0, 0, emptyList())
    }

    override fun blitterFree(i: Int) {
        blitters.remove(i)
    }

    override fun blitterSave(i: Int, x: Int, y: Int) {
        val previous = blitters[i] ?: return
        blitters[i] = previous.copy(x = x, y = y, ops = allOps())
    }

    override fun blitterLoad(i: Int, x: Int, y: Int) {
        if (blitters.containsKey(i)) {
            current += DrawOp.BlitterLoad(i, x, y)
        }
    }

    override fun clipRect(x: Int, y: Int, w: Int, h: Int) {
        current += DrawOp.Clip(x, y, w, h)
    }

    override fun unClip(left: Int, top: Int, right: Int, bottom: Int) {
        current += DrawOp.Unclip(left, top, right, bottom)
    }

    override fun drawCircle(
        thickness: Float,
        x: Float,
        y: Float,
        r: Float,
        lineColour: Int,
        fillColour: Int,
    ) {
        current += DrawOp.Circle(thickness, x, y, r, lineColour, fillColour)
    }

    override fun drawLine(
        thickness: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        colour: Int,
    ) {
        current += DrawOp.Line(thickness, x1, y1, x2, y2, colour)
    }

    override fun drawPoly(
        thickness: Float,
        points: IntArray,
        ox: Int,
        oy: Int,
        line: Int,
        fill: Int,
    ) {
        current += DrawOp.Poly(thickness, points.toList(), ox, oy, line, fill)
    }

    override fun drawText(x: Int, y: Int, flags: Int, size: Int, colour: Int, text: String) {
        current += DrawOp.Text(x, y, flags, size, colour, text)
    }

    override fun fillRect(x: Int, y: Int, w: Int, h: Int, colour: Int) {
        current += DrawOp.FillRect(x, y, w, h, colour)
    }

    override val defaultBackgroundColour: Int
        get() = 0xFF808080.toInt()

    override fun postInvalidateOnAnimation() {
        onInvalidate()
    }
}
