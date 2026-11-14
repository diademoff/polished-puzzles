package name.boyle.chris.sgtpuzzles.parity

import java.io.File

/**
 * Reads the vendored upstream commit from `UPSTREAM.lock`.
 *
 * The `integration.commit` value is a different field and must not leak
 * into a golden. Only the commit under the `upstream:` section counts.
 */
object UpstreamLock {

    fun commit(lock: File): String {
        val regex = Regex("""^\s*commit:\s*"([0-9a-f]{40})"""")
        var inUpstream = false
        for (line in lock.readLines()) {
            if (line == "upstream:") {
                inUpstream = true
                continue
            }
            if (line.isNotEmpty() && !line.startsWith(" ") && line.endsWith(":")) {
                inUpstream = false
            }
            if (inUpstream) {
                regex.find(line)?.let { return it.groupValues[1] }
            }
        }
        error("no upstream commit in $lock")
    }
}
