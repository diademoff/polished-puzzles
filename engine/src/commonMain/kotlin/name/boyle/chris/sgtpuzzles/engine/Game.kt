package name.boyle.chris.sgtpuzzles.engine

/**
 * The contract between the portable game logic and the shell.
 *
 * This is the Kotlin counterpart of the upstream `struct game`: a game
 * owns its parameters, its description generator, its state, its moves,
 * its solver, and its drawing. The midend in `Midend.kt` drives it.
 *
 * The interface grows with the games that use it. Only the operations
 * for the ported games are declared, so an unimplemented operation is a
 * compile error rather than a silent stub.
 */
interface Game {

    /** The display name used in a save file, for example `Fifteen`. */
    val name: String

    /** The backend name used by the chooser and the oracle, for example `fifteen`. */
    val backendName: String

    /** The preferred tile size in device-independent pixels. */
    val preferredTileSize: Int

    /** True when the shell should show the game's status text. */
    val wantsStatusBar: Boolean get() = true

    /**
     * True when the game has a solver. The shell hides the Solve menu
     * item for a game that cannot solve, as upstream `can_solve` does.
     */
    val canSolve: Boolean get() = true

    /**
     * True when the game keeps an elapsed-time clock, the counterpart of
     * upstream `is_timed`. A timed game writes the `TIME` save record
     * and prefixes its status text with `[m:ss] `. `mines` is the only
     * upstream timed game, so every ported game keeps the default.
     */
    val isTimed: Boolean get() = false

    /**
     * The upstream `flags` word: the `BUTTON_BEATS` priority bits and
     * the `SOLVE_ANIMATES`, `REQUIRE_RBUTTON`, and `REQUIRE_NUMPAD`
     * bits. The seam reads only the button priority; the other bits
     * describe shell features and have no engine behavior yet. Every
     * ported game keeps the default.
     */
    val flags: Int get() = 0

    /**
     * `me->ourgame->flags & BUTTON_BEATS(x, y)`: true when this game
     * gives button [x] priority over button [y], so an overlapping
     * press of [y] is ignored while [x] is held. A game that declares
     * the priority sets [buttonBeatsBit] in [flags]; `mines` is the
     * first such game, with `BUTTON_BEATS(LEFT_BUTTON, RIGHT_BUTTON)`.
     * A game with no priority returns false for every pair.
     */
    fun buttonBeats(x: Int, y: Int): Boolean =
        (flags and buttonBeatsBit(x, y)) != 0

    companion object {
        /**
         * The warning that a slow generator returns. One text keeps the
         * shell dialog and the engine tests in agreement; a game adds
         * the reason for its own threshold in a comment at the override.
         */
        const val SLOW_GENERATION_WARNING =
            "This board may take a long time to generate."

        /**
         * The generation guard text for a board whose area is above the
         * game's measured cap. One text keeps the area family in
         * agreement, and a game names its own cap in a comment at the
         * guard.
         */
        const val AREA_TOO_LARGE =
            "Width times height is too large for this build"

        /** `SOLVE_ANIMATES`: the shell animates a solve. */
        const val SOLVE_ANIMATES = 1 shl 9

        /** `REQUIRE_RBUTTON`: the game needs a right-button emulation. */
        const val REQUIRE_RBUTTON = 1 shl 10

        /** `REQUIRE_NUMPAD`: the game needs numeric input. */
        const val REQUIRE_NUMPAD = 1 shl 11

        /**
         * The `BUTTON_BEATS(x, y)` bit for [flags], as the C macro
         * computes it: `1 << ((x - LEFT_BUTTON) * 3 + (y - LEFT_BUTTON))`.
         */
        fun buttonBeatsBit(x: Int, y: Int): Int =
            1 shl ((x - Buttons.LEFT_BUTTON) * 3 + (y - Buttons.LEFT_BUTTON))
    }

    fun defaultParams(): Params

    /** The presets in menu order. The title is the menu label. */
    fun presets(): List<Preset>

    fun decodeParams(text: String): Params

    /**
     * `decode_params` over [base].
     *
     * C decodes a parameter string into a copy of the caller's
     * parameters (`midend.c:1890-1906` and `:1938-1950`), so a field
     * that [text] does not mention keeps the base value. The seam's
     * one-argument [decodeParams] starts from the compile-time default,
     * which is what C does for a parameter-only game id
     * (`midend.c:1905-1907`) but not for `params:desc`.
     *
     * The default ignores [base] because a game's decoder cannot express
     * "over a base" without knowing its fields. It is correct for a game
     * whose `decode_params` writes every field it later reads. A game
     * that keeps a base field and whose short encoding carries it
     * overrides this, as `loopy` does for its grid type and `cube` for
     * its solid.
     */
    fun decodeParams(text: String, base: Params): Params = decodeParams(text)

    fun encodeParams(params: Params, full: Boolean): String

    /** Returns an error message, or null when the parameters are valid. */
    fun validateParams(params: Params, full: Boolean): String?

    /**
     * `game_configure`: the settings dialog for [params], in C item
     * order. The default null means the game has not declared a schema
     * yet, and the app must not enable it. The non-null return is the
     * gate that makes [customParams] meaningful.
     */
    fun configure(params: Params): List<ConfigItem>? = null

    /**
     * `custom_params`: the parameters that the edited settings items
     * describe. A game that returns a non-null [configure] must
     * implement this; the default throws instead of hiding a missing
     * implementation behind a crash in a dialog.
     */
    fun customParams(items: List<ConfigItem>): Params =
        throw UnsupportedOperationException(
            "$backendName declares no configuration schema; " +
                "implement customParams with configure",
        )

    /**
     * `get_prefs` for a fresh UI: the schema and the compile-time
     * defaults, with no [Ui] and no generation. Empty when C has a NULL
     * `get_prefs`, which is most games.
     *
     * The list is static on purpose. `midend_get_prefs` with no live UI
     * calls `new_ui(NULL)` and reads only the compile-time fields
     * (`midend.c:2986-3003`), so a static list equals
     * `get_prefs(new_ui(NULL))`, which is exactly what the preferences
     * page needs.
     */
    val prefs: List<ConfigItem> get() = emptyList()

    /**
     * True when the C backend has a non-NULL `get_prefs`, so this game
     * must return a non-empty [prefs] and implement [setPrefs].
     *
     * The default is false, which matches the 21 ported backends whose C
     * table passes NULL for both `get_prefs` and `set_prefs`. A game
     * with the C function sets `true` in the same commit that ports its
     * schema. This is the second half of the [KotlinGames] gate: a
     * `configure` schema alone must not enable a backend whose C
     * preferences page would silently disappear.
     */
    val declaresPrefs: Boolean get() = false

    /** `set_prefs`: applies [prefs] to a live UI. */
    fun setPrefs(ui: Ui, prefs: Prefs) {}

    /**
     * Generates a description and, when the game has one, the auxiliary
     * solution text that upstream stores in `AUXINFO`.
     */
    fun newDesc(params: Params, random: RandomState): Description

    /**
     * [newDesc] with a cancellation token.
     *
     * The token is read at the top of an attempt only, never inside a
     * draw, so it changes no random number and an accepted generation
     * with the same seed gives the same description either way. A game
     * whose generation can exceed a second overrides this form and
     * checks the token in the retry loops that can run long; the default
     * keeps a game with one short pass unchanged.
     */
    fun newDesc(params: Params, random: RandomState, cancel: Cancellation): Description =
        newDesc(params, random)

    /**
     * A short shell warning when generation of [params] is known to be
     * slow, or null when it is fast. The default null keeps every game
     * that has no measured slow point unchanged.
     *
     * This is a user warning, not a guard: it changes no parameter, no
     * RNG draw, and no description. The shell shows it before it starts
     * a fresh generation and lets the user continue or cancel. A game
     * declares the parameter sets whose work is unbounded or measurably
     * long in C, with the reason in a comment at the override.
     */
    fun generationWarning(params: Params): String? = null

    /** Returns an error message, or null when the description is valid. */
    fun validateDesc(params: Params, desc: String): String?

    fun newState(params: Params, desc: String): State

    /** The move that solves the state, or null when the game cannot solve. */
    fun solve(state: State, auxInfo: String? = null): String?

    /**
     * `solve` with the error channel of `midend_solve`
     * (`midend.c:2096-2123`). A game that has a solver message returns
     * it in [SolveOutcome.error]; the default wraps [solve] and has no
     * message, so every existing game is unchanged.
     */
    fun solveGame(state: State, auxInfo: String? = null): SolveOutcome =
        SolveOutcome(solve(state, auxInfo), null)

    /**
     * `solve_game` with the first state of the history, the `orig`
     * argument of `midend_solve` (`midend.c:2109-2111`). C solves from
     * the state the history started at, not from the current entry, so
     * a `RESTART` record with a different description still solves the
     * first description.
     *
     * The default drops [initialState] and calls [solveGame], so every
     * game whose solver reads only the current state is unchanged.
     * `solo` overrides this. The other games read the first state from
     * their own snapshot (`Shared`, `initial`), which equals
     * [initialState] for every history the app writes; a `RESTART` with
     * a different description exists only in a hand-written save.
     */
    fun solveGame(state: State, auxInfo: String?, initialState: State): SolveOutcome =
        solveGame(state, auxInfo)

    /** Applies one move, or returns null when the move is invalid. */
    fun executeMove(state: State, move: String): State?

    /**
     * The public description of [state], or null when the game does not
     * replace its description. It is the game half of
     * `midend_supersede_game_desc`: a move can resolve a pending
     * description, and the midend then stores the new public text in
     * `DESC` and the private text from [privateDesc] in `PRIVDESC`.
     * `mines` is the first consumer: the first click generates the mine
     * layout, and the layout becomes the public description.
     *
     * The default null leaves the description from the generation or
     * the save file unchanged, so every existing game is unaffected.
     * The text must be printable ASCII, as the save writer requires.
     */
    fun publicDesc(state: State): String? = null

    /**
     * The private description that belongs to [state], or null when the
     * game has none. It is written as `PRIVDESC` next to the public
     * description, and a load rebuilds state 0 from it before it applies
     * [setPublicDesc]. Only a game whose [publicDesc] resolves a pending
     * description returns a value here.
     */
    fun privateDesc(state: State): String? = null

    /**
     * `set_public_desc`: applies the public description [text] to a
     * state that was rebuilt from `PRIVDESC`. The load path calls it
     * after `newState`, so the game can restore the part of the
     * description that the private text omits, such as the first-click
     * coordinate of `mines`.
     *
     * The default returns [state] unchanged, so a game without a
     * private description never needs it.
     */
    fun setPublicDesc(state: State, text: String): State = state

    /** `midend_status`: 1 win, -1 lose, 0 open. */
    fun status(state: State): Int

    /**
     * The status bar text for the state. The [ui] carries frontend state
     * that the text can show, such as a selection count. A game whose
     * text only depends on the state ignores it.
     */
    fun statusText(state: State, ui: Ui): String

    /**
     * True when [textFormat] can describe the state, the counterpart of
     * upstream `can_format_as_text_ever`. A game that sets it false has
     * no `text_format` in C. [Midend.textFormat] then returns an empty
     * string and does not call [textFormat].
     */
    val canFormatAsText: Boolean get() = true

    /**
     * The params-aware gate of `can_format_as_text_now`, the second half
     * of `midend_can_format_as_text_now` (`midend.c:2079-2085`).
     * [canFormatAsText] mirrors the static `can_format_as_text_ever`; a
     * game can also reject the text form for one parameter set, as
     * `loopy` does for a non-square grid (`loopy.c:1144-1149`).
     * [Midend.textFormat] asks both before it calls [textFormat].
     *
     * The default returns [canFormatAsText], so every ported game keeps
     * its behavior and a game without a text form still answers false
     * for every parameter set.
     */
    fun canFormatAsTextNow(params: Params): Boolean = canFormatAsText

    fun textFormat(state: State): String

    /**
     * Mirrors `game_changed_state`: the midend calls it after it enters
     * [newState] from [oldState], so the game can update frontend state.
     * [oldState] is null only when there is no state to leave. The
     * return value is true when this transition completed the game.
     */
    fun changedState(ui: Ui, oldState: State?, newState: State): Boolean = false

    /**
     * Mirrors `game_timing_state`: true when the clock of a timed game
     * should run for this state and frontend state, for example while
     * the board is live and unsolved. The midend calls it whenever the
     * state or the UI can change, and starts or stops the shell timer
     * with the result.
     *
     * The default false. An untimed game never runs a clock, so it
     * needs no override; a timed game declares the real rule.
     */
    fun timingState(state: State, ui: Ui): Boolean = false

    fun requestKeys(params: Params): KeyRequest

    /**
     * The requested keys with the Android full-cursor flag.
     *
     * [fullCursor] is not UI state and is not a game rule. It is the
     * Android C hack: `midend_request_keys_by_game` presets the arrow
     * mode of `game_request_keys` to `ANDROID_ARROWS_ONLY` to tell
     * `palisade` that the shell uses a full-grid cursor, so the game can
     * return four arrow labels instead of none (`midend.c:1294`,
     * `palisade.c:742-771`). A game without that hack ignores the flag,
     * so the default keeps the one-argument request. The palisade port
     * overrides this operation and reads [fullCursor], as the C game
     * reads the preset arrow mode.
     */
    fun requestKeys(params: Params, fullCursor: Boolean): KeyRequest = requestKeys(params)

    /** The physical size of the board for a tile size. */
    fun computeSize(params: Params, tileSize: Int): Size

    /**
     * The physical size of the board for a tile size and the current
     * frontend state.
     *
     * Upstream `game_compute_size` takes the UI because a game can pick
     * its border from a preference. `pearl.c:2446-2447` uses
     * `BORDER`, which is `TILE_SIZE/8` in the Loopy appearance and
     * `TILE_SIZE/2` in the Masyu appearance
     * (`pearl.c:2008`). The size is therefore not a function of the
     * parameters and the tile size alone. The default calls the
     * two-argument form, so a game whose geometry does not read the UI
     * declares nothing and keeps its arithmetic.
     */
    fun computeSize(params: Params, ui: Ui, tileSize: Int): Size =
        computeSize(params, tileSize)

    /** The palette as RGB triples in 0..1, using the default background. */
    fun colours(): FloatArray

    /** The palette with the shell's background colour, when it matters. */
    fun colours(background: FloatArray): FloatArray = colours()

    /**
     * Draws a complete board into [sink], not an incremental update.
     * The [ui] carries the frontend state that affects drawing, such
     * as a visible cursor. A game without such state ignores it.
     *
     * [drawState] is the cache that [newDrawState] returned. A game
     * that caches a background reads it here; a game without a cache
     * ignores it.
     */
    fun redraw(
        state: State,
        ui: Ui,
        drawState: DrawState,
        sink: DrawSink,
        tileSize: Int,
    )

    /**
     * Turns a frontend key into a move. It mirrors `interpret_move`, so
     * the result distinguishes a move, a UI-only change, a key the game
     * ignores, and a key that is valid but does nothing.
     *
     * [button] is canonical, as `midend_process_key` makes it: `CTRL`
     * and `SHFT` stay only on a cursor move and on TAB, and
     * `MOD_NUM_KEYPAD` stays only on a digit. A game that reads a
     * modifier compares the exact button; a game that does not use a
     * modifier calls [Buttons.stripModifiers] at its entry. A modified
     * cursor move is therefore a game rule: `fifteen` strips it, while
     * `slant`, `samegame`, `flood`, and `flip` reject it as unused.
     */
    fun interpretMove(
        state: State,
        ui: Ui,
        x: Int,
        y: Int,
        button: Int,
        tileSize: Int,
    ): MoveResult

    /** Kotlin has no C structs; each game defines its own nested types. */
    interface Params {
        fun copy(): Params
    }

    interface State {
        fun copy(): State
    }

    /**
     * Frontend state, the counterpart of upstream `game_ui`. It is not
     * part of [State], the history, equality, or the `.sgtp` state
     * payload. A game whose upstream struct has an `encode_ui` stores
     * its UI in the separate `UI` record; every other game has no such
     * record, so a load creates a fresh UI.
     */
    interface Ui

    /** A game with no frontend state of its own. */
    data object NoUi : Ui

    /**
     * The drawing cache, the counterpart of upstream `game_drawstate`.
     * It is not part of the save file. The midend creates it before a
     * draw and frees it with [freeDrawState].
     */
    interface DrawState

    /** A game that caches nothing between draws. */
    data object NoDrawState : DrawState

    /**
     * `game_new_drawstate`: allocates the cache for [state] at
     * [tileSize]. A game that uses blitters calls [DrawSink.blitterNew]
     * here, so the allocation happens before the first `start_draw` of
     * the drawstate, as a fresh C drawstate does.
     *
     * [state] is the first state of the history, as C's
     * `states[0].state`: `midend_size` and `midend_force_redraw` create
     * the drawstate that way. It is not necessarily the current state.
     */
    fun newDrawState(state: State, tileSize: Int, sink: DrawSink): DrawState = NoDrawState

    /** `game_free_drawstate`: releases what [newDrawState] allocated. */
    fun freeDrawState(drawState: DrawState, sink: DrawSink) {}

    /** A cursor rectangle in game coordinates. */
    data class CursorRect(val left: Int, val top: Int, val width: Int, val height: Int)

    fun newUi(state: State): Ui = NoUi

    /**
     * `new_ui` with the frontend random seed.
     *
     * [platformSeed] is the bytes the frontend `get_random_seed`
     * returns, not the game seed. The C game takes them from the
     * frontend to create a random state that is independent of the
     * board (`net.c:2061-2085`), so it cannot come from `Midend.seed`.
     * The platform seed is never saved. The default ignores it and
     * calls [newUi], so every game that does not need it is unchanged.
     */
    fun newUi(state: State, platformSeed: ByteArray): Ui = newUi(state)

    /**
     * `encode_ui`: the value of the save file's `UI` record, or null
     * when this game has no such record. The value must be printable
     * ASCII, so the writer rejects anything else instead of writing a
     * corrupt file.
     */
    fun encodeUi(ui: Ui): String? = null

    /**
     * `decode_ui`: restores the UI from the value of a `UI` record.
     * [state] is the state the save was in, as C passes
     * `states[statepos-1].state` to `decode_ui`. A save without the
     * record, and a game that ignores it, keep the fresh UI from
     * [newUi].
     */
    fun decodeUi(state: State, text: String): Ui = newUi(state)

    /**
     * `decode_ui` with the UI that [newUi] created for the save.
     *
     * [base] is the fresh UI from [newUi] with the platform seed, as C
     * keeps `data.ui` from `new_ui` and passes it to `decode_ui`
     * (`midend.c:2669-2673`). A game that stores frontend random state
     * in the UI keeps it across a load because the record does not
     * contain it. The default calls the two-argument [decodeUi], so a
     * game that does not need the base is unchanged.
     */
    fun decodeUi(state: State, text: String, base: Ui): Ui = decodeUi(state, text)

    fun setCursorVisible(ui: Ui, visible: Boolean) {}

    /** The cursor rectangle for the shell, or null when there is no cursor. */
    fun cursorLocation(ui: Ui, state: State, tileSize: Int): CursorRect? = null
}

data class Preset(val title: String, val params: String)

data class Description(val text: String, val auxInfo: String? = null)

/**
 * The result of `game.solve`: the move, or the C error message when the
 * game has no move and knows why. [Midend.solveAttempt] turns a null
 * move into the `midend_solve` text.
 */
data class SolveOutcome(val move: String?, val error: String?)

data class Size(val width: Int, val height: Int)

/**
 * Which extra keys a game wants on the Android keyboard. `arrowsOnly`
 * means the shell should show only the four cursor keys.
 */
data class KeyRequest(val labels: List<KeyLabel>, val arrowMode: ArrowMode)

/**
 * One extra key, the counterpart of upstream `key_label`.
 *
 * [needsArrows] mirrors `key_label.needs_arrows`: the shell shows the
 * key only when it shows the arrow keys, so `android.c` puts such a key
 * in the `keysIfArrows` string instead of `keys`. A game that does not
 * use the flag keeps the default false.
 */
data class KeyLabel(val button: Int, val name: String, val needsArrows: Boolean = false)

/**
 * The arrow mode of a [KeyRequest]. Each mode corresponds to an
 * `ANDROID_ARROWS_*` constant in `puzzles.h`; [ARROWS_DIAGONALS] is
 * `ANDROID_ARROWS_DIAGONALS`, the mode that adds the four diagonal
 * keypad keys.
 */
enum class ArrowMode {
    NONE,
    ARROWS_ONLY,
    ARROWS_LEFT_RIGHT,
    ARROWS_LEFT,
    ARROWS_DIAGONALS,
}

sealed interface MoveResult {
    /** A move string that `executeMove` accepts. */
    data class Move(val text: String) : MoveResult

    /** The game redraws, but no state is added. */
    data object UiUpdate : MoveResult

    /** The key is valid but changes nothing. */
    data object NoEffect : MoveResult

    /** The game does not use this key. */
    data object Unused : MoveResult
}

/** Button values from upstream `puzzles.h`. */
object Buttons {
    const val LEFT_BUTTON = 0x0200
    const val MIDDLE_BUTTON = 0x0201
    const val RIGHT_BUTTON = 0x0202
    const val LEFT_DRAG = 0x0203
    const val MIDDLE_DRAG = 0x0204
    const val RIGHT_DRAG = 0x0205
    const val LEFT_RELEASE = 0x0206
    const val MIDDLE_RELEASE = 0x0207
    const val RIGHT_RELEASE = 0x0208
    const val CURSOR_UP = 0x0209
    const val CURSOR_DOWN = 0x020A
    const val CURSOR_LEFT = 0x020B
    const val CURSOR_RIGHT = 0x020C
    const val CURSOR_SELECT = 0x020D
    const val CURSOR_SELECT2 = 0x020E
    const val UI_LOWER_BOUND = 0x020F
    const val UI_QUIT = 0x0210
    const val UI_NEWGAME = 0x0211
    const val UI_SOLVE = 0x0212
    const val UI_UNDO = 0x0213
    const val UI_REDO = 0x0214
    const val UI_UPPER_BOUND = 0x0215
    const val MOD_CTRL = 0x1000
    const val MOD_SHFT = 0x2000
    const val MOD_NUM_KEYPAD = 0x4000
    const val MOD_MASK = 0x7000

    /** `STRIP_BUTTON_MODIFIERS`: the button without the three modifiers. */
    fun stripModifiers(button: Int): Int = button and MOD_MASK.inv()

    fun isCursorMove(button: Int): Boolean =
        button == CURSOR_UP || button == CURSOR_DOWN ||
            button == CURSOR_LEFT || button == CURSOR_RIGHT

    /** `IS_UI_FAKE_KEY`: a menu action, never a game key. */
    fun isUiFakeKey(button: Int): Boolean =
        button > UI_LOWER_BOUND && button < UI_UPPER_BOUND

    /** `IS_MOUSE_DOWN`: one of the three button presses. */
    fun isMouseDown(button: Int): Boolean =
        (button - LEFT_BUTTON) in 0..(RIGHT_BUTTON - LEFT_BUTTON)

    /** `IS_MOUSE_DRAG`: one of the three button drags. */
    fun isMouseDrag(button: Int): Boolean =
        (button - LEFT_DRAG) in 0..(RIGHT_DRAG - LEFT_DRAG)

    /** `IS_MOUSE_RELEASE`: one of the three button releases. */
    fun isMouseRelease(button: Int): Boolean =
        (button - LEFT_RELEASE) in 0..(RIGHT_RELEASE - LEFT_RELEASE)
}

/**
 * A drawing sink. The upstream `drawing_api` is much larger; this seam
 * has the primitives the ported games use. It grows with each game, in
 * the same commit as the game that needs the primitive.
 */
interface DrawSink {
    companion object {
        /**
         * `draw_thick_line`: the thickness floor. Upstream replaces a
         * thickness below one pixel with `1.0F` before it calls the
         * drawing API (`drawing.c:117-118`), and the oracle records the
         * clamped value. Every sink applies this rule, so the trace and
         * the view see what C passes and not the raw argument.
         */
        fun clampThickness(thickness: Float): Float =
            if (thickness < 1.0f) 1.0f else thickness
    }

    fun changedState(canUndo: Boolean, canRedo: Boolean)
    fun startDraw()
    fun endDraw()
    fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int)
    fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int)

    /**
     * `draw_thick_line`: a line with [thickness] pixels. The endpoints
     * keep the order of [line], and they are floats because upstream
     * takes `float` coordinates. A game that draws fractional geometry
     * must not truncate it here. The trace stores thousandths and rounds
     * each value with the oracle's `tc_milli` rule, so an integer
     * coordinate converts exactly. The upstream `drawing_api` and the
     * Android `ViewCallbacks.drawLine` callback take the thickness
     * first, and both receive [clampThickness] of the argument, because
     * `draw_thick_line` clamps a value below one pixel
     * (`drawing.c:117-118`).
     */
    fun thickLine(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, colour: Int)

    fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int)

    /**
     * `draw_thick_circle`: a circle with a [thickness] pixel outline.
     * The centre, the radius, and the two colours keep the order of
     * [circle]; the upstream `drawing_api` and the Android
     * `ViewCallbacks.drawCircle` callback take the thickness first.
     */
    fun thickCircle(
        cx: Int,
        cy: Int,
        radius: Int,
        thickness: Float,
        fillColour: Int,
        outlineColour: Int,
    )

    fun poly(points: IntArray, fillColour: Int, outlineColour: Int)

    /**
     * `draw_thick_polygon`: a polygon with a [thickness] pixel outline.
     * The points are the flat `x0, y0, x1, y1, ...` list of [poly], and
     * the two colours keep the order of [poly]. Upstream
     * `draw_thick_polygon(thickness, coords, npoints, fill, outline)`
     * takes the thickness first and the two colours last, so the sink
     * places them there. The trace stores the thickness in thousandths
     * and rounds it with the oracle's `tc_milli` rule; the integer
     * coordinates are stored as they are.
     */
    fun thickPoly(
        points: IntArray,
        thickness: Float,
        fillColour: Int,
        outlineColour: Int,
    )

    fun text(x: Int, y: Int, font: Int, size: Int, align: Int, colour: Int, text: String)
    fun clip(x: Int, y: Int, width: Int, height: Int)
    fun unclip()
    fun update(x: Int, y: Int, width: Int, height: Int)
    fun status(text: String)

    /**
     * `inertia_follow`: the Android `drawing_api` follow callback. The
     * game reports whether the auto-solver still steers the player to
     * the next step, so the shell can hide the primary controls. It is a
     * shell hint, not a game rule, and it changes no state.
     */
    fun inertiaFollow(isSolved: Boolean)

    /**
     * `blitter_new`: allocates a [width] x [height] image buffer and
     * returns the id that [blitterFree], [blitterSave], and
     * [blitterLoad] use for it.
     */
    fun blitterNew(width: Int, height: Int): Int

    /** `blitter_free`: releases a buffer from [blitterNew]. */
    fun blitterFree(id: Int)

    /** `blitter_save`: captures the buffer's area at x, y. */
    fun blitterSave(id: Int, x: Int, y: Int)

    /** `blitter_load`: draws the captured area back at x, y. */
    fun blitterLoad(id: Int, x: Int, y: Int)
}

/** Font and alignment constants from upstream `puzzles.h`. */
object Text {
    const val FONT_FIXED = 0
    const val FONT_VARIABLE = 1
    const val ALIGN_VNORMAL = 0x000
    const val ALIGN_VCENTRE = 0x100
    const val ALIGN_HLEFT = 0x000
    const val ALIGN_HCENTRE = 0x001
    const val ALIGN_HRIGHT = 0x002
}
