package com.kovtun.pokevault.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kovtun.pokevault.data.local.PokeVaultDatabase
import com.kovtun.pokevault.data.repository.OfflineFirstPokemonRepository
import com.kovtun.pokevault.domain.PokemonFilter
import com.kovtun.pokevault.testutil.FakeClock
import com.kovtun.pokevault.testutil.FakePokeApi
import com.kovtun.pokevault.testutil.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A database written by version 1 of the app (before paging positions and the type index)
 * is upgraded without losing user data. Room validates the migrated schema on open.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Before
    fun createVersion1() {
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        V1_SCHEMA.forEach(db::execSQL)
        db.execSQL(
            "INSERT INTO pokemon (id, name, spriteUrl, types, listUpdatedAt, detailUpdatedAt) " +
                "VALUES (4, 'charmander', 's', ',fire,', 1, 1), (1, 'bulbasaur', 's', NULL, 1, NULL)",
        )
        db.execSQL("INSERT INTO profiles (id, name, colorIndex, createdAt) VALUES (1, 'Ash', 0, 0)")
        db.execSQL("INSERT INTO user_pokemon VALUES (1, 4, 'CAUGHT', 'first', 0)")
        db.version = 1
        db.close()
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun version1IsMigratedWithUserDataAndTypeIndex() = runTest {
        val db = Room.databaseBuilder(context, PokeVaultDatabase::class.java, name)
            .addMigrations(*PokeVaultDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("CAUGHT", db.userPokemonDao().get(1, 4)?.status)
            assertEquals("first", db.userPokemonDao().get(1, 4)?.note)

            val repository = OfflineFirstPokemonRepository(
                FakePokeApi(), db.pokemonDao(), FakeSettingsRepository(), FakeClock(), Dispatchers.IO,
            )
            val fire = repository.observeList(1L, PokemonFilter(type = "fire")).first()
            assertEquals("old details feed the new type index", listOf(4), fire.map { it.id })
            assertEquals("no catalogue positions yet -> paging starts at 0", 0, db.pokemonDao().catalogLoaded())
        } finally {
            db.close()
        }
    }

    private companion object {
        /** Tables exactly as Room created them for version 1. */
        val V1_SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS `pokemon` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL,
                `spriteUrl` TEXT NOT NULL, `types` TEXT, `heightDm` INTEGER, `weightHg` INTEGER,
                `baseExperience` INTEGER, `stats` TEXT, `abilities` TEXT, `listUpdatedAt` INTEGER NOT NULL,
                `detailUpdatedAt` INTEGER, PRIMARY KEY(`id`))""",
            """CREATE TABLE IF NOT EXISTS `profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL, `colorIndex` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)""",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_profiles_name` ON `profiles` (`name`)",
            """CREATE TABLE IF NOT EXISTS `collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `profileId` INTEGER NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_collections_profileId_name` ON `collections` (`profileId`, `name`)",
            """CREATE TABLE IF NOT EXISTS `collection_pokemon` (`collectionId` INTEGER NOT NULL,
                `pokemonId` INTEGER NOT NULL, `note` TEXT NOT NULL, `addedAt` INTEGER NOT NULL,
                PRIMARY KEY(`collectionId`, `pokemonId`),
                FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
            "CREATE INDEX IF NOT EXISTS `index_collection_pokemon_pokemonId` ON `collection_pokemon` (`pokemonId`)",
            """CREATE TABLE IF NOT EXISTS `user_pokemon` (`profileId` INTEGER NOT NULL, `pokemonId` INTEGER NOT NULL,
                `status` TEXT NOT NULL, `note` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`profileId`, `pokemonId`),
                FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
            "CREATE INDEX IF NOT EXISTS `index_user_pokemon_pokemonId` ON `user_pokemon` (`pokemonId`)",
            """CREATE TABLE IF NOT EXISTS `history` (`profileId` INTEGER NOT NULL, `pokemonId` INTEGER NOT NULL,
                `viewedAt` INTEGER NOT NULL, `viewCount` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `pokemonId`),
                FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
            "CREATE INDEX IF NOT EXISTS `index_history_pokemonId` ON `history` (`pokemonId`)",
            """CREATE TABLE IF NOT EXISTS `filter_presets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `profileId` INTEGER NOT NULL, `name` TEXT NOT NULL, `query` TEXT NOT NULL, `type` TEXT,
                `status` TEXT, `sort` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_filter_presets_profileId_name` ON `filter_presets` (`profileId`, `name`)",
        )
    }
}
