package com.nuostudy.core.ui.text

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 服务端整数枚举 → 中文名的映射表。
 *
 * 为什么映射放在客户端而不是让服务端返回中文名：
 *  服务端**确实**返回了 `subjectName` / `itemTypeName` / `answerModeName`，
 *  但它们是「顺带给的」，不是契约的一部分（`docs/api/openapi.yaml` 里没有声明），
 *  客户端 DTO 也就没建模 —— 于是拿到手也用不上。
 *  而且离线渲染时（孩子端断网读 Room）本来就没有服务端中文名可用，
 *  映射必须落在客户端，否则断网就没法显示。
 *
 * ⚠️ 数值必须与服务端 `server/recognizer.py` 里的三张 MAP 保持一致。
 *    这里刻意用 `when` 表达式而不是 Map：`when` 有**穷尽性检查**，
 *    将来服务端新增一种作业形态（比如「视频打卡」= 5），
 *    Kotlin 会在编译期提醒这里的 `else` 分支需要更新；
 *    而 `map[key] ?: "未知"` 只会安静地显示成未知，一直没人发现。
 */

/** 科目。对应服务端 `SUBJECT_NAME`。 */
fun subjectLabel(subject: Int): String = when (subject) {
    1 -> "语文"
    2 -> "数学"
    3 -> "英语"
    9 -> "其他"
    else -> "科目$subject"
}

/**
 * 作业项的作答形态。对应服务端 `ITEM_TYPE_NAME`。
 *
 * 注意这**不是**「孩子要选什么控件」，控件看 [answerModeLabel]。
 * 这里表达的是「这项作业是什么性质」，用于家长扫一眼就知道有几件事要做。
 */
fun itemTypeLabel(itemType: Int): String = when (itemType) {
    1 -> "客观题"
    2 -> "主观题"
    3 -> "听读打卡"
    4 -> "线下实践"
    else -> "其他"
}

/**
 * 作答方式。对应服务端 `ANSWER_MODE_NAME`。
 *
 * ⚠️ 这个值直接决定孩子端渲染什么控件（0 无 / 1 自绘键盘 / 2 手写 / 3 录音 / 4 拍照），
 *    所以它在家长端的确认界面上必须显示出来 ——
 *    「老师要求默写，模型却判成了键盘输入」这种事，只有家长能发现。
 */
fun answerModeLabel(answerMode: Int): String = when (answerMode) {
    0 -> "无需作答"
    1 -> "键盘作答"
    2 -> "手写作答"
    3 -> "录音作答"
    4 -> "拍照上传"
    else -> "未知方式"
}

/** 作业单状态。对应服务端 `STATUS_NAME`（返回的是英文，这里给中文）。 */
fun assignmentStatusLabel(status: Int): String = when (status) {
    0 -> "待确认"      // 草稿 —— 家长还没点发布
    1 -> "已发布"      // 孩子端可见
    2 -> "已完成"
    3 -> "已归档"
    else -> "状态$status"
}

/**
 * 识别任务状态。对应服务端 `queue.JOB_STATUS_NAME`
 * （`pending / running / succeeded / failed / given_up`）。
 *
 * ⚠️ 服务端给的 `statusName` 是**英文的**（那是给日志和 curl 看的），
 *    直接摆到家长面前会是一句「pending」。所以家长端一律走这个映射，
 *    不要拿 `job.statusName` 当展示文案。
 */
fun jobStatusLabel(status: Int): String = when (status) {
    0 -> "排队中"
    1 -> "识别中"
    2 -> "识别完成"
    3 -> "识别失败"
    4 -> "已放弃"      // 重试到上限
    else -> "状态$status"
}

/**
 * `yyyy-MM-dd` → 「10月8日 周四」。
 *
 * 为什么不用 `DateTimeFormatter.ofPattern("M月d日 E")`：那个走的是 JVM 的
 * locale 数据，在没有 CLDR 的 Android 低版本上会输出英文 weekday 或直接抛异常。
 * 手写星期数组只有一行，且结果稳定。
 *
 * 解析失败时**原样返回**输入 —— 宁可显示 `2026-10-08`，也不要让整个界面崩掉。
 */
fun friendlyDate(iso: String): String = try {
    val d = LocalDate.parse(iso)
    "%d月%d日 周%s".format(d.monthValue, d.dayOfMonth, WEEKDAY_CN[d.dayOfWeek.value - 1])
} catch (e: DateTimeParseException) {
    iso
}

/**
 * `yyyy-MM-dd` → 「10月8日」（**不含星期**）。
 *
 * 与 [friendlyDate] 的分工：屏幕标题那种「一眼要看清是哪天」的位置用带星期的长格式；
 * 作业卡片这类**紧挨着科目名和徽标**的窄位置用这个短格式 —— 星期在这里是噪声，
 * 而且一行里省下的宽度能让「需要提交」徽标紧贴日期排下去，不用甩到最右边。
 *
 * 解析失败同样原样返回，理由同 [friendlyDate]。
 */
fun monthDay(iso: String): String = try {
    val d = LocalDate.parse(iso)
    "%d月%d日".format(d.monthValue, d.dayOfMonth)
} catch (e: DateTimeParseException) {
    iso
}

private val WEEKDAY_CN = arrayOf("一", "二", "三", "四", "五", "六", "日")
