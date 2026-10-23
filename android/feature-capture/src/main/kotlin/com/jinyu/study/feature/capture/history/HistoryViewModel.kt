package com.jinyu.study.feature.capture.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jinyu.study.core.network.ApiException
import com.jinyu.study.core.network.Envelope
import com.jinyu.study.core.network.StudyApi
import com.jinyu.study.core.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * 「历史作业」：已经录过的作业单列表 + 单条编辑/删除（家长端）。
 *
 * 为什么需要它：录入页原先是个**一次性**的通道 —— 拍完、确认、发布，然后就没了。
 * 两个真实场景因此无路可走：
 *   ① 家长把日期选错、把标题打错、少录了一项，唯一的补救是「删了重录」，
 *      而删除入口根本不存在；
 *   ② 识别跑完了但家长没来得及点确认就退出 App（或手机没电），那条草稿就此失联 ——
 *      列表里看不到，孩子端也看不到，只能靠再拍一次同一张图（还会命中缓存）。
 * 所以这一屏既是「历史」，也是「草稿回收站」。
 *
 * ⚠️ 服务端侧的两条规则是配套改的（2026-10-09）：
 *    已发布(1) 从「只读」放开成可改可删；已完成(2)/已归档(3) 仍然拒绝。
 *    误删这条防线**落在本模块的二次确认弹窗上** —— 别把它当成可有可无的装饰。
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val api: StudyApi,
    private val sync: SyncScheduler,
) : ViewModel() {

    /** 列表里的一行。只带渲染需要的东西，不带 items（编辑时才去拉详情）。 */
    data class Row(
        val id: Int,
        val assignDate: String,
        val subject: Int,
        val title: String?,
        val status: Int,
        val needSubmit: Boolean,
        val itemCount: Int,
    ) {
        val isDraft: Boolean get() = status == STATUS_DRAFT
    }

    /**
     * 编辑页里的一项。
     *
     * [uid] 只用于 Compose 的 `key()` —— 新加的项还没有服务端 id，
     * 用列表下标当 key 的话，「删掉第 2 项」会让第 3 项的输入框**继承**第 2 项的焦点与
     * 内部状态（光标位置、输入法组合串），表现是「删了一行，另一行的字串了」。
     */
    data class EditItem(
        val uid: Long,
        /** 服务端已有项的 id；null = 这次新加的，服务端会 INSERT。 */
        val id: Int?,
        val title: String,
        val requirement: String,
    )

    data class Edit(
        val id: Int,
        /** 0 草稿 / 1 已发布。决定编辑器里显不显示「发布」按钮。 */
        val status: Int,
        /** 科目。**只读**：它是作业单身份键（日期+科目）的一半，改它等于换一条单子。 */
        val subject: Int,
        val assignDate: String,
        val title: String,
        val needSubmit: Boolean,
        val items: List<EditItem>,
    ) {
        val isDraft: Boolean get() = status == STATUS_DRAFT

        /** 折回列表行 —— 编辑页里的「删除」要复用同一个确认弹窗。 */
        fun asRow(): Row = Row(
            id = id, assignDate = assignDate, subject = subject, title = title,
            status = status, needSubmit = needSubmit, itemCount = items.size,
        )
    }

    // ---------------- 状态 ----------------
    //
    // ⚠️ 「列表」与「详情」各有一套 loading/error，**不要合成一个**。
    //    早先只有一对 `loading` / `error`，被两处共用，于是：
    //      · 点开某一行拉详情时，列表也会显示「正在加载」；
    //      · 详情拉失败会把消息写进**列表的** error 里，列表上冒出一句
    //        与它无关的报错，而且下次刷新列表还得靠它自己清掉。
    //    合成一个字段看着省事，实际是让两个独立流程互相污染状态。

    var rows by mutableStateOf<List<Row>>(emptyList())
        private set
    /** 列表本身是否在加载（首帧 / 点「刷新」）。 */
    var listLoading by mutableStateOf(false)
        private set
    /** 列表加载失败。**不覆盖已有的 rows** —— 网络抖一下不该把列表清空。 */
    var listError by mutableStateOf<String?>(null)
        private set
    /** 点开某一行后，拉那条详情的过程。与 [listLoading] 是两件事。 */
    var detailLoading by mutableStateOf(false)
        private set
    /** 一次性提示（保存成功 / 已删除）。几秒后自动消失，见界面层的 LaunchedEffect。 */
    var toast by mutableStateOf<String?>(null)
        private set

    var editing by mutableStateOf<Edit?>(null)
        private set
    /** 编辑页里正在保存/发布/删除。用来禁用按钮、防重复提交。 */
    var busy by mutableStateOf(false)
        private set
    /** 编辑页里的校验或提交错误。 */
    var editError by mutableStateOf<String?>(null)
        private set

    /** 等待二次确认的删除目标。非空时界面弹确认框。 */
    var confirmDelete by mutableStateOf<Row?>(null)
        private set

    private var uidSeq = 0L

    init {
        load()
    }

    // ---------------- 列表 ----------------

    /**
     * 拉列表。
     *
     * ⚠️ **不传 status**：草稿和已发布要一起显示（草稿是待确认的，必须能找回来）。
     *    服务端按 `assign_date DESC, id DESC` 返回，客户端再分组。
     */
    fun load() {
        if (listLoading) return
        viewModelScope.launch {
            listLoading = true
            try {
                val list = api.assignments(status = null, limit = LIST_LIMIT).ok()
                rows = list
                    .filter { it.status == STATUS_DRAFT || it.status == STATUS_PUBLISHED }
                    .map {
                        Row(
                            id = it.id,
                            assignDate = it.assignDate,
                            subject = it.subject,
                            title = it.title,
                            status = it.status,
                            needSubmit = it.needSubmit,
                            itemCount = it.items.size,
                        )
                    }
                listError = null
            } catch (e: Exception) {
                listError = describe(e)
            } finally {
                listLoading = false
            }
        }
    }

    fun clearToast() {
        toast = null
    }

    // ---------------- 编辑 ----------------

    /** 点开某一行。**重新拉一次详情**，不用列表里的旧数据 —— 列表可能已经放了几分钟。 */
    fun openEdit(row: Row) {
        viewModelScope.launch {
            detailLoading = true
            editError = null
            try {
                val dto = api.assignment(row.id).ok()
                editing = Edit(
                    id = dto.id,
                    status = dto.status,
                    subject = dto.subject,
                    assignDate = dto.assignDate,
                    title = dto.title.orEmpty(),
                    needSubmit = dto.needSubmit,
                    items = dto.items.map {
                        EditItem(uid = ++uidSeq, id = it.id, title = it.title,
                            requirement = it.requirement.orEmpty())
                    },
                )
            } catch (e: Exception) {
                // ⚠️ 详情失败写进 [editError]（编辑页的错误），**不是** [listError]：
                //    列表本身好好的，让它冒一句「打开失败」是错位的。
                editError = describe(e)
            } finally {
                detailLoading = false
            }
        }
    }

    fun closeEdit() {
        editing = null
        editError = null
    }

    fun setDate(v: String) = updateEdit { it.copy(assignDate = v) }
    fun setTitle(v: String) = updateEdit { it.copy(title = v) }
    fun setNeedSubmit(v: Boolean) = updateEdit { it.copy(needSubmit = v) }

    fun setItemTitle(uid: Long, v: String) = updateEdit { e ->
        e.copy(items = e.items.map { if (it.uid == uid) it.copy(title = v) else it })
    }

    fun setItemRequirement(uid: Long, v: String) = updateEdit { e ->
        e.copy(items = e.items.map { if (it.uid == uid) it.copy(requirement = v) else it })
    }

    fun removeItem(uid: Long) = updateEdit { e ->
        e.copy(items = e.items.filterNot { it.uid == uid })
    }

    fun addItem() = updateEdit { e ->
        e.copy(items = e.items + EditItem(uid = ++uidSeq, id = null, title = "",
            requirement = ""))
    }

    private fun updateEdit(f: (Edit) -> Edit) {
        editing = editing?.let(f)
        editError = null
    }

    /**
     * 保存。
     *
     * ⚠️ 保存完**不自动同步孩子端**：服务端不会主动推。孩子端要等它自己下次刷新
     *    （冷启动 / 6 小时周期 / 手动下拉）才看得到。界面上必须把这句话讲清楚，
     *    否则家长会以为没保存成功然后反复点。
     */
    fun save() {
        val e = editing ?: return
        if (busy) return

        val date = e.assignDate.trim()
        if (!DATE_RE.matches(date)) {
            editError = "日期要写成 2026-10-09 这样（年-月-日），别写「今天」「10月9日」。"
            return
        }
        if (e.items.any { it.title.isBlank() }) {
            editError = "有作业项的题目是空的。空题目发下去，孩子端会出现一个点不开的空白条。"
            return
        }

        viewModelScope.launch {
            busy = true
            try {
                api.patchAssignment(e.id, patchBody(e)).ok()
                editing = null
                load()
                toast = "已保存。孩子端下次刷新（打开 App 或后台同步）时会更新。"
            } catch (err: Exception) {
                editError = describe(err)
            } finally {
                busy = false
            }
        }
    }

    /** 草稿直接从历史列表里发布，不用再走一遍录入流程。 */
    fun publish() {
        val e = editing ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                api.publishAssignment(e.id).ok()
                editing = null
                load()
                // 发布后催一次**本机**（家长手机）的同步；孩子端是另一个进程，
                // 它得靠自己冷启动或周期任务拉 —— 这句注释在 CaptureViewModel 里也有一份。
                sync.syncNow()
                toast = "已发布，孩子端可以看到了。"
            } catch (err: Exception) {
                editError = describe(err)
            } finally {
                busy = false
            }
        }
    }

    // ---------------- 删除 ----------------

    fun askDelete(row: Row) {
        confirmDelete = row
    }

    fun cancelDelete() {
        confirmDelete = null
    }

    /**
     * 真的删。
     *
     * ⚠️ 服务端是**硬删除**（没有软删除、没有回收站）。单家庭场景下这是对的 ——
     *    「删了怎么还在」比「删了就没了」难查得多。代价是这里一旦删错就真没了，
     *    所以入口只有一个，且必须过一次 [confirmDelete] 弹窗。
     */
    fun confirmDeleteNow() {
        val row = confirmDelete ?: return
        confirmDelete = null
        viewModelScope.launch {
            busy = true
            try {
                api.discardAssignment(row.id).ok()
                if (editing?.id == row.id) editing = null
                load()
                sync.syncNow()
                toast = "已删除。孩子端下次刷新时会消失。"
            } catch (e: Exception) {
                listError = describe(e)
            } finally {
                busy = false
            }
        }
    }

    // ---------------- 工具 ----------------

    /**
     * 组装 PATCH 请求体。
     *
     * ⚠️ 这里字段名的**大小写是有讲究的**，两层不一样，别顺手统一：
     *   · 顶层（assignDate / title / needSubmit）走 `AssignmentPatch`，**camelCase**；
     *   · items 里的（itemType / answerMode / estMinutes）走 `ItemPatch`，也是 camelCase，
     *     但服务端 `store.patch_assignment` 写库时用的是 snake_case ——
     *     转换在服务端 `_normalize_item()` 里做（以前没有，于是这几个字段被静默忽略）。
     *   本次只送 seq / title / requirement：数据里没有的字段就**不要送**，
     *   服务端对「给了的键」才更新，送 null 等于把人家清空（例如预估耗时）。
     */
    private fun patchBody(e: Edit): Map<String, JsonElement> = buildMap {
        put("assignDate", JsonPrimitive(e.assignDate.trim()))
        // 标题留空 -> 送空串，服务端会 strip 成 NULL（标题本来就是可选的）。
        put("title", JsonPrimitive(e.title.trim()))
        put("needSubmit", JsonPrimitive(e.needSubmit))
        put("items", JsonArray(e.items.mapIndexed { i, it ->
            buildJsonObject {
                it.id?.let { id -> put("id", JsonPrimitive(id)) }
                put("seq", JsonPrimitive(i + 1))
                put("title", JsonPrimitive(it.title.trim()))
                // 空要求送 null 而不是空串：服务端 `"requirement" in it` 为真，
                // 于是明确写 NULL；送空串会在库里留一堆 ""，将来分不清「没有」和「清空过」。
                val req = it.requirement.trim()
                put("requirement", if (req.isEmpty()) JsonNull else JsonPrimitive(req))
            }
        }))
    }

    /** 业务码非 0 就抛。判断成败看 `code`，不能只看 HTTP 200（见 Envelope 注释）。 */
    private fun <T> Envelope<T>.ok(): T {
        if (!isOk) throw ApiException(code, message.ifBlank { "服务端返回 code=$code" }, traceId)
        return requireData()
    }

    private fun describe(e: Exception): String = when {
        e is UnknownHostException -> "地址解析不了，去「设置」核对服务端地址。"
        e is ConnectException -> "连不上服务端，去「设置」里点一次「测试连接」。"
        e is SocketTimeoutException -> "超时了，确认手机和服务端在同一个局域网。"
        e is HttpException -> when (e.code()) {
            401, 403 -> "令牌被拒（HTTP ${e.code()}），去「设置」核对家庭令牌。"
            404 -> "这条作业单已经不在了（可能已经在别处删掉）。下拉刷新一下列表。"
            409 -> "服务端拒绝了这次改动：同一天同一科只能有一条作业单，或这条已经不能改了。"
            else -> "服务端返回 HTTP ${e.code()}。"
        }
        e is ApiException -> when {
            e.isAuthFailure -> "令牌无效（401001），去「设置」核对家庭令牌。"
            e.code == 0 -> "服务端地址还没配置，去「设置」里填。"
            e.isNotFound -> "这条作业单已经不在了（404001）。"
            e.isConflict -> "服务端拒绝了这次改动（409001）：同一天同一科只能有一条作业单。"
            else -> e.message
        }
        // ⚠️ 别把原始异常直接甩出来。真机上踩过：请求体类型不对时抛的是
        //   Retrofit/kotlinx 的英文 IllegalArgumentException，不加前缀的话
        //   家长看到一串英文只会以为「点了没反应」—— 分不清「保存失败」
        //   和「根本没点中按钮」。加个中文前缀，至少知道是出错了。
        else -> "出了个预期外的问题：${e.message ?: e::class.java.simpleName}"
    }

    companion object {
        const val STATUS_DRAFT = 0
        const val STATUS_PUBLISHED = 1

        private const val LIST_LIMIT = 40

        /** `yyyy-MM-dd`。服务端 schema 里 assign_date 就是这个形状，别送别的。 */
        private val DATE_RE = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
