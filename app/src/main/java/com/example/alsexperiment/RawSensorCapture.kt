package com.example.alsexperiment

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File

/** Vendor values are opaque. Channel meanings require device/vendor documentation. */
internal class RawSensorCapture(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val candidates: List<Sensor> = manager.getSensorList(Sensor.TYPE_ALL).filter { it.type >= 65536 }
    private val lock = Any()
    private data class Reading(val timestampNs: Long, val csv: String)
    private val rows = ArrayList<Reading>()
    private var thread: HandlerThread? = null
    private var begin = Long.MAX_VALUE
    private var end = Long.MAX_VALUE
    var registration = "NOT_SELECTED"
        private set

    fun prepare(sensor: Sensor?) {
        stop()
        synchronized(lock) { rows.clear(); begin = Long.MAX_VALUE; end = Long.MAX_VALUE }
        registration = "NOT_SELECTED"
        if (sensor == null) return
        val worker = HandlerThread("VendorSensorCapture").apply { start() }
        thread = worker
        registration = try {
            if (manager.registerListener(this, sensor, 10_000, 0, Handler(worker.looper)))
                "REGISTERED type=${sensor.type} name=${sensor.name}"
            else "REGISTRATION_FAILED type=${sensor.type}"
        } catch (e: SecurityException) { "PERMISSION_DENIED type=${sensor.type}" }
    }

    fun begin(timestamp: Long) = synchronized(lock) { begin = timestamp }

    fun finish(timestamp: Long): List<String> {
        val snapshot = synchronized(lock) {
            end = timestamp
            CaptureWindow.snapshot(rows, begin, end) { it.timestampNs }.map { it.csv }
        }
        stop()
        return snapshot
    }

    fun stop() {
        manager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val received = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) {
            if (CaptureWindow.contains(event.timestamp, begin, end)) {
                rows.add(Reading(event.timestamp,
                    "${event.timestamp},$received,${event.sensor.type},${event.accuracy}," +
                    event.values.joinToString(";")))
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        fun write(file: File, rows: List<String>) = file.bufferedWriter().use { out ->
            out.appendLine("sensor_timestamp_ns,callback_timestamp_ns,sensor_type,accuracy,raw_values_semicolon_separated")
            rows.forEach { out.appendLine(it) }
        }
    }
}
