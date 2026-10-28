package com.kovtun.pokevault.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface PokeApi {

    @GET("pokemon")
    suspend fun getPokemonList(
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): PokemonListResponseDto

    /** Accepts both a numeric id and a lowercase name. */
    @GET("pokemon/{idOrName}")
    suspend fun getPokemon(@Path("idOrName") idOrName: String): PokemonDetailDto

    /** All pokemon of a type — lets the type filter work before details are downloaded. */
    @GET("type/{name}")
    suspend fun getType(@Path("name") name: String): TypeDetailDto

    companion object {
        const val BASE_URL = "https://pokeapi.co/api/v2/"
    }
}
