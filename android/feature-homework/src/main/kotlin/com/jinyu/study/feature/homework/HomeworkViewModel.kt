package com.jinyu.study.feature.homework

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jinyu.study.core.data.local.LocalAssignment
import com.jinyu.study.core.data.local.LocalItem
import com.jinyu.study.core.data.repo.AssignmentRepository
import com.jinyu.study.core.network.ApiException
import com.jinyu.study.core.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * 今日作业屏幕的状态。
 *
 * ⚠️ 数据来源只有一个：**本地 Room**（通过 [AssignmentRepository]）。
 *    界面从不直接等网络返回 —— 这是「离线优先」在代码里的样子，
 *    也是孩子能断网打开作业的前提。网络只负责在后台把本地库喂饱。
 *
 * 展示规则（2026-10-09 靖哥哥要求）：
 *   **今天有作业就显示今天的；今天没有，就显示最近一次上传的那份。**
 *   周末、假期、老师还没发作业的时候，孩子打开 App 不该只看到一句话 ——
 *   他更可能需要回看昨天那份（比如昨天的默写还没做）。
 *   退回来的时候界面上会**明确写清这是哪一天的**，不能让人误以为是今天的。
 */
@HiltViewModel
class HomeworkViewModel @Inject constructor(
    private val repo: AssignmentRepository,
    private val sync: SyncScheduler,
) : ViewModel() {

    /**
     * 「今天」不是一个固定值 —— 它必须**随时间流动**。
     *
     * ⚠️ 曾经的 bug（2026-10-10 修复）：把 `today` 写成 ViewModel 的 `val`，
     *    只在构造时算一次。App 跨过午夜后（进程没被杀、后台挂了一夜），
     *    `today` 仍是昨天 → `dates.contains(today)` 恒真 → 标题永远是「今日作业」，
     *    昨天那份作业第二天还顶着「今日作业」的名头。重启 App 才正常。
     *
     * 修法是把「当前日期」变成一条冷 Flow：
     *  - 每次订阅时先发一次 `LocalDate.now()` —— 覆盖「后台过夜后回到前台」；
     *  - 之后睡到下一个零点再发一次 —— 覆盖「前台一直开着跨过午夜」。
     * 冷流在重新订阅时会从头执行，所以界面回到前台那一刻就能拿到新日期。
     */
    private fun currentDayFlow(): Flow<String> = flow {
        while (true) {
            val now = LocalDate.now()
            emit(now.toString())
            val nextMidnight = now.plusDays(1).atStartOfDay()
            val waitMs = Duration.between(LocalDateTime.now(), nextMidnight).toMillis()
            // +1s 余量：确保醒来时确实已越过零点，而不是卡在 23:59:59.9xx
            delay(waitMs.coerceAtLeast(1_000L) + 1_000L)
        }
    }

    /** 选中的展示日 + 当前真实「今天」。两个一起往下传，`isToday` 才算得准。 */
    private data class DayContext(val date: String?, val today: String)

    data class AssignmentWithItems(
        val assignment: LocalAssignment,
        val items: List<LocalItem>,
    )

    /**
     * 屏幕要显示的全部内容。
     *
     * [date] 是**实际在展示的那一天**，不一定是今天 —— 界面的措辞全看 [isToday]，
     * 别拿 `today` 去当标题（退回模式下会显示成「今日作业 · 10-08」，自相矛盾）。
     */
    data class HomeworkState(
        val date: String,
        val isToday: Boolean,
        val entries: List<AssignmentWithItems>,
    )

    var refreshing by mutableStateOf(false)
        private set

    /** 手动刷新的结果提示。用大白话，因为孩子也可能点这个按钮。 */
    var message by mutableStateOf<String?>(null)
        private set

    /**
     * 作业单 + 各自的作业项。
     *
     * 四层 Flow 叠起来，每一层都有它的理由：
     *
     * 0. `currentDayFlow()` → **当前真实日期**（订阅时重算 + 零点自动更新）。
     *    与下面的日期列表 `combine`：今天有作业就用今天，否则用最近一次。
     *    ⚠️ 必须用这条流，不能再用构造时算死的 `today` —— 那就是「隔天还显示今日作业」
     *    的根源（见 [currentDayFlow]）。
     *
     * 1. `observePublishedDates()` → **决定看哪一天**。含今天就用今天，否则用最新的那天
     *    （查询已按日期倒序）。`distinctUntilChanged()` 不能省：日期没变时不要
     *    重复订阅下面的查询，否则每天新增第二条作业单都会把整条链重建一遍。
     *
     * 2. `observeAssignmentsOn(date)` → 那一天的作业单。
     *
     * 3. `observeItems(id)` 逐单订阅 → 作业项。`flatMapLatest` + `combine` 是必须的：
     *    作业单和作业项是两张表、两个 Flow，而作业项的数量在拿到数据前未知。
     *    把 items 塞进 assignment 表会变成一对多扁平化，读写都别扭。
     *
     * `WhileSubscribed(5000)`：屏幕旋转/短暂切走时不重建查询，
     * 超过 5 秒没人订阅才停 —— 省电，也避免每次回到首页都闪一下加载态。
     * （冷订阅重启时 [currentDayFlow] 会立刻重算日期，正好覆盖「后台过夜回前台」。）
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HomeworkState> =
        combine(
            currentDayFlow(),
            repo.observePublishedDates(),
        ) { today, dates ->
            DayContext(
                date = if (dates.contains(today)) today else dates.firstOrNull(),
                today = today,
            )
        }
            .distinctUntilChanged()
            .flatMapLatest { ctx ->
                // 从未同步过（新装 / 清过数据）时本地一条都没有，退回态也是空的。
                if (ctx.date == null) {
                    flowOf(HomeworkState(ctx.today, isToday = true, emptyList()))
                } else {
                    repo.observeAssignmentsOn(ctx.date)
                        .flatMapLatest { list ->
                            if (list.isEmpty()) {
                                // 显式给类型参数：不写的话 emptyList() 会推断成 List<Nothing>，
                                // 与另一个分支的 List<AssignmentWithItems> 求交集，
                                // 结果可能是 Flow<List<Nothing>> —— 编译期看不出来，运行期类型不对。
                                flowOf(HomeworkState(ctx.date, ctx.date == ctx.today, emptyList()))
                            } else {
                                combine(
                                    list.map { a ->
                                        repo.observeItems(a.id)
                                            .map { items -> AssignmentWithItems(a, items) }
                                    }
                                ) { arr -> arr.toList() }
                                    .map { HomeworkState(ctx.date, ctx.date == ctx.today, it) }
                            }
                        }
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyState(),
            )

    private fun emptyState() =
        HomeworkState(LocalDate.now().toString(), isToday = true, entries = emptyList())

    init {
        // 冷启动先催一次后台同步。孩子打开 App 到真正坐下来做题之间有几十秒，
        // 足够把最近的作业拉下来 —— 他翻到这一页时通常已经就绪了。
        sync.syncNow()
    }

    /**
     * 手动刷新：**直接拉一次**，不走 WorkManager。
     *
     * 为什么手动刷新要绕过后台任务：WorkManager 有自己的调度延迟（约束、退避），
     * 孩子点了「刷新」却要等几十秒才动，他会以为坏了、然后一直点。
     * 后台任务仍保留，负责「没人操作时的兜底」。
     */
    fun refresh() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            message = null
            val result = repo.refresh()
            refreshing = false
            message = result.fold(
                onSuccess = { "已更新。" },
                onFailure = { describe(it) },
            )
            if (result.isSuccess) repo.purgeOld()
        }
    }

    private fun describe(e: Throwable): String = when {
        e is UnknownHostException -> "找不到服务器，检查一下 Wi-Fi。"

        e is ConnectException -> "连不上服务器，等一下再试。"

        e is SocketTimeoutException -> "网络有点慢，等会儿再试。"

        e is ApiException -> when {
            e.isAuthFailure -> "这台设备的令牌不对，让家长重新设置一下。"
            e.code == 0 -> "还没配置服务器地址，让家长在平板上点右上角齿轮设置一下。"
            else -> "服务端说：${e.message}"
        }

        else -> e.message ?: e::class.java.simpleName
    }
}
