package com.nuostudy.feature.assistant.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 小智 WebSocket 会话：只管「连上、握手、收发帧」，对话状态机在 [XiaozhiClient]。
 *
 * 握手契约（xiaozhi 官方云）：
 *   1. HTTP 升级请求带 `Authorization: Bearer <token>`（OTA 发下来的）+
 *      `Protocol-Version: 1` + `Device-Id` + `Client-Id`
 *   2. 连接建立后客户端先发 hello（声明自己 16kHz opus 60ms）
 *   3. 服务端回 hello（声明它的 TTS 采样率，通常 24kHz）—— 收到即会话可用
 *
 * 文本帧 = JSON 控制消息（listen/tts/stt/llm...）；二进制帧 = opus 音频。
 */
class XiaozhiSocket(
    url: String,
    token: String,
    deviceId: String,
    clientId: String,
    private val onJson: (JsonObject) -> Unit,
    private val onAudio: (ByteArray) -> Unit,
    private val onDisconnected: (Throwable?) -> Unit,
) {
    data class ServerAudio(val sampleRate: Int, val frameMs: Int)

    private val json = Json { ignoreUnknownKeys = true }

    private val clientHello = buildJsonObject {
        put("type", "hello")
        put("version", 1)
        put("transport", "websocket")
        putJsonObject("audio_params") {
            put("format", "opus")
            put("sample_rate", 16_000)
            put("channels", 1)
            put("frame_duration", 60)
        }
    }.toString()

    /** 挂起直到收到服务端 hello（成功）或连接失败（异常） */
    private val handshake = CompletableDeferred<ServerAudio>()

    private var ws: WebSocket? = null

    /** 供外部发送：文本（控制消息）与二进制（opus 音频） */
    fun sendText(text: String): Boolean = ws?.send(text) ?: false

    fun sendAudio(bytes: ByteArray): Boolean =
        ws?.send(bytes.toByteString()) ?: false

    fun close() {
        runCatching { ws?.close(1000, "bye") }
    }

    init {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Protocol-Version", "1")
            .header("Device-Id", deviceId)
            .header("Client-Id", clientId)
            .build()

        ws = SHARED_CLIENT.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(clientHello)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val obj = try {
                        json.parseToJsonElement(text).jsonObject
                    } catch (_: Exception) {
                        return // 非 JSON 文本帧：协议外内容，忽略
                    }
                    val type = runCatching { obj["type"]?.jsonPrimitive?.content }.getOrNull()
                    if (type == "hello") {
                        handshake.complete(parseServerAudio(obj))
                    }
                    onJson(obj)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onAudio(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failHandshake(t)
                    onDisconnected(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    failHandshake(DisconnectedException(null))
                    onDisconnected(null)
                }
            },
        )
    }

    /**
     * 挂起直到收到服务端 hello（成功）或连接失败（异常）。
     *
     * ⚠️ **必须带超时**（实测踩坑）：TCP/TLS 由 connectTimeout 保护，
     *    但 WS 升级响应的等待归 **readTimeout** 管 —— SHARED_CLIENT 的
     *    readTimeout=0（对话必需）意味着升级请求被中间设备吞掉/服务端
     *    不回时，OkHttp **永远不回调 onOpen 也不 onFailure**，协程无限
     *    挂在这里，UI 永远「正在连接」且 logcat 零日志（线程 dump 才抓到）。
     *    服务端对 hello 的响应是秒级的，15s 宽绰有余。
     */
    suspend fun awaitServerHello(): ServerAudio =
        try {
            withTimeout(HANDSHAKE_TIMEOUT_MS) { handshake.await() }
        } catch (e: TimeoutCancellationException) {
            // 必须转译：TimeoutCancellationException 是 CancellationException 子类，
            // 原样抛出去会穿透 sessionLoop 的 catch(Exception)，把整个会话协程取消掉
            throw IOException("连接小智云端超时：${HANDSHAKE_TIMEOUT_MS / 1000} 秒没收到服务端握手响应")
        }

    private fun failHandshake(t: Throwable) {
        if (!handshake.isCompleted) handshake.completeExceptionally(t)
    }

    private fun parseServerAudio(obj: JsonObject): ServerAudio {
        val audio = obj["audio_params"]?.jsonObject
        return ServerAudio(
            sampleRate = audio?.get("sample_rate")?.jsonPrimitive?.int ?: 24_000,
            frameMs = audio?.get("frame_duration")?.jsonPrimitive?.int ?: 60,
        )
    }

    class DisconnectedException(cause: Throwable?) :
        Exception(cause?.message ?: "websocket closed", cause)

    private companion object {
        /**
         * 共享 client：每次重连都 new 一个 OkHttpClient 会留下
         * 关不掉的连接池/调度线程。WS 长连接全局就一条，共享最干净。
         *
         * ⚠️ **不能开 pingInterval**：小智服务端不回 pong（实测），
         *    OkHttp 发出 ping 等 20s 等不到就把活连接掐死 —— 循环往复
         *    「连上→20s→断」，面板永远显示连接中。应用层保活靠
         *    对话帧自身（录音/音频持续流动）。
         *
         * ⚠️ 但「空闲被 NAT 静默掐掉」这个盲区**是真实存在的**：`readTimeout(0)`
         *    + 不开 ping 的代价就是——TCP 被中间设备回收时，客户端既收不到
         *    onFailure 也收不到 onClosed，`closed.await()` 永不返回、重连永不触发。
         *    **兜底不在这里，在 `XiaozhiClient.stallWatchdog`**（通话中长时间
         *    完全没有下行 → 判定假死 → 主动 close 触发重连）。
         *    所以改这里之前先去看那个看门狗，别把唯一的自愈路径删掉。
         */
        val SHARED_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WS 长连接：永不读超时（SSE 的 LiveSync 同款坑）
            .build()

        /** 服务端 hello 等待上限：见 [awaitServerHello] 注释 */
        const val HANDSHAKE_TIMEOUT_MS = 15_000L
    }
}
