package com.freshness.ads.loading

import kotlinx.coroutines.flow.StateFlow

interface AdLoading {
    val isLoading: StateFlow<Boolean>

    fun setLoading(value: Boolean)
}