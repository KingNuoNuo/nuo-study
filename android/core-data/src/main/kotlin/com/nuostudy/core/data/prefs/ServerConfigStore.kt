package com.nuostudy.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuostudy.core.network.MirrorSource
import com.nuostudy.core.network.ServerEndpoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.serverConfigStore: DataStore<Preferences> by preferencesDataStore("server_config")

private object Keys {
    val BASE_URL = stringPreferencesKey("base_url")
    val FAMILY_TOKEN = stringPreferencesKey("family_token")
    val DEVICE_ID = stringPreferencesKey("device_id")

    /**
     * 学生端「实时画面」镜像开关。
     *
     * ⚠️ 这一个**要用布尔型**，不能跟前面两项一样用「空串＝没填」那套：
     *    它的有效取值只有 true/false，没有「空」这个第三态，
     *    用空串表示「没设过」会让「家长主动关掉」和「从没设过」长得一模一样。
     *    所以它走单独的 [mirrorEnabled] / [setMirrorEnabled]，不参与 Snapshot 回落。
     */
    val MIRROR_ENABLED = booleanPreferencesKey("mirror_enabled")

    /**
     * 家长端「实时画面」看哪条采集通道（[MirrorSource.APP] / [MirrorSource.ADB]）。
     *
     * 用字符串存**通道名本身**：加第三条通道时既不用改存储格式，也不用写迁移。
     * 认不出来的值由 [MirrorSource.orDefault] 兜底，所以存坏了也只是回到默认。
     */
    val MIRROR_SOURCE = stringPreferencesKey("mirror_source")
}

/**
 * [ServerEndpoint] 的实现：服务端地址 + 家庭令牌 + 设备标识。
 *
 * ⚠️ 这里有个必须处理的矛盾：OkHttp 的拦截器是**同步**调用，而 DataStore 是**异步**的
 *    （设计上就不给你同步读）。所以启动时 `runBlocking` 预热一次进内存，
 *    之后所有读都走 [snapshot]，不再碰磁盘。
 *
 *    代价是：改配置后必须手动刷新 snapshot（见 [update]）——
 *    如果将来有人绕过 [update] 直接写 DataStore，拦截器会一直用旧地址。
 *    这是有意为之的取舍：把「改配置」收在一个入口里，比让每个请求都去读磁盘划算。
 */
@Singleton
class ServerConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : ServerEndpoint {

    private data class Snapshot(
        val baseUrl: String?,
        val token: String?,
        val deviceId: String,
        /** 地址或令牌至少有一项是回落到 [ServerDefaults] 得来的（而不是家长存的）。 */
        val fromDefaults: Boolean,
    )

    @Volatile
    private var snapshot: Snapshot = Snapshot(null, null, "", false)

    init {
        snapshot = runBlocking { load() }
    }

    private suspend fun load(): Snapshot {
        val p = context.serverConfigStore.data.first()
        var deviceId = p[Keys.DEVICE_ID]
        if (deviceId.isNullOrBlank()) {
            // 设备标识在首次启动时生成一次并固定。服务端用它区分孩子平板与家长手机。
            deviceId = "dev-" + UUID.randomUUID().toString().replace("-", "").take(12)
            context.serverConfigStore.edit { it[Keys.DEVICE_ID] = deviceId }
        }

        // 测试期内置默认值：存了就以存储为准，没存（或存成空串）才回落。
        // ⚠️ 回落**不写回 DataStore** —— 写了的话「清空输入框」就再也回不到内置值，
        //    而「清空 = 恢复默认」是家长唯一的退路（他没有别的办法把配置还原）。
        val storedUrl = p[Keys.BASE_URL]?.takeIf { it.isNotBlank() }
        val storedToken = p[Keys.FAMILY_TOKEN]?.takeIf { it.isNotBlank() }
        val defUrl = builtinBaseUrl()
        val defToken = builtinFamilyToken()

        return Snapshot(
            baseUrl = storedUrl ?: defUrl,
            token = storedToken ?: defToken,
            deviceId = deviceId,
            fromDefaults = (storedUrl == null && defUrl != null) ||
                (storedToken == null && defToken != null),
        )
    }

    override fun baseUrl(): String? = snapshot.baseUrl

    override fun familyToken(): String? = snapshot.token

    override fun deviceId(): String = snapshot.deviceId.ifBlank { "dev-uninitialized" }

    /** 两项都填了才算配置完成，家长端据此决定是停在设置页还是进主页。 */
    val isConfigured: Boolean
        get() = !snapshot.baseUrl.isNullOrBlank() && !snapshot.token.isNullOrBlank()

    /**
     * 当前生效的配置里至少有一项来自内置默认值（见 [ServerDefaults]）。
     * 设置页用它给家长一句提示 —— 否则家长会以为「这地址是我以前填的」，
     * 排查时会去翻自己的记忆而不是代码。
     */
    val usingDefaults: Boolean get() = snapshot.fromDefaults

    /**
     * 学生端「实时画面」镜像开关（见 [ServerDefaults.MIRROR_ENABLED]）。
     *
     * 用 Flow 而不是像 baseUrl/token 那样缓存进 [snapshot]：抓帧循环本来就是
     * 协程，`collectLatest` 到这个 Flow 上天然就能「家长一关，循环立刻停」。
     * 而 baseUrl/token 之所以要缓存，是因为 OkHttp 拦截器是同步的 —— 这里没那个约束。
     */
    val mirrorEnabled: Flow<Boolean> =
        context.serverConfigStore.data
            .map { it[Keys.MIRROR_ENABLED] ?: ServerDefaults.MIRROR_ENABLED }

    suspend fun setMirrorEnabled(on: Boolean) {
        context.serverConfigStore.edit { it[Keys.MIRROR_ENABLED] = on }
    }

    /**
     * 家长端看画面时选的那条通道（[MirrorSource]）。
     *
     * 跟 [mirrorEnabled] 一样走 Flow 而不是缓存进 [snapshot]：它只在
     * 家长端「画面」页里用，那里的轮询循环本来就是协程，`collect` 一下最省事。
     */
    val mirrorSource: Flow<String> =
        context.serverConfigStore.data
            .map { MirrorSource.orDefault(it[Keys.MIRROR_SOURCE]) }

    suspend fun setMirrorSource(source: String) {
        context.serverConfigStore.edit {
            it[Keys.MIRROR_SOURCE] = MirrorSource.orDefault(source)
        }
    }

    /**
     * 写入配置。
     *
     * ⚠️ **与内置值相同的输入不落盘**（写成空串 ＝ 「没填」），这样 [ServerDefaults]
     *    在测试期始终是权威的。不加这条会踩一个很难查的坑：
     *    设置页的「测试连接」会先保存（见 `ServerSettingsViewModel.test`），
     *    于是预填的内置值被写进 DataStore；之后改 `ServerDefaults.FAMILY_TOKEN` 重新编译，
     *    **存储里的旧值会把新常量压住** → 设备一直用旧令牌报 401，而常量看起来完全正确。
     *    输入框上看到的值两种情况一模一样，所以人不可能从界面上发现。
     *
     * ⚠️ 传**空串**等价于「清除该项」→ 该项退回 [ServerDefaults] 内置值。
     *    也就是说保存空值是「恢复默认」而不是「清空」，这一点要跟 UI 说法一致。
     */
    suspend fun update(baseUrl: String? = null, familyToken: String? = null) {
        val defUrl = builtinBaseUrl()
        val defToken = builtinFamilyToken()
        context.serverConfigStore.edit { prefs ->
            if (baseUrl != null) {
                prefs[Keys.BASE_URL] = normalizeBaseUrl(baseUrl).takeIf { it != defUrl }.orEmpty()
            }
            if (familyToken != null) {
                prefs[Keys.FAMILY_TOKEN] = familyToken.trim().takeIf { it != defToken }.orEmpty()
            }
        }
        snapshot = load()
    }

    companion object {
        /**
         * 内置测试地址（**已补全**成 `normalizeBaseUrl` 的规范形式）。
         *
         * 补全是必须的：内置值会绕过设置页的输入框直接进入 OkHttp 的 `baseUrl`，
         * 拿不到 `normalizeBaseUrl` 的「补 http:// 与结尾斜杠」。
         * 而且 `update()` 要靠**字符串相等**判断「这是不是内置值」，
         * 两边形式不一致就永远判不相等，那条不落盘的规则会静默失效。
         *
         * [ServerDefaults.ENABLED] 关掉或常量为空时返回 null＝没有内置值。
         */
        internal fun builtinBaseUrl(): String? =
            ServerDefaults.BASE_URL
                .takeIf { ServerDefaults.ENABLED && it.isNotBlank() }
                ?.let { normalizeBaseUrl(it) }

        internal fun builtinFamilyToken(): String? =
            ServerDefaults.FAMILY_TOKEN.takeIf { ServerDefaults.ENABLED && it.isNotBlank() }

        /**
         * 把家长随手输的半截地址补全。
         *
         * ⚠️ 这一步不能省：真实场景里没人会输 `http://192.168.1.10:8000/`，
         *    大家输的是 `192.168.1.10:8000` 甚至 `192.168.1.10`。
         *    而 Retrofit 对不合法 URL 是直接抛异常的 —— 在设置页拦住，
         *    比等到孩子做题时报「网络错误」好得多。
         *    结尾斜杠也必须补：Retrofit 的 baseUrl 少了它，路径拼接会吃掉最后一段。
         */
        fun normalizeBaseUrl(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return ""
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            if (!s.endsWith("/")) s = "$s/"
            return s
        }
    }
}
