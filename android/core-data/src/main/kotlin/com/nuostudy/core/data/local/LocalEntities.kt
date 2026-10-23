package com.nuostudy.core.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// =============================================================================
// 孩子端的**本地缓存库**（Room）。只 6 张表，别照抄服务端那 18 张。
//
// 与服务端主库的关系：
//   服务端 = 唯一事实来源（文件 data/app.db）
//   孩子端 = 缓存 + 待同步队列。断网时它必须能独立完成「看作业 → 做题 → 保存」
//
// 主键策略：凡是服务端已有的实体，主键**直接用服务端的 id**（不做本地自增再映射）。
// 理由：离线补传时服务端要按 id 对账，自己造一套本地 id 只会多一层映射表和
// 一类别名 bug。真正需要本地生成的只有 outbox 的 clientReqId 和 media 的自增 id。
// =============================================================================

/** 作业单缓存。存「今日 + 近 7 日」，更早的清掉。 */
@Entity(tableName = "local_assignment")
data class LocalAssignment(
    @PrimaryKey val id: Int,
    val assignDate: String,
    val title: String?,
    val subject: Int,
    val needSubmit: Boolean,
    /** 0 草稿 / 1 已发布 / 2 已完成 / 3 已归档（同服务端） */
    val status: Int,
    val cachedAt: String,
)

/**
 * 作业项缓存。
 *
 * ⚠️ `answerMode` 是渲染的分流依据（0 打卡 / 1 自绘键盘 / 2 手写 / 3 录音 / 4 拍照）。
 *    缺了它，孩子端就不知道该给孩子一个输入框还是一块手写板。
 */
@Entity(
    tableName = "local_item",
    indices = [Index("assignmentId")],
)
data class LocalItem(
    @PrimaryKey val id: Int,
    val assignmentId: Int,
    val seq: Int,
    val subject: Int,
    val itemType: Int,
    val title: String,
    val requirement: String?,
    val answerMode: Int,
    val questionId: Int?,
    val status: Int,
)

/**
 * ⭐ 待同步队列 —— 整个离线能力的核心，也是唯一会「本地产生数据」的表。
 *
 * 提交按钮的语义是「已保存到本地」，不是「已上传」。所以每次作答**先写这张表**，
 * 再由 WorkManager 慢慢补传，按 clientReqId 幂等重放。
 *
 * 状态机：0 待传 → 1 传输中 → 2 已确认（可清）｜ 3 永久失败（需人工看）
 * ⚠️ 必须同时有 retryCount 与 lastError：没有上限的重试会把家里服务端打满；
 *    没有错误信息的话，家长只会看到「一直传不上去」而不知道原因。
 */
@Entity(
    tableName = "answer_outbox",
    indices = [Index("state"), Index("assignmentId")],
)
data class AnswerOutbox(
    /** 幂等键，客户端生成 UUID v4。服务端以它唯一约束，重复提交返回同一条记录。 */
    @PrimaryKey val clientReqId: String,
    val assignmentId: Int?,
    val itemId: Int?,
    val questionId: Int?,
    val answerText: String?,
    /** JSON 数组：本地媒体文件的相对路径，上传成功后回填 remoteKey */
    val mediaKeysJson: String?,
    val durationMs: Int?,
    val createdAt: String,
    val retryCount: Int = 0,
    val state: Int = STATE_PENDING,
    val lastError: String? = null,
) {
    companion object {
        const val STATE_PENDING = 0
        const val STATE_UPLOADING = 1
        const val STATE_CONFIRMED = 2
        const val STATE_FAILED = 3

        /** 重试上限。到顶后置为 FAILED，不再无限重试。 */
        const val MAX_RETRY = 5
    }
}

/**
 * 本地媒体（录音、手写图、拍照）。
 *
 * ⚠️ 先落本地文件、再上传，不要「上传成功才保存」——
 *    断网时那道题就没了。localPath 指向应用私有目录，卸载即清理。
 */
@Entity(
    tableName = "local_media",
    indices = [Index("clientReqId")],
)
data class LocalMedia(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientReqId: String,
    /** 1 录音 / 2 手写图片 / 3 拍照 */
    val kind: Int,
    val localPath: String,
    val remoteKey: String? = null,
    val uploaded: Boolean = false,
)

/** 知识点树缓存。离线时也要能展示「哪些知识点弱」，否则学习板块一断网就空白。 */
@Entity(
    tableName = "local_kp",
    indices = [Index("parentId"), Index("subject")],
)
data class LocalKnowledgePoint(
    @PrimaryKey val id: Int,
    val parentId: Int?,
    val subject: Int,
    val name: String,
    val code: String?,
    val mastery: Float?,
)

/**
 * 练习会话缓存。
 *
 * ⚠️ 服务端**不持久化题单**（schema 里没有 practice_item 表），题面由推荐算法随时重现。
 *    代价就是：孩子中途重启 App，题单得靠客户端自己存 —— 这张表就是干这个的。
 */
@Entity(tableName = "local_practice")
data class LocalPractice(
    @PrimaryKey val sessionId: Int,
    val subject: Int,
    val mode: Int,
    /** 题单快照（JSON）。题库更新后可能与新题面不一致，但孩子手上的这份必须稳定。 */
    val itemsJson: String,
    val startedAt: String,
    val finishedAt: String? = null,
)
