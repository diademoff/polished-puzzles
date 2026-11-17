package name.boyle.chris.sgtpuzzles.backend

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.RectF
import android.util.Log
import androidx.annotation.VisibleForTesting
import name.boyle.chris.sgtpuzzles.buttons.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.ArrowMode as EngineArrowMode
import name.boyle.chris.sgtpuzzles.config.ConfigBuilder
import name.boyle.chris.sgtpuzzles.config.CustomDialogBuilder
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.BlackBox
import name.boyle.chris.sgtpuzzles.engine.Bridges
import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.Dominosa
import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Filling
import name.boyle.chris.sgtpuzzles.engine.Flip
import name.boyle.chris.sgtpuzzles.engine.Flood
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Guess
import name.boyle.chris.sgtpuzzles.engine.Inertia
import name.boyle.chris.sgtpuzzles.engine.Keen
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Lightup
import name.boyle.chris.sgtpuzzles.engine.Magnets
import name.boyle.chris.sgtpuzzles.engine.Map
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Mines
import name.boyle.chris.sgtpuzzles.engine.Mosaic
import name.boyle.chris.sgtpuzzles.engine.Net
import name.boyle.chris.sgtpuzzles.engine.Netslide
import name.boyle.chris.sgtpuzzles.engine.Palisade
import name.boyle.chris.sgtpuzzles.engine.Pattern
import name.boyle.chris.sgtpuzzles.engine.Pearl
import name.boyle.chris.sgtpuzzles.engine.Pegs
import name.boyle.chris.sgtpuzzles.engine.Range
import name.boyle.chris.sgtpuzzles.engine.SameGame
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Signpost
import name.boyle.chris.sgtpuzzles.engine.Singles
import name.boyle.chris.sgtpuzzles.engine.Sixteen
import name.boyle.chris.sgtpuzzles.engine.Slant
import name.boyle.chris.sgtpuzzles.engine.Tents
import name.boyle.chris.sgtpuzzles.engine.Text
import name.boyle.chris.sgtpuzzles.engine.Towers
import name.boyle.chris.sgtpuzzles.engine.Tracks
import name.boyle.chris.sgtpuzzles.engine.Twiddle
import name.boyle.chris.sgtpuzzles.engine.Undead
import name.boyle.chris.sgtpuzzles.engine.Unequal
import name.boyle.chris.sgtpuzzles.engine.Unruly
import name.boyle.chris.sgtpuzzles.engine.Untangle
import name.boyle.chris.sgtpuzzles.launch.GameLaunch
import name.boyle.chris.sgtpuzzles.launch.MenuEntry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The frontend `get_random_seed` bytes for the Android shell.
 *
 * The C frontend fills the seed from the clock (`android.c`), and the
 * oracle returns one NUL byte (`tools/oracle/oracle.c:101-107`) so its
 * output is deterministic. This helper is the app side of that
 * callback: 16 bytes from two clock readings, so the frontend random
 * state of a game is fresh on every start. It is not the game seed and
 * it is never saved.
 */
internal fun platformSeed(): ByteArray = ByteBuffer.allocate(16)
    .putLong(System.nanoTime())
    .putLong(System.currentTimeMillis())
    .array()

/**
 * The C `htmlhelp_topic` of a Kotlin game.
 *
 * `android.c` returns `thegame->htmlhelp_topic` verbatim. The field
 * order is `name, winhelp_topic, htmlhelp_topic`, and every `thegame`
 * table in the vendored tree sets the HTML topic to the backend name,
 * for example `"Fifteen", "games.fifteen", "fifteen"`.
 */
internal fun htmlHelpTopicFor(game: Game): String = game.backendName

/**
 * The `KeysResult` of a Kotlin game, the counterpart of
 * `Java_..._requestKeys` in `android.c`.
 *
 * C splits the requested labels into two strings. A key whose
 * `needs_arrows` is false always appears on the keyboard, and a key
 * whose `needs_arrows` is true appears only while the shell shows the
 * arrow keys. Both strings hold the button as a character and keep the
 * request order. The result carries no label text, as in `android.c`;
 * [Game.KeyLabel.name] stays in the engine for a front end that shows
 * it.
 *
 * [fullCursor] is the shell's Android full-cursor flag, the value that
 * `android.c` passes to `midend_request_keys_by_game` as
 * `palisade_full_cursor`. It goes to the game unchanged, because the C
 * hack makes the game decide: `palisade` returns arrow labels in that
 * mode and no labels otherwise. A game without the hack ignores it.
 */
internal fun keysResultFor(
    game: Game,
    params: Game.Params,
    fullCursor: Boolean,
): GameEngine.KeysResult {
    val request = game.requestKeys(params, fullCursor)
    val keys = request.labels
        .filterNot { it.needsArrows }
        .joinToString("") { it.button.toChar().toString() }
    val keysIfArrows = request.labels
        .filter { it.needsArrows }
        .joinToString("") { it.button.toChar().toString() }
    return GameEngine.KeysResult(keys, keysIfArrows, arrowsFor(request.arrowMode))
}

/** The Android arrow mode for an engine arrow mode, as `android.c` maps it. */
private fun arrowsFor(mode: EngineArrowMode): ArrowMode = when (mode) {
    EngineArrowMode.NONE -> ArrowMode.NO_ARROWS
    EngineArrowMode.ARROWS_ONLY -> ArrowMode.ARROWS_ONLY
    EngineArrowMode.ARROWS_LEFT -> ArrowMode.ARROWS_LEFT_CLICK
    EngineArrowMode.ARROWS_LEFT_RIGHT -> ArrowMode.ARROWS_LEFT_RIGHT_CLICK
    EngineArrowMode.ARROWS_DIAGONALS -> ArrowMode.ARROWS_DIAGONALS
}

/**
 * Which games have a Kotlin backend. The flag is on by default for the
 * games that pass the parity matrix; set it to false to fall back to C
 * without rebuilding the rest of the shell.
 */
object KotlinGames {

    /** Kotlin backends are enabled after the parity slice for each game. */
    const val ENABLED = true

    /**
     * Games whose Kotlin implementation is complete enough for the app.
     * `slant`, `samegame`, `flood`, `sixteen`, `flip`, `twiddle`,
     * `pegs`, `guess`, `blackbox`, `cube`, `filling`, `netslide`,
     * `unruly`, `range`, `lightup`, `mosaic`, `palisade`, `inertia`,
     * `tents`, `magnets`, `pattern`, `signpost`, `undead`, `untangle`,
     * `bridges`, `net`, `mines`, `tracks`, `dominosa`, `map`, `keen`,
     * `towers`, `unequal`, `singles`, and `pearl` are in dual-run: their
     * engines pass the parity matrix, but the app still uses C until the
     * configuration dialog and the two-release wait are done.
     */
    private val APP_GAMES = setOf(FIFTEEN)

    fun gameFor(backend: BackendName): Game? = when (backend) {
        FIFTEEN -> Fifteen
        SLANT -> Slant
        SAMEGAME -> SameGame
        FLOOD -> Flood
        SIXTEEN -> Sixteen
        FLIP -> Flip
        TWIDDLE -> Twiddle
        PEGS -> Pegs
        GUESS -> Guess
        BLACKBOX -> BlackBox
        BRIDGES -> Bridges
        CUBE -> Cube
        FILLING -> Filling
        NET -> Net
        NETSLIDE -> Netslide
        UNRULY -> Unruly
        RANGE -> Range
        LIGHTUP -> Lightup
        MOSAIC -> Mosaic
        PALISADE -> Palisade
        PATTERN -> Pattern
        PEARL -> Pearl
        SIGNPOST -> Signpost
        INERTIA -> Inertia
        TENTS -> Tents
        MAGNETS -> Magnets
        MINES -> Mines
        UNDEAD -> Undead
        UNTANGLE -> Untangle
        TRACKS -> Tracks
        DOMINOSA -> Dominosa
        MAP -> Map
        KEEN -> Keen
        TOWERS -> Towers
        UNEQUAL -> Unequal
        SINGLES -> Singles
        else -> null
    }

    fun handles(backend: BackendName): Boolean =
        ENABLED && backend in APP_GAMES && gameFor(backend) != null

    /** The save file that the C generator would have produced. */
    fun generate(backend: BackendName, params: String, seed: String): String {
        val game = requireNotNull(gameFor(backend)) { "no Kotlin backend for $backend" }
        // The frontend seed reaches only frontend state, so it cannot
        // change the board or the records that the oracle writes. It
        // uses the app value here, as the C frontend does.
        return Midend.newGame(game, params, seed, platformSeed()).serialise().serialise()
    }

    /** A fresh printable seed, used when the chooser did not provide one. */
    fun newSeed(): String = java.util.UUID.randomUUID().toString()
}

/**
 * The first Kotlin-backed [GameEngine]. It owns a [Midend] and forwards
 * the drawing primitives to [GameEngine.ViewCallbacks], the same seam
 * the C engine uses.
 *
 * The shell-facing behavior is intentionally small: the game has no
 * timer, no configuration beyond width and height, and no custom
 * serialisation. The dialog and preference methods exist so the shell
 * does not need a special case for this backend.
 */
class KotlinGameEngine private constructor(
    private val game: Game,
    private var midend: Midend,
    private val backend: BackendName,
    private val activityCallbacks: GameEngine.ActivityCallbacks,
    private var viewCallbacks: GameEngine.ViewCallbacks,
    initialPrefs: String?,
) : GameEngine, ConfigBuilder.EngineCallbacks {

    private enum class ConfigMode { SETTINGS, SEED, DESC, PREFS }

    private val state: Game.State get() = midend.state

    private var geometry = BoardGeometry.preferred(game, midend.params, midend.ui)
    private var viewWidth = 1
    private var viewHeight = 1
    private var sized = false
    private var invertCursor = false

    /** The clock reading of the previous timer tick, in nanoseconds. */
    private var timerBaselineNanos = System.nanoTime()

    private var configMode: ConfigMode? = null
    private val configValues = HashMap<String, String>()
    private var configParams: Fifteen.Params =
        game.decodeParams(game.encodeParams(midend.currentParams, true)) as Fifteen.Params

    private val sink = object : DrawSink {
        override fun changedState(canUndo: Boolean, canRedo: Boolean) {
            activityCallbacks.changedState(canUndo, canRedo)
        }

        override fun startDraw() {}
        override fun endDraw() {}

        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            viewCallbacks.fillRect(
                x + geometry.originX,
                y + geometry.originY,
                width,
                height,
                colour,
            )
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            viewCallbacks.drawLine(
                1.0f,
                x1 + geometry.originX.toFloat(),
                y1 + geometry.originY.toFloat(),
                x2 + geometry.originX.toFloat(),
                y2 + geometry.originY.toFloat(),
                colour,
            )
        }

        override fun thickLine(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            thickness: Float,
            colour: Int,
        ) {
            viewCallbacks.drawLine(
                thickness,
                x1 + geometry.originX.toFloat(),
                y1 + geometry.originY.toFloat(),
                x2 + geometry.originX.toFloat(),
                y2 + geometry.originY.toFloat(),
                colour,
            )
        }

        override fun circle(
            cx: Int,
            cy: Int,
            radius: Int,
            fillColour: Int,
            outlineColour: Int,
        ) {
            viewCallbacks.drawCircle(
                1.0f,
                cx + geometry.originX.toFloat(),
                cy + geometry.originY.toFloat(),
                radius.toFloat(),
                outlineColour,
                fillColour,
            )
        }

        override fun thickCircle(
            cx: Int,
            cy: Int,
            radius: Int,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {
            viewCallbacks.drawCircle(
                thickness,
                cx + geometry.originX.toFloat(),
                cy + geometry.originY.toFloat(),
                radius.toFloat(),
                outlineColour,
                fillColour,
            )
        }

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            viewCallbacks.drawPoly(
                1.0f,
                points,
                geometry.originX,
                geometry.originY,
                outlineColour,
                fillColour,
            )
        }

        override fun thickPoly(
            points: IntArray,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {
            viewCallbacks.drawPoly(
                thickness,
                points,
                geometry.originX,
                geometry.originY,
                outlineColour,
                fillColour,
            )
        }

        override fun text(
            x: Int,
            y: Int,
            font: Int,
            size: Int,
            align: Int,
            colour: Int,
            text: String,
        ) {
            val flags = (if (font == Text.FONT_FIXED) 0x10 else 0) or align
            viewCallbacks.drawText(
                x + geometry.originX,
                y + geometry.originY,
                flags,
                size,
                colour,
                text,
            )
        }

        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            viewCallbacks.clipRect(
                x + geometry.originX,
                y + geometry.originY,
                width,
                height,
            )
        }

        override fun unclip() {
            viewCallbacks.unClip(
                geometry.originX,
                geometry.originY,
                viewWidth,
                viewHeight,
            )
        }

        override fun update(x: Int, y: Int, width: Int, height: Int) {}

        override fun status(text: String) {
            activityCallbacks.setStatus(text)
        }

        override fun inertiaFollow(isSolved: Boolean) {
            activityCallbacks.inertiaFollow(isSolved)
        }

        override fun blitterNew(width: Int, height: Int): Int =
            viewCallbacks.blitterAlloc(width, height)

        override fun blitterFree(id: Int) {
            viewCallbacks.blitterFree(id)
        }

        override fun blitterSave(id: Int, x: Int, y: Int) {
            viewCallbacks.blitterSave(id, x + geometry.originX, y + geometry.originY)
        }

        override fun blitterLoad(id: Int, x: Int, y: Int) {
            viewCallbacks.blitterLoad(id, x + geometry.originX, y + geometry.originY)
        }
    }

    init {
        // The C midend starts and stops the shell timer through
        // `activate_timer` and `deactivate_timer`. The setter syncs the
        // shell with the state of the loaded game immediately.
        midend.timerCallback = { on -> activityCallbacks.requestTimer(on) }
        applyPrefs(initialPrefs)
        Log.d(TAG, "using the Kotlin backend for ${game.backendName}")
    }

    override fun onDestroy() {
        // The C engine frees its midend and its drawstate here. The
        // cache belongs to the view callbacks, so it must be released
        // before the view goes away.
        midend.freeDrawState()
    }

    // ------------------------------------------------------------------
    // GameEngine
    // ------------------------------------------------------------------

    override fun configEvent(
        activityCallbacks: ConfigBuilder.ActivityCallbacks,
        whichEvent: Int,
        context: Context,
        backendName: BackendName,
    ) {
        configEvent(
            whichEvent,
            CustomDialogBuilder(context, this, activityCallbacks, whichEvent, backendName),
        )
    }

    override fun configEvent(whichEvent: Int, builder: ConfigBuilder) {
        configValues.clear()
        configParams =
            game.decodeParams(game.encodeParams(midend.currentParams, true)) as Fifteen.Params
        configMode = when (ConfigBuilder.Event.fromJNI(whichEvent)) {
            ConfigBuilder.Event.CFG_SETTINGS -> ConfigMode.SETTINGS
            ConfigBuilder.Event.CFG_SEED -> ConfigMode.SEED
            ConfigBuilder.Event.CFG_DESC -> ConfigMode.DESC
            ConfigBuilder.Event.CFG_PREFS -> ConfigMode.PREFS
        }

        when (configMode) {
            ConfigMode.SETTINGS -> {
                builder.setTitle("${game.name} configuration")
                builder.addString(whichEvent, "Width", "Width", configParams.width.toString())
                builder.addString(whichEvent, "Height", "Height", configParams.height.toString())
            }

            ConfigMode.SEED -> {
                builder.setTitle("${game.name} random selection")
                val params = game.encodeParams(midend.currentParams, true)
                builder.addString(whichEvent, "Seed", "Seed", params + "#" + (midend.seed ?: ""))
            }

            ConfigMode.DESC -> {
                builder.setTitle("${game.name} game selection")
                builder.addString(whichEvent, "Game ID", "Game ID", shortParams() + ":" + midend.desc)
            }

            ConfigMode.PREFS -> {
                builder.setTitle("${game.name} preferences")
                builder.addChoices(
                    whichEvent,
                    "arrow-semantics",
                    "Sense of arrow keys",
                    ":Move the tile:Move the gap",
                    ":tile:gap",
                    if (invertCursor) 1 else 0,
                )
            }

            null -> return
        }
        builder.dialogShow()
    }

    override fun configSetString(itemPtr: String, s: String, isPrefs: Boolean) {
        configValues[itemPtr] = s
        if (isPrefs) applyPrefs("arrow-semantics=$s")
    }

    override fun configSetBool(itemPtr: String, selected: Boolean, isPrefs: Boolean) {
        configValues[itemPtr] = selected.toString()
        if (isPrefs) applyPrefs("$itemPtr=$selected")
    }

    override fun configSetChoice(itemPtr: String, selected: Int, isPrefs: Boolean) {
        configValues[itemPtr] = selected.toString()
        if (isPrefs) {
            invertCursor = selected == 1
            applyPrefs("arrow-semantics=${if (invertCursor) "gap" else "tile"}")
        }
    }

    override fun configCancel() {
        configValues.clear()
        configMode = null
    }

    override fun configOK(): String {
        when (configMode) {
            ConfigMode.SETTINGS -> {
                val width = if (configValues.containsKey("Width")) {
                    configValues["Width"]?.toIntOrNull() ?: 0
                } else {
                    configParams.width
                }
                val height = if (configValues.containsKey("Height")) {
                    configValues["Height"]?.toIntOrNull() ?: 0
                } else {
                    configParams.height
                }
                val params = Fifteen.Params(width, height)
                Fifteen.validateParams(params, true)?.let { throw IllegalArgumentException(it) }
                return Fifteen.encodeParams(params, true)
            }

            ConfigMode.SEED -> return fullSeedFromDialog
            ConfigMode.DESC -> return fullGameIDFromDialog
            ConfigMode.PREFS, null -> {
                configMode = null
                return shortParams()
            }
        }
    }

    override val fullGameIDFromDialog: String
        get() {
            val value = configValues["Game ID"] ?: ""
            return if (value.contains(':')) value else shortParams() + ":" + value
        }

    override val fullSeedFromDialog: String
        get() {
            val value = configValues["Seed"] ?: ""
            val params = game.encodeParams(midend.currentParams, true)
            return if (value.contains('#')) value else params + "#" + value
        }

    override fun savePrefs(context: Context) {
        val text = "arrow-semantics=${if (invertCursor) "gap" else "tile"}\n"
        GameEngineImpl.savePrefs(context, backend, text)
    }

    override fun loadPrefs(context: Context) {
        applyPrefs(GameEngineImpl.getPrefs(context, backend))
    }

    override fun requestKeys(
        backend: BackendName,
        params: String?,
        palisadeFullCursor: Boolean,
    ): GameEngine.KeysResult = keysResultFor(game, midend.currentParams, palisadeFullCursor)

    override fun timerTick() {
        val now = System.nanoTime()
        val delta = (now - timerBaselineNanos).toDouble() / 1_000_000_000.0
        timerBaselineNanos = now
        // The status line shows whole seconds, so a redraw is needed
        // only when the game time crosses one, as `midend_timer` calls
        // `status_bar` only then.
        if (midend.timerTick(delta.toFloat())) redraw()
    }

    override fun htmlHelpTopic(): String = htmlHelpTopicFor(game)

    override fun keyEvent(x: Int, y: Int, k: Int) {
        if (!sized || k < 0) return
        val outcome = midend.processKey(
            geometry.toGameX(x),
            geometry.toGameY(y),
            k,
            geometry.tileSize,
        )
        if (outcome != KeyOutcome.SomeEffect) return
        afterStateChange()
    }

    override fun restartEvent() {
        if (!midend.restart()) return
        afterStateChange()
    }

    override fun solveEvent() {
        if (!midend.solve()) throw IllegalArgumentException("This game does not support Solve")
        afterStateChange()
    }

    override fun resizeEvent(x: Int, y: Int, bottomInset: Int) {
        val availableWidth = x
        val availableHeight = y - bottomInset
        viewWidth = x
        viewHeight = availableHeight
        geometry = midend.geometryFor(availableWidth, availableHeight)
        sized = true
        // `midend_size` frees and recreates the drawstate on every call,
        // even when the tile size is unchanged. The view allocates its
        // blitter bitmaps from the zoom factor, so a zoom change must
        // replace the cache too. The replacement sets the midend's
        // first-draw flag, so the next redraw clears the window.
        midend.forceRedraw()
        viewCallbacks.unClip(
            geometry.originX,
            geometry.originY,
            x - geometry.originX,
            availableHeight - geometry.originY,
        )
        redraw()
    }

    override fun serialise(baos: ByteArrayOutputStream) {
        baos.write(midend.serialise().serialise().toByteArray())
    }

    override val currentParams: String
        get() = game.encodeParams(midend.currentParams, true)

    override fun setCursorVisibility(visible: Boolean) = midend.setCursorVisible(visible)

    override val presets: Array<MenuEntry>
        get() = game.presets().mapIndexed { index, preset ->
            MenuEntry(index, preset.title, preset.params)
        }.toTypedArray()

    override val uiVisibility: Int
        get() {
            val undo = if (midend.canUndo) 1 else 0
            val redo = if (midend.canRedo) 2 else 0
            val solve = if (game.canSolve) 8 else 0
            // can configure, the solve bit from the game, and optionally
            // a status bar
            val status = if (game.wantsStatusBar) 16 else 0
            return undo or redo or 4 or solve or status
        }

    override fun resetTimerBaseline() {
        // The shell calls this when the view regains focus, so the time
        // spent in the background is not added to the game clock.
        timerBaselineNanos = System.nanoTime()
    }

    override fun purgeStates() = midend.purgeStates()

    override val isCompletedNow: Boolean
        get() = midend.status() == 1

    override val colours: FloatArray
        get() {
            val argb = viewCallbacks.defaultBackgroundColour
            return game.colours(
                floatArrayOf(
                    Color.red(argb) / 255.0f,
                    Color.green(argb) / 255.0f,
                    Color.blue(argb) / 255.0f,
                ),
            )
        }

    override fun suggestDensity(x: Int, y: Int): Float {
        val size = game.computeSize(midend.params, midend.ui, game.preferredTileSize)
        val widthRatio = floor(x.toDouble() / size.width).toFloat()
        val heightRatio = floor(y.toDouble() / size.height).toFloat()
        return max(1.0f, min(widthRatio, heightRatio))
    }

    override val cursorLocation: RectF
        get() {
            val cursor = midend.cursorLocation(geometry.tileSize) ?: return RectF()
            return RectF(
                (geometry.originX + cursor.left).toFloat(),
                (geometry.originY + cursor.top).toFloat(),
                (geometry.originX + cursor.left + cursor.width).toFloat(),
                (geometry.originY + cursor.top + cursor.height).toFloat(),
            )
        }

    @get:VisibleForTesting
    override val gameSizeInGameCoords: Point
        get() = Point(geometry.width, geometry.height)

    /** The geometry currently used for drawing and input; for tests. */
    @get:VisibleForTesting
    val boardGeometry: BoardGeometry
        get() = geometry

    @VisibleForTesting
    override fun freezePartialRedo() {
        midend.redo()
    }

    override fun setViewCallbacks(viewCallbacks: GameEngine.ViewCallbacks) {
        this.viewCallbacks = viewCallbacks
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun applyPrefs(text: String?) {
        if (text == null) return
        for (line in text.lineSequence()) {
            if (line.startsWith("arrow-semantics=")) {
                invertCursor = line.substringAfter('=').trim() == "gap"
            }
        }
    }

    private fun shortParams(): String = game.encodeParams(midend.currentParams, false)

    private fun afterStateChange() {
        activityCallbacks.changedState(midend.canUndo, midend.canRedo)
        if (midend.takeCompletedEvent()) activityCallbacks.completed()
        redraw()
    }

    private fun redraw() {
        val drawState = midend.ensureDrawState(geometry.tileSize, sink)
        viewCallbacks.startDraw()
        // The midend owns the first-draw flag, as `midend_redraw` does.
        // A resize, a zoom change, and a new game replace the drawstate
        // and clear the window once; a move keeps it and draws only the
        // game's operations.
        if (midend.takeFirstDraw()) {
            viewCallbacks.fillRect(
                geometry.originX,
                geometry.originY,
                geometry.width,
                geometry.height,
                0,
            )
        }
        game.redraw(state, midend.ui, drawState, sink, geometry.tileSize)
        activityCallbacks.setStatus(midend.statusText())
        viewCallbacks.endDraw()
    }

    companion object {
        private const val TAG = "KotlinGameEngine"

        fun fromLaunch(
            launch: GameLaunch,
            activityCallbacks: GameEngine.ActivityCallbacks,
            viewCallbacks: GameEngine.ViewCallbacks,
            context: Context,
        ): KotlinGameEngine {
            val game = requireNotNull(KotlinGames.gameFor(launch.whichBackend)) {
                "no Kotlin backend for ${launch.whichBackend}"
            }
            val midend = try {
                when {
                    launch.saved != null -> Midend.fromSave(
                        game,
                        SgtpSave.parse(launch.saved),
                        platformSeed(),
                    )

                    launch.gameID != null -> Midend.fromGameId(game, launch.gameID, platformSeed())
                    else -> throw IllegalArgumentException("launch has neither a save nor a game ID")
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: RuntimeException) {
                throw IllegalArgumentException(e.message ?: "Invalid saved game", e)
            }
            return KotlinGameEngine(
                game = game,
                midend = midend,
                backend = launch.whichBackend,
                activityCallbacks = activityCallbacks,
                viewCallbacks = viewCallbacks,
                initialPrefs = GameEngineImpl.getPrefs(context, launch.whichBackend),
            )
        }
    }
}
