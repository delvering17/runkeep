package com.runkeep.core

import kotlin.test.*

class MapCameraPolicyTest {
    @Test fun `saved course fits once when ready and later route changes keep the camera`() {
        val policy = MapCameraPolicy(centerOnUser = false)
        assertFalse(policy.fitInitially(hasRoute = false))
        assertTrue(policy.fitInitially(hasRoute = true))
        repeat(3) { assertFalse(policy.fitInitially(hasRoute = true)) }
    }

    @Test fun `pan or selection before the initial route is ready prevents a forced reset`() {
        val policy = MapCameraPolicy(centerOnUser = false)
        policy.userMoved()
        assertFalse(policy.fitInitially(hasRoute = true))
    }

    @Test fun `new course keeps its location camera while points are added`() {
        val policy = MapCameraPolicy(centerOnUser = true)
        assertFalse(policy.fitInitially(hasRoute = false))
        assertFalse(policy.fitInitially(hasRoute = true))
    }
}
