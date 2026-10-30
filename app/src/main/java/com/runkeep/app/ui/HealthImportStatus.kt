package com.runkeep.app.ui

import com.runkeep.core.healthimport.*

internal fun historyHealthFailure(failure: HealthResult.Failure): String = when (failure.reason) {
    HealthFailure.UNSUPPORTED -> "이 기기 또는 사용자 프로필에서는 Health Connect를 사용할 수 없습니다."
    HealthFailure.UPDATE_REQUIRED -> "Health Connect를 설치 또는 업데이트한 뒤 다시 시도하세요."
    HealthFailure.PERMISSION_REQUIRED -> "심박수·운동 읽기 권한을 허용한 뒤 다시 시도하세요."
    HealthFailure.ACCESS_RESTRICTED -> "Health Connect 읽기 접근이 제한됐습니다. 권한 변경·이전 기록 접근 제한·앱이 화면에 표시되는지 확인한 뒤 다시 시도하세요."
    HealthFailure.INVALID_TIME -> "운동 시작·종료 시간을 확인할 수 없어 가져올 수 없습니다."
    HealthFailure.NO_OVERLAP -> "선택한 운동과 런킵 기록의 시간이 겹치지 않습니다. 운동을 다시 선택하세요."
    HealthFailure.WRONG_SOURCE -> "삼성 헬스 출처의 운동을 다시 선택하세요."
    HealthFailure.TIMEOUT -> "Health Connect 응답 시간이 초과됐습니다. 읽은 일부 데이터는 저장하지 않았습니다. 다시 시도하세요."
    HealthFailure.PAGE_CYCLE -> "Health Connect의 다음 페이지를 확인하지 못했습니다. 일부 데이터는 저장하지 않았습니다. 다시 시도하세요."
    HealthFailure.READ_FAILED -> when (failure.stage) {
        HealthStage.PERMISSION -> "읽기 권한을 확인하지 못했습니다. 다시 시도하세요."
        HealthStage.SESSION_READ -> "운동 후보를 읽지 못했습니다. 다시 시도하세요."
        HealthStage.SOURCE_COMPARE -> "출처 비교를 완료하지 못했습니다. 다시 시도하세요."
        else -> "심박수를 읽지 못했습니다. 일부 데이터는 저장하지 않았습니다. 다시 시도하세요."
    }
}

internal fun historyHealthEmpty(diagnostic: HealthDiagnostics): String = when (diagnostic.reason()) {
    HealthDiagnosticReason.SELECTED_SAMSUNG_SAMPLES -> "선택 시간에 삼성 헬스 심박수가 확인됐습니다. 다시 가져오세요."
    HealthDiagnosticReason.SAMSUNG_OUTSIDE_SELECTION -> "런킵 전체 시간에는 삼성 헬스 심박수가 있지만 선택 시간에는 없습니다. 운동을 다시 선택하세요."
    HealthDiagnosticReason.OTHER_SOURCE_ONLY -> "선택 시간에는 다른 출처의 심박수만 확인됐습니다. 삼성 헬스 데이터로 가져오지 않았습니다."
    HealthDiagnosticReason.SAMSUNG_INTERVAL_WITHOUT_SAMPLES -> "삼성 헬스 심박수 레코드는 있지만 선택 시간 안의 유효 샘플이 없습니다."
    HealthDiagnosticReason.NO_SAMSUNG_RECORDS -> "이번 Health Connect 조회에서 삼성 헬스 심박수 레코드를 찾지 못했습니다. 삼성 헬스의 심박수 쓰기 권한과 해당 시간의 Health Connect 기록을 확인한 뒤 다시 시도하세요."
}

/** One screen instance owns each request; returning to the same run ID creates a new owner. */
internal class HistoryHealthToken internal constructor(internal val owner: HistoryHealthGate, internal val generation: Long)
internal class HistoryHealthGate {
    private var generation = 0L
    private var attached = true
    private var running = false
    fun begin(): HistoryHealthToken? {
        if (!attached || running) return null
        running = true
        return HistoryHealthToken(this, ++generation)
    }
    fun accepts(token: HistoryHealthToken?) = token != null && token.owner === this && attached && running && token.generation == generation
    fun finish(token: HistoryHealthToken) { if (accepts(token)) running = false }
    fun cancel() { generation++; running = false }
    fun close() { cancel(); attached = false }
}
