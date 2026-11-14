package name.boyle.chris.sgtpuzzles.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Level: semantic. Every golden.
 *
 * Checks testdata/manifest.json against the files on disk: every listed
 * hash must match, and every golden file must be listed. The manifest
 * is the provenance record described in docs/PARITY.md, section 8, so
 * it must not drift away from the files it describes.
 */
class GoldenManifestTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the manifest covers the goldens and the hashes match`() {
        val manifestFile = File(testdata, "manifest.json")
        assertTrue("missing ${manifestFile.path}", manifestFile.isFile)

        val manifest = Json.parse(manifestFile.readText()) as JsonValue.Obj
        assertEquals(1L, manifest.number("version"))
        assertEquals(1L, manifest.number("oracle_protocol"))
        assertTrue(manifest.string("upstream_commit").matches(Regex("[0-9a-f]{40}")))

        val listed = (manifest.field("files") as JsonValue.Obj).fields
        assertTrue("the manifest lists no files", listed.isNotEmpty())

        for ((path, value) in listed) {
            val file = File(testdata, path)
            assertTrue("manifest lists a missing file: $path", file.isFile)
            val expected = (value as JsonValue.Str).value
            assertEquals("sha256 of $path", expected, sha256(file.readBytes()))
        }

        val onDisk = File(testdata, "golden").walkTopDown()
            .filter { it.isFile && it.name == "desc.json" }
            .map { it.relativeTo(testdata).invariantSeparatorsPath }
            .sorted()
            .toList()

        assertEquals(
            "every golden file must be in the manifest",
            onDisk,
            listed.keys.sorted(),
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
