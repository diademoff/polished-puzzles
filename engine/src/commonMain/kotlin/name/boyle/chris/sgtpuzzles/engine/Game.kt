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

    fun defaultParams(): Params

    /** The presets in menu order. The title is the menu label. */
    fun presets(): List<Preset>

    fun decodeParams(text: String): Params

    fun encodeParams(params: Params, full: Boolean): String

    /** Returns an error message, or null when the parameters are valid. */
    fun validateParams(params: Params, full: Boolean): String?

    /**
     * Generates a description and, when the game has one, the auxiliary
     * solution text that upstream stores in `AUXINFO`.
     */
    fun newDesc(params: Params, random: RandomState): Description

    /** Returns an error message, or null when the description is valid. */
    fun validateDesc(params: Params, desc: String): String?

    fun newState(params: Params, desc: String): State

    /** The move that solves the state, or null when the game cannot solve. */
    fun solve(state: State, auxInfo: String? = null): String?

    /** Applies one move, or returns null when the move is invalid. */
    fun executeMove(state: State, move: String): State?

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

    fun textFormat(state: State): String

    /**
     * Mirrors `game_changed_state`: the midend calls it after it enters
     * [newState] from [oldState], so the game can update frontend state.
     * [oldState] is null only when there is no state to leave. The
     * return value is true when this transition completed the game.
     */
    fun changedState(ui: Ui, oldState: State?, newState: State): Boolean = false

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
    fun changedState(canUndo: Boolean, canRedo: Boolean)
    fun startDraw()
    fun endDraw()
    fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int)
    fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int)

    /**
     * `draw_thick_line`: a line with [thickness] pixels. The endpoints
     * keep the order of [line]; the upstream `drawing_api` and the
     * Android `ViewCallbacks.drawLine` callback take the thickness
     * first.
     */
    fun thickLine(x1: Int, y1: Int, x2: Int, y2: Int, thickness: Float, colour: Int)

    fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int)
    fun poly(points: IntArray, fillColour: Int, outlineColour: Int)
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
