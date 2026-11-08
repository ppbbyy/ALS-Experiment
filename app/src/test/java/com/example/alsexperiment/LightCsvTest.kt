package com.example.alsexperiment

import java.io.StringWriter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LightCsvTest {
    @Test fun epochNanosecondsKeepLowDigitsAndElapsedUsesRecordingStart() {
        val out = StringWriter()
        LightCsv.writeHeader(out)
        LightCsv.writeSample(out, 9_000_000_123L, 56f,
            9_000_000_000L, 1_793_616_189_000L, 8_000_000_000L)
        assertEquals("time,seconds_elapsed,lux\n1793616190000000123,0.000000123,56.0\n", out.toString())
    }

    @Test fun decimalSeparatorStaysCsvSafeInCommaDecimalLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val out = StringWriter()
            LightCsv.writeSample(out, 10_000_000L, 1.5f, 0L, 0L, 0L)
            assertEquals("10000000,0.010000000,1.5\n", out.toString())
        } finally { Locale.setDefault(previous) }
    }
}
