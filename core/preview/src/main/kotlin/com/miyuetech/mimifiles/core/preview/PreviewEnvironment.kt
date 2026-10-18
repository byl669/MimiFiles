package com.miyuetech.mimifiles.core.preview

import android.content.Context

/**
 * 预览能力的装配结果。
 *
 * ## 为什么做成"一个对象持有三个组件"而不是三个全局单例
 * 这三个组件共享同一份配置（缓存目录、provider authority），而且**生命周期一致**：
 * 缓存目录被清理时三者一起失效。做成三个各自 `object` 的后果是"谁先初始化、
 * authority 从哪来"变成三处各自回答的问题，且早晚会出现"其中一个用了旧的 authority"。
 *
 * ## 为什么不是 Android 的 `ServiceLocator` / DI 框架
 * 全应用只有这一处需要装配（与 `Backends` 同一层）。多引一个 DI 框架，
 * 换来的抽象能力在这里一次都用不上。
 */
class PreviewEnvironment internal constructor(
    /** 类型探测（读文件头 512 字节）。 */
    val probe: ContentProbe,
    /** 列表缩略图（L1 内存 + L2 磁盘）。 */
    val thumbnails: ThumbnailLoader,
    /** 缩略图缓存本体（设置页要显示占用、要能清理）。 */
    val thumbnailStore: ThumbnailStore,
    /** 预览落盘缓存（给"需要可 seek 的本地文件"的平台组件用）。 */
    val cache: PreviewCache,
) {

    companion object {

        @Volatile
        private var instance: PreviewEnvironment? = null

        /**
         * 取（必要时创建）进程级实例。
         *
         * @param documentAuthority `:provider` 的 authority，用于让远端文件也能走
         *        平台的 `ImageDecoder`（HEIC/AVIF 只有它能解）。传 `null` 表示放弃这条路。
         */
        fun of(context: Context, documentAuthority: String? = null): PreviewEnvironment {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: build(context.applicationContext, documentAuthority)
                    .also { instance = it }
            }
        }

        private fun build(context: Context, documentAuthority: String?): PreviewEnvironment {
            val store = ThumbnailStore(context)
            val cache = PreviewCache(context)
            return PreviewEnvironment(
                probe = ContentProbe(),
                thumbnails = ThumbnailLoader(
                    context = context,
                    store = store,
                    documentAuthority = documentAuthority,
                ),
                thumbnailStore = store,
                cache = cache,
            )
        }

        /** 仅测试用：丢弃实例，让下次 [of] 重新装配。 */
        fun resetForTest() {
            synchronized(this) { instance = null }
        }
    }
}
