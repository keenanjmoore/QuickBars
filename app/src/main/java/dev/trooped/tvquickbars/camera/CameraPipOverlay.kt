package dev.trooped.tvquickbars.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import dev.trooped.tvquickbars.R
import dev.trooped.tvquickbars.utils.SharedHttpClients

/**
 * Composable function to display a camera Picture-in-Picture (PiP) overlay.
 * Supports single camera views as well as multi-camera split and grid arrangements.
 */
@Composable
fun CameraPipOverlay(
    spec: CameraPipSpec,
    onClose: () -> Unit = {}
) {
    if (spec.isGroup && spec.groupSpecs.isNotEmpty()) {
        MultiCameraPipOverlay(spec = spec, onClose = onClose)
    } else {
        Box(
            modifier = Modifier
                .requiredSize(spec.widthDp.dp, spec.heightDp.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            SingleCameraFeedView(spec)
        }
    }
}

/**
 * Renders multiple camera feeds in a combined dock/grid container.
 */
@Composable
fun MultiCameraPipOverlay(
    spec: CameraPipSpec,
    onClose: () -> Unit = {}
) {
    val specs = spec.groupSpecs
    val layout = spec.groupLayout.lowercase()

    Box(
        modifier = Modifier
            .requiredSize(spec.widthDp.dp, spec.heightDp.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(4.dp)
    ) {
        when {
            // 2 feeds side by side
            layout == "side_by_side" || (layout == "auto" && specs.size == 2) -> {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    specs.take(2).forEach { s ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                        ) {
                            SingleCameraFeedView(s)
                        }
                    }
                }
            }
            // 2 feeds stacked vertically
            layout == "stacked" -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    specs.take(2).forEach { s ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                        ) {
                            SingleCameraFeedView(s)
                        }
                    }
                }
            }
            // Grid layout (up to 4 feeds in a 2x2 grid)
            else -> {
                val count = specs.size
                if (count <= 2) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        specs.forEach { s ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(4.dp))
                            ) {
                                SingleCameraFeedView(s)
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            specs.take(2).forEach { s ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(4.dp))
                                ) {
                                    SingleCameraFeedView(s)
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            specs.drop(2).take(2).forEach { s ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(4.dp))
                                ) {
                                    SingleCameraFeedView(s)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual camera feed container (MJPEG or ExoPlayer).
 */
@Composable
fun SingleCameraFeedView(spec: CameraPipSpec) {
    Box(modifier = Modifier.fillMaxSize()) {
        val isExoStream = remember(spec.url) {
            spec.url.startsWith("rtsp://", ignoreCase = true) ||
            spec.url.contains(".m3u8", ignoreCase = true) ||
            spec.url.endsWith(".mp4", ignoreCase = true)
        }

        if (isExoStream) {
            CameraRtspView(
                url = spec.url,
                config = spec.rtspProfile,
                snapshotUrl = spec.snapshotUrl,
                authToken = spec.authToken,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CameraMjpegView(
                url = spec.url,
                authToken = spec.authToken,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (spec.showTitle && spec.title.isNotBlank()) {
            val alignment = when (spec.cornerPosition) {
                "TOP_RIGHT" -> Alignment.BottomStart
                "BOTTOM_LEFT" -> Alignment.TopEnd
                "BOTTOM_RIGHT" -> Alignment.TopStart
                else -> Alignment.BottomEnd
            }
            Box(
                modifier = Modifier
                    .align(alignment)
                    .padding(6.dp)
            ) {
                Text(
                    text = spec.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(3.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

enum class StreamLatency(val minBufferMs: Int, val maxBufferMs: Int, val startBufferMs: Int, val rebufferMs: Int) {
    LOW_LATENCY(
        minBufferMs = 500,
        maxBufferMs = 2000,
        startBufferMs = 500,
        rebufferMs = 500
    ),
    BALANCED(
        minBufferMs = 1000,
        maxBufferMs = 4000,
        startBufferMs = 1000,
        rebufferMs = 1000
    ),
    HIGH_STABILITY(
        minBufferMs = 2000,
        maxBufferMs = 8000,
        startBufferMs = 2000,
        rebufferMs = 2000
    )
}

enum class TransportProtocol {
    AUTO,
    TCP,
    UDP
}

data class RtspProfile(
    val transport: TransportProtocol = TransportProtocol.TCP,
    val latency: StreamLatency = StreamLatency.BALANCED,
    val useSoftwareDecoder: Boolean = false,
    val muteAudio: Boolean = false
) : Parcelable {
    constructor(parcel: Parcel) : this(
        try { TransportProtocol.valueOf(parcel.readString() ?: TransportProtocol.TCP.name) } catch (_: Throwable) { TransportProtocol.TCP },
        try { StreamLatency.valueOf(parcel.readString() ?: StreamLatency.BALANCED.name) } catch (_: Throwable) { StreamLatency.BALANCED },
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(transport.name)
        parcel.writeString(latency.name)
        parcel.writeByte(if (useSoftwareDecoder) 1 else 0)
        parcel.writeByte(if (muteAudio) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<RtspProfile> {
        override fun createFromParcel(parcel: Parcel): RtspProfile = RtspProfile(parcel)
        override fun newArray(size: Int): Array<RtspProfile?> = arrayOfNulls(size)
    }
}

// ───────────────────────── Stream player (Media3) ─────────────────────────
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun CameraRtspView(
    url: String,
    config: RtspProfile,
    snapshotUrl: String? = null,
    authToken: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var showDebug by remember(url, config) { mutableStateOf(false) }
    var lastError by remember(url, config) { mutableStateOf<String?>(null) }
    var retryCount by remember(url, config) { mutableStateOf(0) }
    var isFirstFrameRendered by remember(url, config) { mutableStateOf(false) }
    var pendingRetry by remember(url, config) { mutableStateOf(false) }
    var snapshotBmp by remember(snapshotUrl) { mutableStateOf<Bitmap?>(null) }

    // Option A6: Snapshot-first PiP (show instant JPEG frame while RTSP negotiates)
    LaunchedEffect(snapshotUrl, authToken) {
        if (snapshotUrl.isNullOrBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val reqBuilder = Request.Builder().url(snapshotUrl)
                if (!authToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $authToken")
                }
                SharedHttpClients.mediaFetch.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                            snapshotBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    LaunchedEffect(pendingRetry, retryCount) {
        if (pendingRetry && retryCount in 1..5) {
            val backoffMs = (1000L shl minOf(retryCount - 1, 3)).coerceAtMost(8000L)
            delay(backoffMs)
            pendingRetry = false
        }
    }

    val player = remember(url, config, if (pendingRetry) -1 else retryCount) {
        if (pendingRetry) return@remember null
        try {
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    config.latency.minBufferMs,
                    config.latency.maxBufferMs,
                    config.latency.startBufferMs,
                    config.latency.rebufferMs
                )
                .setBackBuffer(0, false)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val renderersFactory = if (config.useSoftwareDecoder) {
                androidx.media3.exoplayer.DefaultRenderersFactory(context)
                    .setMediaCodecSelector(SoftwarePreferredCodecSelector())
                    .setEnableDecoderFallback(true)
            } else {
                androidx.media3.exoplayer.DefaultRenderersFactory(context)
                    .setEnableDecoderFallback(true)
            }

            val mediaItem = androidx.media3.common.MediaItem.Builder()
                .setUri(url)
                .apply {
                    if (config.latency == StreamLatency.LOW_LATENCY) {
                        setLiveConfiguration(
                            androidx.media3.common.MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(400)
                                .setMaxPlaybackSpeed(1.04f)
                                .build()
                        )
                    }
                }
                .build()

            val mediaSource = if (url.startsWith("rtsp://", ignoreCase = true)) {
                val rtspFactory = androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                    .setTimeoutMs(4000)

                when (config.transport) {
                    TransportProtocol.TCP -> rtspFactory.setForceUseRtpTcp(true)
                    TransportProtocol.UDP -> rtspFactory.setForceUseRtpTcp(false)
                    TransportProtocol.AUTO -> rtspFactory.setForceUseRtpTcp(retryCount % 2 == 0)
                }
                rtspFactory.createMediaSource(mediaItem)
            } else {
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                    .createMediaSource(mediaItem)
            }

            val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context)
            if (config.muteAudio) {
                trackSelector.setParameters(
                    trackSelector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                )
            }

            androidx.media3.exoplayer.ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .build().apply {
                    val attrs = androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build()

                    setAudioAttributes(attrs, !config.muteAudio)
                    volume = if (config.muteAudio) 0f else 1f
                    videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT

                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onRenderedFirstFrame() {
                            isFirstFrameRendered = true
                            retryCount = 0
                            showDebug = false
                            lastError = null
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            val cause = error.cause?.message ?: "Unknown"
                            lastError = "Code: ${error.errorCodeName}\n$cause"
                            if (retryCount < 5) {
                                retryCount += 1
                                isFirstFrameRendered = false
                                pendingRetry = true
                            } else {
                                showDebug = true
                            }
                        }
                    })

                    setMediaSource(mediaSource)
                    prepare()
                    playWhenReady = true
                }
        } catch (e: Throwable) {
            lastError = "Init Fail: ${e.message}"
            showDebug = true
            null
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        if (player != null) {
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier.fillMaxSize()
            )

            if (!isFirstFrameRendered && snapshotBmp != null && !showDebug) {
                Image(
                    bitmap = snapshotBmp!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }

            if (isFirstFrameRendered && !showDebug) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4ADE80))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            if (showDebug) {
                DebugInfoOverlay(player = player, errorMessage = lastError)
            }
        } else if (pendingRetry) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Reconnecting ($retryCount/5)…",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        } else {
            androidx.compose.material3.Text(
                "Stream playback unavailable",
                modifier = Modifier.padding(12.dp)
            )
        }
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun DebugInfoOverlay(
    player: androidx.media3.exoplayer.ExoPlayer,
    errorMessage: String? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f))
    ) {
        AndroidView(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            factory = { context ->
                android.widget.TextView(context).apply {
                    setTextColor(android.graphics.Color.GREEN)
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                }
            },
            update = { view ->
                (view.tag as? androidx.media3.exoplayer.util.DebugTextViewHelper)?.stop()
                val helper = androidx.media3.exoplayer.util.DebugTextViewHelper(player, view)
                helper.start()
                view.tag = helper
            },
            onRelease = { view ->
                (view.tag as? androidx.media3.exoplayer.util.DebugTextViewHelper)?.stop()
            }
        )

        if (errorMessage != null) {
            Text(
                text = "PLAYBACK FAILED\n\n$errorMessage",
                color = androidx.compose.ui.graphics.Color.Red,
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@UnstableApi
class SoftwarePreferredCodecSelector : MediaCodecSelector {
    @OptIn(UnstableApi::class)
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): MutableList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
        val decoders = MediaCodecUtil.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder
        ).toMutableList()

        decoders.sortBy { decoder ->
            if (decoder.hardwareAccelerated) 1 else -1
        }

        return decoders
    }
}

@Composable
fun CameraMjpegView(
    url: String,
    authToken: String?,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val tag = "CameraMjpegView"
    val okHttp = SharedHttpClients.mjpegStream

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(url, authToken) {
        error = null
        isLoading = true
        var attempt = 0
        var reusableBmp: Bitmap? = null

        val reqBuilder = Request.Builder().url(url)
        if (!authToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $authToken")
        }
        val req = reqBuilder.build()

        while (isActive) {
            try {
                withContext(Dispatchers.IO) {
                    okHttp.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("HTTP ${resp.code}: ${resp.message}")
                        }
                        val stream = resp.body?.byteStream() ?: throw IllegalStateException("Empty body")
                        attempt = 0

                        BufferedInputStream(stream, 64 * 1024).use { bis ->
                            readMjpegLoop(bis) { frameBytes ->
                                val decoded = decodeMjpegFrame(frameBytes, reusableBmp)
                                if (decoded != null) {
                                    reusableBmp = decoded
                                    bitmap = decoded
                                    error = null
                                    if (isLoading) isLoading = false
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (!isActive) break
                attempt += 1
                Log.w(tag, "MJPEG stream dropped (attempt $attempt): ${t.message}")
                if (attempt > 5) {
                    error = t.message ?: "Stream error"
                    isLoading = false
                    break
                }
                delay((1000L shl minOf(attempt - 1, 3)).coerceAtMost(8000L))
            }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            error != null -> Text(
                "Camera error: $error",
                modifier = Modifier.padding(16.dp),
                textAlign = TextAlign.Center,
                color = colorResource(id = R.color.md_theme_error)
            )
            isLoading -> CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = colorResource(id = R.color.md_theme_primary)
            )
        }
    }
}

private fun decodeMjpegFrame(frameBytes: ByteArray, reusable: Bitmap?): Bitmap? {
    val opts = BitmapFactory.Options().apply {
        inMutable = true
        inSampleSize = if (frameBytes.size > 350 * 1024) 2 else 1
        if (reusable != null && !reusable.isRecycled) {
            inBitmap = reusable
        }
    }
    return try {
        BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size, opts)
    } catch (_: IllegalArgumentException) {
        // Dimensions or sample size changed: decode cleanly without inBitmap
        val fallbackOpts = BitmapFactory.Options().apply {
            inMutable = true
            inSampleSize = opts.inSampleSize
        }
        BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size, fallbackOpts)
    }
}

/**
 * Reusable dynamic frame buffer with 4 MB safety cap avoiding OOM on corrupted streams.
 */
private class FastFrameBuffer(initialCapacity: Int = 256 * 1024) {
    private val maxCapacity = 4 * 1024 * 1024
    var buf = ByteArray(initialCapacity)
    var size = 0
        private set

    fun reset() {
        size = 0
    }

    fun write(source: ByteArray, offset: Int, length: Int) {
        if (size + length > maxCapacity) {
            Log.w("CameraMjpegView", "MJPEG frame buffer exceeded 4MB cap; resetting to resync")
            size = 0
            if (length > maxCapacity) return
        }
        ensureCapacity(size + length)
        System.arraycopy(source, offset, buf, size, length)
        size += length
    }

    private fun ensureCapacity(minCap: Int) {
        if (minCap > buf.size) {
            val newCap = maxOf(buf.size * 2, minCap)
            buf = buf.copyOf(newCap)
        }
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/**
 * Optimized sliding-window MJPEG stream reader: preserves leftover trailing bytes across reads,
 * extracts exact SOI..EOI frame slices, and caps emit cadence to protect UI/GC on TV boxes.
 */
private suspend fun readMjpegLoop(
    input: InputStream,
    minFrameIntervalMs: Long = 50L,
    onFrame: (ByteArray) -> Unit
) {
    val readBuf = ByteArray(16 * 1024)
    val streamBuf = FastFrameBuffer(256 * 1024)
    var lastEmitMs = 0L

    fun findMarker(bytes: ByteArray, start: Int, end: Int, hi: Int, lo: Int): Int {
        val hiB = hi and 0xFF
        val loB = lo and 0xFF
        val limit = minOf(end - 1, bytes.size - 1)
        for (i in start until limit) {
            if ((bytes[i].toInt() and 0xFF) == hiB && (bytes[i + 1].toInt() and 0xFF) == loB) return i
        }
        return -1
    }

    while (coroutineContext.isActive) {
        val read = withContext(Dispatchers.IO) { input.read(readBuf) }
        if (read <= 0) break

        streamBuf.write(readBuf, 0, read)

        while (true) {
            val soi = findMarker(streamBuf.buf, 0, streamBuf.size, 0xFF, 0xD8)
            if (soi == -1) {
                streamBuf.reset()
                break
            }
            val eoi = findMarker(streamBuf.buf, soi + 2, streamBuf.size, 0xFF, 0xD9)
            if (eoi == -1) {
                // Trim leading noise before SOI if any
                if (soi > 0) {
                    val rem = streamBuf.size - soi
                    System.arraycopy(streamBuf.buf, soi, streamBuf.buf, 0, rem)
                    streamBuf.reset()
                    streamBuf.write(streamBuf.buf, 0, rem)
                }
                break
            }

            val frameEnd = eoi + 2
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastEmitMs >= minFrameIntervalMs) {
                val frameBytes = streamBuf.buf.copyOfRange(soi, frameEnd)
                onFrame(frameBytes)
                lastEmitMs = now
            }

            // Shift trailing bytes to start of streamBuf
            val remaining = streamBuf.size - frameEnd
            if (remaining > 0) {
                val tail = streamBuf.buf.copyOfRange(frameEnd, streamBuf.size)
                streamBuf.reset()
                streamBuf.write(tail, 0, tail.size)
            } else {
                streamBuf.reset()
                break
            }
        }
    }
}
