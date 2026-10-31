package com.dskapp.data.export

import com.dskapp.data.export.XmlEscape.text
import java.io.File

/**
 * Generates a .pptx from parsed markdown, ChatGPT-briefing style: every heading starts a new
 * slide, and the paragraphs/bullets/code beneath it become bullets on that slide.
 *
 * Uses a single blank layout and draws text boxes directly, which keeps the XML small and
 * avoids depending on the default template's layout IDs.
 */
object PptxWriter {

    private const val SLIDE_WIDTH = 12192000   // 16:9, EMU
    private const val SLIDE_HEIGHT = 6858000
    private const val MAX_BULLETS_PER_SLIDE = 9

    fun write(target: File, title: String, blocks: List<MdBlock>) {
        val slides = buildSlides(title, blocks)

        val entries = mutableMapOf(
            "[Content_Types].xml" to contentTypes(slides.size),
            "_rels/.rels" to rootRels(),
            "ppt/presentation.xml" to presentation(slides.size),
            "ppt/_rels/presentation.xml.rels" to presentationRels(slides.size),
            "ppt/slideMasters/slideMaster1.xml" to slideMaster(),
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to slideMasterRels(),
            "ppt/slideLayouts/slideLayout1.xml" to slideLayout(),
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to slideLayoutRels(),
            "ppt/theme/theme1.xml" to theme()
        )

        slides.forEachIndexed { index, slide ->
            val number = index + 1
            entries["ppt/slides/slide$number.xml"] = slideXml(slide)
            entries["ppt/slides/_rels/slide$number.xml.rels"] = slideRels()
        }

        OoxmlZip.write(target, entries)
    }

    /** title + bullet lines for one slide. */
    private data class Slide(val title: String, val bullets: List<String>)


    private fun buildSlides(title: String, blocks: List<MdBlock>): List<Slide> {
        val slides = mutableListOf<Slide>()
        var currentTitle = title
        val currentBullets = mutableListOf<String>()

        fun flush() {
            if (currentBullets.isEmpty() && slides.isNotEmpty()) return
            slides.add(Slide(currentTitle, currentBullets.toList()))
            currentBullets.clear()
        }

        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    flush()
                    currentTitle = block.text
                }
                is MdBlock.Paragraph -> currentBullets.add(MarkdownParser.stripInline(block.text))
                is MdBlock.Quote -> currentBullets.add("“${MarkdownParser.stripInline(block.text)}”")
                is MdBlock.Bullet -> block.items.forEach { item ->
                    currentBullets.add(MarkdownParser.stripInline(item))
                }
                is MdBlock.Code -> currentBullets.add("코드: ${block.code.lineSequence().first().take(80)}")
                is MdBlock.Table -> {
                    currentBullets.add("표: ${block.header.joinToString(" | ")}")
                    block.rows.take(4).forEach { row ->
                        currentBullets.add(row.joinToString(" | "))
                    }
                }
                MdBlock.Rule -> Unit
            }
        }
        flush()

        if (slides.isEmpty()) slides.add(Slide(title, listOf("내용이 없습니다.")))

        // Split slides that would overflow with too many bullets.
        return slides.flatMap { slide ->
            if (slide.bullets.size <= MAX_BULLETS_PER_SLIDE) {
                listOf(slide)
            } else {
                slide.bullets.chunked(MAX_BULLETS_PER_SLIDE).mapIndexed { index, chunk ->
                    Slide(
                        if (index == 0) slide.title else "${slide.title} (${index + 1})",
                        chunk
                    )
                }
            }
        }
    }

    private fun contentTypes(slideCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" """ +
            """ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/ppt/presentation.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
        append("""<Override PartName="/ppt/slideMasters/slideMaster1.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>""")
        append("""<Override PartName="/ppt/slideLayouts/slideLayout1.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""")
        append("""<Override PartName="/ppt/theme/theme1.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
        repeat(slideCount) { index ->
            append("""<Override PartName="/ppt/slides/slide${index + 1}.xml" """ +
                """ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
        }
        append("</Types>")
    }

    private fun rootRels() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" """ +
            """Target="ppt/presentation.xml"/></Relationships>"""

    private fun presentation(slideCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """)
        append("""xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""")
        append("""<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>""")
        append("<p:sldIdLst>")
        repeat(slideCount) { index ->
            append("""<p:sldId id="${256 + index}" r:id="rId${index + 2}"/>""")
        }
        append("</p:sldIdLst>")
        append("""<p:sldSz cx="$SLIDE_WIDTH" cy="$SLIDE_HEIGHT"/>""")
        append("""<p:notesSz cx="6858000" cy="9144000"/>""")
        append("</p:presentation>")
    }

    private fun presentationRels(slideCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        append("""<Relationship Id="rId1" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" """ +
            """Target="slideMasters/slideMaster1.xml"/>""")
        repeat(slideCount) { index ->
            append("""<Relationship Id="rId${index + 2}" """ +
                """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" """ +
                """Target="slides/slide${index + 1}.xml"/>""")
        }
        append("</Relationships>")
    }

    private fun slideMasterRels() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" """ +
            """Target="../slideLayouts/slideLayout1.xml"/>""" +
            """<Relationship Id="rId2" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" """ +
            """Target="../theme/theme1.xml"/></Relationships>"""

    private fun slideLayoutRels() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" """ +
            """Target="../slideMasters/slideMaster1.xml"/></Relationships>"""

    private fun slideRels() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" """ +
            """Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""

    /** Minimal but valid empty layout: only the required placeholder shapes. */
    private fun slideLayout() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
            """xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" """ +
            """type="blank" preserve="1"><p:cSld name="빈 레이아웃"><p:spTree>""" +
            """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
            """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>""" +
            """<a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
            """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

    private fun slideMaster() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
            """xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""" +
            """<p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg>""" +
            """<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
            """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>""" +
            """<a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
            """</p:spTree></p:cSld>""" +
            """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" """ +
            """accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" """ +
            """hlink="hlink" folHlink="folHlink"/>""" +
            """<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>""" +
            """</p:sldMaster>"""

    /** Office theme; the accent palette matches the app's blue branding. */
    private fun theme() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
            """name="DSK"><a:themeElements>""" +
            """<a:clrScheme name="DSK">""" +
            """<a:dk1><a:srgbClr val="1E293B"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>""" +
            """<a:dk2><a:srgbClr val="1E3A8A"/></a:dk2><a:lt2><a:srgbClr val="F1F5F9"/></a:lt2>""" +
            """<a:accent1><a:srgbClr val="2563EB"/></a:accent1><a:accent2><a:srgbClr val="0EA5E9"/></a:accent2>""" +
            """<a:accent3><a:srgbClr val="10B981"/></a:accent3><a:accent4><a:srgbClr val="F59E0B"/></a:accent4>""" +
            """<a:accent5><a:srgbClr val="EF4444"/></a:accent5><a:accent6><a:srgbClr val="8B5CF6"/></a:accent6>""" +
            """<a:hlink><a:srgbClr val="2563EB"/></a:hlink><a:folHlink><a:srgbClr val="7C3AED"/></a:folHlink>""" +
            """</a:clrScheme>""" +
            """<a:fontScheme name="DSK"><a:majorFont><a:latin typeface="Malgun Gothic"/>""" +
            """<a:ea typeface="Malgun Gothic"/><a:cs typeface=""/></a:majorFont>""" +
            """<a:minorFont><a:latin typeface="Malgun Gothic"/>""" +
            """<a:ea typeface="Malgun Gothic"/><a:cs typeface=""/></a:minorFont></a:fontScheme>""" +
            """<a:fmtScheme name="DSK"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/>""" +
            """</a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
            """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>""" +
            """<a:lnStyleLst><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>""" +
            """<a:ln w="25400"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>""" +
            """<a:ln w="38100"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>""" +
            """<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle>""" +
            """<a:effectStyle><a:effectLst/></a:effectStyle>""" +
            """<a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>""" +
            """<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
            """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
            """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst>""" +
            """</a:fmtScheme></a:themeElements></a:theme>"""

    /** One slide: a title box plus a bulleted content box, drawn directly on a blank layout. */
    private fun slideXml(slide: Slide): String {
        val titleBox = textBox(
            id = 2,
            name = "제목",
            x = 609600, y = 457200, cx = SLIDE_WIDTH - 1219200, cy = 914400,
            paragraphs = listOf(titleParagraph(slide.title)),
            anchor = "b"
        )

        val bodyBox = textBox(
            id = 3,
            name = "내용",
            x = 609600, y = 1600200, cx = SLIDE_WIDTH - 1219200, cy = SLIDE_HEIGHT - 2057400,
            paragraphs = slide.bullets.map { bulletParagraph(it) },
            anchor = "t"
        )

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
            """xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""" +
            """<p:cSld><p:spTree>""" +
            """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
            """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>""" +
            """<a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
            titleBox + bodyBox +
            """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
    }

    /** Wraps paragraphs into a non-autofit text box shape. */
    private fun textBox(
        id: Int,
        name: String,
        x: Int,
        y: Int,
        cx: Int,
        cy: Int,
        paragraphs: List<String>,
        anchor: String
    ) = buildString {
        append("""<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/>""")
        append("""<p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>""")
        append("""<p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""")
        append("""<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>""")
        append("""<p:txBody><a:bodyPr wrap="square" anchor="$anchor">""")
        append("""<a:normAutofit/></a:bodyPr><a:lstStyle/>""")
        paragraphs.forEach { append(it) }
        append("</p:txBody></p:sp>")
    }

    private fun titleParagraph(raw: String): String =
        """<a:p><a:pPr algn="l"/>""" +
            """<a:r><a:rPr lang="ko-KR" sz="3200" b="1">""" +
            """<a:solidFill><a:srgbClr val="1E3A8A"/></a:solidFill>""" +
            """<a:latin typeface="Malgun Gothic"/><a:ea typeface="Malgun Gothic"/></a:rPr>""" +
            """<a:t>${text(raw.take(120))}</a:t></a:r></a:p>"""

    private fun bulletParagraph(raw: String): String =
        """<a:p><a:pPr marL="285750" indent="-285750"><a:buChar char="•"/>""" +
            """<a:spcBef><a:spcPts val="600"/></a:spcBef></a:pPr>""" +
            """<a:r><a:rPr lang="ko-KR" sz="1800">""" +
            """<a:solidFill><a:srgbClr val="1E293B"/></a:solidFill>""" +
            """<a:latin typeface="Malgun Gothic"/><a:ea typeface="Malgun Gothic"/></a:rPr>""" +
            """<a:t>${text(raw.take(500))}</a:t></a:r></a:p>"""
}



