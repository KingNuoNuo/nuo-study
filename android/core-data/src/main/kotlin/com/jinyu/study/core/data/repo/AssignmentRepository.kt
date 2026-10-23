package com.jinyu.study.core.data.repo

import com.jinyu.study.core.data.local.AssignmentBundle
import com.jinyu.study.core.data.local.LocalAssignment
import com.jinyu.study.core.data.local.LocalItem
import com.jinyu.study.core.data.local.StudyDao
import com.jinyu.study.core.network.StudyApi
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 作业单仓储。**唯一的读路径是本地库**，网络只负责往里灌数据。
 *
 * 这是「离线优先」在代码层面的体现，不是口号：
 *  - UI 永远订阅 [observeAssignmentsOn]，从不直接等网络返回；
 *  - [refresh] 是后台行为，失败了就失败了，界面照常显示缓存内容；
 *  - 孩子端在没网的情况下也能打开今天的作业 —— 因为它读的是 Room，不是 API。
 */
@Singleton
class AssignmentRepository @Inject constructor(
    private val api: StudyApi,
    private val dao: StudyDao,
) {

    fun observeAssignmentsOn(date: String): Flow<List<LocalAssignment>> =
        dao.observeAssignmentsOn(date)

    /**
     * 本地已发布作业单出现过的日期，从新到旧。
     *
     * 首页的「今日 → 否则最近一次」这条回退规则就建在它上面：
     * 日期列表里含今天就用今天，否则取 [List.first]（＝最新的那天）。
     */
    fun observePublishedDates(): Flow<List<String>> =
        dao.observePublishedDates()

    fun observeItems(assignmentId: Int): Flow<List<LocalItem>> =
        dao.observeItems(assignmentId)

    /**
     * 从服务端拉取并落本地。
     *
     * ⚠️ 只拉 status>=1（已发布）。草稿是家长端的活，孩子端**不该看到草稿** ——
     *    否则家长还没确认，孩子就已经照着识别错的题做上了。
     *
     * ⚠️ [date] **默认不传**（拉最近若干条，覆盖多天）。这一点是刻意的，别改回
     *    「只拉今天」：服务端在没有 date 时按 `assign_date DESC, id DESC` 返回，
     *    于是本地自然就有了「今天 + 最近几天」，首页才有得可退回。
     *    只拉今天的话，周末/放假打开 App 时本地永远是空的，退无可退。
     *    代价是每次多传几条作业单 —— 单家庭一天几条，可以忽略。
     */
    suspend fun refresh(date: String? = null): Result<Unit> = runCatching {
        val now = Instant.now().toString()
        val list = api.assignments(date = date, status = 1, limit = ASSIGNMENT_LIMIT)
            .requireData()
        // ⚠️ 走 replaceAll 一次性落盘（单个事务），不是 forEach 调 replaceAssignment。
        //    后者 = N 个事务 = N 次 WAL fsync；这个方法挂在 SSE 事件的热路径上，
        //    60 条作业单的差别在低端机上是肉眼可见的。对齐逻辑见 dropLocallyMissing。
        val bundles = list.map { dto ->
            AssignmentBundle(
                assignment = LocalAssignment(
                    id = dto.id,
                    assignDate = dto.assignDate,
                    title = dto.title,
                    subject = dto.subject,
                    needSubmit = dto.needSubmit,
                    status = dto.status,
                    cachedAt = now,
                ),
                items = dto.items.map { it.toLocal(dto.id) },
            )
        }
        dao.replaceAll(bundles)
        dropLocallyMissing(list, date)
    }

    /**
     * 把「服务端已经删掉、本地还留着」的已发布作业单清掉。
     *
     * ⚠️ 这一步不能省。上面的循环是 upsert：它只会写服务端**返回了**的作业单，
     *    服务端**删掉**的本地一条都不会少 —— 家长在历史作业里删掉一条，
     *    孩子端就会永远显示它（直到 purgeOld 按日期扫掉，那要 30 天）。
     *
     * 对齐规则：
     *  - `date == null` 且返回**空列表**：服务端已经没有任何已发布作业
     *    （2026-10-09 踩过：家长把作业全删光后孩子端还挂着最后一条，就是这里
     *    误当了「空列表保护」跳过对齐）。能走到这里说明 HTTP 已成功 ——
     *    网络异常在 runCatching 里就直接中断了，空列表是服务端的真实状态，
     *    必须跟着清空全部已发布作业。
     *  - `date == null` 且返回条数 < LIMIT：未分页，服务端返回全部已发布作业，
     *    本地不在 keep 中的全部删除，不论日期多旧。
     *  - `date == null` 且返回条数 == LIMIT：列表被截断，只对齐严格晚于最旧日期的区间。
     *  - 有 date（定向刷新）：只覆盖单日，不做删单对齐；空列表也不能清理 ——
     *    「某一天没有作业」不代表「哪一天都没有」。
     */
    private suspend fun dropLocallyMissing(
        pulled: List<com.jinyu.study.core.network.AssignmentDto>,
        date: String?,
    ) {
        if (date != null) return
        if (pulled.isEmpty()) {
            dao.deleteAllPublished()
            dao.deleteOrphanItems()
            return
        }
        val keep = pulled.map { it.id }
        if (pulled.size < ASSIGNMENT_LIMIT) {
            dao.deletePublishedNotIn(keep)
        } else {
            dao.deletePublishedNotInAfter(pulled.minOf { it.assignDate }, keep)
        }
        dao.deleteOrphanItems()
    }

    /** 清掉过期的本地缓存（默认留最近 30 天）。不联网也能跑。 */
    suspend fun purgeOld(days: Int = 30) {
        val before = java.time.LocalDate.now().minusDays(days.toLong()).toString()
        dao.purgeAssignmentsBefore(before)
    }

    private companion object {
        /** 一次拉多少条。一天最多几条，60 条足够覆盖「最近一次」还有富余。 */
        const val ASSIGNMENT_LIMIT = 60
    }
}

private fun com.jinyu.study.core.network.AssignmentItemDto.toLocal(assignmentId: Int) = LocalItem(
    id = id,
    assignmentId = assignmentId,
    seq = seq,
    subject = subject,
    itemType = itemType,
    title = title,
    requirement = requirement,
    answerMode = answerMode,
    questionId = questionId,
    status = status,
)
