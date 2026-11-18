package name.boyle.chris.sgtpuzzles.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/** Test tag for the one composable that paints the board. */
const val BOARD_CANVAS_TAG = "board-canvas"

/**
 * Draws a [BoardFrame] on a Compose Canvas.
 *
 * The frame comes from [BoardRecorder]; the engine still owns all
 * geometry. This composable only maps primitives to Compose draw calls.
 * The transform is the matrix that the old Bitmap renderer used, so a
 * board looks the same at every zoom level.
 *
 * Blitter saves are rendered once into an offscreen image and cached.
 * Replaying the saved operations directly would be exponential: an
 * animation saves the current scene, and the next save contains the
 * previous load.
 */
@Composable
fun BoardCanvas(
    frame: BoardFrame?,
    colours: IntArray,
    matrix: FloatArray,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val layoutDirection = LocalLayoutDirection.current
    val blitterCache = remember { HashMap<Int, CachedBlitter>() }
    val rendering = remember { HashSet<BlitterSnapshot>() }
    val background = colours.getOrElse(0) { 0xFF808080.toInt() }

    Canvas(modifier) {
        drawRect(color = Color(background), size = size)
        if (frame == null || frame.ops.isEmpty()) return@Canvas
        withTransform({ transform(Matrix(matrix)) }) {
            drawFrame(frame, colours, textMeasurer, density, layoutDirection, blitterCache, rendering)
        }
    }
}

private data class CachedBlitter(
    val snapshot: BlitterSnapshot,
    val image: ImageBitmap,
)

private fun DrawScope.drawFrame(
    frame: BoardFrame,
    colours: IntArray,
    textMeasurer: TextMeasurer,
    density: Float,
    layoutDirection: LayoutDirection,
    blitterCache: MutableMap<Int, CachedBlitter>,
    rendering: MutableSet<BlitterSnapshot>,
    depth: Int = 0,
) {
    var clip: Rect? = null
    for (op in frame.ops) {
        when (op) {
            is DrawOp.Clip -> clip = Rect(
                op.x.toFloat(),
                op.y.toFloat(),
                (op.x + op.w).toFloat(),
                (op.y + op.h).toFloat(),
            )

            is DrawOp.Unclip -> clip = Rect(
                op.left.toFloat(),
                op.top.toFloat(),
                op.right.toFloat(),
                op.bottom.toFloat(),
            )

            is DrawOp.BlitterLoad -> withOptionalClip(clip) {
                val snapshot = frame.blitters[op.id] ?: return@withOptionalClip
                if (depth >= MAX_BLITTER_DEPTH) return@withOptionalClip
                val image = blitterImage(
                    snapshot, frame, colours, textMeasurer, density,
                    layoutDirection, blitterCache, rendering,
                ) ?: return@withOptionalClip
                clipRect(
                    left = op.x.toFloat(),
                    top = op.y.toFloat(),
                    right = (op.x + snapshot.w).toFloat(),
                    bottom = (op.y + snapshot.h).toFloat(),
                ) {
                    drawImage(image, topLeft = Offset(op.x.toFloat(), op.y.toFloat()))
                }
            }

            else -> withOptionalClip(clip) {
                drawOp(op, colours, textMeasurer, density)
            }
        }
    }
}

private fun blitterImage(
    snapshot: BlitterSnapshot,
    frame: BoardFrame,
    colours: IntArray,
    textMeasurer: TextMeasurer,
    density: Float,
    layoutDirection: LayoutDirection,
    blitterCache: MutableMap<Int, CachedBlitter>,
    rendering: MutableSet<BlitterSnapshot>,
): ImageBitmap? {
    val cached = blitterCache[snapshot.id]
    if (cached != null && cached.snapshot === snapshot) return cached.image
    if (!rendering.add(snapshot)) return null

    val image = ImageBitmap(snapshot.w, snapshot.h)
    val canvas = Canvas(image)
    CanvasDrawScope().draw(
        density = androidx.compose.ui.unit.Density(density),
        layoutDirection = layoutDirection,
        canvas = canvas,
        size = Size(snapshot.w.toFloat(), snapshot.h.toFloat()),
    ) {
        translate(-snapshot.x.toFloat(), -snapshot.y.toFloat()) {
            drawFrame(
                frame.copy(ops = snapshot.ops),
                colours,
                textMeasurer,
                density,
                layoutDirection,
                blitterCache,
                rendering,
            )
        }
    }

    rendering.remove(snapshot)
    blitterCache[snapshot.id] = CachedBlitter(snapshot, image)
    return image
}

private fun DrawScope.withOptionalClip(clip: Rect?, block: DrawScope.() -> Unit) {
    if (clip == null) {
        block()
    } else {
        clipRect(clip.left, clip.top, clip.right, clip.bottom, block = block)
    }
}

private fun DrawScope.drawOp(
    op: DrawOp,
    colours: IntArray,
    textMeasurer: TextMeasurer,
    density: Float,
) {
    when (op) {
        is DrawOp.FillRect -> fillRect(op, colours)
        is DrawOp.Line -> drawLine(
            color = colour(colours, op.colour),
            start = Offset(op.x1, op.y1),
            end = Offset(op.x2, op.y2),
            strokeWidth = op.thickness.coerceAtLeast(1f),
            cap = StrokeCap.Square,
        )

        is DrawOp.Poly -> drawPoly(op, colours)
        is DrawOp.Circle -> drawCircle(op, colours)
        is DrawOp.Text -> drawText(op, colours, textMeasurer, density)
        is DrawOp.Clip, is DrawOp.Unclip, is DrawOp.BlitterLoad -> Unit
    }
}

private fun DrawScope.fillRect(op: DrawOp.FillRect, colours: IntArray) {
    if (op.w <= 0 || op.h <= 0) return
    val color = colour(colours, op.colour)
    when {
        op.w == 1 && op.h == 1 -> drawRect(
            color = color,
            topLeft = Offset(op.x - 0.5f, op.y - 0.5f),
            size = Size(1f, 1f),
        )

        op.w == 1 || op.h == 1 -> drawLine(
            color = color,
            start = Offset(op.x.toFloat(), op.y.toFloat()),
            end = Offset((op.x + op.w - 1).toFloat(), (op.y + op.h - 1).toFloat()),
            strokeWidth = 1f,
            cap = StrokeCap.Square,
        )

        else -> drawRect(
            color = color,
            topLeft = Offset(op.x - 0.5f, op.y - 0.5f),
            size = Size(op.w.toFloat(), op.h.toFloat()),
        )
    }
}

private fun DrawScope.drawPoly(op: DrawOp.Poly, colours: IntArray) {
    if (op.points.size < 6) return
    val path = Path()
    path.moveTo((op.points[0] + op.ox).toFloat(), (op.points[1] + op.oy).toFloat())
    for (i in 1 until op.points.size / 2) {
        path.lineTo(
            (op.points[2 * i] + op.ox).toFloat(),
            (op.points[2 * i + 1] + op.oy).toFloat(),
        )
    }
    path.close()
    if (op.fill != -1) {
        drawPath(path, colour(colours, op.fill), style = Fill)
    }
    if (op.line != -1) {
        drawPath(
            path,
            colour(colours, op.line),
            style = Stroke(width = op.thickness.coerceAtLeast(1f)),
        )
    }
}

private fun DrawScope.drawCircle(op: DrawOp.Circle, colours: IntArray) {
    val radius = op.r.coerceAtLeast(0.4f)
    val fill = if (op.r <= 0.5f) op.line else op.fill
    if (fill != -1) {
        drawCircle(colour(colours, fill), radius, Offset(op.x, op.y))
    }
    if (op.line != -1) {
        drawCircle(
            colour(colours, op.line),
            radius,
            Offset(op.x, op.y),
            style = Stroke(width = op.thickness.coerceAtLeast(1f)),
        )
    }
}

private fun DrawScope.drawText(
    op: DrawOp.Text,
    colours: IntArray,
    textMeasurer: TextMeasurer,
    density: Float,
) {
    if (op.text.isEmpty()) return
    val style = TextStyle(
        color = colour(colours, op.colour),
        // The canvas is already scaled by the game density, so measure
        // the text in game pixels and let the transform scale it.
        fontSize = (op.size / density).sp,
        fontFamily = if (op.flags and TEXT_MONO != 0) FontFamily.Monospace else FontFamily.Default,
    )
    val layout = textMeasurer.measure(AnnotatedString(op.text), style)
    val x = when {
        op.flags and ALIGN_H_CENTRE != 0 -> op.x - layout.size.width / 2f
        op.flags and ALIGN_H_RIGHT != 0 -> op.x - layout.size.width.toFloat()
        else -> op.x.toFloat()
    }
    val y = if (op.flags and ALIGN_V_CENTRE != 0) {
        op.y - layout.size.height / 2f
    } else {
        op.y - layout.firstBaseline
    }
    drawText(layout, topLeft = Offset(x, y))
}

private fun colour(colours: IntArray, index: Int): Color =
    Color(colours.getOrElse(index) { 0xFFFF00FF.toInt() })

private const val TEXT_MONO = 0x10
private const val ALIGN_H_CENTRE = 0x001
private const val ALIGN_H_RIGHT = 0x002
private const val ALIGN_V_CENTRE = 0x100
private const val MAX_BLITTER_DEPTH = 16
