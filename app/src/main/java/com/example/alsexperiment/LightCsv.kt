package com.example.alsexperiment

import java.io.Writer
import java.util.Locale

/** Epoch time is derived from a fixed wall/monotonic anchor, without Double rounding. */
internal object LightCsv {
    fun writeHeader(out: Writer) {
        out.write("time,seconds_elapsed,lux\n")
    }

    fun writeSample(out: Writer, sensorNs: Long, lux: Float,
                    recordingStartNs: Long, wallAnchorMs: Long, monoAnchorNs: Long) {
        val epochNs = wallAnchorMs * 1_000_000L + (sensorNs - monoAnchorNs)
        val elapsedSeconds = (sensorNs - recordingStartNs) / 1_000_000_000.0
        out.write("$epochNs,${String.format(Locale.US, "%.9f", elapsedSeconds)},$lux\n")
    }
}
