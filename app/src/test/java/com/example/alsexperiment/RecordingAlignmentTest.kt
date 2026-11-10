package com.example.alsexperiment

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class RecordingAlignmentTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(first: String = "UNAVAILABLE", positions: String = "10,100\n30,120\n"): File {
        val folder = temporary.newFolder()
        File(folder, "run_info.txt").writeText("""run_id=test
actual_sensor_log_start_ns=0
run_end_ns=40000000
first_frame_info_callback_ns=$first
pattern_schedule=USER_CONFIRMED
""")
        File(folder, "video_timeline.csv").writeText("event,elapsed_realtime_ns,video_position_ms\n" +
            positions.lineSequence().filter { it.isNotEmpty() }.joinToString("") {
                val (time, position) = it.split(',')
                "VIDEO_POSITION,${time.toLong() * 1000000},$position\n"
            })
        File(folder, "als_lux.csv").writeText("sensor_timestamp_ns,lux\n-1,1.0\n0,2.0\n20000000,177.0\n40000000,3.0\n")
        File(folder, "vendor_raw.csv").writeText("sensor_timestamp_ns,raw_values_semicolon_separated\n20000000,\"1,2;3\"\n")
        File(folder, "pattern_schedule.csv").writeText("pattern_id,segment_type,start_ms,end_ms\n1,PATTERN,0,3000\n")
        return folder
    }

    @Test fun writesPerSampleCorrectionPreservesSourceAndFiltersBoundary() {
        val folder = fixture()
        val original = File(folder, "als_lux.csv").readBytes()
        assertEquals(1, RecordingAlignment.generate(folder))
        assertArrayEquals(original, File(folder, "als_lux.csv").readBytes())
        val result = File(folder, "alignment/als_lux_aligned.csv").readLines()
        assertEquals(3, result.size)
        assertTrue(result[2].startsWith("20000000,177.0,110.000000,90.000000,ESTIMATED_PLAYER_TIME"))
        assertTrue(result[2].endsWith(",1,PATTERN,110.000000,AWAY_FROM_TRANSITION"))
        assertTrue(File(folder, "alignment/vendor_raw_aligned.csv").readText().contains("\"1,2;3\""))
        val metadata = File(folder, "alignment/alignment_metadata.json").readText()
        assertTrue(metadata.contains("\"outside_recording_window_removed\":2"))
        assertTrue(metadata.contains("\"optical_onset_calibrated\":false"))
        assertTrue(metadata.contains("\"median_time_correction_ms\":90.0"))
    }

    @Test fun gatesFirstFrameAndRefusesLongGapsReversalAndStalledPositions() {
        for ((positions, expected) in listOf(
            "10,0\n200,190\n" to "POSITION_GAP_TOO_LONG",
            "10,100\n30,90\n" to "POSITION_REVERSED",
            "10,0\n30,0\n" to "POSITION_NOT_ADVANCING")) {
            val folder = fixture(positions = positions)
            assertEquals(0, RecordingAlignment.generate(folder))
            assertTrue(File(folder, "alignment/als_lux_aligned.csv").readText().contains(expected))
        }
        val folder = fixture(first = "25000000")
        assertEquals(0, RecordingAlignment.generate(folder))
        assertTrue(File(folder, "alignment/als_lux_aligned.csv").readText().contains("BEFORE_FIRST_FRAME_INFO"))
    }

    @Test fun emptyPositionLogStillWritesAuditableUnalignedData() {
        val folder = fixture(positions = "")
        assertEquals(0, RecordingAlignment.generate(folder))
        assertTrue(File(folder, "alignment/als_lux_aligned.csv").readText().contains("OUTSIDE_POSITION_LOG"))
    }

    @Test fun negativeCorrectionAndHeaderOnlyVendorDataAreSupported() {
        val folder = fixture(positions = "10,0\n30,20\n")
        File(folder, "vendor_raw.csv").writeText("sensor_timestamp_ns,raw_values_semicolon_separated\n")
        assertEquals(1, RecordingAlignment.generate(folder))
        assertTrue(File(folder, "alignment/als_lux_aligned.csv").readText().contains("10.000000,-10.000000"))
        assertEquals(1, File(folder, "alignment/vendor_raw_aligned.csv").readLines().size)
    }

    @Test fun zipIncludesNestedAlignmentFilesWithRelativePaths() {
        val folder = fixture()
        RecordingAlignment.generate(folder)
        val output = ByteArrayOutputStream()
        RecordingArchive.write(folder, output)
        val entries = mutableSetOf<String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries.add(entry.name)
                if (entry.name == "alignment/als_lux_aligned.csv")
                    assertTrue(zip.readBytes().toString(Charsets.UTF_8).contains("time_correction_ms"))
            }
        }
        assertTrue(entries.contains("als_lux.csv"))
        assertTrue(entries.contains("alignment/alignment_metadata.json"))
        assertTrue(entries.contains("alignment/vendor_raw_aligned.csv"))
        assertTrue(entries.none { it.startsWith("/") || '\\' in it })
    }
}
