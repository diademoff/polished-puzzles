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

    /** Called when one drawing pass is complete. */
    var onFrame: (BoardFrame) -> Unit = {}

    /** Called when the engine asks the frontend to repaint. */
    var onInvalidate: () -> Unit = {}

    override fun startDraw() {
        current.clear()
    }

    override fun endDraw() {
        val firstOfScene = scene.isEmpty()
        scene.addAll(current)
        current.clear()
        if (firstOfScene) baseSize = scene.size
        onFrame(BoardFrame(scene.toList(), LinkedHashMap(blitters)))
    }

    /**
     * True when incremental updates have grown the scene far beyond one
     * full board. The owner should reset and force a full redraw; drawing
     * the whole history every frame would get slower with every move.
     */
    val shouldCompact: Boolean
        get() = scene.size > maxOf(MIN_SCENE_OPS, baseSize * 4)

    /** Drops the accumulated scene, for example after a resize. */
    fun reset() {
        scene.clear()
        current.clear()
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
