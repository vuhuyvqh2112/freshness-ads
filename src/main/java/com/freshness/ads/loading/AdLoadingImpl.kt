package com.freshness.ads.loading

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

internal class AdLoadingImpl : AdLoading {

    private val _isLoading = MutableStateFlow(false)
    override val isLoading = _isLoading

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var timeoutJob: Job? = null

    /**
     * Gọi được từ mọi luồng (callback GMA chạy trên luồng nền của nó). [timeoutJob] chỉ được đọc/ghi
     * trên main nên luồng khác được hop về trước.
     */
    override fun setLoading(value: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) apply(value) else scope.launch { apply(value) }
    }

    private fun apply(value: Boolean) {
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
