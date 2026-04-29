package eu.kanade.tachiyomi.data.coil

import androidx.core.net.toUri
import coil3.Extras
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.getOrDefault
import coil3.request.Options
import com.hippo.unifile.UniFile
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher.Companion.USE_CUSTOM_COVER_KEY
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException

/**
 * A [Fetcher] that fetches cover image for [Manga] object.
 *
 * It uses [Manga.thumbnailUrl] if custom cover is not set by the user.
 * Disk caching for library items is handled by [CoverCache], otherwise
 * handled by Coil's [DiskCache].
 *
 * Available request parameter:
 * - [USE_CUSTOM_COVER_KEY]: Use custom cover if set by user, default is true
 */
class MangaCoverFetcher(
    // KMK -->
    private val mangaCover: MangaCover,
    private val url: String? = mangaCover.url,
    // private val url: String?,
    // KMK <--
    private val isLibraryManga: Boolean,
    private val options: Options,
    private val customCoverFileLazy: Lazy<File>,
    private val diskCacheKeyLazy: Lazy<String>,
    private val sourceLazy: Lazy<HttpSource?>,
    private val callFactoryLazy: Lazy<Call.Factory>,
    private val imageLoader: ImageLoader,
    private val coverCache: CoverCache,
) : Fetcher {

    // KMK -->
    private val scope by lazy { CoroutineScope(Dispatchers.IO) }
    private val uiPreferences = Injekt.get<UiPreferences>()
    private val themeCoverBased = uiPreferences.themeCoverBased().get()
    private val preloadLibraryColor = uiPreferences.preloadLibraryColor().get()
    // KMK <--

    private val diskCacheKey: String
        get() = diskCacheKeyLazy.value

    /**
     * Called each time a cover is displayed
     */
    override suspend fun fetch(): FetchResult {
        // Use custom cover if exists
        val useCustomCover = options.extras.getOrDefault(USE_CUSTOM_COVER_KEY)
        if (useCustomCover) {
            val customCoverFile = customCoverFileLazy.value
            if (customCoverFile.exists()) {
                return fileLoader(customCoverFile)
            }
        }

        // diskCacheKey is thumbnail_url
        if (url == null) error("No cover specified")
        return when (getResourceType(url)) {
            Type.File -> fileLoader(File(url.substringAfter("file://")))
            Type.URI -> fileUriLoader(url)
            Type.URL -> httpLoader()
            null -> error("Invalid image")
        }
    }

    private fun fileLoader(file: File): FetchResult {
        // KMK -->
        setRatioAndColorsInScope(mangaCover, ogFile = file)
        // KMK <--
        return SourceFetchResult(
            source = ImageSource(
                file = file.toOkioPath(),
                fileSystem = FileSystem.SYSTEM,
                diskCacheKey = diskCacheKey,
            ),
            mimeType = "image/*",
            dataSource = DataSource.DISK,
        )
    }

    private fun fileUriLoader(uri: String): FetchResult {
        // KMK -->
        setRatioAndColorsInScope(mangaCover)
        // KMK <--
        val source = UniFile.fromUri(options.context, uri.toUri())!!
            .openInputStream()
            .source()
            .buffer()
        return SourceFetchResult(
            source = ImageSource(source = source, fileSystem = FileSystem.SYSTEM),
            mimeType = "image/*",
            dataSource = DataSource.DISK,
        )
    }

    private suspend fun httpLoader(): FetchResult {
        // Only cache separately if it's a library item
        // Use user storage (UniFile/SAF) for library covers
        val libraryCoverUniFile = if (isLibraryManga) coverCache.findCoverUniFile(url) else null

        // Check cached in user storage (SAF)
        if (libraryCoverUniFile != null && options.diskCachePolicy.readEnabled) {
            return uniFileLoader(libraryCoverUniFile)
        }

        // Determine write target for library covers
        val writeUniFile = if (isLibraryManga) coverCache.getOrCreateCoverUniFile(url) else null

        var snapshot = readFromDiskCache()
        try {
            // Fetch from disk cache
            if (snapshot != null) {
                if (writeUniFile != null) {
                    val result = moveSnapshotToCoverCacheUniFile(snapshot, writeUniFile)
                    if (result != null) return result
                }

                // Read from snapshot
                // KMK -->
                setRatioAndColorsInScope(mangaCover, bufferedSource = snapshot.toImageSource().source())
                // KMK <--
                return SourceFetchResult(
                    source = snapshot.toImageSource(),
                    mimeType = "image/*",
                    dataSource = DataSource.DISK,
                )
            }

            // Fetch from network
            val response = executeNetworkRequest()
            val responseBody = checkNotNull(response.body) { "Null response source" }
            try {
                // Write to cover cache in user storage
                if (writeUniFile != null) {
                    val result = writeResponseToCoverCacheUniFile(response, writeUniFile)
                    if (result != null) return result
                }

                // Read from disk cache
                snapshot = writeToDiskCache(response)
                if (snapshot != null) {
                    // KMK -->
                    setRatioAndColorsInScope(mangaCover, bufferedSource = snapshot.toImageSource().source())
                    // KMK <--
                    return SourceFetchResult(
                        source = snapshot.toImageSource(),
                        mimeType = "image/*",
                        dataSource = DataSource.NETWORK,
                    )
                }

                // KMK -->
                setRatioAndColorsInScope(
                    mangaCover,
                    bufferedSource = ImageSource(
                        source = responseBody.source(),
                        fileSystem = FileSystem.SYSTEM,
                    ).source(),
                )
                // KMK <--
                // Read from response if cache is unused or unusable
                return SourceFetchResult(
                    source = ImageSource(source = responseBody.source(), fileSystem = FileSystem.SYSTEM),
                    mimeType = "image/*",
                    dataSource = if (response.cacheResponse != null) DataSource.DISK else DataSource.NETWORK,
                )
            } catch (e: Exception) {
                responseBody.close()
                throw e
            }
        } catch (e: Exception) {
            snapshot?.close()
            throw e
        }
    }

    private suspend fun executeNetworkRequest(): Response {
        val client = sourceLazy.value?.client ?: callFactoryLazy.value
        val response = client.newCall(newRequest()).await()
        if (!response.isSuccessful && response.code != HTTP_NOT_MODIFIED) {
            response.close()
            throw IOException(response.message)
        }
        return response
    }

    private fun newRequest(): Request {
        val request = Request.Builder().apply {
            url(url!!)

            val sourceHeaders = sourceLazy.value?.headers
            if (sourceHeaders != null) {
                headers(sourceHeaders)
            }
        }

        when {
            options.networkCachePolicy.readEnabled -> {
                // don't take up okhttp cache
                request.cacheControl(CACHE_CONTROL_NO_STORE)
            }
            else -> {
                // This causes the request to fail with a 504 Unsatisfiable Request.
                request.cacheControl(CACHE_CONTROL_NO_NETWORK_NO_CACHE)
            }
        }

        return request.build()
    }

    private fun uniFileLoader(uniFile: UniFile): FetchResult {
        // KMK -->
        setRatioAndColorsInScope(mangaCover)
        // KMK <--
        val source = uniFile.openInputStream().source().buffer()
        return SourceFetchResult(
            source = ImageSource(source = source, fileSystem = FileSystem.SYSTEM),
            mimeType = "image/*",
            dataSource = DataSource.DISK,
        )
    }

    private fun moveSnapshotToCoverCacheUniFile(snapshot: DiskCache.Snapshot, uniFile: UniFile): FetchResult? {
        return try {
            imageLoader.diskCache?.run {
                fileSystem.source(snapshot.data).use { input ->
                    writeSourceToCoverCacheUniFile(input, uniFile)
                }
                remove(diskCacheKey)
            }
            if (uniFile.exists() && uniFile.length() > 0) uniFileLoader(uniFile) else null
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write snapshot data to UniFile cover cache ${uniFile.name}" }
            null
        }
    }

    private fun writeResponseToCoverCacheUniFile(response: Response, uniFile: UniFile): FetchResult? {
        if (!options.diskCachePolicy.writeEnabled) return null
        return try {
            response.peekBody(Long.MAX_VALUE).source().use { input ->
                writeSourceToCoverCacheUniFile(input, uniFile)
            }
            if (uniFile.exists() && uniFile.length() > 0) uniFileLoader(uniFile) else null
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write response data to UniFile cover cache ${uniFile.name}" }
            null
        }
    }

    private fun writeSourceToCoverCacheUniFile(input: Source, uniFile: UniFile) {
        try {
            uniFile.openOutputStream().sink().buffer().use { output ->
                output.writeAll(input)
            }
        } catch (e: Exception) {
            uniFile.delete()
            throw e
        }
    }

    private fun readFromDiskCache(): DiskCache.Snapshot? {
        return if (options.diskCachePolicy.readEnabled) {
            imageLoader.diskCache?.openSnapshot(diskCacheKey)
        } else {
            null
        }
    }

    private fun writeToDiskCache(
        response: Response,
    ): DiskCache.Snapshot? {
        val diskCache = imageLoader.diskCache
        val editor = diskCache?.openEditor(diskCacheKey) ?: return null
        try {
            diskCache.fileSystem.write(editor.data) {
                response.body.source().readAll(this)
            }
            return editor.commitAndOpenSnapshot()
        } catch (e: Exception) {
            try {
                editor.abort()
            } catch (ignored: Exception) {
            }
            throw e
        }
    }

    private fun DiskCache.Snapshot.toImageSource(): ImageSource {
        return ImageSource(
            file = data,
            fileSystem = FileSystem.SYSTEM,
            diskCacheKey = diskCacheKey,
            closeable = this,
        )
    }

    private fun getResourceType(cover: String?): Type? {
        return when {
            cover.isNullOrEmpty() -> null
            cover.startsWith("http", true) || cover.startsWith("Custom-", true) -> Type.URL
            cover.startsWith("/") || cover.startsWith("file://") -> Type.File
            cover.startsWith("content") -> Type.URI
            else -> null
        }
    }

    // KMK -->
    /**
     * [setRatioAndColorsInScope] is called whenever a cover is loaded with [MangaCoverFetcher.fetch]
     *
     * @param bufferedSource if not null then it will load bitmap from [BufferedSource], regardless of [ogFile]
     * @param ogFile if not null then it will load bitmap from [File]. If it's null then it will try to load bitmap
     *  from [CoverCache] using either [CoverCache.customCoverCacheDir] or [CoverCache.cacheDir]
     * @param force if true then it will always re-calculate ratio & color for favorite mangas.
     */
    private fun setRatioAndColorsInScope(
        mangaCover: MangaCover,
        bufferedSource: BufferedSource? = null,
        ogFile: File? = null,
        onlyFavorite: Boolean = !themeCoverBased,
        force: Boolean = false,
    ) {
        if (!preloadLibraryColor) return
        scope.launch {
            MangaCoverMetadata.setRatioAndColors(mangaCover, bufferedSource, ogFile, onlyFavorite, force)
        }
    }
    // KMK <--

    private enum class Type {
        File,
        URI,
        URL,
    }

    class MangaFactory(
        private val callFactoryLazy: Lazy<Call.Factory>,
    ) : Fetcher.Factory<Manga> {

        private val coverCache: CoverCache by injectLazy()
        private val sourceManager: SourceManager by injectLazy()

        override fun create(data: Manga, options: Options, imageLoader: ImageLoader): Fetcher {
            return MangaCoverFetcher(
                // KMK -->
                // url = data.thumbnailUrl,
                mangaCover = data.asMangaCover(),
                // KMK <--
                isLibraryManga = data.favorite,
                options = options,
                customCoverFileLazy = lazy { coverCache.getCustomCoverFile(data.id) },
                diskCacheKeyLazy = lazy { imageLoader.components.key(data, options)!! },
                sourceLazy = lazy { sourceManager.get(data.source) as? HttpSource },
                callFactoryLazy = callFactoryLazy,
                imageLoader = imageLoader,
                coverCache = coverCache,
            )
        }
    }

    class MangaCoverFactory(
        private val callFactoryLazy: Lazy<Call.Factory>,
    ) : Fetcher.Factory<MangaCover> {

        private val coverCache: CoverCache by injectLazy()
        private val sourceManager: SourceManager by injectLazy()

        override fun create(data: MangaCover, options: Options, imageLoader: ImageLoader): Fetcher {
            return MangaCoverFetcher(
                // KMK -->
                // url = data.url,
                mangaCover = data,
                // KMK <--
                isLibraryManga = data.isMangaFavorite,
                options = options,
                customCoverFileLazy = lazy { coverCache.getCustomCoverFile(data.mangaId) },
                diskCacheKeyLazy = lazy { imageLoader.components.key(data, options)!! },
                sourceLazy = lazy { sourceManager.get(data.sourceId) as? HttpSource },
                callFactoryLazy = callFactoryLazy,
                imageLoader = imageLoader,
                coverCache = coverCache,
            )
        }
    }

    companion object {
        val USE_CUSTOM_COVER_KEY = Extras.Key(true)

        private val CACHE_CONTROL_NO_STORE = CacheControl.Builder().noStore().build()
        private val CACHE_CONTROL_NO_NETWORK_NO_CACHE = CacheControl.Builder().noCache().onlyIfCached().build()

        private const val HTTP_NOT_MODIFIED = 304
    }
}
