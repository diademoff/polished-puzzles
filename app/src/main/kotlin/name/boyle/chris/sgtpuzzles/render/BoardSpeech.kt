package name.boyle.chris.sgtpuzzles.render

import name.boyle.chris.sgtpuzzles.engine.BOARD_UNIT_CAP
import name.boyle.chris.sgtpuzzles.engine.SPOKEN_WRONG
import name.boyle.chris.sgtpuzzles.engine.SpokenBoard
import name.boyle.chris.sgtpuzzles.engine.SpokenUnit
import name.boyle.chris.sgtpuzzles.engine.spokenLabel

/**
 * One snapshot of what the shell says about the game board for a
 * screen reader (D-01 A3).
 *
 * The snapshot carries the flags of the board actions too, so one
 * Compose state change updates the node tree and its actions together.
 */
data class BoardSpeech(
    /** The board of the current engine, or null for the C engine. */
    val board: SpokenBoard?,
    /** The game name, as the title bar shows it. */
    val name: String,
    /** The status line text; it labels the board only when it has no units. */
    val status: String,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val canSolve: Boolean,
)

/** True when the grid has more cells than one node per unit allows (D-01 S2). */
fun SpokenBoard.overUnitCap(): Boolean = rows * columns > BOARD_UNIT_CAP

/**
 * The units the shell exposes, in board order.
 *
 * At or below the cap every unit is a node. Above it only the cursor
 * unit is, and the arrow keys move the cursor. The engine already
 * returns one unit there; this rule keeps that true if a game forgets,
 * and it drops the units of a C game, which has none.
 */
fun BoardSpeech.units(): List<SpokenUnit> {
    val board = board ?: return emptyList()
    return if (board.overUnitCap()) board.units.take(1) else board.units
}

/** The unit under a point in board coordinates, or null outside every unit. */
fun BoardSpeech.cursorUnit(x: Int, y: Int): SpokenUnit? = units().firstOrNull {
    x >= it.left && x < it.left + it.width && y >= it.top && y < it.top + it.height
}

/** What the shell says after one board event, before it formats it. */
sealed interface BoardUtterance {
    /** One or two units changed: read them in board order. */
    data class Changed(val texts: List<String>, val wrong: Boolean) : BoardUtterance

    /** Three or more changed: read the cursor, then count the rest. */
    data class Many(val cursor: String?, val others: Int) : BoardUtterance

    /** Nothing changed, but the cursor moved. */
    data class Cursor(val text: String) : BoardUtterance

    /** Nothing to say: no change, a new board, or a completion. */
    data object None : BoardUtterance
}

/**
 * The D-01 announcement rule for one board event.
 *
 * It is pure, so a JVM test pins the one-unit, two-unit, many-unit, and
 * no-change cases without a device. A board of a different shape is a
 * new game or a zoom change and says nothing: the board node itself
 * changes and TalkBack reads it when it gains focus.
 */
fun utteranceFor(
    before: BoardSpeech?,
    beforeCursor: SpokenUnit?,
    after: BoardSpeech?,
    afterCursor: SpokenUnit?,
): BoardUtterance {
    val old = before?.board ?: return BoardUtterance.None
    val new = after?.board ?: return BoardUtterance.None
    if (old.rows != new.rows || old.columns != new.columns) return BoardUtterance.None
    if (old.units.size != new.units.size) return BoardUtterance.None
    val changed = new.units.indices.filter { old.units[it].text != new.units[it].text }
    if (changed.isEmpty()) {
        if (afterCursor == null || afterCursor.text == beforeCursor?.text) {
            return BoardUtterance.None
        }
        return BoardUtterance.Cursor(afterCursor.spokenLabel())
    }
    if (changed.size <= 2) {
        val wrong = changed.any {
            new.units[it].text.endsWith(SPOKEN_WRONG) &&
                !old.units[it].text.endsWith(SPOKEN_WRONG)
        }
        return BoardUtterance.Changed(changed.map { new.units[it].spokenLabel() }, wrong)
    }
    // The cursor unit is read once, whether or not it is one of the
    // changed units, and the rest are counted.
    val cursorIndex = afterCursor?.let { cursor ->
        new.units.indexOfFirst { it === cursor }
    } ?: -1
    val others = if (cursorIndex in changed) changed.size - 1 else changed.size
    return BoardUtterance.Many(afterCursor?.spokenLabel(), others)
}
