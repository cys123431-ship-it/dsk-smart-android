package com.dskapp.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.dskapp.data.ai.AiImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Turns a photo picked from the camera/gallery into an [AiImage] the API will accept.
 *
 * Why downscale: a modern phone photo is 4-12 MP, which base64-encodes to 5-15 MB and
 * would either be rejected or cost far more tokens than needed. Text in a photo is still
 * perfectly readable at 1568px on the long edge, which matches the provider's guidance
 * for vision inputs.
 */
object ImageProcessor {

    /** Long-edge cap for vision input. */
    private const val MAX_EDGE_PX = 1568

    /** JPEG quality; 82 keeps letters crisp while shrinking the payload. */
    private const val JPEG_QUALITY = 82

    const val MIME_TYPE = "image/jpeg"

    /**
     * Loads [uri], applies EXIF rotation (camera photos are often stored sideways, which
     * makes the model misread rotated text), downscales, and returns JPEG bytes ready to be
     * base64-encoded or written to disk.
     */
    suspend fun toJpegBytes(context: Context, uri: Uri): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    ?: throw IOException("이미지를 열 수 없습니다.")

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                val decoded = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                } ?: throw IOException("이미지를 해석할 수 없습니다.")

                val rotation = resolver.openInputStream(uri)?.use { stream ->
                    runCatching {
                        when (
                            ExifInterface(stream).getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL
                            )
                        ) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else -> 0f
                        }
                    }.getOrDefault(0f)
                } ?: 0f

                val oriented = if (rotation != 0f) {
                    Bitmap.createBitmap(
                        decoded, 0, 0, decoded.width, decoded.height,
                        Matrix().apply { postRotate(rotation) }, true
                    ).also { if (it != decoded) decoded.recycle() }
                } else {
                    decoded
                }

                val scaled = scaleDown(oriented)
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    scaled.recycle()
                    out.toByteArray()
                }
            }
        }

    /** Convenience wrapper for callers that need the base64 AI form directly. */
    suspend fun toAiImage(context: Context, uri: Uri): Result<AiImage> =
        toJpegBytes(context, uri).map { bytes ->
            AiImage(
                mimeType = MIME_TYPE,
                base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            )
        }


    /** Writes an export file the share sheet can hand to other apps. */
    suspend fun writeExport(context: Context, fileName: String, content: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            File(dir, safeName).apply { writeText(content) }
        }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_EDGE_PX) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_EDGE_PX) return source

        val ratio = MAX_EDGE_PX.toFloat() / longest
        val targetWidth = (source.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * ratio).toInt().coerceAtLeast(1)

        val result = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        if (result != source) source.recycle()
        return result
    }
}
