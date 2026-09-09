package com.example.controlfree.ui.todo.quicknote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object QuickNoteImageSampler {
    private const val MAX_SOURCE_DIMENSION = 50_000
    private const val MAX_SOURCE_PIXELS = 100_000_000L

    fun calculateSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        requestedWidth: Int,
        requestedHeight: Int
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || requestedWidth <= 0 || requestedHeight <= 0) {
            return 1
        }
        var sampleSize = 1
        while (maxOf(
                sourceWidth / (sampleSize * 2),
                sourceHeight / (sampleSize * 2)
            ) >= maxOf(requestedWidth, requestedHeight)
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    suspend fun loadThumbnail(
        context: Context,
        uri: Uri,
        maxDimension: Int = 640
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (
                width <= 0 || height <= 0 ||
                width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION ||
                width.toLong() * height.toLong() > MAX_SOURCE_PIXELS
            ) {
                return@runCatching null
            }

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = calculateSampleSize(width, height, maxDimension, maxDimension)
            }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@runCatching null
            val largestSide = maxOf(decoded.width, decoded.height)
            if (largestSide <= maxDimension) return@runCatching decoded

            val scale = maxDimension.toFloat() / largestSide
            val scaled = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== decoded) decoded.recycle()
            scaled
        }.getOrNull()
    }
}
