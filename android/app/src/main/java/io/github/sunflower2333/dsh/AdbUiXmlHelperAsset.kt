package io.github.sunflower2333.dsh

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Copies trusted APK code to a content-addressed, read-only app-private cache. */
internal class AdbUiXmlHelperAsset(
    private val directory: File,
    private val openAsset: () -> InputStream,
) {
    fun materialize(): File {
        val bytes = openAsset().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > MAX_ASSET_BYTES - output.size()) throw IOException("Helper asset exceeds its size limit")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.isEmpty()) throw IOException("Empty helper asset")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create helper cache")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, "dsh-ui-xml-$digest.jar")
        if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) {
            if (!target.setReadOnly()) throw IOException("Cannot protect helper cache")
            return target
        }
        val staged = File.createTempFile("dsh-ui-xml-", ".tmp", directory)
        try {
            FileOutputStream(staged).use { output -> output.write(bytes); output.fd.sync() }
            if (!staged.setReadOnly()) throw IOException("Cannot protect helper cache")
            if (!staged.renameTo(target)) throw IOException("Cannot publish helper cache")
            return target
        } finally {
            staged.delete()
        }
    }

    private companion object { const val MAX_ASSET_BYTES = 256 * 1024 }
}
