package me.earzuchan.chatdrama.client.di

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.WebOpfsStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import me.earzuchan.chatdrama.client.data.DATASTORE_FILENAME
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single { createPreferencesDataStore() }

    /*
    single<AppDatabase> { createDatabase() }
    single { get<AppDatabase>().chatDao() }
    */
}

private fun createPreferencesDataStore() = DataStoreFactory.create(WebOpfsStorage(PreferencesSerializer, DATASTORE_FILENAME))

/*
private fun createDatabase() = databaseBuilder<AppDatabase>("chat-drama.db")
        .setDriver(androidx.sqlite.driver.web.WebWorkerSQLiteDriver(createSqliteWorker()))
        .build()

private fun createSqliteWorker() = Worker(js("""new URL("dw.js", import.meta.url)""")) // dw：db worker
*/
