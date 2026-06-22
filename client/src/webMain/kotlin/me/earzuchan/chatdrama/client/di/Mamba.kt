package me.earzuchan.chatdrama.client.di

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.WebLocalStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.room3.Room
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import kotlinx.coroutines.Dispatchers
import me.earzuchan.chatdrama.client.data.DATABASE_FILENAME
import me.earzuchan.chatdrama.client.data.DATASTORE_FILENAME
import me.earzuchan.chatdrama.client.data.database.AppDatabase
import org.koin.core.module.Module
import org.koin.dsl.module
import org.w3c.dom.Worker

actual val clientPlatformModule: Module = module {
    single { createPreferencesDataStore() }
    single<AppDatabase> { createDatabase() }
    single { get<AppDatabase>().aiChatMessageDao() }
}

private fun createPreferencesDataStore() = DataStoreFactory.create(WebLocalStorage(PreferencesSerializer, DATASTORE_FILENAME))

private fun createDatabase() = Room.databaseBuilder<AppDatabase>(DATABASE_FILENAME).setDriver(WebWorkerSQLiteDriver(Worker("dw.js"))).setQueryCoroutineContext(Dispatchers.Default).build()
