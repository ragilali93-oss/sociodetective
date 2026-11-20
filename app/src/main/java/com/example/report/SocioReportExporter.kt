package com.example.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import org.json.JSONObject
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Generates real PDF documents (via android.graphics.pdf.PdfDocument) and
 * UTF-8 BOM CSV files (compatible with Microsoft Excel) to user-selected SAF URIs.
 */
object SocioReportExporter {

  fun writeCsvWithBom(outputStream: OutputStream, csvContent: String) {
    outputStream.use { stream ->
      // UTF-8 BOM so Microsoft Excel detects Indonesian diacritics and delimiters cleanly
      stream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
      stream.write(csvContent.toByteArray(StandardCharsets.UTF_8))
      stream.flush()
    }
  }

  data class ReportSection(val heading: String, val lines: List<String>)
  data class ReportData(
    val title: String,
    val subtitle: String,
    val generatedAt: String,
    val sections: List<ReportSection>,
  )

  fun parseReportData(reportJson: String): ReportData {
    val json = runCatching { JSONObject(reportJson) }.getOrNull() ?: JSONObject()
    val title = json.optString("title", "Laporan Perkembangan SocioDetective")
    val subtitle = json.optString("subtitle", "")
    val generatedAt = json.optString("generatedAt", "")
    val sectionsArray = json.optJSONArray("sections")
    val sections = buildList {
      if (sectionsArray != null) {
        for (i in 0 until sectionsArray.length()) {
          val sec = sectionsArray.optJSONObject(i) ?: continue
          val heading = sec.optString("heading", "")
          val linesArr = sec.optJSONArray("lines")
          val lines = buildList {
            if (linesArr != null) {
              for (j in 0 until linesArr.length()) {
                val line = linesArr.optString(j, "")
                if (line.isNotBlank()) add(line)
              }
            }
          }
          add(ReportSection(heading = heading, lines = lines))
        }
      }
    }
    return ReportData(title = title, subtitle = subtitle, generatedAt = generatedAt, sections = sections)
  }

  fun writePdfFromJson(outputStream: OutputStream, reportJson: String) {
    val report = parseReportData(reportJson)
    val title = report.title
    val subtitle = report.subtitle
    val generatedAt = report.generatedAt
    val sections = report.sections

    val pageWidth = 595 // A4 width in points (72 dpi)
    val pageHeight = 842 // A4 height in points
    val margin = 42f
    val usableWidth = pageWidth - margin * 2

    val pdf = PdfDocument()
    var pageNumber = 1
    var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
    var canvas: Canvas = page.canvas
    var cursorY = margin

    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 36, 84)
      textSize = 16f
      isFakeBoldText = true
    }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(71, 85, 105)
      textSize = 10f
    }
    val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(29, 78, 216)
      textSize = 12f
      isFakeBoldText = true
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(30, 41, 59)
      textSize = 10f
    }
    val dividerPaint = Paint().apply {
      color = Color.rgb(203, 213, 225)
      strokeWidth = 1f
    }

    fun newPage() {
      pdf.finishPage(page)
      pageNumber += 1
      page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
      canvas = page.canvas
      cursorY = margin
    }

    fun ensureSpace(neededHeight: Float) {
      if (cursorY + neededHeight > pageHeight - margin) {
        newPage()
      }
    }

    fun drawWrappedText(text: String, paint: Paint, lineHeight: Float, indent: Float = 0f) {
      val maxWidth = usableWidth - indent
      val paragraphs = text.split("\n")
      for (paragraph in paragraphs) {
        val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) {
          cursorY += lineHeight * 0.5f
          continue
        }
        var currentLine = StringBuilder()
        for (word in words) {
          val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
          if (paint.measureText(candidate) <= maxWidth) {
            currentLine = StringBuilder(candidate)
          } else {
            ensureSpace(lineHeight)
            canvas.drawText(currentLine.toString(), margin + indent, cursorY, paint)
            cursorY += lineHeight
            currentLine = StringBuilder(word)
          }
        }
        if (currentLine.isNotEmpty()) {
          ensureSpace(lineHeight)
          canvas.drawText(currentLine.toString(), margin + indent, cursorY, paint)
          cursorY += lineHeight
        }
      }
    }

    // Header
    drawWrappedText(title, headerPaint, 20f)
    if (subtitle.isNotBlank()) {
      drawWrappedText(subtitle, subPaint, 14f)
    }
    if (generatedAt.isNotBlank()) {
      drawWrappedText("Dicetak pada: $generatedAt", subPaint, 14f)
    }
    cursorY += 4f
    canvas.drawLine(margin, cursorY, pageWidth - margin, cursorY, dividerPaint)
    cursorY += 14f

    for (sec in sections) {
      if (sec.heading.isNotBlank()) {
        ensureSpace(24f)
        cursorY += 4f
        drawWrappedText(sec.heading, sectionPaint, 16f)
      }
      for (line in sec.lines) {
        if (line.isNotBlank()) {
          drawWrappedText("• $line", bodyPaint, 14f, indent = 8f)
        }
      }
      cursorY += 6f
    }

    pdf.finishPage(page)
    outputStream.use { stream ->
      pdf.writeTo(stream)
      stream.flush()
    }
    pdf.close()
  }
}
