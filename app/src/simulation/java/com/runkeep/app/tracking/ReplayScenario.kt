package com.runkeep.app.tracking

import com.runkeep.core.Coordinate
import kotlin.math.cos

enum class ReplayScenario(val title: String, val seconds: Int, val description: String) {
    STATIONARY("제자리 GPS 흔들림", 90, "2m 흔들림과 간헐적인 속도 오류. 자동 정지 후 재개가 반복되면 실패입니다."),
    STOP_AND_GO("달리기 → 정지 → 재개", 120, "40초 달리기, 30초 정지, 50초 달리기. 정지 중 거리와 시간 증가를 확인하세요."),
    PACE_CHANGES("정상 → 느림 → 빠름 → 정상", 250, "목표 6분 5초. 30초 정상, 80초 7분, 80초 5분, 60초 정상으로 달립니다."),
    GPS_RECOVERY("GPS 끊김 · 정확도 저하", 127, "30초 달리기, 22초 미수신, 30초 오차 60m, 45초 정상 수신."),
    KILOMETER("1km 음성 · 지점 통과", 440, "6분 5초 페이스로 약 1.2km. 1km 안내가 시각이 아닌 분·초인지 확인하세요.");

    fun frames(): List<ReplayFrame> {
        var meters = 0.0
        return (0..seconds).map { second ->
            val speed = when (this) {
                STATIONARY -> 0.0
                STOP_AND_GO -> if (second in 40..69) 0.0 else 1000.0 / 365
                PACE_CHANGES -> when (second) {
                    in 30..109 -> 1000.0 / 420
                    in 110..189 -> 1000.0 / 300
                    else -> 1000.0 / 365
                }
                else -> 1000.0 / 365
            }
            if (second > 0) meters += speed
            val stopped = speed == 0.0
            val jitter = if (stopped) when (second % 4) { 0 -> 0.0; 1 -> 2.0; 2 -> 0.0; else -> -2.0 } else 0.0
            val reportedSpeed = if (stopped && second % 12 == 0) 1.8f else speed.toFloat()
            ReplayFrame(second, coordinateAt(meters + jitter), reportedSpeed,
                if (this == GPS_RECOVERY && second in 52..81) 60f else 5f,
                emit = !(this == GPS_RECOVERY && second in 30..51),
                phase = when {
                    this == GPS_RECOVERY && second in 30..51 -> "GPS 미수신"
                    this == GPS_RECOVERY && second in 52..81 -> "정확도 저하 ±60m"
                    stopped -> "제자리 · GPS 흔들림"
                    else -> "입력 페이스 ${if (speed > 0) (1000 / speed).toInt() else 0}초/km"
                })
        }
    }
}

data class ReplayFrame(val second: Int, val coordinate: Coordinate, val speed: Float,
                       val accuracy: Float, val emit: Boolean, val phase: String)

/** Fixed public test route, independent of the user's location. Two right-angle turns. */
fun coordinateAt(meters: Double): Coordinate {
    val east = when { meters < 200 -> meters; meters < 400 -> 200.0; else -> meters - 200 }
    val north = (meters - 200).coerceIn(0.0, 200.0)
    return Coordinate(37.524 + north / 111_195, 126.923 + east / (111_195 * cos(Math.toRadians(37.524))))
}
