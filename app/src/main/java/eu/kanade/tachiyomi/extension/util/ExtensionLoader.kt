package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import com.hippo.unifile.UniFile
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.injectLazy
import java.io.File

/**
 * Class that handles the loading of the extensions. Supports two kinds of extensions:
 *
 * 1. Shared extension: This extension is installed to the system with package
 * installer, so other variants of Tachiyomi and its forks can also use this extension.
 *
 * 2. User storage extension: This extension APK is stored in user-picked storage
 * under the extension/ directory via SAF, organized by repo hash subdirectories.
 *
 * When both kinds of extensions are installed with a same package name, shared
 * extension will be used unless the version codes are different. In that case the
 * one with higher version code will be used.
 */
internal object ExtensionLoader {

    private val preferences: SourcePreferences by injectLazy()
    private val trustExtension: TrustExtension by injectLazy()

    // KMK -->
    private val getExtensionRepo: GetExtensionRepo by injectLazy()
    private val storageManager: StorageManager by injectLazy()
    // KMK <--

    private val loadNsfwSource by lazy {
        preferences.showNsfwSource().get()
    }

    private const val EXTENSION_FEATURE = "tachiyomi.extension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
    private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
    const val LIB_VERSION_MIN = 1.4
    const val LIB_VERSION_MAX = 1.5

    @Suppress("DEPRECATION")
    private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private const val APK_EXTENSION = "apk"

    private fun getExtensionBaseDir(): UniFile? {
        return try {
            storageManager.getExtensionDirectory()
        } catch (e: Exception) {
            null
        }
    }

    private fun getExtensionRepoDir(repoBaseUrl: String): UniFile? {
        val hash = Hash.sha256(repoBaseUrl.toByteArray()).take(16)
        return getExtensionBaseDir()?.createDirectory(hash)
    }

    private fun findExtensionFile(pkgName: String): UniFile? {
        val baseDir = getExtensionBaseDir() ?: return null
        val fileName = "$pkgName.$APK_EXTENSION"
        // Check root (private extensions)
        baseDir.findFile(fileName)?.takeIf { it.isFile }?.let { return it }
        // Check repo subdirectories
        baseDir.listFiles()?.forEach { subDir ->
            if (subDir.isDirectory) {
                subDir.findFile(fileName)?.takeIf { it.isFile }?.let { return it }
            }
        }
        return null
    }

    fun installPrivateExtension(context: Context, file: File): Boolean {
        val extension = context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
            ?.takeIf { isPackageAnExtension(it) } ?: return false
        val currentExtension = getExtensionPackageInfoFromPkgName(context, extension.packageName)

        if (currentExtension != null) {
            if (PackageInfoCompat.getLongVersionCode(extension) <
                PackageInfoCompat.getLongVersionCode(currentExtension)
            ) {
                logcat(LogPriority.ERROR) { "Installed extension version is higher. Downgrading is not allowed." }
                return false
            }

            val extensionSignatures = getSignatures(extension)
            if (extensionSignatures.isNullOrEmpty()) {
                logcat(LogPriority.ERROR) { "Extension to be installed is not signed." }
                return false
            }

            if (!extensionSignatures.containsAll(getSignatures(currentExtension)!!)) {
                logcat(LogPriority.ERROR) { "Installed extension signature is not matched." }
                return false
            }
        }

        val baseDir = getExtensionBaseDir() ?: return false
        val fileName = "${extension.packageName}.$APK_EXTENSION"
        baseDir.findFile(fileName)?.delete()
        val target = baseDir.createFile(fileName) ?: return false
        return try {
            target.openOutputStream().use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            if (currentExtension != null) {
                ExtensionInstallReceiver.notifyReplaced(context, extension.packageName)
            } else {
                ExtensionInstallReceiver.notifyAdded(context, extension.packageName)
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to copy extension file." }
            target.delete()
            false
        }
    }

    fun installRepoExtension(context: Context, file: File, repoBaseUrl: String): Boolean {
        val extension = context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
            ?.takeIf { isPackageAnExtension(it) } ?: return false
        val currentExtension = getExtensionPackageInfoFromPkgName(context, extension.packageName)

        if (currentExtension != null) {
            if (PackageInfoCompat.getLongVersionCode(extension) <
                PackageInfoCompat.getLongVersionCode(currentExtension)
            ) {
                logcat(LogPriority.ERROR) { "Installed extension version is higher. Downgrading is not allowed." }
                return false
            }

            val extensionSignatures = getSignatures(extension)
            if (extensionSignatures.isNullOrEmpty()) {
                logcat(LogPriority.ERROR) { "Extension to be installed is not signed." }
                return false
            }

            if (!extensionSignatures.containsAll(getSignatures(currentExtension)!!)) {
                logcat(LogPriority.ERROR) { "Installed extension signature is not matched." }
                return false
            }
        }

        val repoDir = getExtensionRepoDir(repoBaseUrl) ?: return false
        val fileName = "${extension.packageName}.$APK_EXTENSION"
        repoDir.findFile(fileName)?.delete()
        val target = repoDir.createFile(fileName) ?: return false
        return try {
            target.openOutputStream().use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            if (currentExtension != null) {
                ExtensionInstallReceiver.notifyReplaced(context, extension.packageName)
            } else {
                ExtensionInstallReceiver.notifyAdded(context, extension.packageName)
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to copy extension file." }
            target.delete()
            false
        }
    }

    fun uninstallExtension(pkgName: String) {
        findExtensionFile(pkgName)?.delete()
    }

    /**
     * Return a list of all the available extensions initialized concurrently.
     *
     * @param context The application context.
     */
    fun loadExtensions(context: Context): List<LoadResult> {
        val pkgManager = context.packageManager

        val installedPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            pkgManager.getInstalledPackages(PACKAGE_FLAGS)
        }

        val sharedExtPkgs = installedPkgs
            .asSequence()
            .filter { isPackageAnExtension(it) }
            .map { ExtensionInfo(packageInfo = it, isShared = true) }

        val userStorageExtPkgs = loadUserStorageExtensions(pkgManager)

        val extPkgs = (sharedExtPkgs + userStorageExtPkgs.asSequence())
            // Remove duplicates. Shared takes priority over user storage by default
            .distinctBy { it.packageInfo.packageName }
            // Compare version number
            .mapNotNull { pkg ->
                val localPkg = userStorageExtPkgs
                    .firstOrNull { it.packageInfo.packageName == pkg.packageInfo.packageName }
                selectExtensionPackage(pkg, localPkg)
            }
            .toList()

        if (extPkgs.isEmpty()) return emptyList()

        // Load each extension concurrently and wait for completion
        return runBlocking {
            // KMK -->
            val extRepos = getExtensionRepo.getAll()
            // KMK <--
            val deferred = extPkgs.map {
                async {
                    loadExtension(
                        context,
                        it,
                        // KMK -->
                        extRepos,
                        // KMK <--
                    )
                }
            }
            deferred.awaitAll()
        }
    }

    /**
     * Scans user storage for extension APK files.
     * Root-level files are private extensions, subdirectory files are repo extensions.
     */
    private fun loadUserStorageExtensions(pkgManager: PackageManager): List<ExtensionInfo> {
        val baseDir = getExtensionBaseDir() ?: return emptyList()
        val result = mutableListOf<ExtensionInfo>()

        baseDir.listFiles()?.forEach { entry ->
            if (entry.isFile && entry.name?.endsWith(".$APK_EXTENSION") == true) {
                loadExtensionInfoFromUniFile(entry, pkgManager)?.let { result.add(it) }
            } else if (entry.isDirectory) {
                entry.listFiles()?.forEach { extFile ->
                    if (extFile.isFile && extFile.name?.endsWith(".$APK_EXTENSION") == true) {
                        loadExtensionInfoFromUniFile(extFile, pkgManager)?.let { result.add(it) }
                    }
                }
            }
        }

        return result
    }

    private fun loadExtensionInfoFromUniFile(uniFile: UniFile, pkgManager: PackageManager): ExtensionInfo? {
        val path = uniFile.filePath ?: return null
        // Android 14+ requires loaded DEX files to be read-only
        val file = File(path)
        if (file.canWrite()) {
            file.setReadOnly()
        }
        return pkgManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
            ?.apply { applicationInfo!!.fixBasePaths(path) }
            ?.takeIf { isPackageAnExtension(it) }
            ?.let { ExtensionInfo(packageInfo = it, isShared = false) }
    }

    /**
     * Attempts to load an extension from the given package name. It checks if the extension
     * contains the required feature flag before trying to load it.
     */
    suspend fun loadExtensionFromPkgName(context: Context, pkgName: String): LoadResult {
        val extensionPackage = getExtensionInfoFromPkgName(context, pkgName)
        if (extensionPackage == null) {
            logcat(LogPriority.ERROR) { "Extension package is not found ($pkgName)" }
            return LoadResult.Error
        }
        return loadExtension(context, extensionPackage)
    }

    fun getExtensionPackageInfoFromPkgName(context: Context, pkgName: String): PackageInfo? {
        return getExtensionInfoFromPkgName(context, pkgName)?.packageInfo
    }

    private fun getExtensionInfoFromPkgName(context: Context, pkgName: String): ExtensionInfo? {
        val extensionFile = findExtensionFile(pkgName)
        val localPkg = extensionFile?.let { uniFile ->
            val path = uniFile.filePath ?: return@let null
            context.packageManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
                ?.takeIf { isPackageAnExtension(it) }
                ?.let {
                    it.applicationInfo!!.fixBasePaths(path)
                    ExtensionInfo(
                        packageInfo = it,
                        isShared = false,
                    )
                }
        }

        val sharedPkg = try {
            context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
                .takeIf { isPackageAnExtension(it) }
                ?.let {
                    ExtensionInfo(
                        packageInfo = it,
                        isShared = true,
                    )
                }
        } catch (error: PackageManager.NameNotFoundException) {
            null
        }

        return selectExtensionPackage(sharedPkg, localPkg)
    }

    /**
     * Loads an extension
     *
     * @param context The application context.
     * @param extensionInfo The extension to load.
     */
    private suspend fun loadExtension(
        context: Context,
        extensionInfo: ExtensionInfo,
        // KMK -->
        extRepos: List<ExtensionRepo>? = null,
        // KMK <--
    ): LoadResult {
        // KMK -->
        val repos = extRepos ?: getExtensionRepo.getAll()
        // KMK <--
        val pkgManager = context.packageManager
        val pkgInfo = extensionInfo.packageInfo
        val appInfo = pkgInfo.applicationInfo!!
        val pkgName = pkgInfo.packageName

        val extName = pkgManager.getApplicationLabel(appInfo).toString().substringAfter("Tachiyomi: ")
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        if (versionName.isNullOrEmpty()) {
            logcat(LogPriority.WARN) { "Missing versionName for extension $extName" }
            return LoadResult.Error
        }

        // Validate lib version
        val libVersion = versionName.substringBeforeLast('.').toDoubleOrNull()
        if (libVersion == null || libVersion < LIB_VERSION_MIN || libVersion > LIB_VERSION_MAX) {
            logcat(LogPriority.WARN) {
                "Lib version is $libVersion, while only versions " +
                    "$LIB_VERSION_MIN to $LIB_VERSION_MAX are allowed"
            }
            return LoadResult.Error
        }

        val signatures = getSignatures(pkgInfo)
        if (signatures.isNullOrEmpty()) {
            logcat(LogPriority.WARN) { "Package $pkgName isn't signed" }
            return LoadResult.Error
        } else if (!trustExtension.isTrusted(pkgInfo, signatures)) {
            val extension = Extension.Untrusted(
                extName,
                pkgName,
                versionName,
                versionCode,
                libVersion,
                signatures.last(),
                // KMK -->
                repoName = repos.firstOrNull { repo ->
                    signatures.all { it == repo.signingKeyFingerprint }
                }?.let { repo ->
                    repo.shortName.takeIf { !it.isNullOrBlank() } ?: repo.name
                },
                // KMK <--
            )
            logcat(LogPriority.WARN) { "Extension $pkgName isn't trusted" }
            return LoadResult.Untrusted(extension)
        }

        val isNsfw = appInfo.metaData.getInt(METADATA_NSFW) == 1
        if (!loadNsfwSource && isNsfw) {
            logcat(LogPriority.WARN) { "NSFW extension $pkgName not allowed" }
            return LoadResult.Error
        }

        val classLoader = try {
            ChildFirstPathClassLoader(appInfo.sourceDir, null, context.classLoader)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Extension load error: $extName ($pkgName)" }
            return LoadResult.Error
        }

        val sources = appInfo.metaData.getString(METADATA_SOURCE_CLASS)!!
            .split(";")
            .map {
                val sourceClass = it.trim()
                if (sourceClass.startsWith(".")) {
                    pkgInfo.packageName + sourceClass
                } else {
                    sourceClass
                }
            }
            .flatMap {
                try {
                    when (val obj = Class.forName(it, false, classLoader).getDeclaredConstructor().newInstance()) {
                        is Source -> listOf(obj)
                        is SourceFactory -> obj.createSources()
                        else -> throw Exception("Unknown source class type: ${obj.javaClass}")
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Extension load error: $extName ($it)" }
                    return LoadResult.Error
                }
            }

        val langs = sources.filterIsInstance<CatalogueSource>()
            .map { it.lang }
            .toSet()
        val lang = when (langs.size) {
            0 -> ""
            1 -> langs.first()
            else -> "all"
        }

        val extension = Extension.Installed(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            lang = lang,
            isNsfw = isNsfw,
            sources = sources,
            pkgFactory = appInfo.metaData.getString(METADATA_SOURCE_FACTORY),
            icon = appInfo.loadIcon(pkgManager),
            isShared = extensionInfo.isShared,
            // KMK -->
            signatureHash = signatures.last(),
            repoName = repos.firstOrNull { repo ->
                signatures.all { it == repo.signingKeyFingerprint }
            }?.let { repo ->
                repo.shortName.takeIf { !it.isNullOrBlank() } ?: repo.name
            },
            // KMK <--
        )
        return LoadResult.Success(extension)
    }

    /**
     * Choose which extension package to use based on version code
     *
     * @param shared extension installed to system
     * @param private extension installed to data directory
     */
    private fun selectExtensionPackage(shared: ExtensionInfo?, private: ExtensionInfo?): ExtensionInfo? {
        when {
            private == null && shared != null -> return shared
            shared == null && private != null -> return private
            shared == null && private == null -> return null
        }

        return if (PackageInfoCompat.getLongVersionCode(shared!!.packageInfo) >=
            PackageInfoCompat.getLongVersionCode(private!!.packageInfo)
        ) {
            shared
        } else {
            private
        }
    }

    /**
     * Returns true if the given package is an extension.
     *
     * @param pkgInfo The package info of the application.
     */
    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
        return pkgInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }
    }

    /**
     * Returns the signatures of the package or null if it's not signed.
     *
     * @param pkgInfo The package info of the application.
     * @return List SHA256 digest of the signatures
     */
    private fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo!!
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
            ?.map { Hash.sha256(it.toByteArray()) }
            ?.toList()
    }

    /**
     * On Android 13+ the ApplicationInfo generated by getPackageArchiveInfo doesn't
     * have sourceDir which breaks assets loading (used for getting icon here).
     */
    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) {
            sourceDir = apkPath
        }
        if (publicSourceDir == null) {
            publicSourceDir = apkPath
        }
    }

    private data class ExtensionInfo(
        val packageInfo: PackageInfo,
        val isShared: Boolean,
    )
}
