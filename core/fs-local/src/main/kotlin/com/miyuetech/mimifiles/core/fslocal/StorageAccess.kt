package com.miyuetech.mimifiles.core.fslocal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.miyuetech.mimifiles.core.model.FsEntry

/**
 * 存储访问档位 —— **三档自动降级**的核心概念。
 *
 * Android 的存储权限史在 API 24 → 36 之间改了四次（传统读写 → 分区存储 →
 * 全盘访问 → 细分媒体权限），一台设备到底能看多少，取决于系统版本、用户逐个点了什么、
 * 以及有没有授权过 SAF 目录。与其在每个 IO 点写 `if`，不如把结果**收敛成一个枚举**，
 * 上层只针对三个分支写 UI。
 */
enum class StorageTier {

    /**
     * 完整档：拿到全盘文件访问（Android 11+ 的 `MANAGE_EXTERNAL_STORAGE`）
     * 或传统外部存储读写（Android 10 及以下）。可以浏览内部存储与 SD 卡。
     */
    FULL,

    /**
     * SAF 档：用户没有给全盘访问，但逐个授权过若干目录。
     * 能做的事和完整档几乎一样，只是**范围被限制在授权目录内**。
     */
    SAF_TREE,

    /**
     * 兜底档：没有任何外部存储权限。只能访问应用私有目录，
     * 以及通过系统文件选择器单次读写用户主动挑的文件。
     */
    APP_PRIVATE,
}

/**
 * 当前设备的存储访问实况。
 *
 * 这是不可变快照：`onResume` 或从设置页返回时重新 `detect()`，
 * 而不是到处缓存布尔值导致"授权完了但界面还提示没权限"。
 */
data class StorageAccess(
    val tier: StorageTier,
    /** Android 11+ 的全盘文件访问是否已授予。 */
    val hasAllFilesAccess: Boolean,
    /** Android 10 及以下的传统外部存储读权限是否已授予。 */
    val hasLegacyReadPermission: Boolean,
    /** 已持久化的 SAF 目录授权。 */
    val grants: List<SafGrant>,
    /** 该档位下可浏览的根，直接喂给侧栏与导航树。 */
    val roots: List<FsEntry>,
) {
    /** 是否处于降级状态（UI 应给出引导入口）。 */
    val isDegraded: Boolean get() = tier != StorageTier.FULL

    /** 能否浏览公共存储（内部存储 / SD 卡）。 */
    val canBrowsePublicStorage: Boolean get() = tier == StorageTier.FULL
}

/**
 * 探测并决定降级档位。
 *
 * ## 判定顺序（刻意如此）
 * 1. 先看**全盘访问**（API 30+ 用 `isExternalStorageManager()`，这是唯一权威答案，
 *    查权限表得到的 `MANAGE_EXTERNAL_STORAGE` 状态在部分 ROM 上会和它对不上）；
 * 2. 再看**传统读写**（API ≤ 32）；
 * 3. 再看 **SAF 授权**（哪怕没权限，用户挑过的目录照样能用）；
 * 4. 最后落到**应用私有目录**——保证"本机"这一栏永远不空。
 */
class StorageAccessManager(private val context: Context) {

    private val appContext: Context get() = context.applicationContext

    fun detect(grants: List<SafGrant> = SafGrantStore(appContext).all()): StorageAccess {
        val allFiles = hasAllFilesAccess()
        val legacyRead = hasLegacyReadPermission()

        val tier = when {
            allFiles || legacyRead -> StorageTier.FULL
            grants.isNotEmpty() -> StorageTier.SAF_TREE
            else -> StorageTier.APP_PRIVATE
        }

        val roots = when (tier) {
            StorageTier.FULL -> LocalRoots.rootEntries(appContext, includeVolumes = true)

            StorageTier.SAF_TREE -> buildList {
                add(
                    FsEntry(
                        path = LocalRoots.appPrivateRoot(appContext).toLocalPath(),
                        name = "本应用目录",
                        kind = com.miyuetech.mimifiles.core.model.FsKind.DIRECTORY,
                    ),
                )
                grants.forEach { add(it.toRootEntry()) }
            }

            StorageTier.APP_PRIVATE -> LocalRoots.rootEntries(
                context = appContext,
                includeVolumes = false,
                appPrivateDir = LocalRoots.appPrivateRoot(appContext),
            )
        }

        return StorageAccess(
            tier = tier,
            hasAllFilesAccess = allFiles,
            hasLegacyReadPermission = legacyRead,
            grants = grants,
            roots = roots,
        )
    }

    /** Android 11+ 是否已授予全盘文件访问。 */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            false
        }

    /**
     * API ≤ 32 的传统外部存储读权限。
     *
     * API 33 起 `READ_EXTERNAL_STORAGE` 已无实际作用（被 `READ_MEDIA_*` 取代），
     * 因此直接返回 false，避免把它当成"有全盘权限"的依据。
     */
    fun hasLegacyReadPermission(): Boolean =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            appContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

    /** 是否需要引导用户去开全盘访问。 */
    fun needsAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()

    /**
     * 跳系统「所有文件访问权限」页。
     *
     * 注意：优先用带 `package:` 的形式直达本应用；个别 ROM 不认这个 action，
     * 此时调用方应回退到 [appDetailsIntent]。
     */
    fun allFilesAccessIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", appContext.packageName, null),
            )
        } else {
            null
        }

    /** 应用详情页（兜底：全盘访问页不可用时的落点）。 */
    fun appDetailsIntent(): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", appContext.packageName, null),
        )

    /**
     * 需要运行时申请的权限（交给 `ActivityResultContracts.RequestMultiplePermissions`）。
     *
     * 分档：API ≤ 28 需要读写；API 29~32 只需读（配合 `requestLegacyExternalStorage`）；
     * API 33+ 返回空数组——此时只走全盘访问或 SAF，申请媒体的细分权限对本应用没有意义。
     */
    fun runtimePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> emptyArray()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    }

    /** 当前档位的一句话说明，直接用于设置页与降级提示条。 */
    fun describe(access: StorageAccess): String = when (access.tier) {
        StorageTier.FULL -> "已获得完整存储访问权限"
        StorageTier.SAF_TREE -> "仅能访问你授权的 ${access.grants.size} 个目录"
        StorageTier.APP_PRIVATE -> "仅能访问本应用目录"
    }
}
