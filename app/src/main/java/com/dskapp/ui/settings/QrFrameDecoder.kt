package com.dskapp.ui.settings

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Decodes QR codes from a single camera luma (Y plane) frame.
 *
 * Only the luminance channel is used: zxing binarizes it itself, so the chroma planes of a
 * YUV frame are irrelevant and decoding luma alone is both cheaper and sufficient.
 *
 * No rotation is applied to the pixels. zxing's QR detector locates finder patterns at any
 * orientation, so rotated camera frames decode as-is. Rotating would also be unsafe here:
 * [PlanarYUVLuminanceSource] inherits `LuminanceSource.rotateCounterClockwise()`, which
 * throws [UnsupportedOperationException] in zxing 3.5.4, and that exception would escape on
 * the camera analysis thread and crash the app.
 *
 * The Y plane is not necessarily tightly packed: each row starts [rowStride] bytes after the
 * previous one, and rowStride is usually greater than the visible width (alignment padding at
 * the end of every row). The source must therefore be built with the stride as its data width,
 * not with the visible width, or every row after the first would be read from the wrong offset.
 *
 * Instances are not thread safe; use one decoder per analysis thread. Both [decode] and
 * [close] are safe to call repeatedly.
 */
internal class QrFrameDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    /**
     * Returns the QR text found in this luma frame, or null when the frame is invalid or
     * holds no readable code. This method never throws.
     *
     * @param luma the Y plane bytes, rows spaced [rowStride] bytes apart; the padding after the
     *   final row may be omitted.
     * @param width visible frame width in pixels.
     * @param height visible frame height in pixels.
     * @param rowStride distance in bytes between the start of two consecutive rows.
     */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null

        // Smallest buffer that can hold every visible row: the last row needs only `width` bytes.
        val minimalSize = rowStride.toLong() * (height - 1).toLong() + width.toLong()
        if (luma.size.toLong() < minimalSize) return null

        try {
            val fullSize = rowStride.toLong() * height.toLong()
            // PlanarYUVLuminanceSource.getMatrix() may read whole rows, so give it a buffer whose
            // final row padding actually exists; the zero-filled tail is padding, never sampled.
            val data = if (luma.size.toLong() < fullSize) {
                ByteArray(fullSize.toInt()).also { padded ->
                    luma.copyInto(padded, 0, 0, luma.size)
                }
            } else {
                luma
            }

            val source = PlanarYUVLuminanceSource(
                data,
                rowStride,
                height,
                0,
                0,
                width,
                height,
                false
            )
            return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: Exception) {
            return null
        } finally {
            reader.reset()
        }
    }

    /** Releases the reader's internal state. Safe to call more than once. */
    fun close() {
        reader.reset()
    }
}
