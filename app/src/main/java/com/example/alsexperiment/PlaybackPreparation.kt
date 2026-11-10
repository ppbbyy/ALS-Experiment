package com.example.alsexperiment

/** Readiness requires decoding AND rewinding; onPrepared alone is insufficient. */
internal class PlaybackPreparation {
    enum class Stage { NONE, FIRST_FRAME, REWIND, READY, FAILED }
    var stage = Stage.NONE
        private set
    private var seekComplete = false
    private var zeroFrame = false

    fun begin() { stage = Stage.FIRST_FRAME; seekComplete = false; zeroFrame = false }
    fun reset() { stage = Stage.NONE; seekComplete = false; zeroFrame = false }
    fun fail() { stage = Stage.FAILED }

    /** True once: caller must pause and seek to zero behind the opaque overlay. */
    fun onFrame(positionMs: Int): Boolean {
        when (stage) {
            Stage.FIRST_FRAME -> { stage = Stage.REWIND; return true }
            Stage.REWIND -> { if (positionMs == 0) zeroFrame = true; updateReady() }
            else -> Unit
        }
        return false
    }

    fun onSeekComplete(positionMs: Int) {
        if (stage != Stage.REWIND) return
        seekComplete = positionMs == 0
        updateReady()
    }

    private fun updateReady() {
        if (seekComplete && zeroFrame) stage = Stage.READY
    }
}
