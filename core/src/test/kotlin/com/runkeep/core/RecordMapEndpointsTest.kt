package com.runkeep.core

import kotlin.test.*

class RecordMapEndpointsTest {
    private val a = Coordinate(0.0, 0.0)
    private val b = Coordinate(0.0, .001)
    @Test fun `no track has no invented endpoints`() { assertTrue(recordMapEndpoints(listOf(emptyList())).isEmpty()) }
    @Test fun `empty and disconnected segments select actual first and last only`() {
        val result = recordMapEndpoints(listOf(emptyList(), listOf(a), emptyList(), listOf(b), emptyList()))
        assertEquals(listOf(a,b), result.map { it.coordinate })
        assertEquals(listOf(RecordEndpointKind.START, RecordEndpointKind.END), result.map { it.kind })
    }
    @Test fun `single point and return loop use both shapes at one real coordinate`() {
        for (points in listOf(listOf(a), listOf(a,b,a))) {
            val result = recordMapEndpoints(listOf(points))
            assertEquals(listOf(RecordMapEndpoint(a, RecordEndpointKind.BOTH, .5f)), result)
        }
    }
    @Test fun `nearby ends keep coordinates and screen layout chooses anchors`() {
        val near = Coordinate(0.0, .00001)
        val result = recordMapEndpoints(listOf(listOf(a,near)))
        assertEquals(listOf(a,near), result.map { it.coordinate })
        assertEquals(.5f to .5f, result[0].anchorX to result[1].anchorX)
        assertEquals(1f to 0f, recordEndpointAnchors(RecordEndpointScreenPoint(0f, 0f),
            RecordEndpointScreenPoint(10f, 0f), 24f))
    }
    @Test fun `displayed goal and full endpoints follow caller range without changing raw route`() {
        val raw = listOf(a,b,Coordinate(0.0,.002))
        assertEquals(b, recordMapEndpoints(listOf(raw.take(2))).last().coordinate)
        assertEquals(raw.last(), recordMapEndpoints(listOf(raw)).last().coordinate)
        assertEquals(3, raw.size)
    }

    @Test fun `reverse nearby screen order moves both icons away instead of onto each other`() {
        // Original preview reproduction: .00004 -> .0005 -> 0, 300dp canvas => 24dp -> 0dp.
        val start = RecordEndpointScreenPoint(24f, 0f); val end = RecordEndpointScreenPoint(0f, 0f)
        val anchors = recordEndpointAnchors(start, end, 36f)
        assertEquals(0f to 1f, anchors)
        val startCenter = start.x + (.5f - anchors.first) * 36f
        val endCenter = end.x + (.5f - anchors.second) * 36f
        assertTrue(startCenter - endCenter >= 36f)
        // At exactly the preview 24dp icon width, the original centred boxes already do not overlap.
        assertEquals(.5f to .5f, recordEndpointAnchors(start, end, 24f))
    }
    @Test fun `screen projection changes anchors across zoom and rotation irrespective of metres`() {
        val result = recordMapEndpoints(listOf(listOf(a, b))) // Actual ends are about 111m apart.
        assertEquals(listOf(a, b), result.map { it.coordinate })
        assertEquals(1f to 0f, recordEndpointAnchors(RecordEndpointScreenPoint(0f, 0f),
            RecordEndpointScreenPoint(8f, 0f), 36f))
        assertEquals(0f to 1f, recordEndpointAnchors(RecordEndpointScreenPoint(8f, 0f),
            RecordEndpointScreenPoint(0f, 0f), 36f))
        assertEquals(.5f to .5f, recordEndpointAnchors(RecordEndpointScreenPoint(0f, 0f),
            RecordEndpointScreenPoint(80f, 0f), 36f))
        assertEquals(.5f to .5f, recordEndpointAnchors(RecordEndpointScreenPoint(0f, 0f),
            RecordEndpointScreenPoint(0f, 80f), 36f))
    }
    @Test fun `all overlapping screen quadrants separate icon boxes`() {
        val size = 24f
        val start = RecordEndpointScreenPoint(0f, 0f)
        for (x in listOf(-36f, -24f, -23f, -1f, 0f, 1f, 23f, 24f, 36f)) {
            for (y in listOf(-36f, -23f, 0f, 23f, 36f)) {
                val end = RecordEndpointScreenPoint(x, y)
                val anchors = recordEndpointAnchors(start, end, size)
                val startCenter = start.x + (.5f - anchors.first) * size
                val endCenter = end.x + (.5f - anchors.second) * size
                assertTrue(kotlin.math.abs(startCenter - endCenter) >= size || kotlin.math.abs(y) >= size,
                    "Overlapping boxes for $end with anchors $anchors")
            }
        }
    }
}
