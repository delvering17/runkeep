package com.runkeep.core

class MapCameraPolicy(centerOnUser: Boolean) {
    private var initialFitPending = !centerOnUser

    fun fitInitially(hasRoute: Boolean): Boolean {
        if (!initialFitPending || !hasRoute) return false
        initialFitPending = false
        return true
    }

    fun userMoved() { initialFitPending = false }
}
