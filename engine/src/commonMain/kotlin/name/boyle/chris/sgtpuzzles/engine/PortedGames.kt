package name.boyle.chris.sgtpuzzles.engine

/**
 * Every game that has a Kotlin backend.
 *
 * This is the one list of ported games. The app selects a backend from
 * it (`KotlinGames.gameFor`) and the parity suite iterates it, so a
 * ported game cannot be missing from the preference comparison and the
 * comparison cannot shrink by editing a test list. [byBackend] is the
 * lookup by the C backend name, which is also the oracle `game` field.
 */
object PortedGames {

    /** The ported games, in no particular order. */
    val all: List<Game> = listOf(
        BlackBox,
        Bridges,
        Cube,
        Dominosa,
        Fifteen,
        Filling,
        Flip,
        Flood,
        Galaxies,
        Guess,
        Inertia,
        Keen,
        Lightup,
        Loopy,
        Magnets,
        Map,
        Mines,
        Mosaic,
        Net,
        Netslide,
        Palisade,
        Pattern,
        Pearl,
        Pegs,
        Range,
        Rect,
        SameGame,
        Signpost,
        Singles,
        Sixteen,
        Slant,
        Solo,
        Tents,
        Towers,
        Tracks,
        Twiddle,
        Undead,
        Unequal,
        Unruly,
        Untangle,
    )

    private val byName: kotlin.collections.Map<String, Game> = buildMap {
        for (game in all) {
            require(put(game.backendName, game) == null) {
                "two Kotlin backends use the name '${game.backendName}'"
            }
        }
    }

    /** The game with the C backend name [backend], or null when it is not ported. */
    fun byBackend(backend: String): Game? = byName[backend]
}
