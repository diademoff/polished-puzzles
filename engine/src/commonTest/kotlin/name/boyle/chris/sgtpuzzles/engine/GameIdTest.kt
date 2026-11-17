package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Level: semantic. A game ID validates its parameters like
 * `midend_game_id_int`.
 *
 * C validates the decoded parameters with `validate_params(params,
 * desc == NULL)`, so a game ID validates with `full = false`: a short id
 * may leave a parameter out on purpose, and the description then
 * determines it. Only a new game, which has no description yet, needs
 * every parameter.
 *
 * The ported games accept a short form, so the test-only game below adds
 * a rule that the full form must name the difficulty. It wraps [Slant]
 * and changes no game rule.
 */
class GameIdTest {

    @Test
    fun `a short game id is accepted where a new game is not`() {
        val desc = SlantGeneration.newDesc(
            SlantGeneration.Params(5, 5, SlantGeneration.EASY),
            RandomState.fromSeed(SEED),
        ).text

        val shortParams = ShortIdGame.decodeParams("5x5")
        assertNotNull(
            ShortIdGame.validateParams(shortParams, true),
            "the full form of this game must name the difficulty",
        )
        assertNull(
            ShortIdGame.validateParams(shortParams, false),
            "the description determines the difficulty, so the short form is valid",
        )

        // C validates a game ID with `full = false` because it has a
        // description, so the short form is accepted and enters a state.
        val midend = Midend.fromGameId(ShortIdGame, "5x5:$desc")
        assertEquals(desc, midend.desc)
        assertEquals("5x5:$desc", midend.id())

        // A full id is accepted too, and the description wins.
        val full = Midend.fromGameId(ShortIdGame, "5x5de:$desc")
        assertEquals(desc, full.desc)
        assertEquals("5x5:$desc", full.id())

        // `midend_new_game` decodes and validates the parameters without
        // a description, so the short form cannot start a game. The
        // generator validates the full set before it generates anything.
        val error = assertFailsWith<IllegalArgumentException> {
            Midend.newGame(ShortIdGame, "5x5", SEED)
        }
        assertEquals("difficulty must be given", error.message)
    }

    /**
     * `midend_game_id_int` decodes a descriptive parameter string over a
     * copy of `me->params` (`midend.c:1890-1906`) and the short encoding
     * over another copy (`midend.c:1938-1950`). A `loopy` id that omits
     * the grid type therefore keeps the current type, and
     * `validate_desc` reads it. Starting from the compile-time default
     * would validate the wrong grid and reject a triangular
     * description.
     *
     * Loopy is the case where the base changes validation. The decoders
     * of `solo`, `keen`, `lightup`, `unequal`, and `map` write every
     * field their `validate_desc` reads, so a base cannot change their
     * answer; a game that keeps a base field outside the short encoding
     * reads it after `encode_params`, not in `validate_desc`.
     */
    @Test
    fun `a descriptive id decodes its parameters over the base`() {
        val base = Loopy.decodeParams("5x5t1")
        val desc = Loopy.newDesc(base, RandomState.fromSeed(SEED)).text

        assertNull(
            Midend.validateGameId(Loopy, "5x5:$desc", isSeed = false, baseParams = base),
            "the base grid type must reach validate_desc",
        )
        assertNotNull(
            Midend.validateGameId(Loopy, "5x5:$desc", isSeed = false),
            "the default grid type must not accept the same description",
        )

        // The short encoding carries the type, so a full id agrees too.
        assertNull(
            Midend.validateGameId(Loopy, "5x5t1:$desc", isSeed = false, baseParams = base),
        )
    }

    private companion object {
        const val SEED = "12345"
    }

    /**
     * A test-only game whose short parameter form leaves the difficulty
     * out. Every rule and every drawing primitive is [Slant]'s.
     */
    private object ShortIdGame : Game by Slant {

        data class Params(
            val inner: SlantGeneration.Params,
            val difficultyGiven: Boolean,
        ) : Game.Params {
            override fun copy(): Game.Params = Params(inner, difficultyGiven)
        }

        override fun defaultParams(): Game.Params =
            Params(SlantGeneration.defaultParams(), difficultyGiven = true)

        override fun decodeParams(text: String): Game.Params =
            Params(SlantGeneration.decode(text), difficultyGiven = text.contains('d'))

        override fun encodeParams(params: Game.Params, full: Boolean): String =
            SlantGeneration.encode((params as Params).inner, full)

        override fun validateParams(params: Game.Params, full: Boolean): String? {
            val slant = (params as Params).inner
            if (full && !(params as Params).difficultyGiven) {
                return "difficulty must be given"
            }
            return SlantGeneration.validateParams(slant, full)
        }

        override fun newDesc(params: Game.Params, random: RandomState): Description =
            Slant.newDesc((params as Params).inner, random)

        override fun validateDesc(params: Game.Params, desc: String): String? =
            Slant.validateDesc((params as Params).inner, desc)

        override fun newState(params: Game.Params, desc: String): Game.State =
            Slant.newState((params as Params).inner, desc)

        override fun requestKeys(params: Game.Params): KeyRequest =
            Slant.requestKeys((params as Params).inner)

        override fun computeSize(params: Game.Params, tileSize: Int): Size =
            Slant.computeSize((params as Params).inner, tileSize)
    }
}
