package com.kovtun.pokevault.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PokemonEntity::class,
        PokemonTypeEntity::class,
        TypeSyncEntity::class,
        ProfileEntity::class,
        CollectionEntity::class,
        CollectionPokemonEntity::class,
        UserPokemonEntity::class,
        HistoryEntity::class,
        FilterPresetEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class PokeVaultDatabase : RoomDatabase() {
    abstract fun pokemonDao(): PokemonDao
    abstract fun profileDao(): ProfileDao
    abstract fun collectionDao(): CollectionDao
    abstract fun userPokemonDao(): UserPokemonDao
    abstract fun historyDao(): HistoryDao
    abstract fun filterPresetDao(): FilterPresetDao

    companion object {
        const val NAME = "pokevault.db"
        val MIGRATIONS = arrayOf(Migration1To2)
    }
}

/**
 * v1 -> v2: paging position (catalogIndex) and a separate type index.
 * User data is untouched. Existing details are copied into pokemon_types; catalogue positions
 * start empty, so the first page is simply downloaded again.
 */
object Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pokemon` ADD COLUMN `catalogIndex` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pokemon_catalogIndex` ON `pokemon` (`catalogIndex`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pokemon_types` (
                `pokemonId` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `slot` INTEGER NOT NULL,
                PRIMARY KEY(`pokemonId`, `type`),
                FOREIGN KEY(`pokemonId`) REFERENCES `pokemon`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pokemon_types_type` ON `pokemon_types` (`type`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `type_sync` (`type` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`type`))",
        )
        // Types of already downloaded details go into the new index.
        db.query("SELECT id, types FROM pokemon WHERE types IS NOT NULL").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getInt(0)
                cursor.getString(1).split(',').filter { it.isNotBlank() }.forEachIndexed { index, type ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO pokemon_types (pokemonId, type, slot) VALUES (?, ?, ?)",
                        arrayOf<Any>(id, type, index + 1),
                    )
                }
            }
        }
    }
}
