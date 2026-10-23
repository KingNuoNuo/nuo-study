package com.nuostudy.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 孩子端本地库。**6 张表，就这么点。**
 *
 * ⚠️ 别把服务端的 18 张表搬过来。孩子端不需要账号、设备、识别任务、修正记录、
 *    识别缓存 —— 那些是家长端和服务端的事，孩子平板上存它们只会拖慢查询、
 *    扩大数据面（未成年人设备上少存数据本身就是好事）。
 *
 * exportSchema 在生产项目应为 true，用导出的 JSON 做迁移测试；
 * 骨架阶段先关，避免还没有 schema 目录就报构建警告。
 */
@Database(
    entities = [
        LocalAssignment::class,
        LocalItem::class,
        AnswerOutbox::class,
        LocalMedia::class,
        LocalKnowledgePoint::class,
        LocalPractice::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class StudyDatabase : RoomDatabase() {
    abstract fun studyDao(): StudyDao

    companion object {
        /**
         * ⚠️ 数据库文件名固定，**改动它等于换一个新库**（旧数据全部丢失）。
         */
        const val NAME = "student_cache.db"
    }
}
