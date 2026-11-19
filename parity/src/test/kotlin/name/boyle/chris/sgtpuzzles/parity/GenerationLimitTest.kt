package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Dominosa
import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Inertia
import name.boyle.chris.sgtpuzzles.engine.Loopy
import name.boyle.chris.sgtpuzzles.engine.Map
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Net
import name.boyle.chris.sgtpuzzles.engine.Netslide
import name.boyle.chris.sgtpuzzles.engine.Palisade
import name.boyle.chris.sgtpuzzles.engine.Pearl
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Singles
import name.boyle.chris.sgtpuzzles.engine.Solo
import name.boyle.chris.sgtpuzzles.engine.Tents
import name.boyle.chris.sgtpuzzles.engine.Tracks
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: semantic. The measured generation caps.
 *
 * D-007, D-008, and D-014 gained tighter numbers, and D-026 to D-034
 * add a cap to every other game whose generator was effectively
 * endless. Each limit set here is at the cap and uses the fast
 * difficulty from the measurement, so the whole class stays inside the
 * test budget. The fast difficulty does not change the cap: the guard
 * reads the area, the region count, the maximum number, or the symbol
 * count, never the difficulty of an area game.
 *
 * The above-cap tests show that the guard is generation-only. The
 * hand-written saves and the C-generated map save are loaded by the
 * oracle and by [Midend.fromSave], and one move is replayed by both
 * engines.
 */
class GenerationLimitTest {

    private fun assertGenerates(
        game: Game,
        name: String,
        params: String,
        seed: String,
    ) = OracleClient().use { oracle ->
        val expected = oracle.newGame(name, params, seed)
        val actual = Midend.newGame(game, params, seed)
        assertEquals("$name $params id", expected.id, actual.id())
        assertEquals("$name $params save", expected.state, actual.serialise().serialise())
    }

    private fun assertSaveLoads(
        game: Game,
        save: String,
    ) = OracleClient().use { oracle ->
        val expected = oracle.id(save)
        val midend = Midend.fromSave(game, SgtpSave.parse(save))
        assertEquals("loaded id", expected.id, midend.id())
        assertEquals("loaded status", expected.status, midend.status())
        assertEquals("loaded history", 2, midend.stateCount)
        assertEquals("loaded save", expected.state, midend.serialise().serialise())
    }

    private fun handSave(
        game: String,
        params: String,
        desc: String,
        move: String,
    ): String = SgtpSave.of(
        listOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", game),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("SEED", "12345"),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", "2"),
            SaveRecord("STATEPOS", "2"),
            SaveRecord("MOVE", move),
        ),
    ).serialise()

    // ------------------------------------------------------------------
    // The limit still generates the C description.
    // ------------------------------------------------------------------

    @Test(timeout = 300_000)
    fun `netslide generates at 300x300`() =
        assertGenerates(Netslide, "netslide", "300x300b1", "12345")

    @Test(timeout = 300_000)
    fun `palisade generates at 50 regions`() =
        assertGenerates(Palisade, "palisade", "25x20n10", "12345")

    @Test(timeout = 300_000)
    fun `inertia generates at 200x200`() =
        assertGenerates(Inertia, "inertia", "200x200", "12345")

    @Test(timeout = 300_000)
    fun `tents generates at 22x22`() =
        assertGenerates(Tents, "tents", "22x22dt", "12345")

    @Test(timeout = 300_000)
    fun `net generates at 100x100`() =
        assertGenerates(Net, "net", "100x100", "12345")

    @Test(timeout = 300_000)
    fun `tracks generates at 22x22`() =
        assertGenerates(Tracks, "tracks", "22x22dh", "12345")

    @Test(timeout = 300_000)
    fun `dominosa generates at 9`() =
        assertGenerates(Dominosa, "dominosa", "9db", "12345")

    @Test(timeout = 300_000)
    fun `map generates at 100 regions`() =
        assertGenerates(Map, "map", "100x100n100dn", "12345")

    @Test(timeout = 300_000)
    fun `singles generates at 20x20`() =
        assertGenerates(Singles, "singles", "20x20de", "12345")

    @Test(timeout = 300_000)
    fun `pearl generates at 11x11`() =
        assertGenerates(Pearl, "pearl", "11x11de", "12345")

    @Test(timeout = 300_000)
    fun `pearl generates its largest preset`() =
        assertGenerates(Pearl, "pearl", "12x8de", "12345")

    @Test(timeout = 300_000)
    fun `loopy generates at 20x20`() =
        assertGenerates(Loopy, "loopy", "20x20t0dh", "12345")

    @Test(timeout = 300_000)
    fun `cube generates at 500x500`() =
        assertGenerates(Cube, "cube", "c500x500", "12345")

    @Test(timeout = 300_000)
    fun `cube generates at the largest preset`() =
        assertGenerates(Cube, "cube", "i3x3", "12345")

    /**
     * 25 symbols is the Simple, Block, and Intersect cap. Intersect is
     * used so the 25-symbol case stays inside the test budget.
     *
     * A first generation of a process can differ from C at 5x5: on
     * OpenJDK 17 the JIT miscompiles the on-stack replacement of
     * `Solo.solver`, so the first 25-symbol board of a JVM sometimes
     * comes back with a different description and the same `AUXINFO`.
     * The port is not at fault, the measurement is in `docs/PARITY.md`,
     * and `SoloFirstGenerationTest` pins the fresh-JVM case with the
     * JIT's on-stack replacement turned off. One generation is
     * discarded here so this cap test compares the settled result in the
     * shared test JVM.
     */
    @Test(timeout = 300_000)
    fun `solo generates at the 25 symbol cap`() {
        Midend.newGame(Solo, "5x5di", "12345")
        assertGenerates(Solo, "solo", "5x5di", "12345")
    }

    @Test(timeout = 300_000)
    fun `solo generates at the 16 symbol cap`() =
        assertGenerates(Solo, "solo", "4x4da", "12345")

    @Test(timeout = 300_000)
    fun `solo generates at the 9 symbol cap`() =
        assertGenerates(Solo, "solo", "3x3du", "12345")

    // ------------------------------------------------------------------
    // A save above the cap still loads and replays one move.
    // ------------------------------------------------------------------

    @Test(timeout = 300_000)
    fun `a tents save above 484 cells loads and plays`() {
        // 23x22 is 506 cells. The run grammar needs 507 area units for
        // 506 cells, so the last tree run ends exactly on the virtual
        // cell. Every cell is blank except the trees that the runs
        // write, so the move on (0,0) is legal.
        val desc = "z" + "y".repeat(18) + "m" + ",0".repeat(23 + 22)
        val save = handSave("Tents", "23x22dt", desc, "T0,0")
        assertEquals(null, Tents.validateDesc(Tents.decodeParams("23x22dt"), desc))
        assertSaveLoads(Tents, save)
    }

    @Test(timeout = 300_000)
    fun `a tracks save above 484 cells loads and plays`() {
        // 23x22 is 506 cells: one no-clue square per cell, with the
        // entrance in the first column and the exit in the first row.
        val numbers = ",S0" + ",0".repeat(22) + ",S0" + ",0".repeat(21)
        val desc = "0".repeat(23 * 22) + numbers
        val save = handSave("Train Tracks", "23x22dh", desc, "TS0,0")
        assertEquals(null, Tracks.validateDesc(Tracks.decodeParams("23x22dh"), desc))
        assertSaveLoads(Tracks, save)
    }

    @Test(timeout = 300_000)
    fun `an inertia save above 40,000 cells loads and plays`() {
        // 201x200 is 40,200 cells: one start, one gem next to it, and
        // blanks. Direction 2 slides right and collects the gem.
        val desc = "Sg" + "b".repeat(201 * 200 - 2)
        val save = handSave("Inertia", "201x200", desc, "2")
        assertEquals(null, Inertia.validateDesc(Inertia.decodeParams("201x200"), desc))
        assertSaveLoads(Inertia, save)
    }

    @Test(timeout = 300_000)
    fun `a palisade save above 50 regions loads and plays`() {
        // 26x20 is 520 cells and 52 regions. Every cell carries clue 0,
        // which loads: only generation is capped.
        val desc = "0".repeat(26 * 20)
        val save = handSave("Palisade", "26x20n10", desc, "F1,0,0")
        assertEquals(null, Palisade.validateDesc(Palisade.decodeParams("26x20n10"), desc))
        assertSaveLoads(Palisade, save)
    }

    @Test(timeout = 300_000)
    fun `a netslide save above 90,000 cells loads and plays`() {
        // 301x300 is 90,300 cells: one empty tile per cell. Sliding row
        // 0 by one cell is a legal move on an empty row.
        val desc = "0".repeat(301 * 300)
        val save = handSave("Netslide", "301x300b1", desc, "R0,1")
        assertEquals(null, Netslide.validateDesc(Netslide.decodeParams("301x300b1"), desc))
        assertSaveLoads(Netslide, save)
    }

    @Test(timeout = 300_000)
    fun `a net save above 10,000 cells loads and plays`() {
        // 101x100 is 10,100 cells: no clues and no barriers. Turning
        // cell 0 clockwise is a legal move.
        val desc = "0".repeat(101 * 100)
        val save = handSave("Net", "101x100", desc, "A0,0")
        assertEquals(null, Net.validateDesc(Net.decodeParams("101x100"), desc))
        assertSaveLoads(Net, save)
    }

    @Test(timeout = 300_000)
    fun `a dominosa save above n=9 loads and plays`() {
        // n=10 needs every number from 0 to 10 exactly 12 times: the
        // board is 11x12. The first domino joins cells 0 and 1.
        val desc = (0..10).joinToString("") { n ->
            (if (n < 10) "$n" else "[$n]").repeat(12)
        }
        val save = handSave("Dominosa", "10de", desc, "D0,1")
        assertEquals(null, Dominosa.validateDesc(Dominosa.decodeParams("10de"), desc))
        assertSaveLoads(Dominosa, save)
    }

    @Test(timeout = 300_000)
    fun `a singles save above 400 cells loads and plays`() {
        // 21x20 is 420 cells, each with clue 1. Marking a cell is a
        // legal move; the load path does not solve the board.
        val desc = "1".repeat(21 * 20)
        val save = handSave("Singles", "21x20de", desc, "B0,0")
        assertEquals(null, Singles.validateDesc(Singles.decodeParams("21x20de"), desc))
        assertSaveLoads(Singles, save)
    }

    @Test(timeout = 300_000)
    fun `a pearl save above 121 cells loads and plays`() {
        // 12x11 is 132 cells, all white. Marking edge 1 of cell 0,0 is
        // a legal move.
        val desc = "W".repeat(12 * 11)
        val save = handSave("Pearl", "12x11de", desc, "M1,0,0")
        assertEquals(null, Pearl.validateDesc(Pearl.decodeParams("12x11de"), desc))
        assertSaveLoads(Pearl, save)
    }

    @Test(timeout = 300_000)
    fun `a loopy save above 400 cells loads and plays`() {
        // 21x20 squares is 420 faces, all without clues. Setting edge 0
        // is a legal move.
        val desc = "0".repeat(21 * 20)
        val save = handSave("Loopy", "21x20t0dh", desc, "0y")
        assertEquals(null, Loopy.validateDesc(Loopy.decodeParams("21x20t0dh"), desc))
        assertSaveLoads(Loopy, save)
    }

    @Test(timeout = 300_000)
    fun `a cube save above 250,000 cells loads and plays`() {
        // 501x500 is 250,500 cells, four squares per hex digit. No
        // square is blue, which the description grammar accepts, and a
        // right roll from the top-left square is a legal move.
        val desc = "0".repeat((501 * 500 + 3) / 4) + ",0"
        val save = handSave("Cube", "c501x500", desc, "R")
        assertEquals(null, Cube.validateDesc(Cube.decodeParams("c501x500"), desc))
        assertSaveLoads(Cube, save)
    }

    @Test(timeout = 300_000)
    fun `a map save above 100 regions loads and plays`() = OracleClient().use { oracle ->
        // 60x60n200dn seed 1 is the measured fast 200-region board:
        // 0.011 s in C. Only generation is capped at 100 regions.
        val generated = oracle.newGame("map", "60x60n200dn", "1")
        val parsed = SgtpSave.parse(generated.state)
        val records = parsed.records.filter {
            it.key != "NSTATES" && it.key != "STATEPOS"
        } + listOf(
            SaveRecord("NSTATES", "2"),
            SaveRecord("STATEPOS", "2"),
            SaveRecord("MOVE", "0:4"),
        )
        val save = SgtpSave.of(records).serialise()

        val expected = oracle.id(save)
        val midend = Midend.fromSave(Map, SgtpSave.parse(save))
        assertEquals("loaded id", expected.id, midend.id())
        assertEquals("loaded status", expected.status, midend.status())
        assertEquals("loaded history", 2, midend.stateCount)
        assertEquals("loaded save", expected.state, midend.serialise().serialise())
        assertEquals("0:4", midend.serialise().value("MOVE"))
    }
}
