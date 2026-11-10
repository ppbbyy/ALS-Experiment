package com.example.alsexperiment

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Post-processing of saved logs. The original samples and files are never rewritten. */
internal object RecordingAlignment {
    private data class Point(val ns: Long, val ms: Double)
    private data class Estimate(val ms: Double?, val status: String, val spanMs: Double? = null)
    private data class Table(val header: List<String>, val rows: List<List<String>>) {
        fun value(row: List<String>, name: String): String = row[header.indexOf(name).also {
            require(it >= 0) { "Missing CSV column: $name" }
        }]
    }
    private val extra = listOf("aligned_video_time_ms", "time_correction_ms", "alignment_status",
        "position_bracket_span_ms", "aligned_pattern_id", "aligned_segment_type",
        "aligned_ms_to_transition", "aligned_transition_flag")

    fun generate(folder: File): Int {
        val metadataFile = File(folder, "run_info.txt")
        val metadata = metadataFile.readLines().filter { '=' in it }.associate {
            it.substringBefore('=') to it.substringAfter('=')
        }
        val begin = metadata.getValue("actual_sensor_log_start_ns").toLong()
        val end = metadata.getValue("run_end_ns").toLong()
        require(end > begin) { "Invalid recording interval" }
        val first = metadata["first_frame_info_callback_ns"]?.toLongOrNull()
        val timelineFile = File(folder, "video_timeline.csv")
        val timeline = readCsv(timelineFile)
        val points = timeline.rows.filter { timeline.value(it, "event") == "VIDEO_POSITION" }
            .map { Point(timeline.value(it, "elapsed_realtime_ns").toLong(),
                timeline.value(it, "video_position_ms").toDouble()) }
            .filter { it.ms.isFinite() && it.ms >= 0 }.sortedBy { it.ns }
        require(points.zipWithNext().all { (a, b) -> b.ns > a.ns }) { "Duplicate position timestamps" }
        val scheduleFile = File(folder, "pattern_schedule.csv")
        val schedule = if (metadata["pattern_schedule"]?.startsWith("USER_") == true && scheduleFile.exists())
            readCsv(scheduleFile) else null
        val directory = File(folder, "alignment")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create alignment folder" }
        val sources = linkedMapOf<String, Any?>()
        val outputs = linkedMapOf<String, Any?>()
        val files = mutableListOf(metadataFile, timelineFile)
        if (scheduleFile.exists()) files.add(scheduleFile)
        var validLuxRows = 0
        for (name in listOf("als_lux.csv", "vendor_raw.csv")) {
            val file = File(folder, name)
            if (!file.exists()) continue
            files.add(file)
            val table = readCsv(file)
            require(table.header.none { it in extra }) { "Input already aligned" }
            val outputFile = File(directory, name.removeSuffix(".csv") + "_aligned.csv")
            val corrections = ArrayList<Double>()
            val statuses = linkedMapOf<String, Int>()
            var removed = 0
            var count = 0
            outputFile.bufferedWriter(Charsets.UTF_8).use { out ->
                out.appendLine(csv(table.header + extra))
                for (row in table.rows) {
                    val ns = table.value(row, "sensor_timestamp_ns").toLong()
                    if (!CaptureWindow.contains(ns, begin, end)) { removed++; continue }
                    val estimate = estimate(ns, first, points)
                    val correction = estimate.ms?.minus((ns - begin) / 1e6)
                    val labels = labels(estimate.ms, schedule)
                    out.appendLine(csv(row + listOf(number(estimate.ms), number(correction),
                        estimate.status, number(estimate.spanMs)) + labels))
                    count++
                    statuses[estimate.status] = (statuses[estimate.status] ?: 0) + 1
                    if (correction != null) corrections.add(correction)
                }
            }
            corrections.sort()
            val median = if (corrections.isEmpty()) null else {
                val i = corrections.size / 2
                if (corrections.size % 2 == 0) (corrections[i - 1] + corrections[i]) / 2 else corrections[i]
            }
            if (name == "als_lux.csv") validLuxRows = corrections.size
            outputs[outputFile.name] = linkedMapOf(
                "input_rows" to table.rows.size, "output_rows" to count,
                "outside_recording_window_removed" to removed, "alignment_status_counts" to statuses,
                "median_time_correction_ms" to median,
                "minimum_time_correction_ms" to corrections.firstOrNull(),
                "maximum_time_correction_ms" to corrections.lastOrNull())
        }
        for (file in files) {
            sources[file.name] = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val report = linkedMapOf<String, Any?>(
            "run_id" to metadata["run_id"],
            "method" to "PIECEWISE_LINEAR_INTERPOLATION_OF_LOGGED_MEDIAPLAYER_POSITION",
            "equation" to "aligned_video_time_ms = (sensor_timestamp_ns - actual_sensor_log_start_ns) / 1e6 + time_correction_ms",
            "lux_values_modified" to false, "sensor_timestamps_modified" to false,
            "resampled" to false, "optical_onset_calibrated" to false,
            "maximum_position_gap_ms" to 100,
            "notes" to listOf(
                "Align runs by aligned_video_time_ms, not device-specific sensor clock origins.",
                "Per-sample player position estimate; not physical LCD onset or sensor response calibration.",
                "No extrapolation, duplicate sensor values or resampling. Blank aligned times are invalid.",
                "Position bracket span and 50ms transition flag are not error bounds.",
                "First-frame info may be unavailable after warm-up; pre-display gating is then unavailable.",
                "Pattern labels rely on the user-confirmed schedule, not content verification."),
            "sources" to sources, "outputs" to outputs)
        File(directory, "alignment_metadata.json").writeText(json(report) + "\n", Charsets.UTF_8)
        return validLuxRows
    }

    private fun estimate(ns: Long, first: Long?, points: List<Point>): Estimate {
        if (first != null && ns < first) return Estimate(null, "BEFORE_FIRST_FRAME_INFO")
        if (points.size < 2 || ns < points.first().ns || ns > points.last().ns)
            return Estimate(null, "OUTSIDE_POSITION_LOG")
        var lo = 0
        var hi = points.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].ns <= ns) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val gap = b.ns - a.ns
        val span = gap / 1e6
        if (gap > 100_000_000L) return Estimate(null, "POSITION_GAP_TOO_LONG", span)
        if (b.ms < a.ms) return Estimate(null, "POSITION_REVERSED", span)
        if (b.ms == a.ms) return Estimate(null, "POSITION_NOT_ADVANCING", span)
        return Estimate(a.ms + (b.ms - a.ms) * ((ns - a.ns).toDouble() / gap),
            "ESTIMATED_PLAYER_TIME", span)
    }

    private fun labels(position: Double?, schedule: Table?): List<String> {
        if (position == null) return listOf("", "", "", "NO_POSITION_ESTIMATE")
        schedule?.rows?.forEach { row ->
            val start = schedule.value(row, "start_ms").toDouble()
            val end = schedule.value(row, "end_ms").toDouble()
            if (position >= start && position < end) {
                val distance = minOf(position - start, end - position)
                return listOf(schedule.value(row, "pattern_id"), schedule.value(row, "segment_type"),
                    number(distance), if (distance <= 50) "NEAR_TRANSITION" else "AWAY_FROM_TRANSITION")
            }
        }
        return listOf("", "", "", "NO_CONFIRMED_SCHEDULE")
    }

    private fun number(value: Double?): String = value?.let { String.format(Locale.US, "%.6f", it) } ?: ""
    private fun csv(values: List<String>): String = values.joinToString(",") { value ->
        if (value.any { it in ",\"\r\n" }) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
    private fun readCsv(file: File): Table {
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val cell = StringBuilder()
        val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row.add(cell.toString()); cell.setLength(0) }
                !quoted && (c == '\n' || c == '\r') -> {
                    row.add(cell.toString()); cell.setLength(0)
                    rows.add(row.toList()); row.clear()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cell.append(c)
            }
            i++
        }
        require(!quoted) { "Unclosed CSV quote" }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row.toList()) }
        require(rows.isNotEmpty()) { "Empty CSV: ${file.name}" }
        val header = rows.first()
        require(rows.drop(1).all { it.size == header.size }) { "Malformed CSV: ${file.name}" }
        return Table(header, rows.drop(1))
    }

    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> "\"" + value.toString().map { c -> when (c) {
            '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
            else -> if (c.code < 32) "\\u%04x".format(c.code) else c.toString()
        } }.joinToString("") + "\""
    }
}
