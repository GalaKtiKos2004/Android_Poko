package com.kovtun.pokevault.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kovtun.pokevault.domain.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** App-wide settings: caching and background sync policy. */
data class AppSettings(
    val cacheTtlHours: Int = DEFAULT_TTL_HOURS,
    val backgroundSync: Boolean = true,
    val wifiOnly: Boolean = false,
    val prefetchCount: Int = DEFAULT_PREFETCH,
    /** Last sync that finished with every item updated. */
    val lastSyncAt: Long? = null,
    /** Last sync attempt, successful or not, and how many items it failed to update. */
    val lastSyncAttemptAt: Long? = null,
    val lastSyncFailures: Int = 0,
    val lastListRefreshAt: Long? = null,
    /** Size of the server list, needed to know whether more pages exist. */
    val catalogTotal: Int? = null,
) {
    val lastAttemptIncomplete: Boolean
        get() = lastSyncAttemptAt != null && lastSyncFailures > 0
    companion object {
        const val DEFAULT_TTL_HOURS = 24
        const val DEFAULT_PREFETCH = 151
        val TTL_OPTIONS = listOf(1, 6, 24, 72)
        val PREFETCH_OPTIONS = listOf(151, 251, 386)
    }
}

/** Preferences that belong to one local profile. */
data class ProfileSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val defaultSort: SortOrder = SortOrder.ID,
    val historyEnabled: Boolean = true,
)

/**
 * DataStore holds only preferences and sync metadata.
 * All user content (collections, notes, statuses, history, presets) lives in Room.
 */
interface SettingsRepository {
    val activeProfileId: Flow<Long?>
    val appSettings: Flow<AppSettings>
    fun profileSettings(profileId: Long): Flow<ProfileSettings>

    suspend fun setActiveProfile(profileId: Long?)

    /** Atomically forgets the active profile if it is [profileId] (used when that profile is deleted). */
    suspend fun clearActiveProfileIf(profileId: Long)
    suspend fun setCacheTtlHours(hours: Int)
    suspend fun setBackgroundSync(enabled: Boolean)
    suspend fun setWifiOnly(enabled: Boolean)
    suspend fun setPrefetchCount(count: Int)
    suspend fun setLastListRefreshAt(time: Long?)
    suspend fun setCatalogTotal(total: Int)

    /** Records a sync attempt; [lastSyncAt] moves only when nothing failed. */
    suspend fun recordSyncAttempt(time: Long, failures: Int)

    suspend fun setTheme(profileId: Long, mode: ThemeMode)
    suspend fun setDefaultSort(profileId: Long, sort: SortOrder)
    suspend fun setHistoryEnabled(profileId: Long, enabled: Boolean)
    suspend fun clearProfile(profileId: Long)
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private object Keys {
        val ACTIVE_PROFILE = longPreferencesKey("active_profile")
        val TTL_HOURS = intPreferencesKey("cache_ttl_hours")
        val BACKGROUND_SYNC = booleanPreferencesKey("background_sync")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val PREFETCH_COUNT = intPreferencesKey("prefetch_count")
        val LAST_SYNC = longPreferencesKey("last_sync_at")
        val LAST_LIST_REFRESH = longPreferencesKey("last_list_refresh_at")
        val LAST_SYNC_ATTEMPT = longPreferencesKey("last_sync_attempt_at")
        val LAST_SYNC_FAILURES = intPreferencesKey("last_sync_failures")
        val CATALOG_TOTAL = intPreferencesKey("catalog_total")

        fun theme(profileId: Long) = stringPreferencesKey("p${profileId}_theme")
        fun sort(profileId: Long) = stringPreferencesKey("p${profileId}_sort")
        fun history(profileId: Long) = booleanPreferencesKey("p${profileId}_history")
    }

    override val activeProfileId: Flow<Long?> =
        dataStore.data.map { it[Keys.ACTIVE_PROFILE] }.distinctUntilChanged()

    override val appSettings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            cacheTtlHours = prefs[Keys.TTL_HOURS] ?: AppSettings.DEFAULT_TTL_HOURS,
            backgroundSync = prefs[Keys.BACKGROUND_SYNC] ?: true,
            wifiOnly = prefs[Keys.WIFI_ONLY] ?: false,
            prefetchCount = prefs[Keys.PREFETCH_COUNT] ?: AppSettings.DEFAULT_PREFETCH,
            lastSyncAt = prefs[Keys.LAST_SYNC],
            lastSyncAttemptAt = prefs[Keys.LAST_SYNC_ATTEMPT],
            lastSyncFailures = prefs[Keys.LAST_SYNC_FAILURES] ?: 0,
            lastListRefreshAt = prefs[Keys.LAST_LIST_REFRESH],
            catalogTotal = prefs[Keys.CATALOG_TOTAL],
        )
    }.distinctUntilChanged()

    override fun profileSettings(profileId: Long): Flow<ProfileSettings> = dataStore.data.map { prefs ->
        ProfileSettings(
            theme = prefs[Keys.theme(profileId)]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            defaultSort = prefs[Keys.sort(profileId)]
                ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.ID,
            historyEnabled = prefs[Keys.history(profileId)] ?: true,
        )
    }.distinctUntilChanged()

    override suspend fun setActiveProfile(profileId: Long?) {
        dataStore.edit { prefs ->
            if (profileId == null) {
                prefs.remove(Keys.ACTIVE_PROFILE)
            } else {
                prefs[Keys.ACTIVE_PROFILE] = profileId
            }
        }
    }

    override suspend fun clearActiveProfileIf(profileId: Long) {
        dataStore.edit { prefs ->
            if (prefs[Keys.ACTIVE_PROFILE] == profileId) prefs.remove(Keys.ACTIVE_PROFILE)
        }
    }

    override suspend fun setCatalogTotal(total: Int) {
        dataStore.edit { it[Keys.CATALOG_TOTAL] = total }
    }

    override suspend fun recordSyncAttempt(time: Long, failures: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNC_ATTEMPT] = time
            prefs[Keys.LAST_SYNC_FAILURES] = failures
            if (failures == 0) prefs[Keys.LAST_SYNC] = time
        }
    }

    override suspend fun setCacheTtlHours(hours: Int) {
        dataStore.edit { it[Keys.TTL_HOURS] = hours }
    }

    override suspend fun setBackgroundSync(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKGROUND_SYNC] = enabled }
    }

    override suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    override suspend fun setPrefetchCount(count: Int) {
        dataStore.edit { it[Keys.PREFETCH_COUNT] = count }
    }

    override suspend fun setLastListRefreshAt(time: Long?) {
        dataStore.edit { prefs ->
            if (time == null) {
                prefs.remove(Keys.LAST_LIST_REFRESH)
            } else {
                prefs[Keys.LAST_LIST_REFRESH] = time
            }
        }
    }

    override suspend fun setTheme(profileId: Long, mode: ThemeMode) {
        dataStore.edit { it[Keys.theme(profileId)] = mode.name }
    }

    override suspend fun setDefaultSort(profileId: Long, sort: SortOrder) {
        dataStore.edit { it[Keys.sort(profileId)] = sort.name }
    }

    override suspend fun setHistoryEnabled(profileId: Long, enabled: Boolean) {
        dataStore.edit { it[Keys.history(profileId)] = enabled }
    }

    override suspend fun clearProfile(profileId: Long) {
        dataStore.edit {
            it.remove(Keys.theme(profileId))
            it.remove(Keys.sort(profileId))
            it.remove(Keys.history(profileId))
        }
    }
}
