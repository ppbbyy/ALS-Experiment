package com.example.alsexperiment

// ALS Experiment MainActivity v3
// v3: 실제 재생 첫 프레임 신호 분리 기록, 종료 구간 최종 필터링,
//     영상 위치 폴링 공백이 큰 구간의 패턴 추정 차단.

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
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

private const val SENSOR_PERIOD_US = 10_000
private const val START_DELAY_MS = 5_000L
private const val VIDEO_POLL_MS = 20L
private const val BOUNDARY_MARGIN_MS = 50.0
private const val PATTERN_COUNT = 30
private const val PREWARM_TIMEOUT_MS = 15_000L
private const val MAX_POSITION_INTERPOLATION_GAP_MS = 100.0

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

    val resourceId: Int
        get() = when (this) {
            PAPER -> R.raw.als_paper_2hz
            LONG -> R.raw.als_3s_3s
        }

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
 * ALS 센서 이벤트 시각과 앱의 영상 관련 로그를 elapsedRealtimeNanos() 기준으로 기록한다.
 *
 * 주의:
 * - 100Hz는 센서에 요청하는 주기이며 실제 출력 속도를 보장하지 않는다.
 * - MediaPlayer의 첫 프레임 콜백은 LCD의 실제 광학 발광 시각이 아니다.
 * - 영상 위치와 패턴 라벨은 MediaPlayer.currentPosition 폴링을 이용한 근사치다.
 */
private class AlsRunManager(private val context: Context) : SensorEventListener {
    private val main = Handler(Looper.getMainLooper())
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private var sensorThread: HandlerThread? = null
    private val samplesLock = Any()
    private val samples = ArrayList<LuxSample>()

    private var gateStartNs = Long.MAX_VALUE
    private var gateEndNs = Long.MAX_VALUE
    private var lastLuxUpdateNs = 0L

    // marks는 메인 스레드에서만 접근한다.
    private val marks = ArrayList<VideoMark>()

    private var textureView: TextureView? = null
    private var displaySurface: Surface? = null
    private var mediaPlayer: MediaPlayer? = null

    private var modeUsed = PatternMode.PAPER
    private var experimentId = ""

    private var wallAnchorEpochMs = 0L
    private var monoAnchorNs = 0L
    private var buttonNs = 0L
    private var targetStartNs = 0L
    private var actualStartNs = 0L
    private var playCommandBeforeNs = 0L
    private var playCommandAfterNs = 0L
    private var runEndNs = 0L
    private var playbackMoved = false

    // 사전 렌더링 정보와 실제 실험 재생 정보는 구분해서 기록한다.
    private var prewarmStarted = false
    private var prewarmReady = false
    private var prewarmSeekPending = false
    private var prewarmFrameNs: Long? = null
    private var actualFirstFrameNs: Long? = null
    private var actualFirstFrameSource: String = "UNAVAILABLE"

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

            val remainNs =
                (targetStartNs - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(0L)

            countdownNumber =
                ceil(remainNs / 1_000_000_000.0).toInt().coerceIn(0, 5)

            if (remainNs > 0L) {
                main.postDelayed(this, 50L)
            }
        }
    }

    private val startRunnable = Runnable { startNow() }

    private val progressTick = object : Runnable {
        override fun run() {
            if (phase != RunPhase.RUNNING) return

            val player = mediaPlayer ?: return

            val before = SystemClock.elapsedRealtimeNanos()
            val position = try {
                player.currentPosition
            } catch (_: Exception) {
                -1
            }
            val after = SystemClock.elapsedRealtimeNanos()

            val midpoint = before + (after - before) / 2L

            marks.add(
                VideoMark(
                    "VIDEO_POSITION",
                    midpoint,
                    position,
                    (after - before) / 2L
                )
            )

            if (position >= 100) playbackMoved = true

            if (
                !playbackMoved &&
                playCommandBeforeNs > 0L &&
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
            this,
            light,
            SENSOR_PERIOD_US,
            0,
            Handler(thread.looper)
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
        if (sensorActive) {
            sensorManager.unregisterListener(this)
        }

        sensorActive = false
        sensorThread?.quitSafely()
        sensorThread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT) return

        val receivedNs = SystemClock.elapsedRealtimeNanos()
        val lux = event.values[0]

        synchronized(samplesLock) {
            // 센서 이벤트 자체의 timestamp를 기준으로 수집 구간을 제한한다.
            if (event.timestamp >= gateStartNs && event.timestamp < gateEndNs) {
                samples.add(
                    LuxSample(
                        event.timestamp,
                        receivedNs,
                        lux,
                        event.accuracy
                    )
                )
            }
        }

        // UI에는 0.25초 단위로 최신 값만 전달한다.
        if (receivedNs - lastLuxUpdateNs >= 250_000_000L) {
            lastLuxUpdateNs = receivedNs
            main.post { currentLux = lux }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun bindVideo(texture: TextureView) {
        textureView = texture

        texture.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {

                override fun onSurfaceTextureAvailable(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    displaySurface?.release()
                    displaySurface = Surface(surfaceTexture)

                    if (phase == RunPhase.IDLE) {
                        loadSelectedVideo()
                    }
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    mediaPlayer?.let {
                        applyAspectFit(texture, it.videoWidth, it.videoHeight)
                    }
                }

                override fun onSurfaceTextureDestroyed(
                    surfaceTexture: SurfaceTexture
                ): Boolean {
                    if (phase == RunPhase.COUNTDOWN) {
                        cancelCountdown()
                    }

                    if (phase == RunPhase.RUNNING) {
                        finish("video_surface_destroyed")
                    }

                    releaseMediaPlayer()
                    displaySurface?.release()
                    displaySurface = null
                    videoReady = false

                    return true
                }

                override fun onSurfaceTextureUpdated(
                    surfaceTexture: SurfaceTexture
                ) {
                    // 실험 재생 시작 이후 TextureView에 첫 프레임 업데이트가 전달된 시각.
                    // 이 콜백도 패널의 실제 광학 발광 시각을 측정하는 것은 아니다.
                    if (phase == RunPhase.RUNNING && actualFirstFrameNs == null) {
                        markActualFirstFrame("TEXTURE_FIRST_FRAME_UPDATE")
                    }
                }
            }

        if (texture.isAvailable) {
            texture.surfaceTexture?.let { available ->
                displaySurface?.release()
                displaySurface = Surface(available)

                if (phase == RunPhase.IDLE) {
                    loadSelectedVideo()
                }
            }
        }
    }

    private fun releaseMediaPlayer() {
        val old = mediaPlayer
        mediaPlayer = null
        videoReady = false

        if (old != null) {
            try { old.setOnPreparedListener(null) } catch (_: Exception) {}
            try { old.setOnCompletionListener(null) } catch (_: Exception) {}
            try { old.setOnErrorListener(null) } catch (_: Exception) {}
            try { old.setOnInfoListener(null) } catch (_: Exception) {}
            try { old.setOnSeekCompleteListener(null) } catch (_: Exception) {}
            try { old.release() } catch (_: Exception) {}
        }
    }

    private fun applyAspectFit(
        texture: TextureView,
        videoWidth: Int,
        videoHeight: Int
    ) {
        if (
            videoWidth <= 0 ||
            videoHeight <= 0 ||
            texture.width <= 0 ||
            texture.height <= 0
        ) return

        val surfaceWidth = texture.width.toFloat()
        val surfaceHeight = texture.height.toFloat()
        val videoAspect = videoWidth.toFloat() / videoHeight
        val viewAspect = surfaceWidth / surfaceHeight

        val scaleX =
            if (videoAspect < viewAspect) videoAspect / viewAspect else 1f

        val scaleY =
            if (videoAspect > viewAspect) viewAspect / videoAspect else 1f

        val matrix = Matrix().apply {
            setScale(
                scaleX,
                scaleY,
                surfaceWidth / 2f,
                surfaceHeight / 2f
            )
        }

        texture.setTransform(matrix)
    }

    fun selectMode(mode: PatternMode) {
        if (phase != RunPhase.IDLE) return
        if (selectedMode == mode) return

        selectedMode = mode
        loadSelectedVideo()
    }

    private fun loadSelectedVideo() {
        if (phase != RunPhase.IDLE) return

        val surface = displaySurface ?: run {
            status = "영상 표시 화면 준비 중"
            return
        }

        releaseMediaPlayer()
        status = "${selectedMode.title} 영상 준비 중"

        try {
            val videoUri = Uri.parse(
                "android.resource://${context.packageName}/${selectedMode.resourceId}"
            )

            val player = MediaPlayer()
            mediaPlayer = player

            player.setOnPreparedListener { prepared ->
                if (prepared === mediaPlayer) {
                    videoReady = true

                    applyAspectFit(
                        textureView ?: return@setOnPreparedListener,
                        prepared.videoWidth,
                        prepared.videoHeight
                    )

                    if (phase == RunPhase.IDLE) {
                        status = "영상 준비 완료 (화면 출력 준비 완료)"
                    }
                }
            }

            player.setOnVideoSizeChangedListener { changed, width, height ->
                if (changed === mediaPlayer) {
                    textureView?.let {
                        applyAspectFit(it, width, height)
                    }
                }
            }

            player.setOnInfoListener { infoPlayer, what, extra ->
                if (infoPlayer === mediaPlayer) {

                    // 카운트다운 중 첫 프레임을 사전 렌더링한다.
                    if (
                        what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START &&
                        phase == RunPhase.COUNTDOWN &&
                        prewarmStarted &&
                        !prewarmReady &&
                        !prewarmSeekPending
                    ) {
                        val now = SystemClock.elapsedRealtimeNanos()

                        prewarmFrameNs = now
                        addMark("PREWARM_FIRST_FRAME_INFO", now)

                        prewarmSeekPending = true

                        try {
                            infoPlayer.pause()
                            infoPlayer.seekTo(0)

                            status =
                                "첫 프레임 사전 렌더링 완료 · 영상 위치 초기화 중"
                        } catch (e: Exception) {
                            status =
                                "영상 사전 렌더링 실패: ${e.message}"
                            cancelCountdown()
                        }
                    }

                    // 실제 실험 재생 중 MediaPlayer가 렌더링 시작을 알리면 기록한다.
                    // TextureView 업데이트 콜백과 경쟁하므로 먼저 도착한 신호를 채택한다.
                    if (
                        what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START &&
                        phase == RunPhase.RUNNING &&
                        actualFirstFrameNs == null
                    ) {
                        markActualFirstFrame("MEDIA_PLAYER_RENDERING_START")
                    }

                    if (
                        what == MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING
                    ) {
                        addMark(
                            "VIDEO_TRACK_LAGGING",
                            SystemClock.elapsedRealtimeNanos()
                        )
                    }
                }

                false
            }

            player.setOnSeekCompleteListener { seekPlayer ->
                if (
                    seekPlayer === mediaPlayer &&
                    phase == RunPhase.COUNTDOWN &&
                    prewarmSeekPending
                ) {
                    prewarmSeekPending = false
                    prewarmReady = true

                    addMark(
                        "PREWARM_SEEK_COMPLETE",
                        SystemClock.elapsedRealtimeNanos()
                    )

                    status = "영상 사전 준비 완료 · 실험 시작 대기"
                }
            }

            player.setOnCompletionListener { completed ->
                if (
                    completed === mediaPlayer &&
                    phase == RunPhase.RUNNING
                ) {
                    finish("video_completed")
                }
            }

            player.setOnErrorListener { errored, what, extra ->
                if (errored === mediaPlayer) {
                    addMark(
                        "VIDEO_ERROR_${what}_${extra}",
                        SystemClock.elapsedRealtimeNanos()
                    )

                    status = "영상 오류 (MediaPlayer): $what / $extra"

                    if (phase == RunPhase.RUNNING) {
                        finish("video_error_${what}_${extra}")
                    } else if (phase == RunPhase.COUNTDOWN) {
                        cancelCountdown()
                    }
                }

                true
            }

            player.setSurface(surface)
            player.setVolume(0f, 0f)
            player.setDataSource(context, videoUri)
            player.prepareAsync()

        } catch (e: Exception) {
            releaseMediaPlayer()
            status =
                "영상 불러오기 실패: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun arm(experimentName: String) {
        val player = mediaPlayer

        if (
            phase != RunPhase.IDLE ||
            !videoReady ||
            !sensorActive ||
            player == null
        ) {
            status = "영상 준비와 조도 센서 연결 상태를 확인해 주세요."
            return
        }

        modeUsed = selectedMode

        val safe = experimentName.trim()
            .ifBlank { "Tablet_01" }
            .replace(Regex("[^A-Za-z0-9가-힣_-]"), "_")
            .take(40)

        experimentId =
            safe + "_" +
            SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.US
            ).format(Date())

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

        prewarmStarted = false
        prewarmReady = false
        prewarmSeekPending = false
        prewarmFrameNs = null
        actualFirstFrameNs = null
        actualFirstFrameSource = "UNAVAILABLE"

        actualStartNs = 0L
        playCommandBeforeNs = 0L
        playCommandAfterNs = 0L
        runEndNs = 0L
        playbackMoved = false

        wallAnchorEpochMs = System.currentTimeMillis()
        monoAnchorNs = SystemClock.elapsedRealtimeNanos()
        buttonNs = SystemClock.elapsedRealtimeNanos()
        targetStartNs =
            buttonNs + START_DELAY_MS * 1_000_000L

        marks.add(
            VideoMark("START_BUTTON_PRESSED", buttonNs, 0)
        )

        marks.add(
            VideoMark("TARGET_START", targetStartNs, 0)
        )

        countdownNumber = 5
        phase = RunPhase.COUNTDOWN
        status = "5초 카운트다운 및 영상 사전 준비 중"

        // 카운트다운 중 영상 첫 프레임을 미리 렌더링한다.
        try {
            prewarmStarted = true

            addMark(
                "PREWARM_START",
                SystemClock.elapsedRealtimeNanos()
            )

            player.start()

        } catch (e: Exception) {
            cancelCountdown()
            status = "영상 사전 준비 실패: ${e.message}"
            return
        }

        main.post(countdownTick)

        val remainingMs =
            (
                (targetStartNs - SystemClock.elapsedRealtimeNanos()) /
                1_000_000L
            ).coerceAtLeast(0L)

        main.postDelayed(startRunnable, remainingMs)
    }

    fun cancelCountdown() {
        if (phase != RunPhase.COUNTDOWN) return

        main.removeCallbacks(startRunnable)
        main.removeCallbacks(countdownTick)

        try {
            mediaPlayer?.pause()
            mediaPlayer?.seekTo(0)
        } catch (_: Exception) {
        }

        synchronized(samplesLock) {
            gateStartNs = Long.MAX_VALUE
            gateEndNs = Long.MAX_VALUE
        }

        prewarmStarted = false
        prewarmReady = false
        prewarmSeekPending = false

        phase = RunPhase.IDLE
        status = "카운트다운 취소 (데이터 수집 안 함)"
    }

    private fun startNow() {
        if (phase != RunPhase.COUNTDOWN) return

        val player = mediaPlayer

        if (
            player == null ||
            !videoReady ||
            !sensorActive
        ) {
            cancelCountdown()
            status = "실험 시작 실패: 영상 또는 센서 준비 오류"
            return
        }

        // 5초가 지났더라도 사전 렌더링/seek가 끝나지 않았다면 기다린다.
        if (!prewarmReady) {
            val waitedMs =
                (SystemClock.elapsedRealtimeNanos() - buttonNs) / 1_000_000L

            if (waitedMs >= PREWARM_TIMEOUT_MS) {
                cancelCountdown()
                status = "실험 시작 실패: 영상 사전 준비 시간 초과"
                return
            }

            status = "카운트다운 완료 · 영상 사전 준비 대기 중"
            main.postDelayed(startRunnable, 20L)
            return
        }

        main.removeCallbacks(countdownTick)

        // 실제 재생 명령 직전의 시각을 ALS 기록 시작 기준으로 사용한다.
        actualStartNs = SystemClock.elapsedRealtimeNanos()

        synchronized(samplesLock) {
            gateStartNs = actualStartNs
            gateEndNs = Long.MAX_VALUE
        }

        marks.add(
            VideoMark("ALS_LOG_START", actualStartNs, -1)
        )

        phase = RunPhase.RUNNING
        status = "영상 재생 및 ALS 기록 진행 중"

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

    private fun markActualFirstFrame(source: String) {
        if (actualFirstFrameNs != null || phase != RunPhase.RUNNING) return
        val now = SystemClock.elapsedRealtimeNanos()
        actualFirstFrameNs = now
        actualFirstFrameSource = source
        addMark(source, now)
    }

    private fun addMark(type: String, timestamp: Long) {
        val position = try {
            mediaPlayer?.currentPosition ?: -1
        } catch (_: Exception) {
            -1
        }

        marks.add(
            VideoMark(type, timestamp, position)
        )
    }

    fun finish(reason: String) {
        when (phase) {
            RunPhase.COUNTDOWN -> {
                cancelCountdown()
                return
            }
            RunPhase.RUNNING -> Unit
            else -> return
        }

        // 종료 기준 시각을 먼저 고정한다.
        runEndNs = SystemClock.elapsedRealtimeNanos()

        synchronized(samplesLock) {
            gateEndNs = runEndNs
        }

        addMark("RUN_END_$reason", runEndNs)

        main.removeCallbacks(progressTick)
        main.removeCallbacks(startRunnable)
        main.removeCallbacks(countdownTick)

        phase = RunPhase.SAVING
        status = "CSV 저장 중..."

        try {
            mediaPlayer?.pause()
        } catch (_: Exception) {
        }

        // 종료 시각 기준으로 저장 직전에 다시 필터링한다.
        // sensorNs == runEndNs인 샘플도 제외한다.
        val sensorSnapshot = synchronized(samplesLock) {
            samples.filter { sample ->
                sample.sensorNs >= actualStartNs &&
                sample.sensorNs < runEndNs
            }.sortedBy { it.sensorNs }
        }

        val markSnapshot = marks.toList()
        val runMode = modeUsed
        val outputName = experimentId

        val button = buttonNs
        val target = targetStartNs
        val start = actualStartNs
        val beforePlay = playCommandBeforeNs
        val afterPlay = playCommandAfterNs
        val prewarmFrame = prewarmFrameNs
        val actualFirstFrame = actualFirstFrameNs
        val firstFrameSource = actualFirstFrameSource
        val end = runEndNs

        val wallAnchor = wallAnchorEpochMs
        val monoAnchor = monoAnchorNs
        val sensorDetails = sensor

        Thread {
            try {
                val folder = File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    "ALSRecords/$outputName"
                )

                if (!folder.exists() && !folder.mkdirs()) {
                    throw IOException("저장 폴더 생성 실패")
                }

                writePatternSchedule(
                    File(folder, "pattern_schedule.csv"),
                    runMode
                )

                writeVideoMarks(
                    File(folder, "video_timeline.csv"),
                    markSnapshot,
                    start,
                    actualFirstFrame
                )

                writeLuxSamples(
                    File(folder, "als_lux.csv"),
                    sensorSnapshot,
                    markSnapshot,
                    runMode,
                    start,
                    beforePlay,
                    actualFirstFrame
                )

                val rate = calculateRate(sensorSnapshot)

                writeRunInfo(
                    File(folder, "run_info.txt"),
                    runMode,
                    outputName,
                    reason,
                    button,
                    target,
                    start,
                    beforePlay,
                    afterPlay,
                    prewarmFrame,
                    actualFirstFrame,
                    firstFrameSource,
                    end,
                    wallAnchor,
                    monoAnchor,
                    sensorDetails,
                    sensorSnapshot.size,
                    rate
                )

                main.post {
                    sampleCount = sensorSnapshot.size
                    observedRateHz = rate
                    savedFolder = folder

                    status =
                        if (reason == "video_completed") {
                            "정상 종료 · 저장 완료: Lux ${sensorSnapshot.size}개 / 영상 로그 ${markSnapshot.size}개"
                        } else {
                            "영상 종료 사유: $reason · 데이터 저장 완료 (Lux ${sensorSnapshot.size}개)"
                        }

                    lastResultStatus = status
                    phase = RunPhase.IDLE

                    prewarmStarted = false
                    prewarmReady = false
                    prewarmSeekPending = false

                    loadSelectedVideo()
                }

            } catch (e: Exception) {
                main.post {
                    status = "데이터 저장 실패: ${e.message}"
                    lastResultStatus = status
                    phase = RunPhase.IDLE

                    prewarmStarted = false
                    prewarmReady = false
                    prewarmSeekPending = false

                    loadSelectedVideo()
                }
            }
        }.start()
    }

    fun handleLeavingScreen() {
        if (phase == RunPhase.COUNTDOWN) {
            cancelCountdown()
        }

        if (phase == RunPhase.RUNNING) {
            finish("activity_stopped")
        }
    }

    private fun calculateRate(data: List<LuxSample>): Double? {
        if (data.size < 2) return null

        val sorted = data.sortedBy { it.sensorNs }
        val span = sorted.last().sensorNs - sorted.first().sensorNs

        return if (span > 0) {
            (sorted.size - 1) * 1e9 / span
        } else {
            null
        }
    }

    private fun relativeMs(time: Long, anchor: Long?): String {
        if (anchor == null || anchor <= 0L) return ""

        return String.format(
            Locale.US,
            "%.3f",
            (time - anchor) / 1e6
        )
    }

    private fun fmt(value: Double): String =
        String.format(Locale.US, "%.3f", value)

    private fun writePatternSchedule(file: File, mode: PatternMode) {
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine(
                "pattern_id,segment_type,start_ms,end_ms,start_frame,end_frame_exclusive"
            )

            var frame = 0

            for (i in 1..PATTERN_COUNT) {
                val patternStart = frame
                frame += mode.patternFrames

                out.appendLine(
                    "$i,PATTERN," +
                    "${fmt(patternStart * 1000.0 / mode.fps)}," +
                    "${fmt(frame * 1000.0 / mode.fps)}," +
                    "$patternStart,$frame"
                )

                val blackStart = frame
                frame += mode.blackFrames

                out.appendLine(
                    "$i,BLACK," +
                    "${fmt(blackStart * 1000.0 / mode.fps)}," +
                    "${fmt(frame * 1000.0 / mode.fps)}," +
                    "$blackStart,$frame"
                )
            }
        }
    }

    private fun writeVideoMarks(
        file: File,
        data: List<VideoMark>,
        beginNs: Long,
        firstFrame: Long?
    ) {
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine(
                "event,elapsed_realtime_ns,relative_to_als_start_ms," +
                "relative_to_actual_first_frame_signal_ms,video_position_ms,query_half_duration_ns"
            )

            data.forEach { mark ->
                out.appendLine(
                    "${mark.type},${mark.timestampNs}," +
                    "${relativeMs(mark.timestampNs, beginNs)}," +
                    "${relativeMs(mark.timestampNs, firstFrame)}," +
                    "${mark.videoPositionMs}," +
                    "${mark.queryHalfDurationNs}"
                )
            }
        }
    }

    // VIDEO_POSITION 이벤트 사이에서 시각 기반 선형 보간.
    // MediaPlayer.currentPosition은 프레임 정확도 측정값이 아니므로 근사치다.
    private fun positionAt(
        timeNs: Long,
        positions: List<VideoMark>
    ): Double? {
        if (
            positions.size < 2 ||
            timeNs < positions.first().timestampNs ||
            timeNs > positions.last().timestampNs
        ) {
            return null
        }

        var low = 0
        var high = positions.lastIndex

        while (high - low > 1) {
            val middle = (low + high) / 2

            if (positions[middle].timestampNs <= timeNs) {
                low = middle
            } else {
                high = middle
            }
        }

        val a = positions[low]
        val b = positions[high]
        val deltaTime = b.timestampNs - a.timestampNs

        if (deltaTime <= 0L) return null

        // 폴링 공백이 너무 큰 구간은 선형 보간으로 패턴을 추정하지 않는다.
        if (deltaTime > (MAX_POSITION_INTERPOLATION_GAP_MS * 1_000_000.0).toLong()) {
            return null
        }

        val fraction =
            (timeNs - a.timestampNs).toDouble() / deltaTime

        return a.videoPositionMs +
            (b.videoPositionMs - a.videoPositionMs) * fraction
    }

    private fun assignPattern(
        positionMs: Double?,
        mode: PatternMode
    ): PatternAssignment? {
        if (
            positionMs == null ||
            positionMs < 0.0 ||
            positionMs >= mode.durationMs
        ) {
            return null
        }

        val zeroBased =
            (positionMs / mode.cycleMs)
                .toInt()
                .coerceIn(0, PATTERN_COUNT - 1)

        val within = positionMs - zeroBased * mode.cycleMs

        val kind =
            if (within < mode.patternDurationMs) "PATTERN" else "BLACK"

        val nearest = min(
            min(
                within,
                kotlin.math.abs(within - mode.patternDurationMs)
            ),
            mode.cycleMs - within
        )

        return PatternAssignment(
            zeroBased + 1,
            kind,
            nearest,
            if (nearest <= BOUNDARY_MARGIN_MS) {
                "NEAR_TRANSITION"
            } else {
                "AWAY_FROM_TRANSITION"
            }
        )
    }

    private fun writeLuxSamples(
        file: File,
        data: List<LuxSample>,
        events: List<VideoMark>,
        mode: PatternMode,
        beginNs: Long,
        playNs: Long,
        firstFrame: Long?
    ) {
        val positions = events.filter {
            it.type == "VIDEO_POSITION" && it.videoPositionMs >= 0
        }

        val sortedSamples = data.sortedBy { it.sensorNs }

        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine(
                "sensor_timestamp_ns,callback_timestamp_ns,delivery_delay_ms," +
                "from_als_start_ms,from_video_play_call_ms,from_actual_first_frame_signal_ms," +
                "estimated_video_position_ms,pattern_id,segment_type," +
                "ms_to_nearest_transition,transition_flag,lux,accuracy"
            )

            sortedSamples.forEach { sample ->
                val position =
                    if (
                        firstFrame == null ||
                        sample.sensorNs < firstFrame
                    ) {
                        null
                    } else {
                        positionAt(sample.sensorNs, positions)
                    }

                val assigned = assignPattern(position, mode)

                out.appendLine(
                    "${sample.sensorNs}," +
                    "${sample.callbackNs}," +
                    "${relativeMs(sample.callbackNs, sample.sensorNs)}," +
                    "${relativeMs(sample.sensorNs, beginNs)}," +
                    "${relativeMs(sample.sensorNs, playNs)}," +
                    "${relativeMs(sample.sensorNs, firstFrame)}," +
                    "${position?.let { fmt(it) } ?: ""}," +
                    "${assigned?.patternId ?: ""}," +
                    "${assigned?.kind ?: ""}," +
                    "${assigned?.distanceToBoundaryMs?.let { fmt(it) } ?: ""}," +
                    "${assigned?.flag ?: "NO_POSITION_ESTIMATE"}," +
                    "${sample.lux}," +
                    "${sample.accuracy}"
                )
            }
        }
    }

    private fun writeRunInfo(
        file: File,
        mode: PatternMode,
        runId: String,
        stop: String,
        button: Long,
        target: Long,
        start: Long,
        beforePlay: Long,
        afterPlay: Long,
        prewarmFrame: Long?,
        actualFirstFrame: Long?,
        firstFrameSource: String,
        end: Long,
        wallAnchor: Long,
        monoAnchor: Long,
        light: Sensor?,
        count: Int,
        rate: Double?
    ) {
        val dateFormatter =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

        dateFormatter.timeZone = TimeZone.getTimeZone("UTC")

        file.writeText(
            """
run_id=$runId
mode=${mode.name}
video_file=${mode.videoFile}
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
prewarm_first_frame_info_ns=${prewarmFrame ?: "UNAVAILABLE"}
prewarm_first_frame_to_start_ms=${prewarmFrame?.let { fmt((start - it) / 1e6) } ?: "UNAVAILABLE"}
actual_first_frame_signal_ns=${actualFirstFrame ?: "UNAVAILABLE"}
actual_first_frame_signal_source=$firstFrameSource
play_command_to_actual_first_frame_signal_ms=${actualFirstFrame?.let { fmt((it - beforePlay) / 1e6) } ?: "UNAVAILABLE"}
start_button_ns=$button
target_start_ns=$target
actual_sensor_log_start_ns=$start
actual_start_lateness_ms=${fmt((start - target) / 1e6)}
video_start_call_before_ns=$beforePlay
video_start_call_after_ns=$afterPlay
sensor_to_play_command_ms=${fmt((beforePlay - start) / 1e6)}
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
max_position_interpolation_gap_ms=$MAX_POSITION_INTERPOLATION_GAP_MS
boundary_flag_margin_ms=$BOUNDARY_MARGIN_MS
notes=All elapsed_realtime_ns timestamps share elapsedRealtimeNanos clock domain. Sensor timestamp is event sample time and callback timestamp is app receipt time. Countdown target is a scheduling target, not an exact hardware guarantee. Video is pre-rendered during countdown, paused, and seeked to position zero before actual playback. The prewarm callback is separate from the actual playback first-frame signal. Actual first-frame signal is the earliest of TextureView frame update and MediaPlayer rendering-start callback; it is not physical LCD light emission. Samples before or without the actual first-frame signal receive no pattern label. VIDEO_POSITION is polled, not frame accurate. Position interpolation is rejected when adjacent polling marks are more than 100ms apart. Estimated video position and pattern labels are approximations. NEAR_TRANSITION is a caution flag, not a calibrated confidence interval. Sensor response latency and actual display light onset require optical calibration. Sensor 100Hz is only a requested sampling period, not a guaranteed output rate. Samples are filtered with actual_start_ns <= sensor_timestamp_ns < run_end_ns immediately before CSV writing.
            """.trimIndent() + "\n",
            Charsets.UTF_8
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                ALSExperimentScreen()
            }
        }
    }
}

@Composable
private fun ALSExperimentScreen() {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    val manager = remember {
        AlsRunManager(context.applicationContext)
    }

    var experimentName by remember {
        mutableStateOf("Tablet_01")
    }

    var exportMessage by remember {
        mutableStateOf("")
    }

    DisposableEffect(manager, activity) {
        manager.startSensor()

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                manager.startSensor()
            }

            if (event == Lifecycle.Event.ON_STOP) {
                manager.handleLeavingScreen()
                manager.stopSensor()
            }
        }

        activity.lifecycle.addObserver(observer)

        onDispose {
            manager.handleLeavingScreen()
            manager.stopSensor()
            activity.lifecycle.removeObserver(observer)
        }
    }

    val fullscreen =
        manager.phase == RunPhase.COUNTDOWN ||
        manager.phase == RunPhase.RUNNING

    DisposableEffect(activity, fullscreen) {
        val previous =
            activity.window.decorView.systemUiVisibility

        if (fullscreen) {
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )

            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        onDispose {
            if (fullscreen) {
                activity.window.clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }

            activity.window.decorView.systemUiVisibility = previous
        }
    }

    BackHandler(enabled = fullscreen) {
        if (manager.phase == RunPhase.COUNTDOWN) {
            manager.cancelCountdown()
        }

        if (manager.phase == RunPhase.RUNNING) {
            manager.finish("manual_back")
        }
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { target ->
        val folder = manager.savedFolder

        if (target != null && folder != null) {
            try {
                val output =
                    context.contentResolver.openOutputStream(target)
                        ?: throw IOException("출력 파일을 열 수 없습니다")

                output.use { fileStream ->
                    ZipOutputStream(fileStream).use { zip ->
                        folder.listFiles()
                            ?.filter { it.isFile }
                            ?.forEach { file ->
                                zip.putNextEntry(ZipEntry(file.name))

                                file.inputStream().use {
                                    it.copyTo(zip)
                                }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    manager.bindVideo(this)
                }
            }
        )

        if (
            manager.phase == RunPhase.IDLE ||
            manager.phase == RunPhase.SAVING
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "ALS Experiment",
                    fontSize = 27.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                Text("영상·센서 동기화 | 시작 5초 지연")

                HorizontalDivider()

                OutlinedTextField(
                    value = experimentName,
                    onValueChange = { experimentName = it },
                    label = { Text("실험 번호") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = manager.phase == RunPhase.IDLE
                )

                Text("실험 영상 선택", fontSize = 19.sp)

                PatternMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = manager.selectedMode == mode,
                            enabled = manager.phase == RunPhase.IDLE,
                            onClick = {
                                manager.selectMode(mode)
                            }
                        )

                        Column {
                            Text(mode.title)

                            Text(
                                "${fmtDuration(mode.durationMs)} / 30개 패턴",
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    "센서: ${manager.sensor?.name ?: "TYPE_LIGHT 없음"}"
                )

                Text(
                    "현재 조도: " +
                    "${manager.currentLux?.let {
                        String.format(Locale.US, "%.2f", it)
                    } ?: "--"} lux",
                    fontSize = 23.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    "요청: 100Hz / 영상 위치 폴링: 약 20ms",
                    fontSize = 12.sp
                )

                Button(
                    enabled =
                        manager.videoReady &&
                        manager.sensorActive &&
                        manager.phase == RunPhase.IDLE,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        manager.arm(experimentName)
                    }
                ) {
                    Text("실험 시작 (5초 후 자동 실행)")
                }

                Text(
                    "카운트다운 중 뒤로 가기: 취소 / " +
                    "재생 중 뒤로 가기: 종료 및 저장",
                    fontSize = 12.sp
                )

                Text("상태: ${manager.status}")

                if (manager.lastResultStatus.isNotBlank()) {
                    Text(manager.lastResultStatus)
                }

                manager.observedRateHz?.let {
                    Text(
                        "최근 실험 센서 이벤트 평균: " +
                        "${String.format(Locale.US, "%.2f", it)} Hz"
                    )
                }

                manager.savedFolder?.let { folder ->
                    Text(
                        "저장 경로: ${folder.absolutePath}",
                        fontSize = 11.sp
                    )

                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            exporter.launch("${folder.name}.zip")
                        }
                    ) {
                        Text("측정 결과 CSV/시간표 ZIP으로 내보내기")
                    }
                }

                if (exportMessage.isNotBlank()) {
                    Text(exportMessage)
                }
            }
        }

        if (manager.phase == RunPhase.COUNTDOWN) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "실험 시작까지",
                        color = Color.White,
                        fontSize = 21.sp
                    )

                    Text(
                        "${manager.countdownNumber}",
                        color = Color.White,
                        fontSize = 82.sp
                    )

                    Text(
                        "카운트다운 동안 영상은 내부적으로 사전 준비됩니다.",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )

                    Text(
                        manager.status,
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

private fun fmtDuration(ms: Double): String =
    if (ms >= 60_000.0) {
        "${(ms / 60_000).toInt()}분 ${(ms.toInt() % 60_000) / 1000}초"
    } else {
        "${(ms / 1000.0).toInt()}초"
    }
