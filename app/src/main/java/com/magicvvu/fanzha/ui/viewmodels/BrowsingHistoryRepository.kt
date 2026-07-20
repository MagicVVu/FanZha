package com.magicvvu.fanzha.ui.viewmodels

import androidx.compose.runtime.mutableStateListOf

object BrowsingHistoryRepository {
    private const val MAX_ITEMS = 100

    val history = mutableStateListOf<SharedCase>()

    /** 记录浏览：同一案例再次打开会移到最前。 */
    fun record(case: SharedCase) {
        history.removeAll { it.id == case.id }
        history.add(0, case)
        while (history.size > MAX_ITEMS) {
            history.removeAt(history.lastIndex)
        }
    }
}
