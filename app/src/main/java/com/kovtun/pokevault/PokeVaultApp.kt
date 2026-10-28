package com.kovtun.pokevault

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.kovtun.pokevault.sync.SyncScheduler
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PokeVaultApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // Lazy: WorkManager must not be created before workerFactory is injected.
    @Inject
    lateinit var syncScheduler: Lazy<SyncScheduler>

    @Inject
    lateinit var imageLoader: Lazy<ImageLoader>

    /** AsyncImage uses the same loader / disk cache that offline prefetch fills. */
    override fun newImageLoader(): ImageLoader = imageLoader.get()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        syncScheduler.get().start()
    }
}
