package com.runkeep.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WaypointPassRegressionTest {
    private fun coordinate(northMeters: Double) = Coordinate(northMeters / 111195.08, 0.0)
    private fun point(northMeters: Double, meters: Double, seconds: Long, segment: Int = 0) =
        TrackPoint(coordinate(northMeters), seconds * 1000, seconds * 1000, meters, accuracy = 3f, segment = segment)
    private val shortLoop = Course(name = "짧은 왕복", points = listOf(coordinate(0.0), coordinate(5.0), coordinate(0.0)))

    @Test fun `a single starting fix only passes the start of a short loop`() {
        assertEquals(listOf(WaypointPass(0, 0.0, 0)), PaceProgress.passes(shortLoop, listOf(point(0.0, 0.0, 0))))
    }

    @Test fun `stationary fixes do not complete nearby waypoints`() {
        val points = listOf(point(0.0, 0.0, 0), point(0.0, 0.0, 1), point(0.0, 0.0, 2, segment = 1))
        assertEquals(listOf(0), PaceProgress.passes(shortLoop, points).map { it.index })
    }

    @Test fun `a short loop completes after actual outward and return movement`() {
        val outward = listOf(point(0.0, 0.0, 0), point(5.0, 5.0, 1))
        assertEquals(listOf(0, 1), PaceProgress.passes(shortLoop, outward).map { it.index })
        val passages = PaceProgress.passes(shortLoop, outward + point(0.0, 10.0, 2))
        assertEquals(listOf(WaypointPass(0, 0.0, 0), WaypointPass(1, 5.0, 1000), WaypointPass(2, 10.0, 2000)), passages)
    }

    @Test fun `one moving fix can cross several close ordered waypoints`() {
        val course = Course(name = "가까운 직선 지점", points = listOf(coordinate(0.0), coordinate(5.0), coordinate(10.0)))
        val passages = PaceProgress.passes(course, listOf(point(0.0, 0.0, 0), point(20.0, 20.0, 2)))
        assertEquals(listOf(0, 1, 2), passages.map { it.index })
        assertEquals(5.0, passages[1].meters, 0.001)
        assertEquals(10.0, passages[2].meters, 0.001)
        assertEquals(500L, passages[1].activeMs)
        assertEquals(1000L, passages[2].activeMs)
    }

    @Test fun `a GPS gap does not connect crossings between its endpoints`() {
        val course = Course(name = "공백", points = listOf(coordinate(0.0), coordinate(50.0), coordinate(100.0)))
        val points = listOf(point(0.0, 0.0, 0), point(100.0, 0.0, 30, segment = 1))
        assertEquals(listOf(0), PaceProgress.passes(course, points).map { it.index })
    }
}
