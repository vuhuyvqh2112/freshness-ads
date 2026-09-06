package com.freshness.ads.datastore

interface AdsDataStore {

    /**
     * Master gate. Returns false if the user is premium/purchased, or if the
     * remote `enableAllAds` master toggle is false. All ad services must
     * short-circuit when this is false.
     */
    val isAdEnabled: Boolean

    var isPurchased: Boolean

}