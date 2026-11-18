package name.boyle.chris.sgtpuzzles.engine

import kotlin.concurrent.Volatile

/**
 * A cooperative stop signal for a long generation.
 *
 * A generator checks it at the top of an attempt and never inside a
 * draw. A check is a plain read, so an accepted generation with the
 * same seed produces the same description with or without a token; only
 * a cancelled run stops early, and then it throws [GenerationCancelled]
 * and returns no board.
 *
 * The engine owns no thread and no timer: the caller that owns the work
 * calls [cancel] from its own thread, and [NEVER] is the default for a
 * caller with no cancel path, such as a test.
 */
open class Cancellation {

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean get() = cancelled

    fun cancel() {
        cancelled = true
    }

    /**
     * Stops the work when [cancel] has been called. It is open so a test
     * can cancel after a chosen number of attempts and prove that a
     * generator checks the token between attempts, not only at entry.
     */
    open fun check() {
        if (cancelled) throw GenerationCancelled()
    }

    companion object {
        /** A token that cannot be cancelled. */
        val NEVER = Cancellation()
    }
}

/**
 * A generation that a [Cancellation] stopped. It is not an error: no
 * board was published, and the caller keeps the previous game.
 */
class GenerationCancelled : RuntimeException("generation cancelled")
