package dev.trooped.tvquickbars.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import dev.trooped.tvquickbars.R
import dev.trooped.tvquickbars.utils.SharedHttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

private val PIP_CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun parseBorderColor(hex: String?): Color? {
    val clean = hex?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        Color(android.graphics.Color.parseColor(clean))
    } catch (_: Throwable) {
        null
    }
}

private fun appendCacheBuster(url: String, epochMs: Long): String {
    val sep = if (url.contains('?')) '&' else '?'
    return "${url}${sep}t=$epochMs"
}

/**
 * Composable function to display a camera Picture-in-Picture (PiP) overlay.
 * Features smooth entrance animation, snapshot->live crossfade, automatic RTSP->MJPEG fallback,
 * zero-copy adaptive MJPEG decoding, and optional doorbell quick-action pills.
 */
@Composable
fun CameraPipOverlay(
    spec: CameraPipSpec,
    countdownResetKey: Long = 0L,
    openedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    onClose: () -> Unit = {},
    onActionClick: (PipAction) -> Unit = {},
    onCodecActive: (String?) -> Unit = {},
    onFirstFrameMs: (Long) -> Unit = {},
    onPlaybackError: (String) -> Unit = {}
) {
    val shape = RoundedCornerShape(spec.cornerRadiusDp.coerceIn(0, 24).dp)
    val borderColor = remember(spec.borderColorHex) { parseBorderColor(spec.borderColorHex) }
    val borderMod = if (borderColor != null && spec.borderWidthDp > 0) {
        Modifier.border(spec.borderWidthDp.coerceIn(1, 8).dp, borderColor, shape)
    } else {
        Modifier
    }

    var entered by remember(spec.slotKey, spec.entityId) { mutableStateOf(false) }
    LaunchedEffect(spec.slotKey, spec.entityId) {
        entered = true
    }

    val slideFromTop = spec.cornerPosition.startsWith("TOP_")

    AnimatedVisibility(
        visible = entered,
        enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { h ->
            if (slideFromTop) -(h / 5) else (h / 5)
        },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { h ->
            if (slideFromTop) -(h / 6) else (h / 6)
        }
    ) {
        Column(
            modifier = Modifier
                .width(spec.widthDp.dp)
                .clip(shape)
                .then(borderMod)
                .background(Color.Black.copy(alpha = 0.88f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(spec.heightDp.dp)
            ) {
                if (spec.isGroup && spec.groupSpecs.isNotEmpty()) {
                    MultiCameraPipOverlay(
                        spec = spec,
                        openedAtElapsedMs = openedAtElapsedMs,
                        onClose = onClose,
                        onCodecActive = onCodecActive,
                        onFirstFrameMs = onFirstFrameMs,
                        onPlaybackError = onPlaybackError
                    )
                } else {
                    SingleCameraFeedView(
                        spec = spec,
                        openedAtElapsedMs = openedAtElapsedMs,
                        onCodecActive = onCodecActive,
                        onFirstFrameMs = onFirstFrameMs,
                        onPlaybackError = onPlaybackError
                    )
                    if (spec.showTimestamp) {
                        PipTimestampBadge(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                        )
                    }
                }
                if (spec.isPinned || spec.priority == "critical" || spec.priority == "high") {
                    PipStatusPills(
                        isPinned = spec.isPinned,
                        priority = spec.priority,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                    )
                }
                if (spec.showCountdownBar && spec.autoHideTimeout > 0 && !spec.isPinned) {
                    PipCountdownBar(
                        autoHideTimeoutSec = spec.autoHideTimeout,
                        accentColor = borderColor,
                        resetKey = countdownResetKey,
                        modifier = Modifier.align(Alignment.BottomStart)
                    )
                }
            }

            if (spec.actions.isNotEmpty()) {
                PipQuickActionBar(
                    actions = spec.actions,
                    accentColor = borderColor ?: Color(0xFF38BDF8),
                    onActionClick = onActionClick
                )
            }
        }
    }
}

@Composable
private fun PipQuickActionBar(
    actions: List<PipAction>,
    accentColor: Color,
    onActionClick: (PipAction) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A).copy(alpha = 0.92f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.take(3).forEach { act ->
            OutlinedButton(
                onClick = { onActionClick(act) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.65f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = accentColor.copy(alpha = 0.16f),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = act.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PipStatusPills(
    isPinned: Boolean,
    priority: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isPinned) {
            Text(
                text = "PINNED",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF38BDF8),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        if (priority == "critical") {
            Text(
                text = "DOORBELL",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFCA5A5),
                modifier = Modifier
                    .background(Color(0xFF7F1D1D).copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun PipTimestampBadge(modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    var clock by remember {
        mutableStateOf(PIP_CLOCK_FORMATTER.format(Instant.now().atZone(zone)))
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            val nowMs = System.currentTimeMillis()
            clock = PIP_CLOCK_FORMATTER.format(Instant.ofEpochMilli(nowMs).atZone(zone))
            val delayMs = (1000L - (nowMs % 1000L)).coerceIn(50L, 1000L)
            delay(delayMs)
        }
    }
    Text(
        text = clock,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = Color.White,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun PipCountdownBar(
    autoHideTimeoutSec: Int,
    accentColor: Color?,
    resetKey: Long,
    modifier: Modifier = Modifier
) {
    var fraction by remember { mutableStateOf(1f) }
    LaunchedEffect(autoHideTimeoutSec, resetKey) {
        val totalMs = (autoHideTimeoutSec * 1000L).coerceAtLeast(1000L)
        val startMs = SystemClock.elapsedRealtime()
        fraction = 1f
        while (isActive) {
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val rem = (1f - (elapsed.toFloat() / totalMs.toFloat())).coerceIn(0f, 1f)
            fraction = rem
            if (rem <= 0f) break
            delay(100L)
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Color.Black.copy(alpha = 0.35f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = fraction)
                .background(accentColor ?: Color(0xFF38BDF8))
        )
    }
}

/**
 * Renders multiple camera feeds in a combined dock/grid container.
 */
@Composable
fun MultiCameraPipOverlay(
    spec: CameraPipSpec,
    openedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    onClose: () -> Unit = {},
    onCodecActive: (String?) -> Unit = {},
    onFirstFrameMs: (Long) -> Unit = {},
    onPlaybackError: (String) -> Unit = {}
) {
    val specs = spec.groupSpecs
    val layout = spec.groupLayout.lowercase()
    val perTileW = if (layout == "stacked") spec.widthDp else (spec.widthDp / 2).coerceAtLeast(140)
    val perTileH = if (layout == "side_by_side") spec.heightDp else (spec.heightDp / 2).coerceAtLeast(90)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(4.dp)
    ) {
        when {
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
                            SingleCameraFeedView(
                                s.copy(widthDp = perTileW, heightDp = perTileH, isGroup = true),
                                openedAtElapsedMs = openedAtElapsedMs,
                                onCodecActive = onCodecActive,
                                onFirstFrameMs = onFirstFrameMs,
                                onPlaybackError = onPlaybackError
                            )
                        }
                    }
                }
            }
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
                            SingleCameraFeedView(
                                s.copy(widthDp = perTileW, heightDp = perTileH, isGroup = true),
                                openedAtElapsedMs = openedAtElapsedMs,
                                onCodecActive = onCodecActive,
                                onFirstFrameMs = onFirstFrameMs,
                                onPlaybackError = onPlaybackError
                            )
                        }
                    }
                }
            }
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
                                SingleCameraFeedView(
                                    s.copy(widthDp = perTileW, heightDp = perTileH, isGroup = true),
                                    openedAtElapsedMs = openedAtElapsedMs,
                                    onCodecActive = onCodecActive,
                                    onFirstFrameMs = onFirstFrameMs,
                                    onPlaybackError = onPlaybackError
                                )
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
                                    SingleCameraFeedView(
                                        s.copy(widthDp = perTileW, heightDp = perTileH, isGroup = true),
                                        openedAtElapsedMs = openedAtElapsedMs,
                                        onCodecActive = onCodecActive,
                                        onFirstFrameMs = onFirstFrameMs,
                                        onPlaybackError = onPlaybackError
                                    )
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
                                    SingleCameraFeedView(
                                        s.copy(widthDp = perTileW, heightDp = perTileH, isGroup = true),
                                        openedAtElapsedMs = openedAtElapsedMs,
                                        onCodecActive = onCodecActive,
                                        onFirstFrameMs = onFirstFrameMs,
                                        onPlaybackError = onPlaybackError
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipLiveBadge(label: String = "LIVE", modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (label == "MJPEG") Color(0xFFFBBF24) else Color(0xFF4ADE80))
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/**
 * Individual camera feed container (RTSP ExoPlayer with automatic MJPEG fallback).
 */
@Composable
fun SingleCameraFeedView(
    spec: CameraPipSpec,
    openedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    onCodecActive: (String?) -> Unit = {},
    onFirstFrameMs: (Long) -> Unit = {},
    onPlaybackError: (String) -> Unit = {}
) {
    val density = LocalDensity.current.density
    val targetPxW = remember(spec.widthDp, density) { (spec.widthDp * density).toInt().coerceAtLeast(160) }
    val targetPxH = remember(spec.heightDp, density) { (spec.heightDp * density).toInt().coerceAtLeast(90) }
    val useSurfaceView = !spec.isGroup && spec.opacity >= 0.99f && spec.cornerRadiusDp == 0

    Box(modifier = Modifier.fillMaxSize()) {
        val isExoStream = remember(spec.url) {
            spec.url.startsWith("rtsp://", ignoreCase = true) ||
                spec.url.contains(".m3u8", ignoreCase = true) ||
                spec.url.endsWith(".mp4", ignoreCase = true)
        }

        var fallbackToMjpeg by remember(spec.url) { mutableStateOf(false) }

        if (isExoStream && !fallbackToMjpeg) {
            CameraRtspView(
                url = spec.url,
                config = spec.rtspProfile,
                snapshotUrl = spec.snapshotUrl,
                authToken = spec.authToken,
                showLiveBadge = spec.showLiveBadge,
                useSurfaceView = useSurfaceView,
                targetPxW = targetPxW,
                targetPxH = targetPxH,
                openedAtElapsedMs = openedAtElapsedMs,
                canFallbackToMjpeg = !spec.fallbackMjpegUrl.isNullOrBlank(),
                onFallbackToMjpeg = {
                    Log.w("SingleCameraFeedView", "RTSP failed 3x for ${spec.entityId}; falling back to HA MJPEG stream")
                    fallbackToMjpeg = true
                },
                onCodecActive = onCodecActive,
                onFirstFrameMs = onFirstFrameMs,
                onPlaybackError = onPlaybackError,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val mjpegUrl = if (fallbackToMjpeg && !spec.fallbackMjpegUrl.isNullOrBlank()) {
                spec.fallbackMjpegUrl
            } else {
                spec.url
            }
            CameraMjpegView(
                url = mjpegUrl,
                authToken = spec.authToken,
                showLiveBadge = spec.showLiveBadge,
                badgeLabel = if (fallbackToMjpeg) "MJPEG" else "LIVE",
                targetPxW = targetPxW,
                targetPxH = targetPxH,
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
    LOW_LATENCY(minBufferMs = 300, maxBufferMs = 1500, startBufferMs = 150, rebufferMs = 250),
    BALANCED(minBufferMs = 600, maxBufferMs = 3000, startBufferMs = 350, rebufferMs = 500),
    HIGH_STABILITY(minBufferMs = 1500, maxBufferMs = 6000, startBufferMs = 1000, rebufferMs = 1500)
}

enum class TransportProtocol { AUTO, TCP, UDP }

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
@OptIn(UnstableApi::class)
@Composable
private fun CameraRtspView(
    url: String,
    config: RtspProfile,
    snapshotUrl: String? = null,
    authToken: String? = null,
    showLiveBadge: Boolean = true,
    useSurfaceView: Boolean = false,
    targetPxW: Int = 560,
    targetPxH: Int = 316,
    openedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    canFallbackToMjpeg: Boolean = false,
    onFallbackToMjpeg: () -> Unit = {},
    onCodecActive: (String?) -> Unit = {},
    onFirstFrameMs: (Long) -> Unit = {},
    onPlaybackError: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var showDebug by remember(url, config.transport, config.latency, config.useSoftwareDecoder) { mutableStateOf(false) }
    var lastError by remember(url, config.transport, config.latency, config.useSoftwareDecoder) { mutableStateOf<String?>(null) }
    var retryCount by remember(url, config.transport, config.latency, config.useSoftwareDecoder) { mutableStateOf(0) }
    var isFirstFrameRendered by remember(url, config.transport, config.latency, config.useSoftwareDecoder) { mutableStateOf(false) }
    var pendingRetry by remember(url, config.transport, config.latency, config.useSoftwareDecoder) { mutableStateOf(false) }
    var snapshotBmp by remember(snapshotUrl) { mutableStateOf<Bitmap?>(null) }
    var videoW by remember(url, config.transport, config.latency, config.useSoftwareDecoder) { mutableStateOf(0) }
    var videoH by remember(url, config.transport, config.latency, config.useSoftwareDecoder) { mutableStateOf(0) }
    val latestOnFirstFrame by rememberUpdatedState(onFirstFrameMs)

    // Cache-busted snapshot-first fetch (uses prefetched bytes if ready; swaps bitmap on Main thread before recycling)
    LaunchedEffect(snapshotUrl, authToken, targetPxW, targetPxH) {
        if (snapshotUrl.isNullOrBlank()) return@LaunchedEffect
        val prefetched = CameraPipController.consumePrefetchedSnapshot(snapshotUrl)
        val decoded: Bitmap? = withContext(Dispatchers.IO) {
            val bytes = prefetched ?: run {
                try {
                    val bustedUrl = appendCacheBuster(snapshotUrl, System.currentTimeMillis())
                    val reqBuilder = Request.Builder().url(bustedUrl)
                    if (!authToken.isNullOrBlank()) {
                        reqBuilder.header("Authorization", "Bearer $authToken")
                    }
                    SharedHttpClients.mediaFetch.newCall(reqBuilder.build()).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.bytes() else null
                    }
                } catch (_: Throwable) {
                    null
                }
            }
            if (bytes != null && bytes.isNotEmpty()) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val sample = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetPxW, targetPxH)
                } else 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } else null
        }
        if (decoded != null) {
            val prev = snapshotBmp
            snapshotBmp = decoded
            if (prev != null && prev !== decoded && !prev.isRecycled) {
                prev.recycle()
            }
        }
    }

    DisposableEffect(snapshotUrl) {
        onDispose {
            val bmp = snapshotBmp
            snapshotBmp = null
            if (bmp != null && !bmp.isRecycled) {
                try { bmp.recycle() } catch (_: Throwable) {}
            }
        }
    }

    LaunchedEffect(pendingRetry, retryCount) {
        if (pendingRetry && retryCount in 1..5) {
            val backoffMs = (1000L shl minOf(retryCount - 1, 3)).coerceAtMost(8000L)
            delay(backoffMs)
            pendingRetry = false
        }
    }

    val player = remember(url, config.transport, config.latency, config.useSoftwareDecoder, if (pendingRetry) -1 else retryCount) {
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
                                .setTargetOffsetMs(250)
                                .setMaxPlaybackSpeed(1.06f)
                                .build()
                        )
                    }
                }
                .build()

            val mediaSource = if (url.startsWith("rtsp://", ignoreCase = true)) {
                val rtspFactory = androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                    .setTimeoutMs(6000)

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
                        .setUsage(C.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build()

                    // Manage transient ducking audio focus manually so unmuted PiP never permanently stops Plex/Netflix
                    setAudioAttributes(attrs, false)
                    volume = if (config.muteAudio) 0f else 1f
                    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT

                    val self = this
                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            videoW = videoSize.width
                            videoH = videoSize.height
                        }

                        override fun onRenderedFirstFrame() {
                            isFirstFrameRendered = true
                            retryCount = 0
                            showDebug = false
                            lastError = null
                            val effW = if (videoW > 0) videoW else self.videoSize.width
                            val effH = if (videoH > 0) videoH else self.videoSize.height
                            videoW = effW
                            videoH = effH
                            val codecName = self.videoFormat?.codecs ?: self.videoDecoderCounters?.let {
                                self.videoFormat?.sampleMimeType
                            }
                            onCodecActive(codecName ?: "h264")
                            val ttff = (SystemClock.elapsedRealtime() - openedAtElapsedMs).coerceAtLeast(1L)
                            latestOnFirstFrame(ttff)
                            Log.i("CameraRtspView", "FIRST_FRAME url=$url size=${effW}x${effH} ttffMs=$ttff")
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            val cause = error.cause?.message ?: "Unknown"
                            lastError = "Code: ${error.errorCodeName}\n$cause"
                            Log.e("CameraRtspView", "PLAYBACK_ERROR url=$url code=${error.errorCodeName} msg=$cause")
                            onPlaybackError("${error.errorCodeName}: $cause")

                            if (canFallbackToMjpeg && retryCount >= 2) {
                                onFallbackToMjpeg()
                                return
                            }

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
            onPlaybackError("INIT_FAIL: ${e.message}")
            showDebug = true
            null
        }
    }

    // Dynamic in-place mute/unmute + transient ducking audio focus
    DisposableEffect(player, config.muteAudio) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var focusReq: AudioFocusRequest? = null
        if (player != null) {
            player.volume = if (config.muteAudio) 0f else 1f
            if (!config.muteAudio && am != null) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                focusReq = req
                runCatching { am.requestAudioFocus(req) }
            }
        }
        onDispose {
            focusReq?.let { r -> runCatching { am?.abandonAudioFocusRequest(r) } }
        }
    }

    // Smoothly crossfade out snapshot once live video first frame renders
    val snapshotAlpha by animateFloatAsState(
        targetValue = if (!isFirstFrameRendered && snapshotBmp != null && !showDebug) 1f else 0f,
        animationSpec = tween(250),
        label = "snapshotFade"
    )

    Box(modifier = modifier.background(Color.Black)) {
        if (player != null) {
            PlayerSurface(
                player = player,
                surfaceType = if (useSurfaceView) SURFACE_TYPE_SURFACE_VIEW else SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier.fillMaxSize()
            )

            if (snapshotBmp != null && snapshotAlpha > 0.01f) {
                Image(
                    bitmap = snapshotBmp!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(snapshotAlpha),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else if (!isFirstFrameRendered && snapshotBmp == null && !showDebug) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        text = "Connecting…",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }

            if (showLiveBadge && isFirstFrameRendered && !showDebug) {
                PipLiveBadge(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                )
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
            Text(
                "Stream playback unavailable",
                color = Color.White,
                modifier = Modifier.padding(12.dp)
            )
        }
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun DebugInfoOverlay(
    player: androidx.media3.exoplayer.ExoPlayer,
    errorMessage: String? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
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
                val cur = view.tag as? Pair<*, *>
                if (cur?.first !== player) {
                    (cur?.second as? androidx.media3.exoplayer.util.DebugTextViewHelper)?.stop()
                    val helper = androidx.media3.exoplayer.util.DebugTextViewHelper(player, view)
                    helper.start()
                    view.tag = player to helper
                }
            },
            onRelease = { view ->
                val cur = view.tag as? Pair<*, *>
                (cur?.second as? androidx.media3.exoplayer.util.DebugTextViewHelper)?.stop()
                view.tag = null
            }
        )

        if (errorMessage != null) {
            Text(
                text = "PLAYBACK FAILED\n\n$errorMessage",
                color = Color.Red,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
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
    showLiveBadge: Boolean = true,
    badgeLabel: String = "LIVE",
    targetPxW: Int = 560,
    targetPxH: Int = 316,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val tag = "CameraMjpegView"
    val okHttp = SharedHttpClients.mjpegStream

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loggedFirstFrame by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, authToken, targetPxW, targetPxH) {
        error = null
        isLoading = true
        loggedFirstFrame = false
        var attempt = 0
        var reusableBmp: Bitmap? = null

        val reqBuilder = Request.Builder().url(url)
        if (!authToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $authToken")
        }
        val req = reqBuilder.build()

        try {
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
                                readMjpegLoop(bis, minFrameIntervalMs = 85L) { buf, offset, len ->
                                    val decoded = decodeMjpegFrame(buf, offset, len, targetPxW, targetPxH, reusableBmp)
                                    if (decoded != null) {
                                        reusableBmp = decoded
                                        bitmap = decoded
                                        error = null
                                        if (!loggedFirstFrame) {
                                            loggedFirstFrame = true
                                            Log.i(tag, "MJPEG_FIRST_FRAME url=$url size=${decoded.width}x${decoded.height}")
                                        }
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
        } finally {
            bitmap = null
            reusableBmp?.let {
                if (!it.isRecycled) {
                    try { it.recycle() } catch (_: Throwable) {}
                }
            }
            reusableBmp = null
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                if (showLiveBadge) {
                    PipLiveBadge(
                        label = badgeLabel,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    )
                }
            }
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

private fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
    var inSample = 1
    if (srcH > reqH && srcW > reqW) {
        val halfH = srcH / 2
        val halfW = srcW / 2
        while ((halfH / inSample) >= reqH && (halfW / inSample) >= reqW) {
            inSample *= 2
        }
    }
    return inSample.coerceAtLeast(1)
}

private fun decodeMjpegFrame(
    buf: ByteArray,
    offset: Int,
    length: Int,
    targetPxW: Int,
    targetPxH: Int,
    reusable: Bitmap?
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(buf, offset, length, bounds)
    val sample = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetPxW, targetPxH)
    } else {
        if (length > 250 * 1024) 2 else 1
    }

    val opts = BitmapFactory.Options().apply {
        inMutable = true
        inSampleSize = sample
        if (reusable != null && !reusable.isRecycled) {
            inBitmap = reusable
        }
    }
    return try {
        BitmapFactory.decodeByteArray(buf, offset, length, opts)
    } catch (_: IllegalArgumentException) {
        val fallbackOpts = BitmapFactory.Options().apply {
            inMutable = true
            inSampleSize = sample
        }
        BitmapFactory.decodeByteArray(buf, offset, length, fallbackOpts)
    }
}

/**
 * Reusable dynamic frame buffer supporting zero-copy direct slice decoding and in-place compaction.
 */
private class FastFrameBuffer(initialCapacity: Int = 256 * 1024) {
    private val maxCapacity = 4 * 1024 * 1024
    var buf = ByteArray(initialCapacity)
    var size = 0
        private set

    fun reset() {
        size = 0
    }

    fun compactFrom(startIdx: Int) {
        if (startIdx <= 0) return
        if (startIdx >= size) {
            size = 0
            return
        }
        val rem = size - startIdx
        System.arraycopy(buf, startIdx, buf, 0, rem)
        size = rem
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
}

/**
 * Zero-copy sliding-window MJPEG stream reader: decodes frames directly from the shared buffer
 * slice `(buf, soi, frameLen)` and shifts trailing bytes in-place without extra per-frame allocations.
 */
private suspend fun readMjpegLoop(
    input: InputStream,
    minFrameIntervalMs: Long = 85L,
    onFrame: (buf: ByteArray, offset: Int, length: Int) -> Unit
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
                if (soi > 0) {
                    streamBuf.compactFrom(soi)
                }
                break
            }

            val frameEnd = eoi + 2
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastEmitMs >= minFrameIntervalMs) {
                onFrame(streamBuf.buf, soi, frameEnd - soi)
                lastEmitMs = now
            }

            streamBuf.compactFrom(frameEnd)
            if (streamBuf.size == 0) break
        }
    }
}
