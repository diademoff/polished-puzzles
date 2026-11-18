package name.boyle.chris.sgtpuzzles.launch

import name.boyle.chris.sgtpuzzles.backend.FIFTEEN
import name.boyle.chris.sgtpuzzles.engine.GenerationCancelled
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Level: unit. The Kotlin game path submits its work to the shared
 * [GameGenerator] executor. These tests need no Android runtime: the
 * task returns a string, and the callback only records what it got.
 */
class GameGeneratorTest {

    private val generator = GameGenerator()

    @After
    fun tearDown() {
        generator.onDestroy()
    }

    @Test
    fun `a result reaches the callback with the finished launch`() {
        val launch = launch()
        val done = CountDownLatch(1)
        var finished: GameLaunch? = null
        var previous: String? = null
        val callback = object : GameGenerator.Callback {
            override fun gameGeneratorSuccess(launch: GameLaunch, previousGame: String?) {
                finished = launch
                previous = previousGame
                done.countDown()
            }

            override fun gameGeneratorFailure(e: Exception, launch: GameLaunch) {
                error("unexpected failure: $e")
            }
        }

        val future = generator.submit(launch, "previous game", callback) { "saved game" }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("saved game", future.get())
        assertEquals("saved game", finished!!.saved)
        assertEquals("previous game", previous)
    }

    @Test
    fun `any exception becomes the failure callback`() {
        val launch = launch()
        val done = CountDownLatch(1)
        var failure: Exception? = null
        val callback = object : GameGenerator.Callback {
            override fun gameGeneratorSuccess(launch: GameLaunch, previousGame: String?) {
                error("unexpected success")
            }

            override fun gameGeneratorFailure(e: Exception, launch: GameLaunch) {
                failure = e
                done.countDown()
            }
        }
        val boom = IllegalStateException("generator bug")

        generator.submit(launch, null, callback) { throw boom }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertSame(boom, failure)
    }

    @Test
    fun `a cancelled task leaves no callback`() {
        val launch = launch()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val taskFinished = CountDownLatch(1)
        var success: GameLaunch? = null
        var failure: Exception? = null
        val callback = object : GameGenerator.Callback {
            override fun gameGeneratorSuccess(launch: GameLaunch, previousGame: String?) {
                success = launch
            }

            override fun gameGeneratorFailure(e: Exception, launch: GameLaunch) {
                failure = e
            }
        }

        val future = generator.submit(launch, null, callback) {
            started.countDown()
            release.await()
            taskFinished.countDown()
            "saved game"
        }

        assertTrue(started.await(5, TimeUnit.SECONDS))
        future.cancel(false)
        release.countDown()
        assertTrue(taskFinished.await(5, TimeUnit.SECONDS))

        // The task ran to completion, but the wrapper saw the cancel
        // flag before it called back.
        assertTrue(future.isCancelled)
        assertNull(success)
        assertNull(failure)
        assertTrue(
            runCatching { future.get() }.exceptionOrNull() is CancellationException,
        )
    }

    @Test
    fun `a cancelled generation reports no failure and no game`() {
        val launch = launch()
        var success: GameLaunch? = null
        var failure: Exception? = null
        val callback = object : GameGenerator.Callback {
            override fun gameGeneratorSuccess(launch: GameLaunch, previousGame: String?) {
                success = launch
            }

            override fun gameGeneratorFailure(e: Exception, launch: GameLaunch) {
                failure = e
            }
        }

        val future = generator.submit(launch, null, callback) { throw GenerationCancelled() }

        assertNull(future.get(5, TimeUnit.SECONDS))
        assertNull("a cancelled generation published a game", success)
        assertNull("a cancelled generation reported an error", failure)
    }

    private fun launch(): GameLaunch =
        GameLaunch.toGenerate(FIFTEEN, "4x4", GameLaunch.Origin.CUSTOM_DIALOG)
}
