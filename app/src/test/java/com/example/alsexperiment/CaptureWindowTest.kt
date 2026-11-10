package com.example.alsexperiment

import org.junit.Assert.*
import org.junit.Test

class CaptureWindowTest {
    @Test fun excludesPreStartExactEndAndPostEndEventsEvenIfAlreadyBuffered() {
        val rows = listOf(99L, 100L, 150L, 199L, 200L, 201L)
        assertEquals(listOf(100L, 150L, 199L), CaptureWindow.snapshot(rows, 100L, 200L) { it })
    }

    @Test fun removesThePostEndSampleObservedOnSmT550() {
        val end = 1_701_477_459_140L
        val rows = listOf(1_701_473_276_119L, 1_701_483_091_953L)
        assertEquals(rows.take(1), CaptureWindow.snapshot(rows, 1_520_437_726_865L, end) { it })
        assertFalse(CaptureWindow.contains(end, 1_520_437_726_865L, end))
    }
    @Test fun removesBothPostEndSamplesFromTablet03() {
        val start = 177_589_302_580_465L
        val end = 177_769_557_770_656L
        val rows = listOf(start - 1, start, end - 1, end,
            177_769_561_750_656L, 177_769_566_280_917L)
        assertEquals(listOf(start, end - 1), CaptureWindow.snapshot(rows, start, end) { it })
    }
}
