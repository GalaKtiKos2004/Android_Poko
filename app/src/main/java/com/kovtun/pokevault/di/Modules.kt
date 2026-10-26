package com.kovtun.pokevault.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.work.WorkManager
import com.kovtun.pokevault.BuildConfig
import com.kovtun.pokevault.core.AndroidConnectivityObserver
import com.kovtun.pokevault.core.ApplicationScope
import com.kovtun.pokevault.core.ConnectivityObserver
import com.kovtun.pokevault.core.IoDispatcher
import com.kovtun.pokevault.core.SystemTimeProvider
import com.kovtun.pokevault.core.TimeProvider
import com.kovtun.pokevault.data.local.CollectionDao
import com.kovtun.pokevault.data.local.FilterPresetDao
import com.kovtun.pokevault.data.local.HistoryDao
import com.kovtun.pokevault.data.local.PokeVaultDatabase
import com.kovtun.pokevault.data.local.PokemonDao
import com.kovtun.pokevault.data.local.ProfileDao
import com.kovtun.pokevault.data.local.UserPokemonDao
import com.kovtun.pokevault.data.remote.PokeApi
import com.kovtun.pokevault.data.repository.CollectionRepository
import com.kovtun.pokevault.data.repository.HistoryRepository
import com.kovtun.pokevault.data.repository.OfflineFirstPokemonRepository
import com.kovtun.pokevault.data.repository.PokemonRepository
import com.kovtun.pokevault.data.repository.PresetRepository
import com.kovtun.pokevault.data.repository.ProfileRepository
import com.kovtun.pokevault.data.repository.RoomCollectionRepository
import com.kovtun.pokevault.data.repository.RoomHistoryRepository
import com.kovtun.pokevault.data.repository.RoomPresetRepository
import com.kovtun.pokevault.data.repository.RoomProfileRepository
import com.kovtun.pokevault.data.repository.RoomUserPokemonRepository
import com.kovtun.pokevault.data.repository.UserPokemonRepository
import com.kovtun.pokevault.data.settings.DataStoreSettingsRepository
import com.kovtun.pokevault.data.settings.SettingsRepository
import com.kovtun.pokevault.domain.SessionRepository
import com.kovtun.pokevault.sync.SyncScheduler
import com.kovtun.pokevault.sync.WorkManagerSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTimeProvider(): TimeProvider = SystemTimeProvider

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") })
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(PokeApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun providePokeApi(retrofit: Retrofit): PokeApi = retrofit.create(PokeApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PokeVaultDatabase =
        Room.databaseBuilder(context, PokeVaultDatabase::class.java, PokeVaultDatabase.NAME).build()

    @Provides fun providePokemonDao(db: PokeVaultDatabase): PokemonDao = db.pokemonDao()
    @Provides fun provideProfileDao(db: PokeVaultDatabase): ProfileDao = db.profileDao()
    @Provides fun provideCollectionDao(db: PokeVaultDatabase): CollectionDao = db.collectionDao()
    @Provides fun provideUserPokemonDao(db: PokeVaultDatabase): UserPokemonDao = db.userPokemonDao()
    @Provides fun provideHistoryDao(db: PokeVaultDatabase): HistoryDao = db.historyDao()
    @Provides fun provideFilterPresetDao(db: PokeVaultDatabase): FilterPresetDao = db.filterPresetDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun bindSettings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun bindPokemon(impl: OfflineFirstPokemonRepository): PokemonRepository
    @Binds abstract fun bindProfiles(impl: RoomProfileRepository): ProfileRepository
    @Binds abstract fun bindSession(impl: RoomProfileRepository): SessionRepository
    @Binds abstract fun bindCollections(impl: RoomCollectionRepository): CollectionRepository
    @Binds abstract fun bindUserPokemon(impl: RoomUserPokemonRepository): UserPokemonRepository
    @Binds abstract fun bindHistory(impl: RoomHistoryRepository): HistoryRepository
    @Binds abstract fun bindPresets(impl: RoomPresetRepository): PresetRepository
    @Binds abstract fun bindConnectivity(impl: AndroidConnectivityObserver): ConnectivityObserver
    @Binds abstract fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler
}
