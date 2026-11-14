package name.boyle.chris.sgtpuzzles.engine

/**
 * The displayed board geometry.
 *
 * Drawing, input, the cursor rectangle, and size negotiation all use
 * this one value. A game board drawn at a tile size and a board read at
 * another tile size is a bug that the type system can prevent: there is
 * only one tile size in scope.
 *
 * [originX] and [originY] are the top-left corner of the board in view
 * coordinates. The game itself always draws in game coordinates, with
 * the board at the origin.
 */
data class BoardGeometry(
    val tileSize: Int,
    val originX: Int,
    val originY: Int,
    val width: Int,
    val height: Int,
) {

    /** Converts a view coordinate to a game coordinate. */
    fun toGameX(viewX: Int): Int = viewX - originX

    fun toGameY(viewY: Int): Int = viewY - originY

    /** True when the view point lies inside the board rectangle. */
    fun contains(viewX: Int, viewY: Int): Boolean =
        viewX >= originX && viewY >= originY &&
            viewX < originX + width && viewY < originY + height

    companion object {

        /** The board at the preferred tile size, before the first resize. */
        fun preferred(game: Game, params: Game.Params): BoardGeometry {
            val size = game.computeSize(params, game.preferredTileSize)
            return BoardGeometry(game.preferredTileSize, 0, 0, size.width, size.height)
        }
    }
}
