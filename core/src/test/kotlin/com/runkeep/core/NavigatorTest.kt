package com.runkeep.core

import kotlin.test.*

class NavigatorTest {
    private fun p(north: Double, east: Double = 0.0) = Coordinate(north / 111195.08, east / 111195.08)
    @Test fun `announces a turn once within the configured lead`() {
        val nav = CourseNavigator(listOf(p(0.0), p(100.0), p(100.0, 100.0)), 20)
        val cues = (0..100 step 5).mapNotNull { nav.update(p(it.toDouble()), it * 1000L) }
        assertEquals(listOf("앞에서 오른쪽으로 이동하세요"), cues)
    }
    @Test fun `future overlapping segment cannot skip the outward leg`() {
        val nav = CourseNavigator(listOf(p(0.0), p(100.0), p(100.0, 100.0), p(0.0, 100.0), p(0.0)), 20)
        assertNull(nav.update(p(0.0), 0))
        val cues = (5..90 step 5).mapNotNull { nav.update(p(it.toDouble()), it * 1000L) }
        assertEquals(listOf("앞에서 오른쪽으로 이동하세요"), cues)
    }
    @Test fun `off route cue is rate limited without rerouting`() {
        val nav = CourseNavigator(listOf(p(0.0), p(100.0)), 20)
        nav.update(p(0.0), 0)
        assertEquals("설정한 경로를 벗어났습니다", nav.update(p(0.0, 100.0), 1000))
        assertNull(nav.update(p(0.0, 105.0), 10_000))
        assertEquals("설정한 경로를 벗어났습니다", nav.update(p(0.0, 110.0), 62_000))
    }
}
