package com.nuostudy.core.network

/**
 * 服务端地址与家庭令牌的来源。
 *
 * ⚠️ 为什么 BaseUrl 必须**运行时可变**：这是自己家的服务端，地址是家长第一次
 *    打开 App 时手填的（局域网 IP，或内网穿透域名）。写死进 BuildConfig 的话，
 *    换个网络环境（在家 / 不在家）就得重新发版。
 *
 * 三个方法都是**同步**的 —— OkHttp 拦截器跑在同步链路上，没法 suspend。
 * 所以实现方（core-data 用 DataStore）必须在内存里维护一份缓存值，
 * 不能每次都去读磁盘。
 */
interface ServerEndpoint {
    /** 形如 `http://192.168.1.10:8000/`，**结尾必须有斜杠**。未配置时返回 null。 */
    fun baseUrl(): String?

    /** 家庭令牌。单家庭部署只有一个 token，无过期、无刷新、无短信登录。 */
    fun familyToken(): String?

    /** 设备标识。服务端用它区分「孩子平板」与「家长手机」，家长端可查最后同步时间。 */
    fun deviceId(): String
}
