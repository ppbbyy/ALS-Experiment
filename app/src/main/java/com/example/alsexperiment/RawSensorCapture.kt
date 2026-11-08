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
    private val rows = ArrayList<String>()
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
        val snapshot = synchronized(lock) { end = timestamp; rows.toList() }
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
            if (event.timestamp >= begin && event.timestamp < end) {
                rows.add("${event.timestamp},$received,${event.sensor.type},${event.accuracy}," +
                    event.values.joinToString(";"))
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
