package com.kovtun.pokevault.sync

import android.content.Context
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Puts images into the same disk cache the UI (Coil AsyncImage) reads from. */
interface ImagePrefetcher {
    /** Makes sure every url is stored on disk; returns how many could not be downloaded. */
    suspend fun ensureCached(urls: List<String>): Int
}

/**
 * Uses the app-wide [ImageLoader] (see ImageModule): its disk cache lives in filesDir and ignores
 * HTTP cache headers, so an image downloaded here is shown later without network.
 * Already cached urls are skipped, so a retry only downloads what is missing.
 */
@Singleton
class CoilImagePrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
) : ImagePrefetcher {

    override suspend fun ensureCached(urls: List<String>): Int {
        var failed = 0
        for (url in urls) {
            if (isOnDisk(url)) continue
            val request = ImageRequest.Builder(context)
                .data(url)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
            if (imageLoader.execute(request) !is SuccessResult) failed++
        }
        return failed
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun isOnDisk(url: String): Boolean =
        imageLoader.diskCache?.openSnapshot(url)?.use { true } ?: false
}
