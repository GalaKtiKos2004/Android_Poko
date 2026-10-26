package com.kovtun.pokevault.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PokemonEntity::class,
        ProfileEntity::class,
        CollectionEntity::class,
        CollectionPokemonEntity::class,
        UserPokemonEntity::class,
        HistoryEntity::class,
        FilterPresetEntity::class,
    ],
    version = 1,
    exportSchema = false,
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
    }
}
