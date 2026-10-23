package com.jinyu.study.feature.assistant.identity

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 小智设备身份 —— **一台平板 = 一个"虚拟硬件设备"**。
 *
 * xiaozhi.me 的云端按 (Device-Id, Client-Id) 识别设备，激活状态绑定在 Device-Id 上。
 * 这四个值必须**持久且稳定**：换了 MAC，云端就当成一台新设备，又要重新激活。
 *
 * 为什么不用真实网卡 MAC：
 *   - Android 6+ 拿 Wi-Fi MAC 需要开 Wi-Fi 且返回的还可能是随机化地址，
 *     恢复出厂/换网络都会变 —— 反而做不到「持久」。
 *   - 设备身份没有任何隐私价值（就是一个会话钥匙），随机生成更干净。
 *   - ESP32 系真机的 Device-Id 就是 12 位大写 hex，我们照这个格式伪造，云端不区分。
 *
 * hmac_key 的机制（v2 激活协议的关键）：
 *   OTA 请求里我们把 hmac_key 填在 `application.elf_sha256` 字段 —— 真机上那是
 *   固件哈希，云端把它记录为「该设备的固件指纹」。激活时云端要求证明
 *   「你就是申报这台设备的人」：用 hmac_key 对 challenge 做 HMAC-SHA256 签名，
 *   服务端用它记录的 elf_sha256 原样验签。所以 hmac_key 只要生成后**永不改变**
 *   就行，内容本身不要求是真实哈希（但格式照 hex 串来，别惹麻烦）。
 */
@Singleton
class XiaozhiIdentity @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ⚠️ 四个值一律 **lazy**。它们是 @Singleton 的构造参数，会在**主线程**被注入构造 ——
    //    而每个取值都是 SharedPreferences 读写（首次访问要解析 XML，是磁盘 IO）。
    //    全部推迟到真正用到时再算：那时已经在会话协程（Dispatchers.Default）里了。
    //    `lazy` 默认线程安全，多个线程首次并发访问也只会算一次。

    /** 设备 MAC（**带冒号**小写 `aa:bb:cc:dd:ee:ff`）—— Device-Id header / OTA payload 用 */
    val deviceId: String by lazy {
        val saved = prefs.getString(KEY_DEVICE_ID, null)
        // 两轮迁移史：
        //   v1 完全随机 12 位无分隔 → 云端拒（Invalid MAC address）
        //   v2 无分隔但单播 → 云端仍拒 —— 云端按标准格式校验，
        //      ESP32 真机固件与 py-xiaozhi 上报的都是**冒号分隔**的 MAC。
        // 判据：冒号分隔 + 各段 hex + 第一字节单播（LSB=0）。
        // ⚠️ 2026-10-09 实测：云端 WS 网关**只认小写 MAC**，大写会被静默拒
        //   （不回 hello，客户端 15s 超时）。之前一直「连不上」的真凶就是这里——
        //   旧实现生成大写 MAC，OTA 能过、WS 永远被拒。故统一小写，并把存量值规范化。
        val normalized = saved?.lowercase()
        val valid = normalized != null && MAC_REGEX.matches(normalized) &&
            normalized.substring(0, 2).toInt(16) and 0x01 == 0
        if (valid) {
            if (normalized != saved) prefs.edit().putString(KEY_DEVICE_ID, normalized).apply()
            normalized
        } else randomMac().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    /** 客户端 UUID —— Client-Id header，区分「同一台设备上的不同客户端」 */
    val clientId: String by lazy {
        prefs.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_CLIENT_ID, it).apply()
        }
    }

    /**
     * 序列号 —— v2 激活协议要求上报。
     * 借鉴 py-xiaozhi 的生成格式：`SN-<md5(mac_clean)[:8]>-<mac_clean>`，
     * 其中 mac_clean = 去冒号小写的 MAC。无深意，唯一即可。
     */
    val serialNumber: String by lazy {
        val macClean = deviceId.replace(":", "").lowercase()
        val md5 = MessageDigest.getInstance("MD5").digest(macClean.toByteArray())
        val head = md5.joinToString("") { "%02x".format(it) }.take(8).uppercase()
        "SN-$head-$macClean"
    }

    /** HMAC-SHA256 密钥（64 位 hex 串）—— 生成后**永不变更**，见类注释 */
    val hmacKey: String by lazy {
        prefs.getString(KEY_HMAC_KEY, null) ?: randomHex(32).also {
            prefs.edit().putString(KEY_HMAC_KEY, it).apply()
        }
    }

    /**
     * 本地管理 + 单播的随机 MAC，**冒号分隔**（云端按标准 MAC 格式校验）：
     * 第一字节固定 02（本地管理位=1、多播位=0）。
     * 完全随机 + 无分隔的 MAC 会被云端拒（两轮真机实测）。
     */
    private fun randomMac(): String =
        (0..5).joinToString(":") { if (it == 0) "02" else "%02x".format(Random.nextBits(8)) }

    private fun randomHex(bytes: Int): String =
        (1..bytes).joinToString("") { "%02x".format(Random.nextBits(8)) }

    private companion object {
        const val PREFS_NAME = "xiaozhi_identity"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_HMAC_KEY = "hmac_key"

        private val MAC_REGEX = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    }
}
