package com.nuostudy.core.network

import okhttp3.MultipartBody
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * 服务端接口。**与 docs/api/openapi.yaml 严格对应**，只覆盖 M1（识别链路）。
 *
 * M2 的作答 / 练习 / 错题本 / 学情报告已整体暂缓，对应端点不在服务端注册，
 * 所以这里也**不要提前声明** —— 声明了但调不通，比不声明更糟。
 * 恢复 M2 时，把这几个接口按契约补进来即可。
 *
 * 所有方法返回 Envelope<T>，由 Repository 层统一校验 code 并抛 ApiException。
 *
 * ⚠️⚠️ 接口上这个 [JvmSuppressWildcards] **不能删**（2026-10-09 真机上踩到）：
 *
 *   Kotlin 的 `Map<K, V>` 是**协变**的（声明为 `Map<K, out V>`），编译成 Java 签名时会
 *   变成 `Map<String, ? extends JsonElement>`。而 kotlinx.serialization 在反射取
 *   serializer 时**拒绝任何通配符**，直接抛：
 *
 *     Parameter type must not include a type variable or wildcard:
 *       java.util.Map<java.lang.String, ? extends kotlinx.serialization.json.JsonElement>
 *       (parameter #2) for method StudyApi.patchAssignment
 *
 *   后果是**调用当场就炸**（`IllegalArgumentException`），请求根本没发出去 ——
 *   家长的「保存」按钮点了没反应，界面上只有一串英文异常。
 *
 *   这个坑编译期、静态检查、服务端单测**全都发现不了**：服务端接口本身完全正常
 *   （用 curl 打一模一样 JSON 是通的），只有真机上从 App 里发一次才会暴露。
 *
 *   加在**接口**上而不是各个方法上：凡是类型里带 `Map<..., JsonElement>` 的地方
 *   都有这问题（`health()` / `uploadDebugFrame()` 的**返回类型**同样中招），
 *   一处声明覆盖全部，避免下次加接口时又漏掉一个。
 */
@JvmSuppressWildcards
interface StudyApi {

    // ---------------- 系统 ----------------
    /** 连通性探测。**不需要鉴权** —— 家长首次填地址时用它验证「地址填对了没」。 */
    @GET("api/v1/system/ping")
    suspend fun ping(): Envelope<PingData>

    @GET("api/v1/system/health")
    suspend fun health(): Envelope<Map<String, kotlinx.serialization.json.JsonElement>>

    // ---------------- 家庭与设备 ----------------
    @GET("api/v1/family")
    suspend fun family(): Envelope<FamilyDto>

    @GET("api/v1/devices")
    suspend fun devices(): Envelope<List<DeviceDto>>

    @POST("api/v1/devices")
    suspend fun registerDevice(@retrofit2.http.Body body: DeviceDto): Envelope<DeviceDto>

    @GET("api/v1/students")
    suspend fun students(): Envelope<List<StudentDto>>

    // ---------------- 截图上传与识别 ----------------
    /**
     * 上传作业截图。服务端**立刻返回 jobId，不在这里等识别**（单次识别 6–60 秒）。
     * 客户端随后轮询 [recognitionJob]。
     */
    @Multipart
    @POST("api/v1/captures")
    suspend fun uploadCapture(
        @Part file: MultipartBody.Part,
        @Query("studentId") studentId: Int = 1,
    ): Envelope<CaptureUploadResult>

    /** 重新识别。force=true 会清掉该图的缓存（换了提示词后重跑用）。 */
    @POST("api/v1/captures/{captureId}/recognize")
    suspend fun reRecognize(
        @Path("captureId") captureId: Int,
        @Query("force") force: Boolean = false,
    ): Envelope<CaptureUploadResult>

    @GET("api/v1/recognition-jobs/{jobId}")
    suspend fun recognitionJob(@Path("jobId") jobId: Int): Envelope<RecognitionJobDto>

    @GET("api/v1/recognition-jobs")
    suspend fun recognitionJobs(@Query("limit") limit: Int = 50): Envelope<List<RecognitionJobDto>>

    // ---------------- 作业单 ----------------
    @GET("api/v1/assignments")
    suspend fun assignments(
        @Query("date") date: String? = null,
        @Query("status") status: Int? = null,
        @Query("studentId") studentId: Int? = null,
        @Query("limit") limit: Int = 50,
    ): Envelope<List<AssignmentDto>>

    @GET("api/v1/assignments/{assignmentId}")
    suspend fun assignment(@Path("assignmentId") assignmentId: Int): Envelope<AssignmentDto>

    /** 家长确认 / 修正草稿。这是「人在环路」的唯一入口。 */
    @PATCH("api/v1/assignments/{assignmentId}")
    suspend fun patchAssignment(
        @Path("assignmentId") assignmentId: Int,
        @retrofit2.http.Body body: Map<String, kotlinx.serialization.json.JsonElement>,
    ): Envelope<AssignmentDto>

    /** 发布作业：草稿(0) → 已发布(1)。幂等。 */
    @POST("api/v1/assignments/{assignmentId}/publish")
    suspend fun publishAssignment(@Path("assignmentId") assignmentId: Int): Envelope<AssignmentDto>

    /** 丢弃草稿。已发布的不允许删（服务端返回 409001）。 */
    @DELETE("api/v1/assignments/{assignmentId}")
    suspend fun discardAssignment(@Path("assignmentId") assignmentId: Int): Envelope<Map<String, Int>>

    // ---------------- 调试：学生端实时画面 ----------------
    /**
     * 上传一帧「学生端自己的画面」。
     *
     * ⚠️ 这是**测试期调试接口**，不在 docs/api/openapi.yaml 的产品契约里 ——
     *    服务端把它放在 `api/v1/debug/` 这个前缀下，就是为了让它一眼能看出不属于正式功能。
     *    正式使用前应当整体删掉（客户端这一侧是 `app-student` 的 `mirror` 包）。
     *
     * ⚠️ 写这类路径注释时，**别在 `debug/` 后面接一个星号**。Kotlin 的块注释是
     *    **可嵌套的**（与 C/Java 不同）：注释里出现「斜杠 + 星号」会在 KDoc 内部
     *    再开一层注释，把后面的代码全吞掉，报错却是**文件末尾**的
     *    "Unclosed comment" —— 看上去和这里毫无关系，能白查半小时。
     *    （这条是真的踩了：KDoc 里写了那个前缀，`core-network` 编译直接挂。）
     *
     * 频率约 3 秒一次，服务端只写内存，所以这里不需要任何重试/退避 ——
     * 丢一帧对「看看孩子现在屏幕上是什么」毫无影响，而退避只会让循环变复杂。
     */
    @Multipart
    @POST("api/v1/debug/frames")
    suspend fun uploadDebugFrame(
        @Part file: MultipartBody.Part,
        @Query("label") label: String? = null,
    ): Envelope<Map<String, kotlinx.serialization.json.JsonElement>>

    /**
     * 取**最新一帧**画面（裸的 image/jpeg 字节）。
     *
     * ⚠️ 与其它端点三处不同，改之前先读：
     *
     * 1. **没有 Envelope**。响应体就是图片本身，所以这里返回 [ResponseBody]，
     *    不能套 `Envelope<T>` —— 解析器会在第一个字节上炸掉。
     * 2. **鉴权走 `?token=`，不走 `Authorization` 头**。这是服务端为了让浏览器
     *    直接打开网页看画面而刻意放宽的（浏览器给 `<img>` 带不了自定义头）。
     *    客户端的 OkHttp 拦截器仍会照常附上 Bearer 头，服务端不看它。
     * 3. **[Streaming]：响应体不缓冲**。不加的话 Retrofit 会在返回前把整个
     *    响应读进内存 —— 单张几十 KB 无所谓，但这是 1.5 秒一次的循环，
     *    白白多一轮拷贝。加了就必须自己关流，见调用方（`body.use { it.bytes() }`）。
     *
     * ⚠️ 服务端**一帧都没收到过**时返回 HTTP 404 + code 404001，
     *    Retrofit 对非 2xx 直接抛 [retrofit2.HttpException]，
     *    调用方必须 catch 住 —— 这是「孩子端还没打开」而不是「出错」。
     */
    @Streaming
    @GET("api/v1/debug/frames/latest.jpg")
    suspend fun latestFrame(
        @Query("token") token: String,
        /** 为空＝取所有设备里最新的那一帧。家里目前只有一台平板，不必指定。 */
        @Query("deviceId") deviceId: String? = null,
        /**
         * 采集通道：`app`（孩子端自己截的）或 `adb`（服务端抓的整屏）。
         * 为空＝不限通道，谁新给谁。
         *
         * ⚠️ `adb` 那一路是**按需求驱动**的：服务端收到这个参数的请求才会去启动
         *    抓帧线程，并给一次「还有人要看」的续期。所以切回 `app` 之后
         *    服务端几十秒内会自己停掉 adb 抓帧 —— 不需要额外的停止接口。
         */
        @Query("source") source: String? = null,
    ): okhttp3.ResponseBody

    /**
     * 每台设备最新一帧的元信息（谁在上传、多久以前、第几帧）。
     *
     * 与图片分开取：状态拿不到不该影响看图，图拿不到也不该影响「知道孩子端在线」。
     * 图片本身用 [latestFrame]，这里只负责标题那一行的信息。
     */
    @GET("api/v1/debug/frames/state")
    suspend fun framesState(@Query("token") token: String): Envelope<FramesStateDto>
}
