package com.dskapp.ui.settings

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for [QrFrameDecoder]: QR codes rendered into synthetic luma frames, including
 * padded rows, rotated codes, truncated last-row padding and invalid input.
 */
class QrFrameDecoderTest {

    private val payload = "DSK1|100.64.0.5:3284|tailscale|TEST-PC|abcdefghijklmnopqrstuvwxyz012345"

    private fun matrixOf(text: String): BitMatrix =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 4)
        )

    /**
     * Renders [matrix] into a luma frame.
     *
     * Background is white, modules are black, each module is [scale] pixels wide/tall and the
     * code is centred. Padding bytes past the visible [width] in each row are filled with
     * 0x80 garbage. When [rotate90] is true the code is drawn rotated counter-clockwise
     * (code pixel (x, y) lands on (codeH - 1 - y, x)). When [trimLastRowPadding] is true the
     * array ends after the visible part of the last row, i.e. it has length
     * rowStride * (height - 1) + width.
     */
    private fun frame(
        matrix: BitMatrix,
        width: Int,
        height: Int,
        rowStride: Int,
        scale: Int,
        rotate90: Boolean,
        trimLastRowPadding: Boolean
    ): ByteArray {
        val codeW = matrix.width * scale
        val codeH = matrix.height * scale
        // Effective size of the drawn code after rotation.
        val drawnW = if (rotate90) codeH else codeW
        val drawnH = if (rotate90) codeW else codeH
        val offsetX = (width - drawnW) / 2
        val offsetY = (height - drawnH) / 2

        val arraySize = if (trimLastRowPadding) {
            rowStride * (height - 1) + width
        } else {
            rowStride * height
        }
        val luma = ByteArray(arraySize)

        for (row in 0 until height) {
            val rowStart = row * rowStride
            // Fill the visible part of the row with white first.
            for (col in 0 until width) {
                luma[rowStart + col] = 0xFF.toByte()
            }
            // Garbage in the padding area beyond the visible width.
            for (col in width until rowStride) {
                val index = rowStart + col
                if (index < luma.size) {
                    luma[index] = 0x80.toByte()
                }
            }
        }

        // Draw the code.
        for (cy in 0 until matrix.height) {
            for (cx in 0 until matrix.width) {
                if (!matrix.get(cx, cy)) continue
                for (sy in 0 until scale) {
                    for (sx in 0 until scale) {
                        // Pixel position within the unrotated drawing.
                        val px = cx * scale + sx
                        val py = cy * scale + sy
                        val dx: Int
                        val dy: Int
                        if (rotate90) {
                            // (px, py) -> (codeH - 1 - py, px)
                            dx = offsetX + (codeH - 1 - py)
                            dy = offsetY + px
                        } else {
                            dx = offsetX + px
                            dy = offsetY + py
                        }
                        if (dx in 0 until width && dy in 0 until height) {
                            luma[dy * rowStride + dx] = 0x00
                        }
                    }
                }
            }
        }

        return luma
    }

    @Test
    fun decodesWhenRowStrideEqualsWidth() {
        val matrix = matrixOf(payload)
        val decoder = QrFrameDecoder()
        try {
            val luma = frame(matrix, 640, 480, 640, 6, rotate90 = false, trimLastRowPadding = false)
            val result = decoder.decode(luma, 640, 480, 640)
            assertEquals(payload, result)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun decodesWhenRowStrideGreaterThanWidth() {
        val matrix = matrixOf(payload)
        val decoder = QrFrameDecoder()
        try {
            val luma = frame(matrix, 640, 480, 704, 6, rotate90 = false, trimLastRowPadding = false)
            val result = decoder.decode(luma, 640, 480, 704)
            assertEquals(payload, result)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun decodesRotatedCodeWithoutThrowing() {
        val matrix = matrixOf(payload)
        val decoder = QrFrameDecoder()
        try {
            val luma = frame(matrix, 640, 480, 704, 6, rotate90 = true, trimLastRowPadding = false)
            val result = decoder.decode(luma, 640, 480, 704)
            assertEquals(payload, result)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun decodesWhenLastRowPaddingMissing() {
        val matrix = matrixOf(payload)
        val decoder = QrFrameDecoder()
        try {
            val luma = frame(matrix, 640, 480, 704, 6, rotate90 = false, trimLastRowPadding = true)
            assertEquals(704 * 479 + 640, luma.size)
            val result = decoder.decode(luma, 640, 480, 704)
            assertEquals(payload, result)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun returnsNullForBlankFrame() {
        val decoder = QrFrameDecoder()
        try {
            val luma = ByteArray(640 * 480) { 0xFF.toByte() }
            val result = decoder.decode(luma, 640, 480, 640)
            assertNull(result)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun returnsNullForInvalidInput() {
        val decoder = QrFrameDecoder()
        try {
            // rowStride smaller than width.
            val luma = ByteArray(640 * 480) { 0xFF.toByte() }
            assertNull(decoder.decode(luma, 640, 480, 640 - 1))

            // Buffer too short for the declared geometry.
            val short = ByteArray(100) { 0xFF.toByte() }
            assertNull(decoder.decode(short, 640, 480, 640))
        } finally {
            decoder.close()
        }
    }

    @Test
    fun decodesTwiceWithSameInstance() {
        val matrix = matrixOf(payload)
        val decoder = QrFrameDecoder()
        try {
            val luma = frame(matrix, 640, 480, 704, 6, rotate90 = false, trimLastRowPadding = false)
            val first = decoder.decode(luma, 640, 480, 704)
            assertEquals(payload, first)
            val second = decoder.decode(luma, 640, 480, 704)
            assertEquals(payload, second)
        } finally {
            decoder.close()
        }
    }
}
