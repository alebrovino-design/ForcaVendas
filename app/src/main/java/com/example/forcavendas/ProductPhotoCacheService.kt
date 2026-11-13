package com.example.forcavendas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ProductPhotoCacheService {
    private fun cacheDirectory(context: Context): File =
        File(context.applicationContext.cacheDir, "product_photos").apply {
            if (!exists()) mkdirs()
        }

    private fun cacheFile(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(cacheDirectory(context), "$hash.jpg")
    }

    fun loadCached(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        val file = cacheFile(context, url)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun download(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        val cached = loadCached(context, url)
        if (cached != null) return cached

        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            if (connection.responseCode !in 200..299) return null
            val file = cacheFile(context, url)
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (tempFile.length() <= 0L) return null
            if (file.exists()) file.delete()
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            BitmapFactory.decodeFile(file.absolutePath)
        } finally {
            connection.disconnect()
        }
    }

    fun prefetch(context: Context, photoUrls: List<String>): Int {
        val uniqueUrls = photoUrls.filter { it.isNotBlank() }.distinct()
        var downloaded = 0
        uniqueUrls.forEach { url ->
            if (download(context, url) != null) downloaded++
        }
        return downloaded
    }
}
