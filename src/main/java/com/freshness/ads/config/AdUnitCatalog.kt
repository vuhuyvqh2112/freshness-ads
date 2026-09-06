package com.freshness.ads.config

import com.freshness.ads.remoteconfig.RemoteConfig

/**
 * Nguồn duy nhất cho ad unit id: đọc payload Remote Config `ads_id_config`, rơi về bản default đóng gói
 * trong `assets/ads_id_config.json` khi Remote Config chưa fetch xong / rỗng / hỏng.
 *
 * KHÔNG chứa master gate. IAP và `enableAllAds` vẫn do [com.freshness.ads.datastore.AdsDataStore] và
 * [com.freshness.ads.manager.AdsManagerProvider] quyết định — catalog chỉ trả lời "placement này có
 * những id nào còn dùng được".
 */
interface AdUnitCatalog {

    /**
     * CHỈ CÓ TÁC DỤNG Ở DEBUG BUILD. `false` (mặc định) = debug ép dùng test id của Google.
     * Bật lên để debug dùng id thật + waterfall thật — cần thiết khi test waterfall, vì với test id
     * thì mọi tầng đều fill và không tầng nào rớt xuống tầng sau.
     *
     * Release build bỏ qua hoàn toàn: gán giá trị vào đây không có tác dụng gì.
     */
    var useRealIds: Boolean

    /**
     * Id để load, theo đúng thứ tự waterfall (thử id đầu, fail mới xuống id kế).
     * Rỗng = placement bị tắt, không còn id nào bật, hoặc key không tồn tại ở cả Remote Config lẫn default.
     */
    fun idsFor(placement: String): List<String>

    /** true khi placement bật VÀ còn ít nhất một id bật. */
    fun isEnabled(placement: String): Boolean

    /**
     * Ngân sách thời gian của placement: [fallback] là default trong code, từng field bị payload
     * Remote Config ghi đè nếu có. Cho phép siết/nới thời gian chờ mà không cần release.
     */
    fun budgetSpecFor(placement: String, fallback: AdBudgetSpec): AdBudgetSpec

    /**
     * Nạp payload mới từ Remote Config. Payload null/rỗng/hỏng đều bị BỎ QUA và giữ nguyên payload đang
     * dùng — không bao giờ tự đẩy catalog về trạng thái rỗng, vì đó là cách nhanh nhất để mất sạch ads.
     */
    fun update(json: String?)

    /**
     * Các setting ads không thuộc placement nào, lấy từ payload đang có hiệu lực (Remote Config hoặc
     * asset default). Một chỗ resolve duy nhất cho cả id lẫn setting, tránh hai đường parse lệch nhau.
     */
    fun settingsSnapshot(): RemoteConfig
}
