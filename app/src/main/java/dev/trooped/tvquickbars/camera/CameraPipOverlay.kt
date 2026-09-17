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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
                modifier = Modifier.fillMaxSize(),
                config = spec.rtspProfile
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
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var showDebug by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    val player = remember(url, config) {
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

            val mediaItem = androidx.media3.common.MediaItem.fromUri(url)
            val mediaSource = if (url.startsWith("rtsp://", ignoreCase = true)) {
                val rtspFactory = androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                    .setTimeoutMs(4000)

                when (config.transport) {
                    TransportProtocol.TCP -> rtspFactory.setForceUseRtpTcp(true)
                    TransportProtocol.UDP -> rtspFactory.setForceUseRtpTcp(false)
                    TransportProtocol.AUTO -> { /* Let Exo decide */ }
                }
                rtspFactory.createMediaSource(mediaItem)
            } else {
                // HLS (.m3u8), MP4, or other direct media supported natively
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
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            val cause = error.cause?.message ?: "Unknown"
                            lastError = "Code: ${error.errorCodeName}\n$cause"
                            showDebug = true
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

    Box(modifier = modifier) {
        if (player != null) {
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier.fillMaxSize()
            )

            if (showDebug) {
                DebugInfoOverlay(player = player, errorMessage = lastError)
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
    val okHttp = remember {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(url, authToken) {
        error = null
        isLoading = true

        val reqBuilder = Request.Builder().url(url)
        if (!authToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $authToken")
        }
        val req = reqBuilder.build()

        try {
            withContext(Dispatchers.IO) {
                try {
                    okHttp.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val errorMsg = "HTTP ${resp.code}: ${resp.message}"
                            Log.e(tag, errorMsg)
                            error = errorMsg
                            isLoading = false
                            return@withContext
                        }

                        val stream = resp.body?.byteStream()
                        if (stream == null) {
                            error = "Empty body"
                            isLoading = false
                            return@withContext
                        }

                        BufferedInputStream(stream, 64 * 1024).use { bis ->
                            readMjpegLoop(bis) { frameBytes ->
                                val bmp = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
                                if (bmp != null) {
                                    bitmap = bmp
                                    if (isLoading) isLoading = false
                                } else {
                                    Log.e(tag, "Failed to decode bitmap from frame")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error in MJPEG request", e)
                    throw e
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "MJPEG failed", t)
            error = t.message ?: "Unknown error"
            isLoading = false
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

/**
 * Reusable dynamic frame buffer avoiding continuous allocations on TV garbage collectors.
 */
private class FastFrameBuffer(initialCapacity: Int = 256 * 1024) {
    var buf = ByteArray(initialCapacity)
    var size = 0
        private set

    fun reset() {
        size = 0
    }

    fun write(source: ByteArray, offset: Int, length: Int) {
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
 * Optimized non-allocating MJPEG stream reader.
 */
private suspend fun readMjpegLoop(
    input: InputStream,
    onFrame: (ByteArray) -> Unit
) {
    val buf = ByteArray(8 * 1024)
    val frame = FastFrameBuffer(256 * 1024)

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
        val read = withContext(Dispatchers.IO) { input.read(buf) }
        if (read <= 0) break

        var offset = 0
        while (offset < read) {
            // Find SOI 0xFF 0xD8 directly in read buffer
            val soi = findMarker(buf, offset, read, 0xFF, 0xD8)
            if (soi == -1) {
                offset = read
                break
            }

            val start = soi
            frame.reset()
            val chunkLen = read - start
            frame.write(buf, start, chunkLen)

            // Search for EOI incrementally without copying
            var searchStart = 0
            var eoiIndex = findMarker(frame.buf, searchStart, frame.size, 0xFF, 0xD9)

            while (eoiIndex == -1) {
                searchStart = maxOf(0, frame.size - 1)
                val more = withContext(Dispatchers.IO) { input.read(buf) }
                if (more <= 0) return
                frame.write(buf, 0, more)
                eoiIndex = findMarker(frame.buf, searchStart, frame.size, 0xFF, 0xD9)
            }

            // Emit single frame slice
            onFrame(frame.toByteArray())

            offset = read
        }
    }
}
