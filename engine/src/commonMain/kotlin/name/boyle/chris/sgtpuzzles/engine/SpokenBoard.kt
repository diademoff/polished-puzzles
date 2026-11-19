package name.boyle.chris.sgtpuzzles.engine

/**
 * One focus stop of a game board for a screen reader, the D-01 seam.
 *
 * A unit is a cell, an edge, a clue, a palette entry, or any other
 * place where the player can act. The rectangle is in game coordinates;
 * the shell adds the board origin. A unit that is not in a grid has
 * [row] and [column] zero, so only a cell carries a position.
 *
 * [text] is content only and never repeats a coordinate that
 * `collectionItemInfo` already carries, as `board-a11y-spec.md` Q2
 * decides. The shell speaks a cell as `Row R, column C, <text>`; a unit
 * outside the grid is spoken as `text` alone.
 */
data class SpokenUnit(
    /** The 1-based grid row, or 0 when the unit is not in a grid. */
    val row: Int,
    /** The 1-based grid column, or 0 when the unit is not in a grid. */
    val column: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    /** Content only: `tile 7`, `tree`, `Row 3, 2 tents`. */
    val text: String,
    /** The pointer buttons this unit offers, in the game's words. */
    val actions: List<SpokenAction> = emptyList(),
)

/** One pointer button a unit offers, in the game's own words. */
data class SpokenAction(val label: String, val button: Int)

/**
 * The suffix a unit text gains when the game marks it wrong. The shell
 * prefixes `Wrong.` when a change gives a unit this suffix.
 */
const val SPOKEN_WRONG = ", wrong"

/**
 * The suffix a unit text gains when the unit is part of the selection
 * the game will act on.
 */
const val SPOKEN_SELECTED = ", selected"

/**
 * One digit cell in the D-01 grammar (`board-a11y-spec.md` section 4):
 * `given 5` for a clue the player cannot change, `5` for a typed value,
 * `pencil 1 4 7` for the marks of an empty cell, and `empty` for a cell
 * with neither.
 *
 * [pencil] is the ascending list of marked digits. A cell with marks and
 * no value speaks the marks alone, so the player hears that it is empty
 * without a second word. The games that own digits (`keen`, `towers`,
 * `unequal`) all call this, so one digit is spelled once.
 */
internal fun digitSpeech(value: Int, given: Boolean, pencil: List<Int>): String = when {
    value != 0 && given -> "given $value"
    value != 0 -> "$value"
    pencil.isNotEmpty() -> "pencil ${pencil.joinToString(" ")}"
    else -> "empty"
}

/**
 * The digits marked in a pencil bitmask, ascending, in `1..max`.
 *
 * `keen` and `towers` set bit `n` for the digit `n`, so one reader
 * serves both.
 */
internal fun pencilDigits(mask: Int, max: Int): List<Int> =
    (1..max).filter { mask and (1 shl it) != 0 }

/**
 * The board as a screen reader reads it, built from game state and not
 * from pixels (D-01 section 4).
 *
 * [rows] and [columns] are zero when the game has no grid. [summary] is
 * the extra board text after the name and the shape; it is empty when
 * the game has nothing to add.
 */
data class SpokenBoard(
    val rows: Int,
    val columns: Int,
    val summary: String,
    val units: List<SpokenUnit>,
)

/**
 * The utterance of one unit: the grid position, then the content.
 *
 * TalkBack reads `collectionItemInfo` itself, so a unit text never
 * repeats a coordinate (ADR 0013 point 4); the shell and the spoken
 * tables both build the full sentence here, so a label cannot drift
 * between them. A unit outside the grid is its content alone.
 */
fun SpokenUnit.spokenLabel(): String =
    if (row > 0 && column > 0) "Row $row, column $column, $text" else text

/**
 * The largest grid the shell exposes one node per unit for (D-01 S2).
 *
 * It is the area bound Loopy already uses (D-033). A larger grid returns
 * the cursor unit only, and the player uses the arrow keys.
 *
 * shortcut: above 400 units only the cursor speaks, add row nodes when a
 * real user with a large board asks.
 */
const val BOARD_UNIT_CAP = 400

/**
 * The spoken board of a plain tile game: one unit per cell, in
 * row-major order.
 *
 * [left] and [top] are the game's own margins for cell (0, 0), and
 * [tileSize] is the side of a cell. A game appends its units outside the
 * grid (a clue row, a palette, a laser ring) to the result with
 * [SpokenUnit.row] and [SpokenUnit.column] zero, and applies the cap
 * with [capped]. Section 8 of `board-a11y-spec.md` marks the games that
 * use this helper.
 */
internal fun gridSpeech(
    width: Int,
    height: Int,
    tileSize: Int,
    left: Int,
    top: Int,
    summary: String,
    text: (x: Int, y: Int) -> String,
    actions: (x: Int, y: Int) -> List<SpokenAction> = { _, _ -> emptyList() },
): SpokenBoard = SpokenBoard(
    rows = height,
    columns = width,
    summary = summary,
    units = buildList {
        for (y in 0 until height) {
            for (x in 0 until width) {
                add(
                    SpokenUnit(
                        row = y + 1,
                        column = x + 1,
                        left = left + x * tileSize,
                        top = top + y * tileSize,
                        width = tileSize,
                        height = tileSize,
                        text = text(x, y),
                        actions = actions(x, y),
                    ),
                )
            }
        }
    },
)

/**
 * The board the shell can speak: every unit, or the cursor unit alone
 * when the grid is above [BOARD_UNIT_CAP] cells. [cursor] is the unit
 * the arrow keys move; a game that does not pass one drops every unit,
 * which speaks the board node only.
 */
internal fun SpokenBoard.capped(cursor: SpokenUnit?): SpokenBoard =
    if (rows * columns <= BOARD_UNIT_CAP) {
        this
    } else {
        SpokenBoard(rows, columns, summary, listOfNotNull(cursor))
    }
