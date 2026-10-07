package com.enigmacast.di

import android.content.Context
import com.enigmacast.data.reminder.DataStoreReminderStorage
import com.enigmacast.domain.reminder.ReminderStorage
import com.enigmacast.live.ReminderScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ReminderModule {
    @Provides
    @Singleton
    fun reminderStorage(@ApplicationContext context: Context): ReminderStorage = DataStoreReminderStorage(context)

    @Provides
    @Singleton
    fun reminderScheduler(storage: ReminderStorage): ReminderScheduler =
        ReminderScheduler(storage, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), System::currentTimeMillis)
}
