package com.runkeep.app.tracking

import com.runkeep.core.*
import kotlinx.serialization.Serializable

object ReplayLimits {
    const val MAX_STAGES = 30
    const val MAX_SCENARIOS = 20
    const val MAX_SECONDS = MeasurementLimits.MAX_STEP_SECONDS
}

@Serializable enum class ReplayStageKind(val title: String) {
    RUN("달리기"), STOP("정지"), GPS_GAP("GPS 미수신"), LOW_ACCURACY("정확도 저하")
}

@Serializable data class ReplayStage(
    val id: String = newId(), val kind: ReplayStageKind = ReplayStageKind.RUN,
    val seconds: Int = 30, val paceSeconds: Int = 365, val gpsJitter: Boolean = false
)

@Serializable data class CustomReplayScenario(
    val id: String = newId(), val name: String = "내 시나리오", val targetPaceSeconds: Int = 365,
    val stages: List<ReplayStage> = listOf(ReplayStage())
) {
    val totalSeconds: Long get() = stages.sumOf { it.seconds.toLong() }
    val inputMeters: Double get() = stages.filter { it.kind != ReplayStageKind.STOP }
        .sumOf { it.seconds * 1000.0 / it.paceSeconds }

    fun validationMessage(): String? = when {
        id.isBlank() || id.length > 64 -> "시나리오 식별자를 확인해주세요."
        name.trim().length !in 1..40 -> "시나리오 이름을 1~40자로 입력해주세요."
        !MeasurementLimits.pace(targetPaceSeconds) -> "목표 페이스는 2:00~30:00 /km입니다."
        stages.size !in 1..ReplayLimits.MAX_STAGES -> "구간은 1~${ReplayLimits.MAX_STAGES}개로 구성해주세요."
        stages.any { it.id.isBlank() || it.id.length > 64 } || stages.map { it.id }.distinct().size != stages.size -> "구간 식별자가 중복되거나 유효하지 않습니다."
        stages.any { !MeasurementLimits.duration(it.seconds) } -> "구간 시간은 1초~200분입니다."
        totalSeconds > ReplayLimits.MAX_SECONDS -> "전체 시나리오는 200분 이내로 구성해주세요."
        stages.any { it.kind != ReplayStageKind.STOP && !MeasurementLimits.pace(it.paceSeconds) } -> "입력 페이스는 2:00~30:00 /km입니다."
        else -> null
    }

    fun frames(): List<ReplayFrame> {
        require(validationMessage() == null) { validationMessage()!! }
        var meters = 0.0
        var second = 0
        fun frame(stage: ReplayStage, index: Int): ReplayFrame {
            val stopped = stage.kind == ReplayStageKind.STOP
            val speed = if (stopped) 0.0 else 1000.0 / stage.paceSeconds
            val jitter = if (stopped && stage.gpsJitter) when (second % 4) { 1 -> 2.0; 3 -> -2.0; else -> 0.0 } else 0.0
            return ReplayFrame(second, coordinateAt(meters + jitter),
                if (stopped && stage.gpsJitter && second % 12 == 0) 1.8f else speed.toFloat(),
                if (stage.kind == ReplayStageKind.LOW_ACCURACY) 60f else 5f,
                emit = stage.kind != ReplayStageKind.GPS_GAP,
                phase = "${index + 1}/${stages.size} 구간 · ${stage.kind.title}" +
                    if (stopped) (if (stage.gpsJitter) " · GPS 흔들림" else "") else " · ${paceText(stage.paceSeconds.toDouble())}/km")
        }
        // t=0은 시작점이다. 각 구간은 지정한 수만큼의 1초 이동 간격을 생성한다.
        return buildList {
            add(frame(stages.first(), 0))
            stages.forEachIndexed { index, stage ->
                repeat(stage.seconds) {
                    second++
                    if (stage.kind != ReplayStageKind.STOP) meters += 1000.0 / stage.paceSeconds
                    add(frame(stage, index))
                }
            }
        }
    }

    fun moveStage(id: String, offset: Int): CustomReplayScenario {
        val from = stages.indexOfFirst { it.id == id }
        val to = from + offset
        if (from !in stages.indices || to !in stages.indices || offset !in listOf(-1, 1)) return this
        return moveStageTo(id, to)
    }

    fun moveStageTo(id: String, to: Int): CustomReplayScenario {
        val from = stages.indexOfFirst { it.id == id }
        if (from !in stages.indices || to !in stages.indices || from == to) return this
        val reordered = stages.toMutableList().apply { add(to, removeAt(from)) }
        return copy(stages = reordered)
    }
}

@Serializable data class ReplayLibrary(
    val schemaVersion: Int = 1, val scenarios: List<CustomReplayScenario> = emptyList(), val selectedId: String? = null
) {
    fun validated(): ReplayLibrary {
        require(schemaVersion == 1) { "지원하지 않는 시나리오 저장 형식입니다." }
        require(scenarios.size <= ReplayLimits.MAX_SCENARIOS) { "시나리오는 최대 ${ReplayLimits.MAX_SCENARIOS}개 저장할 수 있습니다." }
        require(scenarios.map { it.id }.distinct().size == scenarios.size) { "저장된 시나리오 식별자가 중복됩니다." }
        scenarios.forEach { require(it.validationMessage() == null) { it.validationMessage()!! } }
        require(selectedId == null || scenarios.any { it.id == selectedId }) { "선택한 시나리오를 찾을 수 없습니다." }
        return this
    }

    fun upsert(scenario: CustomReplayScenario): ReplayLibrary {
        val normalized = scenario.copy(name = scenario.name.trim())
        val exists = scenarios.any { it.id == normalized.id }
        return copy(scenarios = if (exists) scenarios.map { if (it.id == normalized.id) normalized else it } else scenarios + normalized,
            selectedId = normalized.id).validated()
    }

    fun remove(id: String) = copy(scenarios = scenarios.filterNot { it.id == id },
        selectedId = selectedId.takeUnless { it == id }).validated()

    companion object {
        fun decode(raw: String?): ReplayLibrary = if (raw == null) ReplayLibrary()
            else codec.decodeFromString<ReplayLibrary>(raw).validated()
    }
}
