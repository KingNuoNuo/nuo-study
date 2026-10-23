package com.nuostudy.core.data.di

import android.content.Context
import androidx.room.Room
import com.nuostudy.core.data.local.StudyDao
import com.nuostudy.core.data.local.StudyDatabase
import com.nuostudy.core.data.prefs.ServerConfigStore
import com.nuostudy.core.network.ServerEndpoint
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StudyDatabase =
        Room.databaseBuilder(context, StudyDatabase::class.java, StudyDatabase.NAME)
            // ⚠️ 骨架阶段用「破坏性迁移」：改表结构直接清库，方便迭代。
            //    一旦有真实数据（孩子做过的作业），必须换成正经的 Migration ——
            //    否则某次改字段会把孩子的作答记录全部清空。
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideStudyDao(db: StudyDatabase): StudyDao = db.studyDao()

    /**
     * 把 [ServerConfigStore] 作为 [ServerEndpoint] 暴露出去。
     *
     * 特意用 @Provides 而不是 @Binds：core-network 的拦截器只依赖
     * ServerEndpoint 这个窄接口，不关心背后是 DataStore 还是别的实现。
     */
    @Provides
    @Singleton
    fun provideServerEndpoint(store: ServerConfigStore): ServerEndpoint = store
}
