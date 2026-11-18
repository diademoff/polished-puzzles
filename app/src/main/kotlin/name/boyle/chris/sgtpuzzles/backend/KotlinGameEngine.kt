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
import name.boyle.chris.sgtpuzzles.engine.Cancellation
import name.boyle.chris.sgtpuzzles.engine.ConfigItem
import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.GenerationCancelled
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Text
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

/**
 * The game's own key names of a Kotlin game, by button character.
 *
 * `android.c` drops the names and returns only the buttons, which is
 * enough for the screen but not for a screen reader: the shell cannot
 * tell a "Labels" key from a bare "L". The names stay in the engine for
 * a front end that shows them.
 */
internal fun keyNamesFor(
    game: Game,
    params: Game.Params,
    fullCursor: Boolean,
): Map<Char, String> = buildMap {
    for (label in game.requestKeys(params, fullCursor).labels) {
        if (label.name.isNotEmpty()) put(label.button.toChar(), label.name)
    }
}

/**
 * The display name to position map of an open dialog.
 *
 * `configItemWithName` (`android.c:402-414`) scans the list from the
 * first item and returns the first match, so a duplicate display name
 * resolves to the earlier item. C never assumes unique names.
 */
internal fun configNameIndex(items: List<ConfigItem>): Map<String, Int> {
    val index = LinkedHashMap<String, Int>()
    for ((position, item) in items.withIndex()) {
        if (item.name !in index) index[item.name] = position
    }
    return index
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
     * Games whose Kotlin implementation is enabled in the app.
     *
     * The owner waived the two-release wait on 2026-11-18 and chose
     * enablement in waves (decision 1a). The first wave is `samegame`,
     * `flood`, and `pegs`; the second wave is `sixteen`, `flip`,
     * `twiddle`, and `guess`; the third wave is `blackbox`, `lightup`,
     * `undead`, and `rect`; the fourth wave is `slant`, `range`,
     * `towers`, and `unequal`; the fifth wave is `magnets`, `pattern`,
     * `mosaic`, `signpost`, and `untangle`; the sixth wave is
     * `galaxies`, `bridges`, `unruly`, `filling`, and `mines`; the
     * seventh wave is `netslide`, `palisade`, `inertia`, `tents`,
     * `net`, `tracks`, `dominosa`, `map`, `keen`, `singles`, `pearl`,
     * and `loopy`. Each game was checked for a `Review:` deviation, a
     * flagged size or retry debt, and an undeclared preference schema
     * before it was added. A game joins this set only after its device
     * test passes, so the set is evidence, not a plan. See
     * `docs/ROADMAP.md` Phase 4 and
     * `docs/adr/0011-enable-games-in-waves.md`.
     *
     * `cube` and `solo` are in dual-run: their engines pass the parity
     * matrix, but the app still uses C. `solo` needs a separate owner
     * decision because D-025 waives parity for it.
     */
    private val APP_GAMES = setOf(
        FIFTEEN, SAMEGAME, FLOOD, PEGS,
        SIXTEEN, FLIP, TWIDDLE, GUESS,
        BLACKBOX, LIGHTUP, UNDEAD, RECT,
        SLANT, RANGE, TOWERS, UNEQUAL,
        MAGNETS, PATTERN, MOSAIC, SIGNPOST, UNTANGLE,
        GALAXIES, BRIDGES, UNRULY, FILLING, MINES,
        NETSLIDE, PALISADE, INERTIA, TENTS, NET, TRACKS,
        DOMINOSA, MAP, KEEN, SINGLES, PEARL, LOOPY,
    )

    /**
     * The Kotlin backend for [backend], or null when the game is not
     * ported. The list is [name.boyle.chris.sgtpuzzles.engine.PortedGames],
     * which the parity suite also iterates, so adding a game there is
     * the only way to add it here.
     */
    fun gameFor(backend: BackendName): Game? =
        name.boyle.chris.sgtpuzzles.engine.PortedGames.byBackend(backend.sourceName)

    /**
     * True when the app may use the Kotlin backend for [backend].
     *
     * Two conditions, both honest. `configure` is the game's settings
     * dialog, which the app needs for a new game. [Game.declaresPrefs]
     * must agree with [Game.prefs]: a game whose C table has a
     * non-NULL `get_prefs` but whose Kotlin port forgot the schema would
     * otherwise ship without its preferences page. A NULL C `get_prefs`
     * game must not invent a schema either.
     */
    fun handles(backend: BackendName): Boolean {
        if (!ENABLED || backend !in APP_GAMES) return false
        val game = gameFor(backend) ?: return false
        return isSelectable(game)
    }

    /**
     * The per-game half of [handles], exposed so a test can check the
     * rule with a game that is not in [APP_GAMES]. The C half, that
     * [Game.declaresPrefs] is true exactly for a non-NULL C
     * `get_prefs`, is compared with the oracle in `PrefsSchemaParityTest`.
     */
    internal fun isSelectable(game: Game): Boolean =
        game.configure(game.defaultParams()) != null &&
            game.declaresPrefs == game.prefs.isNotEmpty()

    /**
     * `encode_params(default_params(), true)`, the call that
     * `android.c:966-977` makes. A later enablement uses this instead of
     * the C external, so no dialog depends on C for a Kotlin backend.
     */
    fun defaultParams(backend: BackendName): String {
        val game = requireNotNull(gameFor(backend)) { "no Kotlin backend for $backend" }
        return game.encodeParams(game.defaultParams(), true)
    }

    /**
     * The save file that the C generator would have produced.
     *
     * This is the only Kotlin entry point that may run on a worker
     * thread. It creates its own [Midend] and returns a `String`; it
     * touches no activity, view, or shared engine state. The caller owns
     * the result and posts it to the UI thread.
     *
     * [cancel] stops the generation between attempts and throws
     * [GenerationCancelled] instead of returning a board. The default
     * token is never cancelled, so a test or a caller with no cancel
     * path is unchanged.
     */
    fun generate(
        backend: BackendName,
        params: String,
        seed: String,
        cancel: Cancellation = Cancellation.NEVER,
    ): String {
        val game = requireNotNull(gameFor(backend)) { "no Kotlin backend for $backend" }
        // The frontend seed reaches only frontend state, so it cannot
        // change the board or the records that the oracle writes. It
        // uses the app value here, as the C frontend does.
        return Midend.newGame(game, params, seed, platformSeed(), cancel = cancel)
            .serialise()
            .serialise()
    }

    /** A fresh printable seed, used when the chooser did not provide one. */
    fun newSeed(): String = java.util.UUID.randomUUID().toString()

    /**
     * The warning to show before [launch] with the resolved [params], or
     * null. Only a fresh generation warns: a save, a game ID, and a
     * restored local state load an existing board and generate nothing.
     */
    fun generationWarning(launch: GameLaunch, params: String): String? =
        generationWarning(launch, params, gameFor(launch.whichBackend))

    /**
     * [game] is the engine that would generate for [launch]. The
     * parameter is a seam, so a test can ask the gate with a game that
     * is not in [APP_GAMES] and does declare a slow set.
     */
    internal fun generationWarning(launch: GameLaunch, params: String, game: Game?): String? =
        if (!launch.needsGenerating || !handles(launch.whichBackend)) {
            null
        } else {
            game?.generationWarning(game.decodeParams(params))
        }
}

/**
 * The first Kotlin-backed [GameEngine]. It owns a [Midend] and forwards
 * the drawing primitives to [GameEngine.ViewCallbacks], the same seam
 * the C engine uses.
 *
 * The settings, seed, game-ID, and preference dialogs come from the
 * game's engine schema ([Game.configure] and [Game.prefs]), so this
 * class has no field of one game's parameters. A game that declares no
 * schema is never enabled: [KotlinGames.handles] requires `configure`
 * and requires `declaresPrefs` to agree with the schema.
 *
 * Ordering and threading: this class, its [Midend], its [Game], the game
 * UI, and the draw state are not thread-safe and are used on the UI
 * thread only. The one exception is [KotlinGames.generate], which may
 * run on a worker because it returns a `String` and touches none of
 * them. The stored preferences reach the [Midend] factory
 * ([KotlinGameEngine.fromLaunch]), so they are applied before the first
 * geometry ([BoardGeometry.preferred]) and before the first resize.
 * A preference dialog applies each edit to the midend before
 * [savePrefs] writes the stored text.
 */
class KotlinGameEngine private constructor(
    private val game: Game,
    private var midend: Midend,
    private val backend: BackendName,
    private val activityCallbacks: GameEngine.ActivityCallbacks,
    private var viewCallbacks: GameEngine.ViewCallbacks,
) : GameEngine, ConfigBuilder.EngineCallbacks {

    private enum class ConfigMode { SETTINGS, SEED, DESC, PREFS }

    private val state: Game.State get() = midend.state

    /**
     * The initial geometry. The factory applied the stored preferences
     * to the midend before this object exists, so a preference that
     * changes [Game.computeSize] is already in the UI here.
     */
    private var geometry = BoardGeometry.preferred(game, midend.params, midend.ui)
    private var viewWidth = 1
    private var viewHeight = 1
    private var sized = false

    /** The clock reading of the previous timer tick, in nanoseconds. */
    private var timerBaselineNanos = System.nanoTime()

    private var configMode: ConfigMode? = null

    /** The items of the open dialog, in C order. */
    private var configItems: List<ConfigItem> = emptyList()

    /** Name to index in [configItems]; C assumes unique names. */
    private var configIndex: kotlin.collections.Map<String, Int> = emptyMap()

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
                DrawSink.clampThickness(thickness),
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
        val mode = when (ConfigBuilder.Event.fromJNI(whichEvent)) {
            ConfigBuilder.Event.CFG_SETTINGS -> ConfigMode.SETTINGS
            ConfigBuilder.Event.CFG_SEED -> ConfigMode.SEED
            ConfigBuilder.Event.CFG_DESC -> ConfigMode.DESC
            ConfigBuilder.Event.CFG_PREFS -> ConfigMode.PREFS
        }
        configMode = mode

        /*
         * C uses the long-term parameters for the settings dialog
         * (`me->params`, `midend.c:1737`) and the current parameters for
         * the seed and game-ID dialogs (`me->curparams`,
         * `midend.c:1772-1795`).
         */
        val items = when (mode) {
            ConfigMode.SETTINGS -> game.configure(midend.params) ?: return
            ConfigMode.SEED -> listOf(
                ConfigItem.Text(
                    "Seed",
                    game.encodeParams(midend.currentParams, true) + "#" + (midend.seed ?: ""),
                ),
            )

            ConfigMode.DESC -> listOf(
                ConfigItem.Text("Game ID", shortParams() + ":" + midend.desc),
            )

            ConfigMode.PREFS -> midend.prefItems
        }
        configItems = items
        configIndex = configNameIndex(items)

        val title = when (mode) {
            ConfigMode.SETTINGS -> "${game.name} configuration"
            ConfigMode.SEED -> "${game.name} random selection"
            ConfigMode.DESC -> "${game.name} game selection"
            ConfigMode.PREFS -> "${game.name} preferences"
        }
        val isPrefs = mode == ConfigMode.PREFS
        builder.setTitle(title)
        for (item in items) {
            /*
             * `android.c:492-512`: a settings item passes its display
             * name as the keyword, and a preference item passes its
             * real keyword. A settings choice has no keyword list, so
             * C passes the choice names in its place
             * (`android.c:505-508`).
             */
            val kw = if (isPrefs) item.kw else item.name
            when (item) {
                is ConfigItem.Text -> builder.addString(whichEvent, kw, item.name, item.value)
                is ConfigItem.Bool -> builder.addBoolean(whichEvent, kw, item.name, item.value)
                is ConfigItem.Choices -> {
                    val keywords = if (isPrefs) item.keywords ?: item.choices else item.choices
                    builder.addChoices(
                        whichEvent,
                        kw,
                        item.name,
                        item.choices,
                        keywords,
                        item.selected,
                    )
                }
            }
        }
        builder.dialogShow()
    }

    override fun configSetString(itemPtr: String, s: String, isPrefs: Boolean) {
        replaceConfigItem(itemPtr) {
            (it as ConfigItem.Text).copy(value = s)
        }
        applyPrefsIfRequested(isPrefs)
    }

    override fun configSetBool(itemPtr: String, selected: Boolean, isPrefs: Boolean) {
        replaceConfigItem(itemPtr) {
            (it as ConfigItem.Bool).copy(value = selected)
        }
        applyPrefsIfRequested(isPrefs)
    }

    override fun configSetChoice(itemPtr: String, selected: Int, isPrefs: Boolean) {
        replaceConfigItem(itemPtr) {
            (it as ConfigItem.Choices).copy(selected = selected)
        }
        applyPrefsIfRequested(isPrefs)
    }

    override fun configCancel() {
        configItems = emptyList()
        configIndex = emptyMap()
        configMode = null
    }

    override fun configOK(): String = when (configMode) {
        ConfigMode.SETTINGS -> {
            // `midend_config_to_encoded_params`: custom_params, the full
            // validation, then the full encoding (`midend.c:2031-2042`).
            val params = game.customParams(configItems)
            game.validateParams(params, true)?.let { throw IllegalArgumentException(it) }
            game.encodeParams(params, true)
        }

        ConfigMode.SEED -> {
            val full = fullSeed
            Midend.validateGameId(game, full, isSeed = true, midend.params)?.let {
                throw IllegalArgumentException(it)
            }
            full
        }

        ConfigMode.DESC -> {
            val full = fullGameId
            Midend.validateGameId(game, full, isSeed = false, midend.params)?.let {
                throw IllegalArgumentException(it)
            }
            full
        }

        ConfigMode.PREFS, null -> {
            configMode = null
            shortParams()
        }
    }

    override val fullGameIDFromDialog: String get() = fullGameId

    override val fullSeedFromDialog: String get() = fullSeed

    override fun savePrefs(context: Context) {
        // The prefs dialog applies every edit with configSet* before it
        // calls this, so the stored text is the new state.
        GameEngineImpl.savePrefs(context, backend, midend.serialisePrefs())
    }

    override fun loadPrefs(context: Context) {
        // `android.c:648-656` ignores a parse error; the stored values
        // stay as they were.
        midend.applyPrefs(GameEngineImpl.getPrefs(context, backend))
    }

    override fun requestKeys(
        backend: BackendName,
        params: String?,
        palisadeFullCursor: Boolean,
    ): GameEngine.KeysResult = keysResultFor(game, midend.currentParams, palisadeFullCursor)

    override fun keyNames(
        backend: BackendName,
        params: String?,
        palisadeFullCursor: Boolean,
    ): Map<Char, String> = keyNamesFor(game, midend.currentParams, palisadeFullCursor)

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
        val error = midend.solveAttempt()
        if (error != null) throw IllegalArgumentException(error)
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

    /** The current game ID, the counterpart of `midend_get_game_id`; for tests. */
    @get:VisibleForTesting
    val gameId: String
        get() = midend.id()

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

    private fun applyPrefsIfRequested(isPrefs: Boolean) {
        if (isPrefs) {
            /*
             * `midend_set_config(CFG_PREFS)` applies every edit at once
             * (`android.c:425-441`). The prefs builder then calls
             * `savePrefs`, so the stored text is the new state.
             */
            midend.applyPrefs(configItems)
        }
    }

    /** Replaces one open-dialog item by its display name, as C does. */
    private fun replaceConfigItem(name: String, update: (ConfigItem) -> ConfigItem) {
        val index = configIndex[name] ?: error("no config item named '$name'")
        val next = configItems.toMutableList()
        next[index] = update(next[index])
        configItems = next
    }

    /** The first text item, which seed and game-ID dialogs always have. */
    private fun firstTextValue(): String =
        (configItems.firstOrNull { it is ConfigItem.Text } as? ConfigItem.Text)?.value ?: ""

    private val fullGameId: String
        get() {
            val value = firstTextValue()
            val full = if (value.contains(':')) value else shortParams() + ":" + value
            Midend.validateGameId(game, full, isSeed = false, midend.params)?.let {
                throw IllegalArgumentException(it)
            }
            return full
        }

    private val fullSeed: String
        get() {
            val value = firstTextValue()
            val params = game.encodeParams(midend.currentParams, true)
            val full = if (value.contains('#')) value else params + "#" + value
            Midend.validateGameId(game, full, isSeed = true, midend.params)?.let {
                throw IllegalArgumentException(it)
            }
            return full
        }

    private fun shortParams(): String = game.encodeParams(midend.currentParams, false)

    private fun afterStateChange() {
        activityCallbacks.changedState(midend.canUndo, midend.canRedo)
        if (midend.takeCompletedEvent()) activityCallbacks.completed()
        redraw()
    }

    override fun forceFullRedraw() {
        // redraw() replaces the drawstate itself, so nothing is needed
        // here; see the forceRedraw() call below.
    }

    private fun redraw() {
        // Every pass repaints the whole board: a fresh cache draws
        // everything, and the first-draw clear lets the renderer drop
        // the passes before it instead of replaying the session.
        midend.forceRedraw()
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
        // `game_redraw` calls `status_bar` only when the game wants one
        // (`Game.wantsStatusBar`), so the shell keeps the previous line
        // for a game without a status bar.
        if (game.wantsStatusBar) activityCallbacks.setStatus(midend.statusText())
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
            /*
             * The stored preferences go to the factory, which applies
             * them after it builds the UI. They are therefore in place
             * before this engine reads its first geometry and before the
             * first resize, as `midend_load_prefs` precedes
             * `midend_size` in C.
             */
            val prefs = GameEngineImpl.getPrefs(context, launch.whichBackend)
            val midend = try {
                when {
                    launch.saved != null -> Midend.fromSave(
                        game,
                        SgtpSave.parse(launch.saved),
                        platformSeed(),
                        prefs,
                    )

                    launch.gameID != null -> Midend.fromGameId(
                        game,
                        launch.gameID,
                        platformSeed(),
                        prefs,
                    )

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
            )
        }
    }
}
