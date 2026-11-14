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

    fun textFormat(state: State): String

    /**
     * Mirrors `game_changed_state`: the midend calls it after it enters
     * [newState] from [oldState], so the game can update frontend state.
     * [oldState] is null only when there is no state to leave. The
     * return value is true when this transition completed the game.
     */
    fun changedState(ui: Ui, oldState: State?, newState: State): Boolean = false

    fun requestKeys(params: Params): KeyRequest

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
     */
    fun redraw(state: State, ui: Ui, sink: DrawSink, tileSize: Int)

    /**
     * Turns a frontend key into a move. It mirrors `interpret_move`, so
     * the result distinguishes a move, a UI-only change, a key the game
     * ignores, and a key that is valid but does nothing.
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
     * part of the save file: a load and an undo keep the game's own
     * state but reset the shell-owned cursor.
     */
    interface Ui

    /** A game with no frontend state of its own. */
    data object NoUi : Ui

    /** A cursor rectangle in game coordinates. */
    data class CursorRect(val left: Int, val top: Int, val width: Int, val height: Int)

    fun newUi(state: State): Ui = NoUi

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

data class KeyLabel(val button: Int, val name: String)

enum class ArrowMode { NONE, ARROWS_ONLY, ARROWS_LEFT_RIGHT, ARROWS_LEFT }

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
    const val UI_QUIT = 0x0210
    const val UI_NEWGAME = 0x0211
    const val UI_SOLVE = 0x0212
    const val UI_UNDO = 0x0213
    const val UI_REDO = 0x0214
    const val MOD_MASK = 0x7000

    fun stripModifiers(button: Int): Int = button and MOD_MASK.inv()

    fun isCursorMove(button: Int): Boolean =
        button == CURSOR_UP || button == CURSOR_DOWN ||
            button == CURSOR_LEFT || button == CURSOR_RIGHT
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
    fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int)
    fun poly(points: IntArray, fillColour: Int, outlineColour: Int)
    fun text(x: Int, y: Int, font: Int, size: Int, align: Int, colour: Int, text: String)
    fun clip(x: Int, y: Int, width: Int, height: Int)
    fun unclip()
    fun update(x: Int, y: Int, width: Int, height: Int)
    fun status(text: String)
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
