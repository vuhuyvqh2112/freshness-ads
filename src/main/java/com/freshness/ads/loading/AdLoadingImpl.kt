package com.freshness.ads.loading

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class AdLoadingImpl constructor(
) : AdLoading {

    private val _isLoading = MutableStateFlow(false)
    override val isLoading = _isLoading

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var timeoutJob: Job? = null

    override fun setLoading(value: Boolean) {
        Timber.d("setLoading: $value")
        _isLoading.value = value
        timeoutJob?.cancel()
        timeoutJob = null
        if (value) {
            timeoutJob = scope.launch {
                delay(60_000L)
                _isLoading.value = false
            }
        }
    }

}