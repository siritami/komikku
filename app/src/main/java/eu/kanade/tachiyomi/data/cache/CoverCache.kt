package eu.kanade.tachiyomi.data.cache

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Class used to create cover cache.
 * It is used to store the covers of the library.
 * Names of files are created with the md5 of the thumbnail URL.
 *
 * Library thumbnails are stored in the user-picked storage location
 * under library/thumbnail via SAF (UniFile).
 *
 * @param context the application context.
 * @constructor creates an instance of the cover cache.
 */
class CoverCache(private val context: Context) {

    private val storageManager: StorageManager by injectLazy()

    companion object {
        private const val CUSTOM_COVERS_DIR = "covers/custom"
    }

    /**
     * Library thumbnail directory in user-picked storage via SAF.
     */
    private val libraryThumbnailDir: UniFile? by lazy {
        try {
            storageManager.getLibraryThumbnailDirectory()
        } catch (e: Exception) {
            null
        }
    }

    private val customCoverCacheDir = getAppDataDir(CUSTOM_COVERS_DIR)

    /**
     * Finds existing cached cover in user storage via SAF.
     * Returns null if not found, empty, or user storage is unavailable.
     *
     * @param mangaThumbnailUrl thumbnail url for the manga.
     * @return UniFile of the cached cover, or null.
     */
    fun findCoverUniFile(mangaThumbnailUrl: String?): UniFile? {
        return mangaThumbnailUrl?.let { url ->
            val hash = DiskUtil.hashKeyForDisk(url)
            libraryThumbnailDir?.findFile(hash)?.takeIf { it.exists() && it.length() > 0 }
        }
    }

    /**
     * Gets or creates a UniFile for writing cover data to user storage via SAF.
     * Returns null if user storage is unavailable.
     *
     * @param mangaThumbnailUrl thumbnail url for the manga.
     * @return UniFile for writing, or null.
     */
    fun getOrCreateCoverUniFile(mangaThumbnailUrl: String?): UniFile? {
        return mangaThumbnailUrl?.let { url ->
            val hash = DiskUtil.hashKeyForDisk(url)
            libraryThumbnailDir?.let { dir ->
                dir.findFile(hash) ?: dir.createFile(hash)
            }
        }
    }

    /**
     * Returns the custom cover from cache.
     *
     * @param mangaId the manga id.
     * @return cover image.
     */
    fun getCustomCoverFile(mangaId: Long?): File {
        return File(customCoverCacheDir, DiskUtil.hashKeyForDisk(mangaId.toString()))
    }

    /**
     * Saves the given stream as the manga's custom cover to cache.
     *
     * @param manga the manga.
     * @param inputStream the stream to copy.
     * @throws IOException if there's any error.
     */
    @Throws(IOException::class)
    fun setCustomCoverToCache(manga: Manga, inputStream: InputStream) {
        getCustomCoverFile(manga.id).outputStream().use {
            inputStream.copyTo(it)
        }
    }

    /**
     * Delete the cover files of the manga from the cache.
     *
     * @param manga the manga.
     * @param deleteCustomCover whether the custom cover should be deleted.
     * @return number of files that were deleted.
     */
    fun deleteFromCache(manga: Manga, deleteCustomCover: Boolean = false): Int {
        var deleted = 0

        manga.thumbnailUrl?.let { url ->
            val hash = DiskUtil.hashKeyForDisk(url)
            // Delete from user storage (SAF)
            libraryThumbnailDir?.findFile(hash)?.let {
                if (it.exists() && it.delete()) ++deleted
            }
        }

        if (deleteCustomCover) {
            if (deleteCustomCover(manga.id)) ++deleted
        }

        return deleted
    }

    /**
     * Delete custom cover of the manga from the cache
     *
     * @param mangaId the manga id.
     * @return whether the cover was deleted.
     */
    fun deleteCustomCover(mangaId: Long?): Boolean {
        return getCustomCoverFile(mangaId).let {
            it.exists() && it.delete()
        }
    }

    private fun getAppDataDir(dir: String): File {
        return context.getExternalFilesDir(dir)
            ?: File(context.filesDir, dir).also { it.mkdirs() }
    }
}
