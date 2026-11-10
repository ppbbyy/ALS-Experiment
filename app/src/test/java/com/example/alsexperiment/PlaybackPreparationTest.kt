package com.example.alsexperiment

import org.junit.Assert.*
import org.junit.Test

class PlaybackPreparationTest {
    @Test fun firstFrameTriggersRewindAndSeekAloneIsInsufficient() {
        val prep = PlaybackPreparation()
        prep.begin()
        assertTrue(prep.onFrame(95))
        prep.onSeekComplete(0)
        assertEquals(PlaybackPreparation.Stage.REWIND, prep.stage)
        assertFalse(prep.onFrame(0))
        assertEquals(PlaybackPreparation.Stage.READY, prep.stage)
    }

    @Test fun rewindFrameCanArriveBeforeSeekCompletion() {
        val prep = PlaybackPreparation()
        prep.begin()
        prep.onFrame(95)
        prep.onFrame(0)
        assertEquals(PlaybackPreparation.Stage.REWIND, prep.stage)
        prep.onSeekComplete(0)
        assertEquals(PlaybackPreparation.Stage.READY, prep.stage)
    }

    @Test fun nonzeroRewindCannotEnableStartAndFailureIgnoresLateCallbacks() {
        val prep = PlaybackPreparation()
        prep.begin()
        prep.onFrame(100)
        prep.onFrame(100)
        prep.onSeekComplete(100)
        assertEquals(PlaybackPreparation.Stage.REWIND, prep.stage)
        prep.fail()
        prep.onSeekComplete(0)
        prep.onFrame(0)
        assertEquals(PlaybackPreparation.Stage.FAILED, prep.stage)
        prep.reset()
        assertEquals(PlaybackPreparation.Stage.NONE, prep.stage)
    }
}
