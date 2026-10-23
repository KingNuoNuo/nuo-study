package com.nuostudy.feature.capture

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuostudy.core.network.ApiException
import com.nuostudy.core.network.AssignmentDto
import com.nuostudy.core.network.Envelope
import com.nuostudy.core.network.RecognitionJobDto
import com.nuostudy.core.network.StudyApi
import com.nuostudy.core.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * 作业录入 + 识别确认的状态机（家长端）。
 *
 * 流程：选图（**一次可勾选多张**）→ 逐张上传（各拿一个 jobId）→ 并行轮询 →
 * **合并确认** → 发布。
 *
 * ⚠️ 五条不能让步的设计约束：
 *
 * 1. **识别结果永远是草稿，家长不点发布，孩子端就看不到。**
 *    这是防错，不是合规 —— 识别错了，孩子就照着错的做一晚上。
 *    所以 [Stage.Review] 是必经状态，没有「自动发布」这条捷径。
 *
 * 2. **识别是异步的，等待必须可感知**。单张图实测 6–60 秒，多张时更长。
 *    不给进度和已等待秒数的话，家长会以为卡死，然后反复点上传 ——
 *    产生一堆重复 job（虽然服务端按 image_hash 去重，但体验是坏的）。
 *    2026-10-09 起进一步补上：**状态名中文化 + 服务端重试次数 + 下一步是重试还是重传**。
 *
 * 3. **失败必须能重试，而且要接着上次那一步重试。**
 *    [retry] 依据 [Resume] 决定是重传图片、继续轮询、重跑识别，还是重发发布指令。
 *    少了它，家长遇到一次网络抖动就得从头选图 —— 而图还在相册里翻半天。
 *    批量版再加一层：**断点续传** —— 传完的图不重传、识别完的图不再轮询，
 *    失败只影响出问题那一张。
 *
 * 4. **必须区分「这不是作业」和「没识别出来」**。
 *    QQ 群里混着学号反馈、通知、表情包。服务端的提示词允许模型输出
 *    `not_homework`，这里就要如实告诉家长「这张图不是作业」，
 *    而不是显示一个空荡荡的确认页让他以为程序坏了。
 *
 * 5. **多图走客户端批量，服务端契约不动**（2026-10-09）。
 *    服务端 `/captures` 一次收一张图（长边 1600 契约 + 单文件 multipart），
 *    改成多文件上传要动契约、动队列、动去重 —— 收益撑不起风险。
 *    所以每张图独立走一遍「上传→识别→草稿」，全部完成后把各图的作业单
 *    合并进同一个确认页（[mergeReviews]）；确认页本来就支持一图拆多条，
 *    发布/丢弃用真实 id，语义完全不变。
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: StudyApi,
    private val sync: SyncScheduler,
) : ViewModel() {

    // ---------------- 状态 ----------------

    sealed interface Stage {
        /** 还没选图。 */
        data object Pick : Stage

        /** 上传中 / 识别中。 */
        data class Busy(val progress: Progress) : Stage

        /** 识别完成，等家长确认。 */
        data class Review(val review: DraftReview) : Stage

        /** 已发布成功。 */
        data class Published(val message: String) : Stage

        /**
         * 出错了。
         *
         * [retryable] 为 false 时界面不显示「重试」按钮 —— 一个点了也必然失败的按钮
         * 比没有按钮更糟（家长会反复点）。
         */
        data class Failed(val message: String, val retryable: Boolean = true) : Stage
    }

    /**
     * 处理进度。界面把它渲染成「一句话 + 一个进度条 + 已等待秒数」。
     *
     * ⚠️ [headline] 一定不能直接用服务端的 `job.statusName` ——
     *    那是 `pending / running / succeeded` 这种英文字符串（给日志和 curl 看的），
     *    直接摆到家长面前就是一句「pending」。批量轮询的 headline 由 [pollBatch]
     *    直接给中文（「识别中（已完成 x/y 张）」）。
     */
    data class Progress(
        val headline: String,
        /** 第二行的小字，比如「命中缓存，通常几秒」或尺寸信息。 */
        val detail: String? = null,
        val elapsedSec: Int = 0,
        /** 服务端已重试次数。> 0 时界面会显示「第 N/M 次」。 */
        val attempt: Int = 0,
        val maxAttempt: Int = 0,
        val cacheHit: Boolean = false,
        /**
         * 三段流水线上的位置：0 上传 / 1 识别 / 2 家长确认。
         * **-1 表示不在这条线上**（发布、丢弃），界面此时不画步骤条 ——
         * 给一个假的进度比不给更糟。
         */
        val step: Int = -1,
    )

    /**
     * 确认界面要展示的全部内容。
     *
     * ⚠️ 类名刻意叫 `DraftReview` 而不是 `Review`：如果它叫 `Review`，那么在
     *    [Stage.Review] 内部写 `val review: Review` 时，这个 `Review` 会被解析成
     *    **内层的 `Stage.Review` 自己**（Kotlin 的嵌套类型遮蔽外层同名类型），
     *    属性就变成自引用类型，构造处直接报「actual type is Review, but Review was expected」——
     *    报错信息看起来像胡说，根因是名字撞了。别改回去。
     */
    data class DraftReview(
        val assignments: List<ReviewAssignment>,
        /** 模型自认拿不准的地方（顶层 `needs_review`，中文说明）。 */
        val needsReview: List<String>,
        /** 被判定为不是作业的内容片段（顶层 `not_homework`）。 */
        val notHomework: List<String>,
        /** 这次确认页来自几张图（一次选多张时 >1，界面据此调整措辞）。 */
        val imageCount: Int = 1,
    ) {
        /** 一张图可能同时含「作业」和「不是作业」的内容（群里混着发）。 */
        val hasAssignments: Boolean get() = assignments.isNotEmpty()
    }

    data class ReviewAssignment(
        val id: Int,
        val assignDate: String,
        val subject: Int,
        val title: String?,
        val needSubmit: Boolean,
        val items: List<ReviewItem>,
    )

    data class ReviewItem(
        val seq: Int,
        val title: String,
        val requirement: String?,
        val itemType: Int,
        val answerMode: Int,
        val estMinutes: Int?,
    )

    /** 重试时该从哪一步接上。每次状态推进都要把它摆到正确的位置。 */
    private enum class Resume { Upload, Poll, Recognize, Publish, Discard }

    var stage by mutableStateOf<Stage>(Stage.Pick)
        private set

    /** 本地预览用的图片尺寸信息（让家长确认「传的是这张图」）。 */
    var lastUploadInfo by mutableStateOf<String?>(null)
        private set

    // ---- 重试需要的东西。全是内存态：进程被杀就没了，这是可接受的（重选几张图而已）。 ----
    private var resume = Resume.Upload

    /**
     * 多选批量状态（2026-10-09 起一次可勾选多张）。
     *
     * 服务端契约是**单文件**上传（`/captures` 一次一张），所以多图走客户端批量：
     * 每张图独立走一遍「上传→识别→草稿」，全部完成后把各图的作业单合并进
     * 同一个确认页（见 [mergeReviews]）。
     *
     * 批量状态留到下一次选图为止：中途失败点「重试」能**接着断点继续** ——
     * 传完的图不重传（[batchCursor]）、识别完的图不再轮询（[batchResults]），
     * 失败只影响出问题的那一张。这是批量版最重要的体验，别在重构时丢掉。
     */
    private var batch: List<ImagePreparer.Prepared> = emptyList()

    /** 下一条待上传的图的下标（下标之前的都已上传成功）。 */
    private var batchCursor = 0

    private data class BatchJob(val captureId: Int, val jobId: Int, val index: Int)

    /** 已上传成功的识别任务（[index] 是「第几张图」，从 1 计，失败提示要用）。 */
    private val batchJobs = mutableListOf<BatchJob>()

    /** 已识别完成的结果，与 [batchJobs] 按 jobId 对应。 */
    private val batchResults = mutableListOf<Pair<BatchJob, RecognitionJobDto>>()

    private var lastCaptureId: Int? = null
    private var pendingPublishIds: List<Int> = emptyList()
    private var pendingDiscardIds: List<Int> = emptyList()

    // ---------------- 动作 ----------------

    fun reset() {
        stage = Stage.Pick
        lastUploadInfo = null
        resume = Resume.Upload
        batch = emptyList()
        batchCursor = 0
        batchJobs.clear()
        batchResults.clear()
        lastCaptureId = null
        pendingPublishIds = emptyList()
        pendingDiscardIds = emptyList()
    }

    /**
     * 家长选好了图（一次可多张）。整个链路在这里串起来。
     *
     * 注意用 [Dispatchers.IO] 包了图片解码与压缩 —— 50MP 图解码是百毫秒级的
     * CPU + 内存操作，放主线程会直接卡出一帧 ANR 的味道。
     */
    fun onImagesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return   // 用户取消不算错，也别把已有状态冲掉
        viewModelScope.launch {
            val total = uris.size
            // 先把所有图压完再上传：逐张给进度（「压缩 2/5」），不给反馈像卡死
            val prepared = mutableListOf<ImagePreparer.Prepared>()
            for ((i, uri) in uris.withIndex()) {
                stage = Stage.Busy(Progress("正在压缩图片 ${i + 1}/$total…", step = STEP_UPLOAD))
                try {
                    prepared += withContext(Dispatchers.IO) { ImagePreparer.prepare(context, uri) }
                } catch (e: Exception) {
                    // 读不出图重试也没用（文件坏了/已被清理），不给「重试」按钮
                    stage = Stage.Failed(
                        "读不出第 ${i + 1} 张图：${e.message ?: e::class.java.simpleName}",
                        retryable = false,
                    )
                    return@launch
                }
            }
            batch = prepared
            batchCursor = 0
            batchJobs.clear()
            batchResults.clear()
            lastUploadInfo = "共 ${batch.size} 张 · 合计 ${batch.sumOf { it.bytes.size } / 1024} KB"
            resume = Resume.Upload
            runBatch()
        }
    }

    /**
     * 重试。**接着上次失败的那一步**，不无脑从头来。
     *
     * 每一步都留着恢复所需的句柄：
     *  · 上传失败 → 已压缩的字节还在 [batch] 里，从断点那张继续传（已传的不重传）；
     *  · 轮询失败 → 任务早就在服务端跑着了，只需继续问（重传会多产生一条 job）；
     *  · 识别失败 → 只对失败那张让服务端重跑（force=true 连缓存一起清），
     *    批量里其余图不受影响；
     *  · 发布失败 → 直接重发指令。
     */
    fun retry() {
        if (stage is Stage.Busy) return
        when (resume) {
            Resume.Upload -> {
                if (batch.isEmpty()) return reset()
                viewModelScope.launch { runBatch() }
            }

            Resume.Poll -> viewModelScope.launch { pollBatch() }

            Resume.Recognize -> {
                val captureId = lastCaptureId ?: return reset()
                viewModelScope.launch { recognizeAgain(captureId) }
            }

            Resume.Publish -> publish(pendingPublishIds)

            Resume.Discard -> discard(pendingDiscardIds)
        }
    }

    // ---------------- 各步实现 ----------------

    /**
     * 批量流水线前半段：把 [batch] 里没传完的传完，然后把所有任务轮询到出结果。
     *
     * 传一张立刻拿一个 jobId（服务端是异步队列），所以「上传」和「识别」
     * 天然重叠 —— 传到最后一张时，第一张多半已经在识别了。
     */
    private suspend fun runBatch() {
        val total = batch.size

        while (batchCursor < total) {
            val i = batchCursor
            stage = Stage.Busy(
                Progress(
                    "正在上传 第${i + 1}/$total 张…",
                    detail = lastUploadInfo,
                    step = STEP_UPLOAD,
                )
            )
            val upload = try {
                val body = batch[i].bytes.toRequestBody(ImagePreparer.MIME.toMediaType())
                val part = MultipartBody.Part.createFormData(
                    "file", ImagePreparer.FILE_NAME, body,
                )
                api.uploadCapture(part, studentId = STUDENT_ID).requireOk()
            } catch (e: Exception) {
                resume = Resume.Upload          // 图还在内存里，重试＝从这张继续传
                stage = Stage.Failed("第 ${i + 1} 张上传失败：${describe(e)}")
                return
            }
            batchJobs += BatchJob(captureId = upload.captureId, jobId = upload.jobId, index = i + 1)
            batchCursor = i + 1
        }

        pollBatch()
    }

    /**
     * 轮询批量里所有还没出结果的任务，全部完成后合并进同一个确认页。
     *
     * 服务端状态：0 排队 / 1 识别中 / 2 成功 / 3 失败 / 4 放弃（重试到顶）。
     * 每张图是**独立任务**：先完成的先收，家长看到的是「已完成 x/y 张」，
     * 等最慢的一张收齐才进确认页。
     *
     * 间隔 2 秒 —— 实测单张 6–60 秒，2 秒能把「已等待 Ns」更新得足够细，
     * 又不至于把家里的小服务端轮询出压力（间隔 500ms 就是 4 倍请求量）。
     */
    private suspend fun pollBatch() {
        // ⚠️ **第一行就占住 Busy**，这是 [retry] 唯一闸门所依赖的那条不变量。
        //    早先第一次写 `Stage.Busy` 在下面「整轮 api.recognitionJob() 之后」——
        //    于是从 Resume.Poll 进入时（stage 还是上次失败留下的 Stage.Failed），
        //    在第一个网络请求返回之前再点一次「重试」，就能**启动第二条 pollBatch()**。
        //    两条协程对同一批 jobId 各自 `batchResults += entry to job`，
        //    确认页就出现重复作业单（mergeReviews 只做 flatMap、不去重）；
        //    而且 `batchResults.size == batchJobs.size` 还可能被提前满足，带着缺项跳出。
        //    （viewModelScope 用的是 Main.immediate，这里同步执行到第一个挂起点，
        //     所以 retry() 返回时 stage 已经是 Busy 了。）
        stage = Stage.Busy(Progress("识别中…", step = STEP_RECOGNIZE))
        var elapsed = 0
        /** 连续「整轮一个结果都没拿到」的轮数 —— 判断服务端是不是真的不通了。 */
        var blindRounds = 0

        while (true) {
            val pending = batchJobs.filter { job -> batchResults.none { it.first.jobId == job.jobId } }
            if (pending.isEmpty()) break

            var attempt = 0
            var maxAttempt = 0
            var anyCacheHit = false
            var gotAnyAnswer = false
            var failed: Pair<BatchJob, RecognitionJobDto>? = null

            for (entry in pending) {
                val job = try {
                    api.recognitionJob(entry.jobId).requireOk()
                } catch (e: Exception) {
                    // 轮询期间的网络抖动不该让整件事失败：识别已经在服务端跑着了，
                    // 再等一轮通常就恢复。连续整轮颗粒无收超过放弃线才交还家长。
                    continue
                }
                gotAnyAnswer = true
                anyCacheHit = anyCacheHit || job.cacheHit
                attempt = maxOf(attempt, job.retryCount)
                maxAttempt = maxOf(maxAttempt, job.maxRetry)

                when (job.status) {
                    STATUS_SUCCEEDED -> batchResults += entry to job

                    STATUS_FAILED, STATUS_GIVEN_UP -> failed = entry to job
                    // 0 排队 / 1 识别中：下一轮统一更新进度
                }
            }

            failed?.let { (entry, job) ->
                // 只有这一张失败。重试时只对它 force 重跑，其余图照旧轮询
                lastCaptureId = entry.captureId
                resume = Resume.Recognize
                stage = Stage.Failed(
                    "第 ${entry.index} 张识别失败：${job.error ?: "服务端没给原因"}" +
                        if (job.status == STATUS_GIVEN_UP) "（服务端已重试 ${job.retryCount} 次）" else ""
                )
                return
            }

            blindRounds = if (gotAnyAnswer) 0 else blindRounds + 1
            if (blindRounds * POLL_INTERVAL_SEC > POLL_GIVE_UP_SEC) {
                resume = Resume.Poll        // 任务还在，重试＝继续问
                stage = Stage.Failed(
                    "识别任务已在服务端排队，但结果取不回来" +
                        "（连续 ${blindRounds} 轮没拿到任何状态）。任务不会丢，点重试继续等。"
                )
                return
            }

            if (batchResults.size == batchJobs.size) break

            // 把「已完成 x/y」显出来是关键 —— 多张时家长尤其需要知道还剩几张。
            val pendingIds = pending.joinToString("、") { "#${it.jobId}" }
            stage = Stage.Busy(
                Progress(
                    headline = if (blindRounds > 0) "网络不稳，正在重试…"
                    else "识别中（已完成 ${batchResults.size}/${batchJobs.size} 张）",
                    detail = when {
                        blindRounds > 0 -> "识别任务 $pendingIds 仍在服务端处理"
                        anyCacheHit -> "有缓存命中，通常几秒"
                        else -> "识别任务 $pendingIds"
                    },
                    elapsedSec = elapsed,
                    attempt = attempt,
                    maxAttempt = maxAttempt,
                    step = STEP_RECOGNIZE,
                )
            )
            delay(POLL_INTERVAL_SEC * 1000L)
            elapsed += POLL_INTERVAL_SEC
        }

        stage = Stage.Review(mergeReviews())
    }

    /** 让服务端重跑一张图的识别。force=true 连缓存一起清。**只影响这一张**，批量里其余照旧。 */
    private suspend fun recognizeAgain(captureId: Int) {
        stage = Stage.Busy(Progress("正在重新排队…", step = STEP_RECOGNIZE))
        val res = try {
            api.reRecognize(captureId, force = true).requireOk()
        } catch (e: Exception) {
            resume = Resume.Recognize
            stage = Stage.Failed("重新识别失败：${describe(e)}")
            return
        }
        // 旧任务已作废，原地换成新任务号再轮询整批（其余图不重传不重跑）
        val i = batchJobs.indexOfFirst { it.captureId == captureId }
        if (i >= 0) batchJobs[i] = BatchJob(captureId = captureId, jobId = res.jobId, index = batchJobs[i].index)
        resume = Resume.Poll
        pollBatch()
    }

    /**
     * 把服务端返回的两份数据拼成确认界面。
     *
     * 为什么要拼两份，而不是只用其中一份：
     *  - `job.assignments`（落库后的作业单）有**真实 id**（发布/丢弃要用），
     *    也有服务端补的中文名，但**没有** confidence / needs_review；
     *  - `job.draft`（模型原始输出）反过来：有 needs_review / not_homework / 日期依据，
     *    但没有 id。
     *  两份缺一不可，所以在客户端拼。
     */
    private fun buildReview(draft: JsonObject?, assignments: List<AssignmentDto>): DraftReview {
        val needsReview = draft.stringList("needs_review")
        val notHomework = draft?.get("not_homework")
            ?.let { it as? JsonArray }
            ?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val raw = (o["raw"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val kind = (o["kind"] as? JsonPrimitive)?.contentOrNull
                if (kind.isNullOrBlank()) raw else "【$kind】$raw"
            }
            .orEmpty()

        return DraftReview(
            assignments = assignments.map { a ->
                ReviewAssignment(
                    id = a.id,
                    assignDate = a.assignDate,
                    subject = a.subject,
                    title = a.title,
                    needSubmit = a.needSubmit,
                    items = a.items.map { i ->
                        ReviewItem(
                            seq = i.seq,
                            title = i.title,
                            requirement = i.requirement,
                            itemType = i.itemType,
                            answerMode = i.answerMode,
                            estMinutes = i.estMinutes,
                        )
                    },
                )
            },
            needsReview = needsReview,
            notHomework = notHomework,
        )
    }

    /**
     * 把各张图的确认内容按**选图顺序**合并成一份确认页。
     *
     * 发布/丢弃用的是草稿的真实 id（服务端逐图建单），所以合并只是展示层的拼接，
     * [publish] / [discard] 语义一个字都不用改 —— 一张图拆出的多条作业单
     * 和多张图各自的单，在确认页里长一个样。这正是把多图做在客户端的理由之一。
     */
    private fun mergeReviews(): DraftReview {
        val perImage = batchResults
            .sortedBy { it.first.index }
            .map { (_, job) -> buildReview(job.draft, job.assignments) }
        return DraftReview(
            assignments = perImage.flatMap { it.assignments },
            needsReview = perImage.flatMap { it.needsReview },
            notHomework = perImage.flatMap { it.notHomework },
            imageCount = perImage.size,
        )
    }

    private fun JsonObject?.stringList(key: String): List<String> =
        this?.get(key)
            ?.let { it as? JsonArray }
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /**
     * 发布。**逐单发布**：一张群聊截图可能含语文和数学两科，拆成两条作业单。
     * 家长可能只想先发数学（语文还在改），所以不做成「一把全发」。
     */
    fun publish(assignmentIds: List<Int>) {
        if (assignmentIds.isEmpty()) return
        pendingPublishIds = assignmentIds
        viewModelScope.launch {
            stage = Stage.Busy(Progress("正在发布…"))
            try {
                assignmentIds.forEach { api.publishAssignment(it).requireOk() }
            } catch (e: Exception) {
                resume = Resume.Publish
                // ⚠️ retryable 必须跟 `discard()` 一样算出来。默认值 true 会让
                //    「重试一万次也是同样结果」的错误（令牌失效 / 409001 状态冲突）
                //    也挂上「重试」按钮 —— 一个点了必然失败的按钮比没有按钮更糟。
                stage = Stage.Failed("发布失败：${describe(e)}", retryable = !isPermanent(e))
                return@launch
            }
            // 发布完立刻催一次同步，孩子端不用等到下一个 6 小时周期。
            // 注意：这只对**本机**（家长手机）的 WorkManager 有效 ——
            // 孩子端那个 App 是另一个进程、另一个 WorkManager，
            // 它靠自己冷启动时的 syncNow() 或周期任务拉取。
            sync.syncNow()
            pendingPublishIds = emptyList()
            stage = Stage.Published(
                if (assignmentIds.size == 1) "已发布，孩子端可以看到了。"
                else "已发布 ${assignmentIds.size} 条作业单。"
            )
        }
    }

    /** 丢弃草稿（拍错图 / 老师撤回）。已发布的删不掉，服务端会返回 409001。 */
    fun discard(assignmentIds: List<Int>) {
        if (assignmentIds.isEmpty()) return
        pendingDiscardIds = assignmentIds
        viewModelScope.launch {
            stage = Stage.Busy(Progress("正在丢弃…"))
            try {
                assignmentIds.forEach { id ->
                    // ⚠️ 必须容忍 404001。丢弃是**批量**的，第一条成功后第二条失败时，
                    //    重试会把第一条再删一次 —— 而 deleted 的 id 再删服务端报「不存在」。
                    //    不容忍的话，重试永远卡在同一个地方，家长只能重选图。
                    try {
                        api.discardAssignment(id).requireOk()
                    } catch (e: ApiException) {
                        if (!e.isNotFound) throw e
                    }
                }
                pendingDiscardIds = emptyList()
                reset()
            } catch (e: Exception) {
                resume = Resume.Discard
                // 状态冲突（409001）说明这条已经发布了，重试一万次也是同样结果 ——
                // 这种不给「重试」按钮。
                stage = Stage.Failed("丢弃失败：${describe(e)}", retryable = !isPermanent(e))
            }
        }
    }

    /** 业务码非 0 就抛。判断成败看 `code`，不能只看 HTTP 200（见 Envelope 注释）。 */
    private fun <T> Envelope<T>.requireOk(): T {
        if (!isOk) throw ApiException(code, message.ifBlank { "服务端返回 code=$code" }, traceId)
        return requireData()
    }

    /** 重试一定没用的错误。只用来决定「要不要显示重试按钮」。 */
    private fun isPermanent(e: Exception): Boolean = when (e) {
        is ApiException -> e.isAuthFailure || e.isConflict || e.isNotFound
        is HttpException -> e.code() == 401 || e.code() == 403 || e.code() == 409
        else -> false
    }

    private fun describe(e: Exception): String = when {
        e is UnknownHostException -> "地址解析不了，去「设置」核对服务端地址。"

        e is ConnectException ->
            "连不上服务端，去「设置」里点一次「测试连接」看是哪一步不通。"

        e is SocketTimeoutException ->
            "超时了。原图较大时上传会慢一些，确认手机和服务器在同一局域网。"

        e is HttpException -> when (e.code()) {
            401, 403 -> "令牌被拒（HTTP ${e.code()}），去「设置」核对家庭令牌。"
            413 -> "图片太大了（HTTP 413）。"
            else -> "服务端返回 HTTP ${e.code()}。"
        }

        e is ApiException -> when {
            e.isAuthFailure -> "令牌无效（401001），去「设置」核对家庭令牌。"
            e.code == 0 -> "服务端地址还没配置，去「设置」里填。"
            e.isNotFound -> "识别任务或作业单不存在（404001），可能已被清理。"
            e.isConflict -> "状态冲突（409001）：这条作业单已经是发布状态了。"
            else -> e.message
        }

        else -> e.message ?: e::class.java.simpleName
    }

    private companion object {
        /** 单家庭、家里只有一个孩子，先固定 1（服务端 `/students` 也只有一条）。 */
        const val STUDENT_ID = 1

        /** 三段流水线的下标，与 [Progress.step] 对应。 */
        const val STEP_UPLOAD = 0
        const val STEP_RECOGNIZE = 1

        /** 与服务端 `queue.py` 的 JOB_STATUS_* 一致。 */
        const val STATUS_SUCCEEDED = 2
        const val STATUS_FAILED = 3
        const val STATUS_GIVEN_UP = 4

        const val POLL_INTERVAL_SEC = 2
        const val POLL_GIVE_UP_SEC = 300
    }
}
