package com.nuostudy.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuostudy.core.data.prefs.ServerConfigStore
import com.nuostudy.core.network.ApiException
import com.nuostudy.core.network.StudyApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * 服务端设置页的状态机。孩子端与家长端共用同一份。
 *
 * 两件事要在这里做对：
 *
 * 1. **「测试连接」必须先落盘**。[com.nuostudy.core.network.DynamicUrlInterceptor]
 *    是从 [ServerConfigStore] 的**内存快照**读地址与令牌的（这是为了迁就 OkHttp
 *    的同步拦截器链路）。所以不先 `update()` 的话，测的还是旧地址 ——
 *    表现就是「明明改了地址，测试结果却没变」，非常难查。
 *    代价是「测试」会顺带保存，UI 上已经把这件事明确写给家长看。
 *
 * 2. **两步分开验，失败要说清是哪一步**。`/system/ping` 不需要鉴权，它只证明
 *    「地址填对了、服务确实在跑」；`/system/health` 要 Bearer 令牌，才证明
 *    「令牌对」。合成一步的话，家长拿到「连接失败」根本不知道是地址错还是令牌错，
 *    而这两种错的处理方式完全不同。
 */
@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
    private val store: ServerConfigStore,
    private val api: StudyApi,
) : ViewModel() {

    /** 连通性测试的结果。刻意做成密封类而不是「一个 Boolean + 一条消息」——
     *  「地址通但令牌错」是个真实存在、且必须单独表达的状态。 */
    sealed interface TestState {
        data object Idle : TestState
        data object Running : TestState

        /** 地址通了。回头看 [tokenOk] 才知道令牌验没验过。 */
        data class Reachable(
            val service: String,
            val version: String,
            val model: String?,
            val tokenOk: Boolean,
            val tokenError: String?,
        ) : TestState

        /** 地址这一步就没过。 [detail] 已经是给人看的话，不是异常类名。 */
        data class Unreachable(val detail: String) : TestState
    }

    var baseUrl by mutableStateOf(store.baseUrl().orEmpty())
        private set

    var token by mutableStateOf(store.familyToken().orEmpty())
        private set

    var state by mutableStateOf<TestState>(TestState.Idle)
        private set

    /** 保存成功/失败的一次性提示。用大白话。 */
    var hint by mutableStateOf<String?>(null)
        private set

    /**
     * 当前生效的配置里有几项来自**内置测试默认值**（见 `ServerDefaults`）。
     *
     * 做成 Compose 的 state 而不是直接转发 `store.usingDefaults`：
     * 后者是个普通 getter，读了不会触发重组 —— 家长保存完自定义地址后，
     * 那句「用的是内置配置」会一直挂在那儿，比不显示还糟。
     */
    var usingDefaults by mutableStateOf(store.usingDefaults)
        private set

    /** 服务端靠它区分「孩子平板」和「家长手机」。填错地址时把这个念给排查的人听很有用。 */
    val deviceId: String get() = store.deviceId()

    /** 两项都填了 = 可以正常联网。家长端用它决定要不要在建档时提示「还没配置」。 */
    val isConfigured: Boolean get() = store.isConfigured

    /**
     * 从存储回读，把表单重置成**当前实际生效**的配置。
     *
     * 为什么需要它：这个 VM 挂在 Activity 作用域上，会比设置页活得久。
     * 不重置的话，家长上次输了半截就退出去，下次进来看到的还是那半截草稿 ——
     * 而他以为看到的是「现在的配置」。配置页必须「所见即所得」。
     *
     * 由设置页在每次进入时调用（见 `ServerSettingsScreen` 的 LaunchedEffect）。
     */
    fun reloadFromStore() {
        baseUrl = store.baseUrl().orEmpty()
        token = store.familyToken().orEmpty()
        usingDefaults = store.usingDefaults
        state = TestState.Idle
        hint = null
    }

    fun onBaseUrlChange(value: String) {
        baseUrl = value
        state = TestState.Idle
        hint = null
    }

    fun onTokenChange(value: String) {
        token = value
        state = TestState.Idle
        hint = null
    }

    fun save() {
        viewModelScope.launch {
            // 刻意**不拦空值**：按 ServerConfigStore 的约定，空串表示「该项用内置默认值」。
            // 拦下来会让「清空 = 恢复默认」这条退路消失，而这个测试期里
            // 家长（=自己）最常做的操作恰恰是「把改坏的地址退回去」。
            store.update(baseUrl = baseUrl, familyToken = token)
            // 回读一次：normalizeBaseUrl 会给地址补 http:// 和结尾斜杠，
            // 把补全后的结果显示回去，家长下次才知道该填成什么样。
            baseUrl = store.baseUrl().orEmpty()
            token = store.familyToken().orEmpty()
            usingDefaults = store.usingDefaults
            hint = when {
                baseUrl.isBlank() && token.isBlank() -> "两项都空着，已恢复内置测试配置。"
                baseUrl.isBlank() || token.isBlank() -> "空着的那项会用内置测试配置。已保存。"
                else -> "已保存。"
            }
        }
    }

    fun test() {
        viewModelScope.launch {
            state = TestState.Running
            hint = null

            if (baseUrl.isBlank()) {
                state = TestState.Unreachable("先填服务端地址。")
                return@launch
            }

            // 见类注释第 1 条：不先落盘就测不到新地址。
            store.update(baseUrl = baseUrl, familyToken = token)
            // ⚠️ 令牌也要回读：输入框空着时，生效的是内置默认令牌，
            //    不回读就会走进下面「令牌没填，没验」的分支 —— 明明能用却报没验，
            //    而这一步的整个意义就是验证「现在真正生效的配置」能不能连上。
            baseUrl = store.baseUrl().orEmpty()
            token = store.familyToken().orEmpty()
            usingDefaults = store.usingDefaults

            // ---- 第一步：ping，验地址 ----
            val ping = try {
                api.ping().requireData()
            } catch (e: Exception) {
                state = TestState.Unreachable(describe(e))
                return@launch
            }

            // ---- 第二步：health，验令牌 ----
            var tokenError: String? = null
            if (token.isBlank()) {
                tokenError = "令牌没填，没验。"
            } else {
                try {
                    val h = api.health()
                    if (!h.isOk) tokenError = "服务端拒绝：${h.message}（code=${h.code}）"
                } catch (e: Exception) {
                    tokenError = describe(e)
                }
            }

            state = TestState.Reachable(
                service = ping.service,
                version = ping.version,
                model = ping.model,
                tokenOk = tokenError == null,
                tokenError = tokenError,
            )
        }
    }

    /**
     * 把异常翻成家长看得懂的一句话。
     *
     * 逐条枚举而不是直接 `e.message` —— 原始异常长这样
     * `java.net.ConnectException: failed to connect to /192.168.1.10:8092`，
     * 家长看了只会更慌，而且「该改什么」一个字都没说。
     *
     * 注意文案里不写「平板」或「手机」—— 两端共用这个方法，
     * 提设备名会让另一端的家长以为自己走错了入口。
     */
    private fun describe(e: Exception): String = when {
        e is UnknownHostException -> "地址解析不了，检查有没有写错。"

        e is ConnectException -> "连不上，确认服务在跑、端口对（家庭服务器用 8092）。"

        e is SocketTimeoutException -> "超时了，检查这台设备和服务器是否在同一个局域网。"

        e is HttpException -> when (e.code()) {
            401, 403 -> "服务在，但令牌被拒了（HTTP ${e.code()}）。"
            404 -> "服务在，但这个接口没找到（HTTP 404）—— 地址是不是多了/少了一段路径？"
            else -> "服务返回 HTTP ${e.code()}。"
        }

        e is ApiException -> when {
            e.isAuthFailure -> "令牌无效（401001），去服务端 .env 里核对 FAMILY_TOKEN。"
            // code=0 是 DynamicUrlInterceptor 在地址为空时抛的
            e.code == 0 -> "地址还没配置成功。"
            else -> e.message
        }

        else -> e.message ?: e::class.java.simpleName
    }
}
