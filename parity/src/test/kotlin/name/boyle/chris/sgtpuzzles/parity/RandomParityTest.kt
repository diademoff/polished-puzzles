package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.RandomState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: strict. Differential test against upstream `random.c`.
 *
 * A seed must generate the same board on every platform. This test
 * compares the Kotlin port with the oracle on raw bit streams, on
 * `random_upto`, and on generator state transfer in both directions.
 */
class RandomParityTest {

    private val seeds = listOf(
        "",
        "0",
        "1",
        "12345",
        "hello world",
        "seed-with-dashes",
        "x".repeat(63),
        "y".repeat(64),
        "z".repeat(65),
        "\u044e\u043d\u0438\u043a\u043e\u0434",
    )

    private val limits = listOf(
        1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 16L, 17L, 31L, 32L,
        33L, 52L, 100L, 255L, 256L, 257L, 1000L, 1024L, 65535L, 65536L,
        1L shl 20, 1L shl 27, 134_217_727L,
    )

    @Test
    fun `random_bits matches every bit width`() = OracleClient().use { oracle ->
        for (seed in seeds) {
            for (bits in 1..31) {
                val expected = oracle.randomBits(bits, COUNT, seed = seed)
                val actual = RandomState.fromSeed(seed)
                    .let { random -> List(COUNT) { random.bits(bits) } }
                assertEquals("seed='$seed' bits=$bits", expected.values, actual)
                assertEquals("seed='$seed' bits=$bits state", expected.state, localState(seed, bits))
            }
        }
    }

    @Test
    fun `random_upto matches every limit`() = OracleClient().use { oracle ->
        for (seed in seeds) {
            for (limit in limits) {
                val expected = oracle.randomUpto(limit, COUNT, seed = seed)
                val random = RandomState.fromSeed(seed)
                assertEquals(
                    "seed='$seed' limit=$limit",
                    expected.values,
                    List(COUNT) { random.upto(limit) },
                )
                assertEquals("seed='$seed' limit=$limit state", expected.state, random.encodeState())
            }
        }
    }

    @Test
    fun `bits state transfers from Kotlin to the oracle and back`() = OracleClient().use { oracle ->
        for (seed in seeds) {
            for (bits in listOf(1, 7, 8, 13, 31)) {
                val local = RandomState.fromSeed(seed)
                repeat(17) { local.bits(bits) }

                val fromKotlin = oracle.randomBits(bits, COUNT, state = local.encodeState())
                assertEquals(
                    "seed='$seed' bits=$bits oracle continuation",
                    List(COUNT) { local.bits(bits) },
                    fromKotlin.values,
                )
                assertEquals("seed='$seed' bits=$bits oracle state", local.encodeState(), fromKotlin.state)

                val first = oracle.randomBits(bits, 17, seed = seed)
                val fromOracle = RandomState.decode(first.state)
                val rest = oracle.randomBits(bits, COUNT, state = first.state)
                assertEquals(
                    "seed='$seed' bits=$bits Kotlin continuation",
                    rest.values,
                    List(COUNT) { fromOracle.bits(bits) },
                )
                assertEquals("seed='$seed' bits=$bits Kotlin state", rest.state, fromOracle.encodeState())
            }
        }
    }

    @Test
    fun `upto state transfers from Kotlin to the oracle and back`() = OracleClient().use { oracle ->
        for (seed in seeds) {
            for (limit in listOf(2L, 6L, 52L, 1000L, 65536L)) {
                val local = RandomState.fromSeed(seed)
                repeat(19) { local.upto(limit) }

                val fromKotlin = oracle.randomUpto(limit, COUNT, state = local.encodeState())
                assertEquals(
                    "seed='$seed' limit=$limit oracle continuation",
                    List(COUNT) { local.upto(limit) },
                    fromKotlin.values,
                )
                assertEquals("seed='$seed' limit=$limit oracle state", local.encodeState(), fromKotlin.state)

                val first = oracle.randomUpto(limit, 19, seed = seed)
                val fromOracle = RandomState.decode(first.state)
                val rest = oracle.randomUpto(limit, COUNT, state = first.state)
                assertEquals(
                    "seed='$seed' limit=$limit Kotlin continuation",
                    rest.values,
                    List(COUNT) { fromOracle.upto(limit) },
                )
                assertEquals("seed='$seed' limit=$limit Kotlin state", rest.state, fromOracle.encodeState())
            }
        }
    }

    private fun localState(seed: String, bits: Int): String {
        val random = RandomState.fromSeed(seed)
        repeat(COUNT) { random.bits(bits) }
        return random.encodeState()
    }

    private companion object {
        const val COUNT = 24
    }
}
