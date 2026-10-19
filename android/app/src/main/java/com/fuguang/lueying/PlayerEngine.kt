package com.fuguang.lueying

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * 播放器的底子（照 Media3 官方建议和 Just Player / Seal 这类成熟播放器的做法调）：
 * - 解码：MediaCodec 异步排队（新机默认开，旧机也强开），硬解失败自动换下一个解码器。
 * - 缓冲：本地和网上分开调、按字节封顶（见 build）。
 * - 网上读的视频走磁盘缓存（1 GB，最久没看的先删）：看过的段落往回拖、重看都是秒开。下载的成品不进缓存。
 * - 声音：抢音频焦点（来电、别的 App 放声音时自动停），拔耳机就暂停；放的时候不让手机锁网络和 CPU。
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PlayerEngine {
    private var cache: SimpleCache? = null

    /**
     * local：下好的本地文件。读盘很快，不用攒多：往前 6–12 秒，身后留 5 秒。网上读的：往前 15–30 秒，身后留 15 秒，
     * 起播 1 秒，卡过之后攒到 3 秒再放（以前 1.5 秒，网一抖就放一下停一下）。
     * 内存靠「时间」管住（4K 原画每秒约 3 MB，12 秒约 36 MB）：以前一律往前 60 秒、身后 30 秒，光缓冲占 200 多 MB。
     * 字节上限（48 / 64 MB）只在攒够最少的秒数以后才管用（PrioritizeTimeOverSizeThresholds = true）。
     * 第 217 版设成 false 出过事：拖动时画面那一轨先把 48 MB 占满，声音那一轨一点没读到，读取被字节上限停住，
     * 画面停在跳到的那一帧、一直转圈不放。不要再改回 false。
     */
    fun build(ctx: Context, local: Boolean = false): ExoPlayer {
        val app = ctx.applicationContext
        val rf = DefaultRenderersFactory(app).setEnableDecoderFallback(true).forceEnableMediaCodecAsynchronousQueueing()
        val lc = (if (local) DefaultLoadControl.Builder()
                .setBufferDurationsMs(6_000, 12_000, 500, 1_000).setTargetBufferBytes(48 shl 20).setBackBuffer(5_000, false)
            else DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 30_000, 1_000, 3_000).setTargetBufferBytes(64 shl 20).setBackBuffer(15_000, false))
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val audio = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        return ExoPlayer.Builder(app, rf).setLoadControl(lc)
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    /** 网上读的套一层磁盘缓存；缓存打不开就直接读网。 */
    fun cached(ctx: Context, upstream: DataSource.Factory): DataSource.Factory {
        val c = cache ?: runCatching {
            SimpleCache(File(ctx.applicationContext.cacheDir, "media"), LeastRecentlyUsedCacheEvictor(1L shl 30), StandaloneDatabaseProvider(ctx.applicationContext))
        }.getOrNull()?.also { cache = it } ?: return upstream
        return CacheDataSource.Factory().setCache(c).setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** 每个下载看到哪了：再打开从那里接着放。 */
    fun lastPos(ctx: Context, id: String): Long = ctx.getSharedPreferences("dl-play", 0).getLong(id, 0)

    fun forget(ctx: Context, id: String) { ctx.getSharedPreferences("dl-play", 0).edit().remove(id).apply() }

    fun keepPos(ctx: Context, id: String, pos: Long, dur: Long) {
        val sp = ctx.getSharedPreferences("dl-play", 0).edit()
        // 刚开头、快结尾的不记，下次从头放
        if (pos < 10_000 || (dur > 0 && pos > dur - 15_000)) sp.remove(id) else sp.putLong(id, pos)
        sp.apply()
    }
}
