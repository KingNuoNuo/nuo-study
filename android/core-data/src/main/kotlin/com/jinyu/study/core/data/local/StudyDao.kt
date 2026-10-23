package com.jinyu.study.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyDao {

    // ---------------- 作业单 ----------------
    @Query("SELECT * FROM local_assignment WHERE status >= 1 ORDER BY assignDate DESC, id DESC")
    fun observePublishedAssignments(): Flow<List<LocalAssignment>>

    @Query("SELECT * FROM local_assignment WHERE assignDate = :date AND status >= 1 ORDER BY id")
    fun observeAssignmentsOn(date: String): Flow<List<LocalAssignment>>

    /**
     * 本地**已发布**作业单出现过的日期，从新到旧。
     *
     * 只取日期而不是整行：孩子端首页要判断「今天有没有作业」，没有就退回最近一次。
     * 拿整行的话每插一条作业单就要重新组合一次列表；只要日期，变化频率低得多，
     * 而且 `DISTINCT` 让「同一天新增第二条作业单」不会触发上游重新订阅。
     *
     * 排序必须是 DESC —— 调用方取 `first()` 就是「最近一次作业」的日期。
     */
    @Query(
        """
        SELECT DISTINCT assignDate FROM local_assignment
        WHERE status >= 1
        ORDER BY assignDate DESC
        """
    )
    fun observePublishedDates(): Flow<List<String>>

    @Query("SELECT * FROM local_item WHERE assignmentId = :assignmentId ORDER BY seq")
    fun observeItems(assignmentId: Int): Flow<List<LocalItem>>

    /** 拉一次服务端数据后的整体落盘。整单替换，避免残留已删除的作业项。 */
    @Transaction
    suspend fun replaceAssignment(a: LocalAssignment, items: List<LocalItem>) {
        deleteItemsOf(a.id)
        insertAssignment(a)
        insertItems(items)
    }

    /**
     * 一次刷新拉到**多条**作业单时的整批落盘。
     *
     * ⚠️ 别改回「在 Repository 里 forEach 调 [replaceAssignment]」。
     *    那等同于 N 个独立事务：room 的默认 journal 是 WAL，**每个事务结尾
     *    都要 fsync 一次** —— 60 条作业单就是 60 次刷盘，在低端平板上
     *    `repo.refresh()` 能跑出明显卡顿（它还在 SSE 的热路径上）。
     *    合成一个事务后只有一次提交、一次 fsync。
     */
    @Transaction
    suspend fun replaceAll(bundles: List<AssignmentBundle>) {
        bundles.forEach { b ->
            deleteItemsOf(b.assignment.id)
            insertAssignment(b.assignment)
            insertItems(b.items)
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssignment(a: LocalAssignment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<LocalItem>)

    @Query("DELETE FROM local_item WHERE assignmentId = :assignmentId")
    suspend fun deleteItemsOf(assignmentId: Int)

    @Query("DELETE FROM local_assignment WHERE assignDate < :beforeDate")
    suspend fun purgeAssignmentsBefore(beforeDate: String)

    // ---------------- 跟服务端对齐（删掉服务端已经不存在的） ----------------
    /**
     * 服务端成功返回「一条已发布作业都没有」时的全量清空。
     *
     * 只在无 date 的全量刷新成功拿到空列表时使用：那表示家长端已经把所有
     * 会给孩子看到的作业都删光了，孩子端必须跟着清空。这里不用
     * `deletePublishedNotIn(emptyList())`，因为 Room 会把它编译成空集合绑定，
     * SQLite 的 `NOT IN` 没有空占位符可绑，运行期会炸。
     */
    @Query("DELETE FROM local_assignment WHERE status >= 1")
    suspend fun deleteAllPublished()

    /**
     * 删掉服务端已经没有的已发布作业单。
     *
     * ⚠️ 为什么必须有这一步：refresh() 是 upsert 语义 —— 它只会插入/覆盖**服务端返回的**
     *    那些作业单，服务端**删掉的**本地一条都不会少。家长在历史作业里删掉一条，
     *    孩子端就会永远显示它（直到 purgeOld 按日期扫掉，那要 30 天）。
     *    这个 bug 在只有「发布」没有「删除」时看不出来。
     *
     * ⚠️ 只在「本次一定拉全了」时才能调用（判据见 AssignmentRepository.dropLocallyMissing）：
     *    服务端不带 date、返回条数又没到 LIMIT，说明**全部**已发布作业单都在返回里，
     *    此时本地任何不在 `keepIds` 里的已发布单都是被删了的 —— **不论日期多旧**。
     *    （2026-10-09 踩过：早先版本按「assignDate >= 最旧一天」收区间，结果家长删掉
     *    一条比列表里最旧记录还旧的作业时，孩子端永远删不掉它。）
     */
    @Query(
        """
        DELETE FROM local_assignment
        WHERE status >= 1 AND id NOT IN (:keepIds)
        """
    )
    suspend fun deletePublishedNotIn(keepIds: List<Int>)

    /**
     * 同上，但只覆盖**严格晚于** `afterDate` 的日期。
     *
     * 用在「服务端返回条数正好等于 LIMIT」的时候：这是**分页截断**，最旧的那一天
     * 可能只返回了一半，那一天里没返回的作业单不能当成被删了 —— 只对齐更晚的区间。
     */
    @Query(
        """
        DELETE FROM local_assignment
        WHERE status >= 1 AND assignDate > :afterDate AND id NOT IN (:keepIds)
        """
    )
    suspend fun deletePublishedNotInAfter(afterDate: String, keepIds: List<Int>)

    /**
     * 清掉没有归属的作业项。
     *
     * 作业单被删掉后，`local_item` 的行不会自动消失（Room 的 @Relation / 手写 join
     * 都不会替你级联）。留下来的孤儿项有两个后果：本地库越积越大；
     * 万一将来用 `SELECT * FROM local_item WHERE assignmentId=?` 而 id 被复用了，
     * 就会串到别的作业单上。删除后立刻清一次，代价是一条 SQL。
     */
    @Query("DELETE FROM local_item WHERE assignmentId NOT IN (SELECT id FROM local_assignment)")
    suspend fun deleteOrphanItems(): Int

    // ---------------- 待同步队列 ----------------
    /**
     * 取一批待传作答。**不按时间严格 FIFO** —— 同一道后期的题先传上去没坏处，
     * 但一道卡的题会拖住后面所有题，所以失败多了就排到队尾（见 order by）。
     */
    @Query(
        """
        SELECT * FROM answer_outbox
        WHERE state = 0
        ORDER BY retryCount ASC, createdAt ASC
        LIMIT :limit
        """
    )
    suspend fun nextBatch(limit: Int = 20): List<AnswerOutbox>

    @Query("SELECT COUNT(*) FROM answer_outbox WHERE state = 0")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM answer_outbox WHERE state = 3")
    fun observeFailedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(outbox: AnswerOutbox)

    @Query("UPDATE answer_outbox SET state = :state, lastError = :error WHERE clientReqId = :id")
    suspend fun markState(id: String, state: Int, error: String? = null)

    @Query("UPDATE answer_outbox SET retryCount = retryCount + 1, state = 0, lastError = :error WHERE clientReqId = :id")
    suspend fun bumpRetry(id: String, error: String?)

    /** 已被服务端确认的记录保留一段时间再清，便于排查「到底传没传成功」。 */
    @Query("DELETE FROM answer_outbox WHERE state = 2 AND createdAt < :before")
    suspend fun purgeConfirmedBefore(before: String)

    // ---------------- 媒体 ----------------
    @Insert
    suspend fun insertMedia(media: LocalMedia): Long

    @Query("SELECT * FROM local_media WHERE clientReqId = :clientReqId AND uploaded = 0")
    suspend fun pendingMedia(clientReqId: String): List<LocalMedia>

    @Query("UPDATE local_media SET uploaded = 1, remoteKey = :remoteKey WHERE id = :id")
    suspend fun markMediaUploaded(id: Long, remoteKey: String)

    // ---------------- 知识点 ----------------
    @Query("SELECT * FROM local_kp WHERE subject = :subject ORDER BY id")
    fun observeKnowledgePoints(subject: Int): Flow<List<LocalKnowledgePoint>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKnowledgePoints(list: List<LocalKnowledgePoint>)

    // ---------------- 练习 ----------------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPractice(p: LocalPractice)

    @Query("SELECT * FROM local_practice WHERE sessionId = :sessionId")
    suspend fun practice(sessionId: Int): LocalPractice?

    @Query("SELECT * FROM local_practice WHERE finishedAt IS NULL ORDER BY startedAt DESC")
    suspend fun unfinishedPractices(): List<LocalPractice>
}

/**
 * 一次落盘用的「作业单 + 它的作业项」。
 *
 * 单独定义一个类型而不是塞 `Pair`：Room 的 `@Transaction` 默认方法在 KSP 里
 * 要能解析出参数类型，`Pair<A, B>` 这种泛型在部分版本上会生成不出来的桥接方法。
 */
data class AssignmentBundle(
    val assignment: LocalAssignment,
    val items: List<LocalItem>,
)
