package name.boyle.chris.sgtpuzzles.engine

/**
 * The spoken names of the board colours, in one fixed order
 * (D-01 section 8).
 *
 * A game maps its own colour index to one entry of this enum, so a
 * colour is spelled once for every game that draws it and a colour-blind
 * player hears the name in the label instead of reading the palette. The
 * order is the palette order the games share, not the raw colour value:
 * a game never speaks a number or a hex code.
 *
 * A name is never the only cue where the game has another one: `filling`
 * pairs its values with `given`, `mosaic` pairs a colour with `black`
 * and `white`, and only a game whose whole state is a colour (`samegame`,
 * `flood`) speaks the name alone.
 */
enum class BoardColour(val spokenName: String) {
    RED("red"),
    YELLOW("yellow"),
    GREEN("green"),
    BLUE("blue"),
    ORANGE("orange"),
    PURPLE("purple"),
    BROWN("brown"),
    LIGHT_BLUE("light blue"),
    LIGHT_GREEN("light green"),
    PINK("pink"),
    CYAN("cyan"),
    MAGENTA("magenta"),
}
