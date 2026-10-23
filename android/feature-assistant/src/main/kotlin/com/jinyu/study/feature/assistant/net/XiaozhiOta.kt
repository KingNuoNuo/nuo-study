package com.jinyu.study.feature.assistant.net

import com.jinyu.study.feature.assistant.identity.XiaozhiIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * 小智 OTA + v2 激活协议（api.tenclass.net 官方云）。
 *
 * 一台设备第一次接入的完整流程：
 *   1. POST /ota/ —— 已激活的设备直接拿到 websocket 配置；
 *      未激活的拿到 `activation.challenge` 和 6 位 `activation.code`。
 *   2. 家长在 xiaozhi.me 官网登录后，在设备管理里输入这 6 位码。
 *   3. 客户端每 5 秒 POST /ota/activate（用 hmac_key 对 challenge 签名）：
 *      202 = 还没输码，继续等；200 = 激活完成。
 *   4. 再 POST /ota/ 一次，这次就能拿到 websocket 配置了。
 *
 * ⚠️ 这里**必须用独立 OkHttpClient**：主工程的 client 带 DynamicUrlInterceptor，
 *    会把 host 改写成局域网服务端 —— 对外网服务这是毁灭性的。
 */
@Singleton
class XiaozhiOta @Inject constructor(
    private val identity: XiaozhiIdentity,
) {
    @Serializable
    data class WsConfig(val url: String, val token: String)

    /** OTA 一次性结果：要么能连 WS，要么需要人去输激活码 */
    sealed interface OtaOutcome {
        data class Ready(val config: WsConfig) : OtaOutcome
        data class NeedsActivation(val challenge: String, val code: String?) : OtaOutcome
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 请求一次 OTA。
     * @throws IOException 网络不通 / 服务端 4xx 5xx / 响应缺关键字段
     */
    suspend fun fetchOta(): OtaOutcome = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonObject("application") {
                put("version", APP_VERSION)
                // 模拟设备的关键：云端把这个值记录为设备指纹，激活时用它验 HMAC
                put("elf_sha256", identity.hmacKey)
            }
            putJsonObject("board") {
                put("type", BOARD_TYPE)
                put("name", BOARD_NAME)
                put("ip", localIp())
                put("mac", identity.deviceId)
            }
        }.toString()

        val request = Request.Builder()
            .url(OTA_URL)
            .header("Device-Id", identity.deviceId)
            .header("Client-Id", identity.clientId)
            .header("Accept-Language", "zh-CN")
            .header("Activation-Version", "2")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("OTA HTTP ${resp.code}：${body.take(200)}")
            }
            val parsed = json.decodeFromString(OtaResponse.serializer(), body)
            // ⚠️ 判据顺序是**实测踩出来的**：云端对未激活设备**也**返回 websocket 字段
            //    （url 真实、token 是占位 "test-token"）—— 只看 websocket.url 非空会拿着
            //    假 token 去连 WS，认证失败无限循环，UI 永远停在「正在连接」。
            //    正确顺序：有 activation.challenge 就必须先走激活。
            val challenge = parsed.activation?.challenge
            if (!challenge.isNullOrBlank()) {
                OtaOutcome.NeedsActivation(challenge, parsed.activation?.code)
            } else {
                val ws = parsed.websocket
                if (!ws?.url.isNullOrBlank()) {
                    OtaOutcome.Ready(WsConfig(ws!!.url, ws.token.orEmpty()))
                } else {
                    throw IOException("OTA 响应既没有 websocket 也没有激活挑战：${body.take(200)}")
                }
            }
        }
    }

    /**
     * 轮询激活结果，直到云端确认（200）。
     * 家长在官网输码需要时间，所以宽限到 60 次 × 5 秒 ≈ 5 分钟。
     *
     * @throws ActivationTimeoutException 超过宽限期还没人输码
     */
    suspend fun awaitActivation(challenge: String) {
        repeat(ACTIVATE_MAX_TRIES) {
            coroutineContext.ensureActive()
            if (activateOnce(challenge)) return
            delay(ACTIVATE_INTERVAL_MS)
        }
        throw ActivationTimeoutException(
            "激活等待超时（约 ${ACTIVATE_MAX_TRIES * ACTIVATE_INTERVAL_MS / 1000} 秒）：一直没人输入验证码"
        )
    }

    /** @return true = 激活完成（HTTP 200） */
    private suspend fun activateOnce(challenge: String): Boolean = withContext(Dispatchers.IO) {
        val hmac = hmacSha256Hex(identity.hmacKey, challenge)
        val payload = buildJsonObject {
            putJsonObject("Payload") {
                put("algorithm", "hmac-sha256")
                put("serial_number", identity.serialNumber)
                put("challenge", challenge)
                put("hmac", hmac)
            }
        }.toString()

        val request = Request.Builder()
            .url(ACTIVATE_URL)
            .header("Device-Id", identity.deviceId)
            .header("Client-Id", identity.clientId)
            .header("Accept-Language", "zh-CN")
            .header("Activation-Version", "2")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            when {
                resp.code == 200 -> true
                resp.code == 202 -> false
                else -> throw IOException("activate HTTP ${resp.code}")
            }
        }
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<InetAddress>()
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: "127.0.0.1"
    } catch (_: Exception) {
        "127.0.0.1"
    }

    // ---- 协议 DTO（ignoreUnknownKeys：云端加字段不能让客户端崩） ----

    @Serializable
    internal data class OtaResponse(
        val activation: ActivationInfo? = null,
        val websocket: WsInfo? = null,
    )

    @Serializable
    internal data class ActivationInfo(
        val challenge: String? = null,
        val code: String? = null,
        val message: String? = null,
    )

    @Serializable
    internal data class WsInfo(
        val url: String? = null,
        val token: String? = null,
    )

    class ActivationTimeoutException(message: String) : IOException(message)

    private companion object {
        const val OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
        const val ACTIVATE_URL = "https://api.tenclass.net/xiaozhi/ota/activate"
        const val APP_VERSION = "1.0.0"
        const val BOARD_TYPE = "bread-compact-wifi"
        const val BOARD_NAME = "study-tablet"
        const val ACTIVATE_INTERVAL_MS = 5_000L
        const val ACTIVATE_MAX_TRIES = 60
    }
}
