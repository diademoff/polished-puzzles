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
import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.KeyRequest
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Text
import name.boyle.chris.sgtpuzzles.launch.GameLaunch
import name.boyle.chris.sgtpuzzles.launch.MenuEntry
import java.io.ByteArrayOutputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Which games have a Kotlin backend. The flag is on by default for the
 * games that pass the parity matrix; set it to false to fall back to C
 * without rebuilding the rest of the shell.
 */
object KotlinGames {

    /** Kotlin backends are enabled after the parity slice for each game. */
    const val ENABLED = true

    fun gameFor(backend: BackendName): Game? = when (backend) {
        FIFTEEN -> Fifteen
        else -> null
    }

    fun handles(backend: BackendName): Boolean = ENABLED && gameFor(backend) != null

    /** The save file that the C generator would have produced. */
    fun generate(backend: BackendName, params: String, seed: String): String {
        val game = requireNotNull(gameFor(backend)) { "no Kotlin backend for $backend" }
        return Midend.newGame(game, params, seed).serialise().serialise()
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
    private val fifteenState: Fifteen.State get() = state as Fifteen.State

    private var tileSize = game.preferredTileSize
    private var winWidth = 1
    private var winHeight = 1
    private var originX = 0
    private var originY = 0
    private var sized = false
    private var firstDraw = true
    private var invertCursor = false

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
            viewCallbacks.fillRect(x + originX, y + originY, width, height, colour)
        }

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            viewCallbacks.drawPoly(1.0f, points, originX, originY, outlineColour, fillColour)
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
            viewCallbacks.drawText(x + originX, y + originY, flags, size, colour, text)
        }

        override fun update(x: Int, y: Int, width: Int, height: Int) {}

        override fun status(text: String) {
            activityCallbacks.setStatus(text)
        }
    }

    init {
        applyPrefs(initialPrefs)
        setPreferredSize()
        Log.d(TAG, "using the Kotlin backend for ${game.backendName}")
    }

    override fun onDestroy() {}

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
    ): GameEngine.KeysResult {
        val request: KeyRequest = game.requestKeys(midend.currentParams)
        val keys = request.labels.joinToString("") { it.button.toChar().toString() }
        val arrow = when (request.arrowMode) {
            EngineArrowMode.NONE -> ArrowMode.NO_ARROWS
            EngineArrowMode.ARROWS_ONLY -> ArrowMode.ARROWS_ONLY
            EngineArrowMode.ARROWS_LEFT -> ArrowMode.ARROWS_LEFT_CLICK
            EngineArrowMode.ARROWS_LEFT_RIGHT -> ArrowMode.ARROWS_LEFT_RIGHT_CLICK
        }
        return GameEngine.KeysResult(keys, "", arrow)
    }

    override fun timerTick() {}

    override fun htmlHelpTopic(): String = "games." + game.backendName

    override fun keyEvent(x: Int, y: Int, k: Int) {
        if (!sized || k < 0) return
        val before = midend.status()
        val outcome = midend.processKey(x - originX, y - originY, k)
        if (outcome != KeyOutcome.SomeEffect) return
        afterStateChange(before)
    }

    override fun restartEvent() {
        val before = midend.status()
        midend.restart()
        afterStateChange(before)
    }

    override fun solveEvent() {
        val before = midend.status()
        if (!midend.solve()) throw IllegalArgumentException("This game does not support Solve")
        afterStateChange(before)
    }

    override fun resizeEvent(x: Int, y: Int, bottomInset: Int) {
        val availableWidth = x
        val availableHeight = y - bottomInset
        tileSize = largestTileThatFits(availableWidth, availableHeight)
        val size = game.computeSize(midend.params, tileSize)
        winWidth = size.width
        winHeight = size.height
        originX = (availableWidth - winWidth) / 2
        originY = (availableHeight - winHeight) / 2
        sized = true
        firstDraw = true
        viewCallbacks.unClip(originX, originY, x - originX, availableHeight - originY)
        redraw()
    }

    override fun serialise(baos: ByteArrayOutputStream) {
        baos.write(midend.serialise().serialise().toByteArray())
    }

    override val currentParams: String
        get() = game.encodeParams(midend.currentParams, true)

    override fun setCursorVisibility(visible: Boolean) {}

    override val presets: Array<MenuEntry>
        get() = game.presets().mapIndexed { index, preset ->
            MenuEntry(index, preset.title, preset.params)
        }.toTypedArray()

    override val uiVisibility: Int
        get() {
            val undo = if (midend.canUndo) 1 else 0
            val redo = if (midend.canRedo) 2 else 0
            // can configure, can solve, and wants a status bar
            return undo or redo or 4 or 8 or 16
        }

    override fun resetTimerBaseline() {}

    override fun purgeStates() = midend.purgeStates()

    override val isCompletedNow: Boolean
        get() = midend.status() == 1

    override val colours: FloatArray
        get() {
            val argb = viewCallbacks.defaultBackgroundColour
            return Fifteen.colours(
                max(0.0f, min(1.0f, Color.red(argb) / 255.0f)),
            )
        }

    override fun suggestDensity(x: Int, y: Int): Float {
        val size = game.computeSize(midend.params, game.preferredTileSize)
        val widthRatio = floor(x.toDouble() / size.width).toFloat()
        val heightRatio = floor(y.toDouble() / size.height).toFloat()
        return max(1.0f, min(widthRatio, heightRatio))
    }

    override val cursorLocation: RectF
        get() {
            val border = tileSize / 2
            val x = fifteenState.gapPos % fifteenState.width
            val y = fifteenState.gapPos / fifteenState.width
            val left = originX + x * tileSize + border
            val top = originY + y * tileSize + border
            return RectF(left.toFloat(), top.toFloat(), (left + tileSize).toFloat(), (top + tileSize).toFloat())
        }

    @get:VisibleForTesting
    override val gameSizeInGameCoords: Point
        get() = Point(winWidth, winHeight)

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

    private fun setPreferredSize() {
        val size = game.computeSize(midend.params, game.preferredTileSize)
        winWidth = size.width
        winHeight = size.height
    }

    /**
     * `midend_size(..., user_size = true)`: the largest tile size that
     * fits. The doubling and the binary search are the same as C, so the
     * result is identical for odd and even tile sizes.
     */
    private fun largestTileThatFits(width: Int, height: Int): Int {
        var maximum = 1
        while (true) {
            val size = game.computeSize(midend.params, maximum)
            if (size.width > width || size.height > height) break
            maximum *= 2
        }
        var minimum = 1
        while (maximum - minimum > 1) {
            val middle = (maximum + minimum) / 2
            val size = game.computeSize(midend.params, middle)
            if (size.width <= width && size.height <= height) {
                minimum = middle
            } else {
                maximum = middle
            }
        }
        return minimum
    }

    private fun afterStateChange(previousStatus: Int) {
        activityCallbacks.changedState(midend.canUndo, midend.canRedo)
        if (previousStatus == 0 && midend.status() == 1 && !fifteenState.usedSolve) {
            activityCallbacks.completed()
        }
        redraw()
    }

    private fun redraw() {
        viewCallbacks.startDraw()
        if (firstDraw) {
            viewCallbacks.fillRect(originX, originY, winWidth, winHeight, 0)
            firstDraw = false
        }
        game.redraw(state, sink, tileSize)
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
                    launch.saved != null -> Midend.fromSave(game, SgtpSave.parse(launch.saved))
                    launch.gameID != null -> Midend.fromGameId(game, launch.gameID)
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
