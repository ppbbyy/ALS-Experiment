package com.example.alsexperiment

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class LanSample(val sensorNs: Long, val callbackNs: Long, val lux: Float, val accuracy: Int)

/** One worker per run; retries the same sequence range until the receiver acknowledges it. */
internal class LanUploader(
    private val address: String,
    private val metadata: JSONObject,
    private val startNs: Long,
    private val snapshot: (Int) -> List<LanSample>,
    private val status: (String) -> Unit
) {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var sent = 0
    private val stateLock = Any()
    private var ending: Pair<List<LanSample>, JSONObject>? = null

    fun start() {
        worker.scheduleWithFixedDelay({ tick() }, 0, 200, TimeUnit.MILLISECONDS)
    }

    fun finish(samples: List<LanSample>, end: JSONObject) {
        synchronized(stateLock) { ending = samples to end }
    }

    private fun tick() {
        try {
            val (final, samples) = synchronized(stateLock) {
                val final = ending
                final to (final?.first?.drop(sent)?.take(200) ?: snapshot(sent))
            }
            val until = sent + samples.size
            val rows = JSONArray()
            for ((offset, s) in samples.withIndex()) {
                val i = sent + offset
                rows.put(JSONObject().put("seq", i).put("sensor_timestamp_ns", s.sensorNs.toString())
                    .put("callback_timestamp_ns", s.callbackNs.toString())
                    .put("elapsed_ms", (s.sensorNs - startNs) / 1e6)
                    .put("lux", s.lux.toDouble()).put("accuracy", s.accuracy))
            }
            val done = final != null && until >= final.first.size
            val packet = JSONObject().put("run_id", metadata.getString("run_id"))
                .put("metadata", metadata).put("samples", rows)
            if (done) packet.put("end", final!!.second)
            val connection = URL("${address.trimEnd('/')}/ingest").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(packet.toString().toByteArray(Charsets.UTF_8)) }
                check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                connection.inputStream.use { it.readBytes() }
            } finally { connection.disconnect() }
            sent = until
            status(if (done) "전송 완료: ${final!!.first.size}개" else "노트북 수신 확인: ${sent}개")
            if (done) worker.shutdown()
        } catch (e: Exception) {
            status("전송 재시도 중: ${e.message}")
        }
    }
}
