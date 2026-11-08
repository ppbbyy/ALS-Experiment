package com.example.alsexperiment

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.min

private const val SENSOR_PERIOD_US = 10_000       // 100 Hz 요청 (보장되는 실제 측정 속도는 아님)
private const val START_DELAY_MS = 5_000L        // 버튼 후 5초
private const val VIDEO_POLL_MS = 20L           // 영상 위치 조회 목표 간격
private const val BOUNDARY_MARGIN_MS = 50.0     // 패턴 전환부에 붙일 주의 플래그 (보장된 오차 한계 X)
private const val PATTERN_COUNT = 30

private enum class RunPhase { IDLE, COUNTDOWN, RUNNING, SAVING }

private enum class PatternMode(
    val title: String,
    val fps: Int,
    val patternFrames: Int,
    val blackFrames: Int,
    val videoFile: String
) {
    PAPER("A. 논문 방식: 패턴 0.333초 / 검정 0.167초", 12, 4, 2, "als_paper_2hz.mp4"),
    LONG("B. 긴 방식: 패턴 3초 / 검정 3초", 10, 30, 30, "als_3s_3s.mp4");

    val patternDurationMs: Double get() = 1000.0 * patternFrames / fps
    val blackDurationMs: Double get() = 1000.0 * blackFrames / fps
    val cycleMs: Double get() = patternDurationMs + blackDurationMs
    val durationMs: Double get() = PATTERN_COUNT * cycleMs
}

private data class LuxSample(
    val sensorNs: Long,
    val callbackNs: Long,
    val lux: Float,
    val accuracy: Int
)

private data class VideoMark(
    val type: String,
    val timestampNs: Long,
    val videoPositionMs: Int,
    val queryHalfDurationNs: Long = 0L
)

private data class PatternAssignment(
    val patternId: Int,
    val kind: String,
    val distanceToBoundaryMs: Double,
    val flag: String
)

/**
 * 센서 타임스탬프와 TextureView/MediaPlayer 콜백을 동일한 elapsedRealtimeNanos() 시계로 기록.
 * 실제 디스플레이 광학 발광 시각/ALS 아날로그 응답 지연까지 0으로 만드는 것은 불가능합니다.
 */
private class AlsRunManager(private val context: Context) : SensorEventListener {
    val rawCapture = RawSensorCapture(context)
    var rawSensor by mutableStateOf<Sensor?>(null)
    var scheduleEnabled by mutableStateOf(false)
    private val main = Handler(Looper.getMainLooper())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var sensorThread: HandlerThread? = null
    private val samplesLock = Any()
    private val samples = ArrayList<LuxSample>()
    private var gateStartNs = Long.MAX_VALUE
    private var gateEndNs = Long.MAX_VALUE
    private var lastLuxUpdateNs = 0L
    private val marks = ArrayList<VideoMark>() // main thread 전용
    private var textureView: TextureView? = null
    private var displaySurface: Surface? = null
    private var mediaPlayer: MediaPlayer? = null
    private var modeUsed = PatternMode.PAPER
    var selectedVideoUri by mutableStateOf<Uri?>(null)
        private set
    var selectedVideoName by mutableStateOf("영상을 선택하세요")
        private set
    private var runVideoName = ""
    private var frameCount = 0
    private var runFrameCount = 0
    private var previousFrameTimestamp = Long.MIN_VALUE
    private var disposed = false
    private var experimentId = ""
    private var wallAnchorEpochMs = 0L
    private var monoAnchorNs = 0L
    private var buttonNs = 0L
    private var targetStartNs = 0L
    private var actualStartNs = 0L
    private var playCommandBeforeNs = 0L
    private var playCommandAfterNs = 0L
    private var firstFrameInfoNs: Long? = null
    private var runEndNs = 0L
    private var playbackMoved = false

    var phase by mutableStateOf(RunPhase.IDLE)
        private set
    var selectedMode by mutableStateOf(PatternMode.PAPER)
        private set
    var videoReady by mutableStateOf(false)
        private set
    var countdownNumber by mutableIntStateOf(5)
        private set
    var currentLux by mutableStateOf<Float?>(null)
        private set
    var sensorActive by mutableStateOf(false)
        private set
    var sampleCount by mutableIntStateOf(0)
        private set
    var status by mutableStateOf("영상 및 ALS 센서 준비 중")
        private set
    var lastResultStatus by mutableStateOf("")
        private set
    var savedFolder by mutableStateOf<File?>(null)
        private set
    var observedRateHz by mutableStateOf<Double?>(null)
        private set

    private val countdownTick = object : Runnable {
        override fun run() {
            if (phase != RunPhase.COUNTDOWN) return
            val remainNs = (targetStartNs - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(0L)
            countdownNumber = ceil(remainNs / 1_000_000_000.0).toInt().coerceIn(0, 5)
            if (remainNs > 0L) main.postDelayed(this, 50L)
        }
    }

    private val startRunnable = Runnable { startNow() }

    private val progressTick = object : Runnable {
        override fun run() {
            if (phase != RunPhase.RUNNING) return
            val player = mediaPlayer ?: return
            val before = SystemClock.elapsedRealtimeNanos()
            val position = try { player.currentPosition } catch (_: Exception) { -1 }
            val after = SystemClock.elapsedRealtimeNanos()
            val midpoint = before + (after - before) / 2L
            marks.add(VideoMark("VIDEO_POSITION", midpoint, position, (after - before) / 2L))
            if (position >= 100) playbackMoved = true

            // 검은 화면이 무한정 유지되는 경우 원인을 알아볼 수 있도록 종료합니다.
            // 시작 후 5초 동안 영상 위치가 0 근처에서 전혀 진행되지 않았을 때만 중단.
            if (!playbackMoved && playCommandBeforeNs > 0L &&
                midpoint - playCommandBeforeNs >= 5_000_000_000L
            ) {
                marks.add(VideoMark("VIDEO_STALLED", midpoint, position))
                finish("video_position_stalled")
                return
            }
            main.postDelayed(this, VIDEO_POLL_MS)
        }
    }

    fun startSensor() {
        if (sensorActive) return
        val light = sensor
        if (light == null) {
            status = "이 기기에서 TYPE_LIGHT 센서를 찾지 못했습니다."
            return
        }
        val thread = HandlerThread("Als100HzCapture")
        thread.start()
        sensorThread = thread
        sensorActive = sensorManager.registerListener(
            this, light, SENSOR_PERIOD_US, 0, Handler(thread.looper)
        )
        if (!sensorActive) {
            thread.quitSafely()
            sensorThread = null
            status = "센서 등록 실패"
        } else {
            status = "센서 준비 완료 (100Hz 요청)"
        }
    }

    fun stopSensor() {
        if (sensorActive) sensorManager.unregisterListener(this)
        sensorActive = false
        sensorThread?.quitSafely()
        sensorThread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT) return
        val receivedNs = SystemClock.elapsedRealtimeNanos()
        val lux = event.values[0]
        synchronized(samplesLock) {
            // 콜백 수신 지연으로 과거 센서 이벤트가 RUN_BEGIN 이후 들어오는 경우 제외
            if (event.timestamp >= gateStartNs && event.timestamp < gateEndNs) {
                samples.add(LuxSample(event.timestamp, receivedNs, lux, event.accuracy))
            }
        }
        // 비실험 화면에서는 0.25초 단위로 최신 측정값만 UI에 전달 (100Hz 콜백 부하 억제)
        if (receivedNs - lastLuxUpdateNs >= 250_000_000L) {
            lastLuxUpdateNs = receivedNs
            main.post { currentLux = lux }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * VideoView(SurfaceView)는 구형 Android에서 Compose 오버레이와 겹칠 때 검게 보일 수 있습니다.
     * TextureView는 일반 UI 합성 계층에서 출력하므로 이를 피하기 위한 변경입니다.
     */
    fun bindVideo(texture: TextureView) {
        textureView = texture
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                displaySurface?.release()
                displaySurface = Surface(surfaceTexture)
                if (phase == RunPhase.IDLE) loadSelectedVideo()
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                mediaPlayer?.let { applyAspectFit(texture, it.videoWidth, it.videoHeight) }
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                if (phase == RunPhase.COUNTDOWN) cancelCountdown()
                if (phase == RunPhase.RUNNING) finish("video_surface_destroyed")
                releaseMediaPlayer()
                displaySurface?.release()
                displaySurface = null
                videoReady = false
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                if (phase != RunPhase.RUNNING) return
                val timestamp = surfaceTexture.timestamp
                if (timestamp == previousFrameTimestamp) return
                previousFrameTimestamp = timestamp
                frameCount++
                // Callback receipt time, NOT physical light onset. Texture timestamp has its own origin.
                marks.add(VideoMark("TEXTURE_FRAME_$timestamp", SystemClock.elapsedRealtimeNanos(),
                    try { mediaPlayer?.currentPosition ?: -1 } catch (_: Exception) { -1 }))
            }
        }
        if (texture.isAvailable) {
            texture.surfaceTexture?.let { available ->
                displaySurface?.release()
                displaySurface = Surface(available)
                if (phase == RunPhase.IDLE) loadSelectedVideo()
            }
        }
    }

    private fun releaseMediaPlayer() {
        val old = mediaPlayer
        mediaPlayer = null
        videoReady = false
        if (old != null) {
            try { old.setOnPreparedListener(null) } catch (_: Exception) { }
            try { old.setOnCompletionListener(null) } catch (_: Exception) { }
            try { old.setOnErrorListener(null) } catch (_: Exception) { }
            try { old.release() } catch (_: Exception) { }
        }
    }

    /** 태블릿 화면 비율(4:3)에 16:9 영상을 찌그러지지 않게 맞춥니다. */
    private fun applyAspectFit(texture: TextureView, videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0 || texture.width <= 0 || texture.height <= 0) return
        val surfaceWidth = texture.width.toFloat()
        val surfaceHeight = texture.height.toFloat()
        val videoAspect = videoWidth.toFloat() / videoHeight
        val viewAspect = surfaceWidth / surfaceHeight
        val scaleX = if (videoAspect < viewAspect) videoAspect / viewAspect else 1f
        val scaleY = if (videoAspect > viewAspect) viewAspect / videoAspect else 1f
        val matrix = Matrix().apply {
            setScale(scaleX, scaleY, surfaceWidth / 2f, surfaceHeight / 2f)
        }
        texture.setTransform(matrix)
    }

    fun selectVideo(uri: Uri, name: String) {
        if (phase != RunPhase.IDLE) return
        selectedVideoUri = uri
        selectedVideoName = name.replace('\n', '_').replace('\r', '_')
        loadSelectedVideo()
    }

    fun dispose() {
        disposed = true
        handleLeavingScreen()
        stopSensor()
        rawCapture.stop()
        main.removeCallbacks(startRunnable)
        main.removeCallbacks(countdownTick)
        main.removeCallbacks(progressTick)
        releaseMediaPlayer()
        displaySurface?.release()
        displaySurface = null
    }

    fun selectMode(mode: PatternMode) {
        if (phase != RunPhase.IDLE) return
        if (selectedMode == mode) return
        selectedMode = mode
        loadSelectedVideo()
    }

    private fun loadSelectedVideo() {
        if (disposed || phase != RunPhase.IDLE) return
        val uri = selectedVideoUri ?: run { status = "먼저 영상 파일을 선택하세요"; return }
        val surface = displaySurface ?: run {
            status = "영상 표시 화면 준비 중"
            return
        }
        releaseMediaPlayer()
        status = "${selectedMode.title} 영상 준비 중"

        try {
            val videoUri = uri
            val player = MediaPlayer()
            mediaPlayer = player
            player.setOnPreparedListener { prepared ->
                if (prepared === mediaPlayer) {
                    videoReady = true
                    applyAspectFit(textureView ?: return@setOnPreparedListener,
                        prepared.videoWidth, prepared.videoHeight)
                    if (phase == RunPhase.IDLE) status = "영상 준비 완료 (화면 출력 준비 완료)"
                }
            }
            player.setOnVideoSizeChangedListener { changed, width, height ->
                if (changed === mediaPlayer) textureView?.let { applyAspectFit(it, width, height) }
            }
            player.setOnInfoListener { infoPlayer, what, extra ->
                if (infoPlayer === mediaPlayer) {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START &&
                        phase == RunPhase.RUNNING && firstFrameInfoNs == null
                    ) {
                        val now = SystemClock.elapsedRealtimeNanos()
                        firstFrameInfoNs = now
                        addMark("VIDEO_FIRST_FRAME_INFO", now)
                    }
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING) {
                        addMark("VIDEO_TRACK_LAGGING", SystemClock.elapsedRealtimeNanos())
                    }
                }
                false
            }
            player.setOnCompletionListener { completed ->
                if (completed === mediaPlayer && phase == RunPhase.RUNNING) finish("video_completed")
            }
            player.setOnErrorListener { errored, what, extra ->
                if (errored === mediaPlayer) {
                    addMark("VIDEO_ERROR_${what}_${extra}", SystemClock.elapsedRealtimeNanos())
                    status = "영상 오류 (MediaPlayer): $what / $extra"
                    if (phase == RunPhase.RUNNING) finish("video_error_${what}_${extra}")
                    else if (phase == RunPhase.COUNTDOWN) cancelCountdown()
                }
                true
            }
            player.setSurface(surface)
            player.setVolume(0f, 0f)
            player.setDataSource(context, videoUri)
            player.prepareAsync()
        } catch (e: Exception) {
            releaseMediaPlayer()
            status = "영상 불러오기 실패: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun arm(experimentName: String) {
        val player = mediaPlayer
        if (phase != RunPhase.IDLE || !videoReady || !sensorActive || player == null) {
            status = "영상 준비와 조도 센서 연결 상태를 확인해 주세요."
            return
        }
        modeUsed = selectedMode
        rawCapture.prepare(rawSensor)
        runVideoName = selectedVideoName
        frameCount = 0
        previousFrameTimestamp = Long.MIN_VALUE
        val safe = experimentName.trim().ifBlank { "Tablet_01" }
            .replace(Regex("[^A-Za-z0-9가-힣_-]"), "_").take(40)
        experimentId = safe + "_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        synchronized(samplesLock) {
            samples.clear()
            gateStartNs = Long.MAX_VALUE
            gateEndNs = Long.MAX_VALUE
        }
        marks.clear()
        savedFolder = null
        lastResultStatus = ""
        observedRateHz = null
        sampleCount = 0
        firstFrameInfoNs = null
        actualStartNs = 0L
        playCommandBeforeNs = 0L
        playCommandAfterNs = 0L
        runEndNs = 0L
        playbackMoved = false
        val anchorBeforeNs = SystemClock.elapsedRealtimeNanos()
        wallAnchorEpochMs = System.currentTimeMillis()
        val anchorAfterNs = SystemClock.elapsedRealtimeNanos()
        monoAnchorNs = anchorBeforeNs + (anchorAfterNs - anchorBeforeNs) / 2L
        buttonNs = SystemClock.elapsedRealtimeNanos()
        targetStartNs = buttonNs + START_DELAY_MS * 1_000_000L
        marks.add(VideoMark("START_BUTTON_PRESSED", buttonNs, 0))
        marks.add(VideoMark("TARGET_START", targetStartNs, 0))
        countdownNumber = 5
        phase = RunPhase.COUNTDOWN
        status = "5초 카운트다운 중 (아직 측정 저장 시작 전)"
        // 영상은 매 실험 전에 새 MediaPlayer로 준비하여 이미 0ms 위치에 있습니다.
        // 카운트다운 중 seekTo(0)를 호출하면 구형 디코더에서 시작을 늦출 수 있어 생략합니다.
        main.post(countdownTick)
        // 버튼 타임스탬프를 기준으로 남은 시간만 예약 (seekTo 처리 시간에 따른 지연 누적 방지)
        val remainingMs = ((targetStartNs - SystemClock.elapsedRealtimeNanos() + 999_999L) / 1_000_000L)
            .coerceAtLeast(0L)
        main.postDelayed(startRunnable, remainingMs)
    }

    fun cancelCountdown() {
        if (phase != RunPhase.COUNTDOWN) return
        main.removeCallbacks(startRunnable)
        main.removeCallbacks(countdownTick)
        rawCapture.stop()
        phase = RunPhase.IDLE
        status = "카운트다운 취소 (데이터 수집 안 함)"
    }

    private fun startNow() {
        if (phase != RunPhase.COUNTDOWN) return
        val player = mediaPlayer
        if (player == null || !videoReady || !sensorActive) {
            cancelCountdown()
            status = "실험 시작 실패: 영상 또는 센서 준비 오류"
            return
        }
        main.removeCallbacks(countdownTick)
        actualStartNs = SystemClock.elapsedRealtimeNanos()
        rawCapture.begin(actualStartNs)
        // 시작되는 센서 측정의 실제 이벤트 시각이 actualStartNs 이상인 샘플만 기록
        synchronized(samplesLock) {
            gateStartNs = actualStartNs
            gateEndNs = Long.MAX_VALUE
        }
        marks.add(VideoMark("ALS_LOG_START", actualStartNs, -1))
        phase = RunPhase.RUNNING
        status = "영상과 ALS 기록 진행 중"
        playCommandBeforeNs = SystemClock.elapsedRealtimeNanos()
        addMark("VIDEO_START_CALL_BEFORE", playCommandBeforeNs)
        try {
            player.start()
        } catch (e: Exception) {
            status = "영상 시작 오류: ${e.message}"
            finish("start_exception")
            return
        }
        playCommandAfterNs = SystemClock.elapsedRealtimeNanos()
        addMark("VIDEO_START_CALL_AFTER", playCommandAfterNs)
        main.post(progressTick)
    }

    private fun addMark(type: String, timestamp: Long) {
        val position = try { mediaPlayer?.currentPosition ?: -1 } catch (_: Exception) { -1 }
        marks.add(VideoMark(type, timestamp, position))
    }

    fun finish(reason: String) {
        when (phase) {
            RunPhase.COUNTDOWN -> { cancelCountdown(); return }
            RunPhase.RUNNING -> Unit
            else -> return
        }
        runEndNs = SystemClock.elapsedRealtimeNanos()
        val rawSnapshot = rawCapture.finish(runEndNs)
        val rawRegistration = rawCapture.registration
        synchronized(samplesLock) { gateEndNs = runEndNs }
        addMark("RUN_END_$reason", runEndNs)
        main.removeCallbacks(progressTick)
        phase = RunPhase.SAVING
        status = "CSV 저장 중..."
        val player = mediaPlayer
        try { player?.pause() } catch (_: Exception) { }
        val sensorSnapshot = synchronized(samplesLock) { samples.toList() }
        val markSnapshot = marks.toList()
        val runMode = modeUsed
        val outputName = experimentId
        val button = buttonNs
        val target = targetStartNs
        val start = actualStartNs
        val beforePlay = playCommandBeforeNs
        val afterPlay = playCommandAfterNs
        val firstFrame = firstFrameInfoNs
        val end = runEndNs
        val wallAnchor = wallAnchorEpochMs
        val monoAnchor = monoAnchorNs
        val sensorDetails = sensor
        runFrameCount = frameCount
        val videoNameSnapshot = runVideoName

        Thread {
            try {
                val folder = File(context.getExternalFilesDir(null) ?: context.filesDir,
                    "ALSRecords/$outputName")
                if (!folder.exists() && !folder.mkdirs()) throw IOException("저장 폴더 생성 실패")
                File(folder, "sensor_inventory.txt").writeText(
                    sensorManager.getSensorList(Sensor.TYPE_ALL).joinToString("\n") {
                        "name=${it.name}; vendor=${it.vendor}; type=${it.type}; stringType=${it.stringType}; " +
                            "reportingMode=${it.reportingMode}; minDelayUs=${it.minDelay}; resolution=${it.resolution}"
                    }, Charsets.UTF_8)
                File(folder, "selected_video.txt").writeText(videoNameSnapshot, Charsets.UTF_8)
                RawSensorCapture.write(File(folder, "vendor_raw.csv"), rawSnapshot)
                File(folder, "vendor_registration.txt").writeText(rawRegistration)
                File(folder, "Light.csv").bufferedWriter(Charsets.UTF_8).use { out ->
                    LightCsv.writeHeader(out)
                    sensorSnapshot.sortedBy { it.sensorNs }.forEach { sample ->
                        LightCsv.writeSample(out, sample.sensorNs, sample.lux,
                            start, wallAnchor, monoAnchor)
                    }
                }
                File(folder, "Metadata.csv").bufferedWriter(Charsets.UTF_8).use { out ->
                    fun cell(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
                    out.appendLine("format,device name,platform,platform version,sensors,requested_sample_period_us,recording_start_epoch_ns,selected_video")
                    out.appendLine(listOf("ALS-Experiment-1", Build.MODEL, "android",
                        Build.VERSION.SDK_INT.toString(), "Light", SENSOR_PERIOD_US.toString(),
                        (wallAnchor * 1_000_000L + start - monoAnchor).toString(),
                        videoNameSnapshot).joinToString(",") { cell(it) })
                }
                if (scheduleEnabled) writePatternSchedule(File(folder, "pattern_schedule.csv"), runMode)
                writeVideoMarks(File(folder, "video_timeline.csv"), markSnapshot, start, firstFrame)
                writeLuxSamples(File(folder, "als_lux.csv"), sensorSnapshot, markSnapshot,
                    runMode, start, beforePlay, firstFrame)
                val rate = calculateRate(sensorSnapshot)
                writeRunInfo(File(folder, "run_info.txt"), runMode, outputName,
                    reason, button, target, start, beforePlay, afterPlay, firstFrame,
                    end, wallAnchor, monoAnchor, sensorDetails, sensorSnapshot.size, rate)
                main.post {
                    sampleCount = sensorSnapshot.size
                    observedRateHz = rate
                    savedFolder = folder
                    status = if (reason == "video_completed")
                        "정상 종료 · 저장 완료: Lux ${sensorSnapshot.size}개 / 영상 로그 ${markSnapshot.size}개"
                    else "영상 종료 사유: $reason · 데이터 저장 완료 (Lux ${sensorSnapshot.size}개)"
                    lastResultStatus = status
                    phase = RunPhase.IDLE
                    loadSelectedVideo() // 다음 실험을 위해 처음부터 준비
                }
            } catch (e: Exception) {
                main.post {
                    status = "데이터 저장 실패: ${e.message}"
                    lastResultStatus = status
                    phase = RunPhase.IDLE
                    loadSelectedVideo()
                }
            }
        }.start()
    }

    fun handleLeavingScreen() {
        if (phase == RunPhase.COUNTDOWN) cancelCountdown()
        if (phase == RunPhase.RUNNING) finish("activity_stopped")
    }

    private fun calculateRate(data: List<LuxSample>): Double? {
        if (data.size < 2) return null
        val sorted = data.sortedBy { it.sensorNs }
        val span = sorted.last().sensorNs - sorted.first().sensorNs
        return if (span > 0) (sorted.size - 1) * 1e9 / span else null
    }

    private fun relativeMs(time: Long, anchor: Long?): String {
        if (anchor == null || anchor <= 0L) return ""
        return String.format(Locale.US, "%.3f", (time - anchor) / 1e6)
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.3f", value)

    private fun writePatternSchedule(file: File, mode: PatternMode) {
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("pattern_id,segment_type,start_ms,end_ms,start_frame,end_frame_exclusive")
            var f = 0
            for (i in 1..PATTERN_COUNT) {
                val pStart = f
                f += mode.patternFrames
                out.appendLine("$i,PATTERN,${fmt(pStart * 1000.0 / mode.fps)},${fmt(f * 1000.0 / mode.fps)},$pStart,$f")
                val bStart = f
                f += mode.blackFrames
                out.appendLine("$i,BLACK,${fmt(bStart * 1000.0 / mode.fps)},${fmt(f * 1000.0 / mode.fps)},$bStart,$f")
            }
        }
    }

    private fun writeVideoMarks(file: File, data: List<VideoMark>,
                                beginNs: Long, firstFrame: Long?) {
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("event,elapsed_realtime_ns,relative_to_als_start_ms,relative_to_first_frame_info_ms,video_position_ms,query_half_duration_ns")
            data.forEach { m ->
                out.appendLine("${m.type},${m.timestampNs},${relativeMs(m.timestampNs, beginNs)}," +
                        "${relativeMs(m.timestampNs, firstFrame)},${m.videoPositionMs},${m.queryHalfDurationNs}")
            }
        }
    }

    // VIDEO_POSITION 이벤트 사이에서 시각 기반 선형 보간.
    // MediaPlayer.currentPosition은 광학적/프레임 정확도의 시간 측정값이 아니므로 근사치로 표기.
    private fun positionAt(t: Long, positions: List<VideoMark>): Double? {
        if (positions.size < 2 || t < positions.first().timestampNs || t > positions.last().timestampNs)
            return null
        var lo = 0
        var hi = positions.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (positions[mid].timestampNs <= t) lo = mid else hi = mid
        }
        val a = positions[lo]
        val b = positions[hi]
        val dt = b.timestampNs - a.timestampNs
        if (dt <= 0L) return null
        val fraction = (t - a.timestampNs).toDouble() / dt
        return a.videoPositionMs + (b.videoPositionMs - a.videoPositionMs) * fraction
    }

    private fun assignPattern(positionMs: Double?, mode: PatternMode): PatternAssignment? {
        if (positionMs == null || positionMs < 0.0 || positionMs >= mode.durationMs) return null
        val zeroBased = (positionMs / mode.cycleMs).toInt().coerceIn(0, PATTERN_COUNT - 1)
        val within = positionMs - zeroBased * mode.cycleMs
        val kind = if (within < mode.patternDurationMs) "PATTERN" else "BLACK"
        val nearest = min(min(within, kotlin.math.abs(within - mode.patternDurationMs)),
            mode.cycleMs - within)
        return PatternAssignment(zeroBased + 1, kind, nearest,
            if (nearest <= BOUNDARY_MARGIN_MS) "NEAR_TRANSITION" else "AWAY_FROM_TRANSITION")
    }

    private fun writeLuxSamples(file: File, data: List<LuxSample>,
                                events: List<VideoMark>, mode: PatternMode, beginNs: Long,
                                playNs: Long, firstFrame: Long?) {
        val positions = events.filter { it.type == "VIDEO_POSITION" && it.videoPositionMs >= 0 }
        val sortedSamples = data.sortedBy { it.sensorNs }
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("sensor_timestamp_ns,callback_timestamp_ns,delivery_delay_ms,from_als_start_ms,from_video_play_call_ms,from_first_frame_info_ms,estimated_video_position_ms,pattern_id,segment_type,ms_to_nearest_transition,transition_flag,lux,accuracy")
            sortedSamples.forEach { s ->
                // 첫 프레임 정보 콜백 이전 데이터는 P01로 강제 라벨링하지 않습니다.
                val pos = if (firstFrame != null && s.sensorNs < firstFrame) null
                else positionAt(s.sensorNs, positions)
                val assigned = if (scheduleEnabled) assignPattern(pos, mode) else null
                out.appendLine(
                    "${s.sensorNs},${s.callbackNs},${relativeMs(s.callbackNs, s.sensorNs)}," +
                            "${relativeMs(s.sensorNs, beginNs)},${relativeMs(s.sensorNs, playNs)}," +
                            "${relativeMs(s.sensorNs, firstFrame)},${pos?.let { fmt(it) } ?: ""}," +
                            "${assigned?.patternId ?: ""},${assigned?.kind ?: ""}," +
                            "${assigned?.distanceToBoundaryMs?.let { fmt(it) } ?: ""}," +
                            "${assigned?.flag ?: "NO_POSITION_ESTIMATE"},${s.lux},${s.accuracy}"
                )
            }
        }
    }

    private fun writeRunInfo(file: File, mode: PatternMode, runId: String,
                             stop: String, button: Long, target: Long, start: Long,
                             beforePlay: Long, afterPlay: Long, firstFrame: Long?, end: Long,
                             wallAnchor: Long, monoAnchor: Long, light: Sensor?, count: Int, rate: Double?) {
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        dateFormatter.timeZone = TimeZone.getTimeZone("UTC")
        file.writeText("""run_id=$runId
mode=${mode.name}
video_file=see_selected_video.txt
texture_frame_callbacks=$runFrameCount
physical_light_onset=NOT_MEASURED
rgb_channels=NOT_AVAILABLE_FROM_STANDARD_TYPE_LIGHT
pattern_schedule=USER_ASSUMED_NOT_VERIFIED_AGAINST_VIDEO
device=${Build.MANUFACTURER} ${Build.MODEL}
android_sdk=${Build.VERSION.SDK_INT}
sensor_name=${light?.name ?: "NONE"}
sensor_reporting_mode=${light?.reportingMode ?: "UNKNOWN"}
sensor_min_delay_us=${light?.minDelay ?: "UNKNOWN"}
sensor_resolution_lux=${light?.resolution ?: "UNKNOWN"}
requested_sample_period_us=$SENSOR_PERIOD_US
requested_event_rate_hz=100
observed_event_rate_hz=${rate?.let { fmt(it) } ?: "UNAVAILABLE"}
received_sensor_events=$count
start_delay_requested_ms=$START_DELAY_MS
start_button_ns=$button
target_start_ns=$target
actual_sensor_log_start_ns=$start
actual_start_lateness_ms=${fmt((start - target) / 1e6)}
video_start_call_before_ns=$beforePlay
video_start_call_after_ns=$afterPlay
sensor_to_play_command_ms=${fmt((beforePlay - start) / 1e6)}
first_frame_info_callback_ns=${firstFrame ?: "UNAVAILABLE"}
play_command_to_first_frame_info_ms=${firstFrame?.let { fmt((it - beforePlay) / 1e6) } ?: "UNAVAILABLE"}
run_end_ns=$end
stop_reason=$stop
wall_clock_anchor_utc=${dateFormatter.format(Date(wallAnchor))}
wall_clock_anchor_epoch_ms=$wallAnchor
monotonic_anchor_ns=$monoAnchor
video_fps=${mode.fps}
video_expected_duration_ms=${fmt(mode.durationMs)}
pattern_count=$PATTERN_COUNT
pattern_frames=${mode.patternFrames}
black_frames=${mode.blackFrames}
pattern_duration_ms=${fmt(mode.patternDurationMs)}
black_duration_ms=${fmt(mode.blackDurationMs)}
video_position_poll_target_ms=$VIDEO_POLL_MS
boundary_flag_margin_ms=$BOUNDARY_MARGIN_MS
notes=All elapsed_realtime_ns timestamps share elapsedRealtimeNanos clock domain. Sensor timestamp is event sample time, callback timestamp is app receipt time. Countdown target and actual start are both recorded; 5s is a scheduling target, not an exact hardware guarantee. VIDEO_FIRST_FRAME_INFO reports that the media player submitted a frame for rendering, NOT light emission on physical LCD. VIDEO_POSITION is polled, NOT frame accurate. estimated_video_position_ms and per-pattern labels are interpolated approximations. NEAR_TRANSITION is a caution flag, not a calibrated confidence interval. Sensor response latency / actual display light onset require optical calibration. Sensor 100Hz is only a requested sampling period, not a guaranteed output rate.
""".trimIndent() + "\n", Charsets.UTF_8)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ALSExperimentScreen() } }
    }
}

@Composable
private fun ALSExperimentScreen() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val manager = remember { AlsRunManager(context.applicationContext) }
    var experimentName by remember { mutableStateOf("Tablet_01") }
    var exportMessage by remember { mutableStateOf("") }

    DisposableEffect(manager, activity) {
        manager.startSensor()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) manager.startSensor()
            if (event == Lifecycle.Event.ON_STOP) {
                manager.handleLeavingScreen()
                manager.stopSensor()
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            manager.dispose()
            activity.lifecycle.removeObserver(observer)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            val name = context.contentResolver.query(uri,
                arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "선택한 영상"
            manager.selectVideo(uri, name)
        }
    }

    val fullscreen = manager.phase == RunPhase.COUNTDOWN || manager.phase == RunPhase.RUNNING
    DisposableEffect(activity, fullscreen) {
        val previous = activity.window.decorView.systemUiVisibility
        val previousBrightness = activity.window.attributes.screenBrightness
        if (fullscreen) {
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = 1f }
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        onDispose {
            if (fullscreen) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = previousBrightness }
            activity.window.decorView.systemUiVisibility = previous
        }
    }

    BackHandler(enabled = fullscreen) {
        if (manager.phase == RunPhase.COUNTDOWN) manager.cancelCountdown()
        if (manager.phase == RunPhase.RUNNING) manager.finish("manual_back")
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { target ->
        val folder = manager.savedFolder
        if (target != null && folder != null) {
            try {
                val output = context.contentResolver.openOutputStream(target)
                    ?: throw IOException("출력 파일을 열 수 없습니다")
                output.use { fileStream ->
                    ZipOutputStream(fileStream).use { zip ->
                        folder.listFiles()?.filter { it.isFile }?.forEach { f ->
                            zip.putNextEntry(ZipEntry(f.name))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                exportMessage = "ZIP 내보내기 완료"
            } catch (e: Exception) {
                exportMessage = "내보내기 실패: ${e.message}"
            }
        }
    }

    // TextureView는 SurfaceView 기반 VideoView와 달리 Compose 오버레이와 정상 합성됩니다.
    // 카운트다운 중에는 검정색 Compose 레이어가 가려서 측정 전 화면이 노출되지 않습니다.
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> TextureView(ctx).apply { manager.bindVideo(this) } }
        )

        if (manager.phase == RunPhase.IDLE || manager.phase == RunPhase.SAVING) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("ALS Experiment", fontSize = 27.sp,
                    color = MaterialTheme.colorScheme.primary)
                Text("영상·센서 동기화 | 시작 5초 지연")
                HorizontalDivider()
                OutlinedTextField(
                    value = experimentName, onValueChange = { experimentName = it },
                    label = { Text("실험 번호") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = manager.phase == RunPhase.IDLE
                )
                Text(manager.selectedVideoName)
                Button(enabled = manager.phase == RunPhase.IDLE,
                    onClick = { picker.launch(arrayOf("video/*")) }) { Text("기기에서 영상 선택") }
                Text("아래 시간표는 선택 영상과 자동 검증되지 않습니다. 일치하는 경우에만 패턴 라벨을 사용하세요.", fontSize = 12.sp)
                Text("분석용 패턴 시간표", fontSize = 19.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = manager.scheduleEnabled,
                        enabled = manager.phase == RunPhase.IDLE,
                        onCheckedChange = { manager.scheduleEnabled = it })
                    Text("선택 영상과 아래 시간표가 일치함 (패턴 라벨 활성화)")
                }
                PatternMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = manager.selectedMode == mode,
                            enabled = manager.phase == RunPhase.IDLE,
                            onClick = { manager.selectMode(mode) }
                        )
                        Column {
                            Text(mode.title)
                            Text("${fmtDuration(mode.durationMs)} / 30개 패턴", fontSize = 12.sp)
                        }
                    }
                }
                HorizontalDivider()
                Text("센서: ${manager.sensor?.name ?: "TYPE_LIGHT 없음"}")
                Text("RGB는 표준 API로 제공되지 않습니다. 제조사 센서를 선택하면 채널 의미를 해석하지 않고 원시값을 저장합니다.", fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = manager.rawSensor == null,
                        enabled = manager.phase == RunPhase.IDLE,
                        onClick = { manager.rawSensor = null })
                    Text("제조사 원시 센서 사용 안 함")
                }
                manager.rawCapture.candidates.forEach { sensor ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = manager.rawSensor == sensor,
                            enabled = manager.phase == RunPhase.IDLE,
                            onClick = { manager.rawSensor = sensor })
                        Text("${sensor.name} (type ${sensor.type})", fontSize = 12.sp)
                    }
                }
                Text("현재 조도: ${manager.currentLux?.let { String.format(Locale.US, "%.2f", it) } ?: "--"} lux",
                    fontSize = 23.sp, color = MaterialTheme.colorScheme.primary)
                Text("요청: 100Hz / 영상 위치 폴링: 약 20ms", fontSize = 12.sp)
                Button(
                    enabled = manager.videoReady && manager.sensorActive && manager.phase == RunPhase.IDLE,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { manager.arm(experimentName) }
                ) { Text("실험 시작 (5초 후 자동 실행)") }
                Text("카운트다운 중 뒤로 가기: 취소 / 재생 중 뒤로 가기: 종료 및 저장", fontSize = 12.sp)
                Text("상태: ${manager.status}")
                if (manager.lastResultStatus.isNotBlank()) Text(manager.lastResultStatus)
                manager.observedRateHz?.let {
                    Text("최근 실험 센서 이벤트 평균: ${String.format(Locale.US, "%.2f", it)} Hz")
                }
                manager.savedFolder?.let { folder ->
                    Text("저장 경로: ${folder.absolutePath}", fontSize = 11.sp)
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { exporter.launch("${folder.name}.zip") }
                    ) { Text("측정 결과 CSV/시간표 ZIP으로 내보내기") }
                }
                if (exportMessage.isNotBlank()) Text(exportMessage)
            }
        }

        if (manager.phase == RunPhase.COUNTDOWN) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("실험 시작까지", color = Color.White, fontSize = 21.sp)
                    Text("${manager.countdownNumber}", color = Color.White, fontSize = 82.sp)
                    Text("카운트다운 동안 데이터는 저장하지 않습니다.",
                        color = Color.LightGray, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun fmtDuration(ms: Double): String = if (ms >= 60_000.0) {
    "${(ms / 60_000).toInt()}분 ${(ms.toInt() % 60_000) / 1000}초"
} else "${(ms / 1000.0).toInt()}초"
