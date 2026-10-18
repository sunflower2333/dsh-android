package io.github.sunflower2333.dsh

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException

class AdbUiXmlHelperAssetTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "Packaged helper byte fixture; not a capture result".toByteArray()

    @Test fun materializesIdenticalReadOnlyBytesAndReusesTheContentAddressedFile() {
        val cache = AdbUiXmlHelperAsset(temporary.root) { ByteArrayInputStream(bytes) }
        val file = cache.materialize()
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(file.name.matches(Regex("dsh-ui-xml-[a-f0-9]{64}\\.jar")))
        assertFalse(file.canWrite())
        assertEquals(file, cache.materialize())
        assertEquals(listOf(file), temporary.root.listFiles()!!.toList())
    }

    @Test fun corruptCachedBytesAreReplacedBeforeReturningAHelperPath() {
        val cache = AdbUiXmlHelperAsset(temporary.root) { ByteArrayInputStream(bytes) }
        val file = cache.materialize()
        assertTrue(file.setWritable(true, true))
        file.writeBytes(ByteArray(bytes.size) { 42 })
        assertEquals(file, cache.materialize())
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(file.canWrite())
    }

    @Test fun aChangedPackagedHelperGetsItsOwnImmutableFile() {
        val first = AdbUiXmlHelperAsset(temporary.root) { ByteArrayInputStream(bytes) }.materialize()
        val second = AdbUiXmlHelperAsset(temporary.root) { ByteArrayInputStream(bytes + 1) }.materialize()
        assertNotEquals(first, second)
        assertArrayEquals(bytes, first.readBytes())
        assertArrayEquals(bytes + 1, second.readBytes())
    }

    @Test fun sourceReadFailureDoesNotExposeAPartialCachedHelper() {
        val cache = AdbUiXmlHelperAsset(temporary.root) { throw IOException("fixture") }
        assertThrows(IOException::class.java) { cache.materialize() }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun oversizedPackagedHelperIsRejectedWithoutCreatingACacheEntry() {
        val cache = AdbUiXmlHelperAsset(temporary.root) { ByteArrayInputStream(ByteArray(256 * 1024 + 1)) }
        assertThrows(IOException::class.java) { cache.materialize() }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }
}
