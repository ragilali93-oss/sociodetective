package com.example

import com.example.report.SocioReportExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SocioReportExporterTest {

  @Test
  fun `writeCsvWithBom prepends UTF-8 BOM and preserves Indonesian content`() {
    val out = ByteArrayOutputStream()
    val csv = "\"Nama Siswa\",\"Progres (%)\"\n\"Sinta Kirana\",75"
    SocioReportExporter.writeCsvWithBom(out, csv)
    val bytes = out.toByteArray()

    assertTrue(bytes.size > 3)
    assertTrue(bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
    val decoded = String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
    assertTrue(decoded.contains("Sinta Kirana"))
  }

  @Test
  fun `parseReportData extracts title, subtitle, and sections cleanly`() {
    val json = """
      {
        "title": "Laporan Progres Kelas — XI IPS 1",
        "subtitle": "Guru: Budi Santoso, S.Pd.",
        "generatedAt": "18 Nov 2026",
        "sections": [
          {
            "heading": "Ringkasan Kelas",
            "lines": ["Siswa bergabung: 2", "Rata-rata nilai kuis: 85%"]
          }
        ]
      }
    """.trimIndent()

    val parsed = SocioReportExporter.parseReportData(json)
    assertEquals("Laporan Progres Kelas — XI IPS 1", parsed.title)
    assertEquals("Guru: Budi Santoso, S.Pd.", parsed.subtitle)
    assertEquals("18 Nov 2026", parsed.generatedAt)
    assertEquals(1, parsed.sections.size)
    assertEquals("Ringkasan Kelas", parsed.sections[0].heading)
    assertEquals(2, parsed.sections[0].lines.size)
  }
}
