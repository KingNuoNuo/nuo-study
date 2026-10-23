package com.nuostudy.core.network

import kotlinx.serialization.Serializable

/**
 * 服务端统一响应信封。
 *
 * 契约约定（见 docs/api/openapi.yaml）：
 *  - 成功：`{ code: 0, message: "ok", data: ..., traceId }`
 *  - 失败：`{ code: 6位数字, message, traceId }`，**HTTP 状态码只表达传输层语义**
 *
 * ⚠️ 因此判断业务成败要看 `code`，不能只看 HTTP 200 —— 服务端在
 *    「作业单已发布，不允许修改」这类业务拒绝时也会返回 200 + 非零 code。
 */
@Serializable
data class Envelope<T>(
    val code: Int,
    val message: String = "",
    val data: T? = null,
    val traceId: String? = null,
) {
    val isOk: Boolean get() = code == 0

    fun requireData(): T =
        data ?: throw ApiException(code, "服务端返回 code=0 但没有 data（traceId=$traceId）", traceId)
}

/** 业务异常。`code` 是服务端给的 6 位业务码，不是 HTTP 状态码。 */
class ApiException(
    val code: Int,
    override val message: String,
    val traceId: String? = null,
) : Exception(message) {
    /** 401001 = 家庭令牌无效。多设备场景下要提示家长重新填令牌。 */
    val isAuthFailure: Boolean get() = code == 401001

    /** 404001 = 资源不存在。识别任务被清理、作业单被删除时会遇到。 */
    val isNotFound: Boolean get() = code == 404001

    /** 409001 = 状态冲突。例如对「已发布」的作业单再次 PATCH。 */
    val isConflict: Boolean get() = code == 409001
}
