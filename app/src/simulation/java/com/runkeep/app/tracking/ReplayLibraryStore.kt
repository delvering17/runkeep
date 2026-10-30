package com.runkeep.app.tracking

import android.content.Context
import com.runkeep.core.codec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** 테스트 앱 전용 저장소. 손상된 원본은 명시적 초기화 전까지 덮어쓰지 않는다. */
class ReplayLibraryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("gps-replay-library", Context.MODE_PRIVATE)
    fun load(): Result<ReplayLibrary> = runCatching { ReplayLibrary.decode(preferences.getString("library", null)) }
    suspend fun save(library: ReplayLibrary): ReplayLibrary = withContext(Dispatchers.IO) {
        val validated = library.validated()
        check(preferences.edit().putString("library", codec.encodeToString(validated)).commit()) {
            "시나리오를 저장하지 못했습니다. 다시 시도해주세요."
        }
        validated
    }
}
