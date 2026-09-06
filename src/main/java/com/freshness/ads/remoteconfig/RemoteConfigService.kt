package com.freshness.ads.remoteconfig

import android.app.Application

interface RemoteConfigService {

    val remoteConfigValue: RemoteConfig

    fun init(app: Application)

    fun refreshRemoteConfig()

    suspend fun waitRemoteConfig(): RemoteConfig

}