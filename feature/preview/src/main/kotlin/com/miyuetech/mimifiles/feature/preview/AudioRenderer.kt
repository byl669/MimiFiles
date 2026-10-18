package com.miyuetech.mimifiles.feature.preview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.miyuetech.mimifiles.core.common.Formatters
import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.preview.PreviewUri
import com.miyuetech.mimifiles.core.preview.SeekableSourceDataSource
import com.miyuetech.mimifiles.feature.preview.databinding.ViewAudioPreviewBinding

/**
 * 音频预览（Media3 ExoPlayer + 自定义数据源）。
 *
 * ## 为什么音频也走 [SeekableSourceDataSource]
 * 音频文件在 NAS 上动辄几十 MB，而且**用户拖进度条是常态**。
 * 若用 `FileDataSource` 就得先下载完；用我们的数据源则和视频一样按需随机读。
 * 顺带一条硬收益：这条路对 SMB / WebDAV **完全无差别**——协议细节在数据源下一层，
 * 播放器只会说"给我第 3 MB 起的 64 KB"。
 *
 * ## 为什么必须显式指定 mimeType
 * 我们的地址 scheme 是 `mimifs:`，Media3 无法据此推断容器类型
 * （它默认按 URI 后缀猜，而 `mimifs:smb://…/a.mp3` 的"后缀"解析结果是不确定的）。
 * 显式给 mime 才会稳定落到 `ProgressiveMediaSource`。
 */
class AudioRenderer(
    private val host: PreviewHost,
    private val path: FsPath,
) : PreviewRenderer {

    private var binding: ViewAudioPreviewBinding? = null

    private var player: ExoPlayer? = null

    private var failed: String? = null

    override fun createView(inflater: LayoutInflater, parent: ViewGroup): View {
        val created = ViewAudioPreviewBinding.inflate(inflater, parent, false)
        binding = created
        created.audioTitle.text = path.displayName
        created.audioSubtitle.text = host.activity.getString(R.string.preview_audio_prepare)
        host.setSubtitle(path.displayName)
        setupPlayer(created)
        return created.root
    }

    override fun onStop() {
        // 离开页面就暂停，但**不释放**：返回时能继续播（旋转屏幕也不中断）。
        player?.pause()
    }

    override fun release() {
        binding?.audioPlayer?.player = null
        player?.release()
        player = null
        binding = null
    }

    override fun infoRows(): List<InfoRow> = listOf(
        InfoRow(R.string.preview_info_path, path.toString()),
    )

    private fun setupPlayer(created: ViewAudioPreviewBinding) {
        val exo = ExoPlayer.Builder(host.activity)
            // 数据源换成"我们的 FileSystem"：这样本地/网络走同一条路，
            // 也就不存在"本地能拖进度条、NAS 上不能"这种不一致。
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(SeekableSourceDataSource.Factory()),
            )
            .build()
        player = exo
        created.audioPlayer.player = exo
        exo.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    failed = error.message ?: error.errorCodeName
                    created.audioSubtitle.text = host.activity.getString(
                        R.string.preview_audio_failed,
                        failed.orEmpty(),
                    )
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        created.audioSubtitle.text = buildSubtitle(exo)
                    }
                }
            },
        )
        val mime = MimeTypes.of(path.displayName)
        exo.setMediaItem(
            MediaItem.Builder()
                .setUri(PreviewUri.of(path))
                .setMimeType(mime)
                .build(),
        )
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun buildSubtitle(exo: ExoPlayer): String {
        val duration = exo.duration
        return if (duration > 0) Formatters.duration(duration) else path.extension.uppercase()
    }
}
