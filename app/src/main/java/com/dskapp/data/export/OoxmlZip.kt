package com.dskapp.data.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes OOXML packages (.docx/.xlsx/.pptx) with the standard JDK ZipOutputStream.
 *
 * Using the platform ZIP writer keeps the APK small - no Apache POI or docx4j, which would
 * add megabytes and a large method count. These files are only ever *generated* by us, so
 * we do not need a reader.
 */
object OoxmlZip {

    fun write(target: File, entries: Map<String, String>) {
        target.parentFile?.mkdirs()
        val buffer = ByteArrayOutputStream()

        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (path, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                val entry = ZipEntry(path).apply {
                    // Fixed timestamp keeps output deterministic for tests.
                    time = FIXED_TIME
                    method = ZipEntry.DEFLATED
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        target.writeBytes(buffer.toByteArray())
    }

    private const val FIXED_TIME = 1_700_000_000_000L
}

/** Escapes text for XML content/attributes (all OOXML parts are XML). */
object XmlEscape {

    fun text(raw: String): String = buildString(raw.length + 16) {
        raw.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                // Control characters are illegal in XML 1.0 and would corrupt the file.
                '\n' -> append('\n')
                '\t' -> append('\t')
                else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\t') append(ch)
            }
        }
    }
}
