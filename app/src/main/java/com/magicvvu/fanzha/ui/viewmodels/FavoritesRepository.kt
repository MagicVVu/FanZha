package com.magicvvu.fanzha.ui.viewmodels

import androidx.compose.runtime.mutableStateListOf

object FavoritesRepository {
    val favorites = mutableStateListOf<SharedCase>()

    fun toggle(case: SharedCase) {
        if (isFavorited(case.id)) {
            favorites.removeAll { it.id == case.id }
        } else {
            favorites.add(case)
        }
    }

    fun isFavorited(id: String): Boolean = favorites.any { it.id == id }
}
