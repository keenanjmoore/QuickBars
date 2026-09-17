package dev.trooped.tvquickbars.camera

import android.content.Context
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import dev.trooped.tvquickbars.QuickBarsApp
import dev.trooped.tvquickbars.R
import dev.trooped.tvquickbars.persistence.SavedEntitiesManager
import dev.trooped.tvquickbars.persistence.SecurePrefsManager
import dev.trooped.tvquickbars.data.EntityItem
import dev.trooped.tvquickbars.services.ComposeViewLifecycleOwner
import kotlin.math.roundToInt

/**
 * Manages Camera PiP surfaces on Android TV:
 *  - Independent multi-slot corner overlays (TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT)
 *  - Multi-camera combined grid/split overlays
 *  - Per-slot lifecycle, timers, and explicit close controls
 *  - Concurrency caps and auto-muting safeguards
 */
class CameraPipController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val runOnMain: (block: () -> Unit) -> Unit,
    private val showToast: (String) -> Unit
) {
    private val TAG = "CameraPipController"

    private data class ActivePipInstance(
        val slotKey: String,
        val spec: CameraPipSpec,
        val view: ComposeView,
        val lifecycleOwner: ComposeViewLifecycleOwner,
        val hideHandler: Handler,
        val hideRunnable: Runnable
    )

    // Registry of all currently active overlays keyed by slotKey (e.g. "TOP_LEFT", "TOP_RIGHT")
    private val activeOverlays = LinkedHashMap<String, ActivePipInstance>()

    private var showToastOnToggle: Boolean = true

    /** Main entry point used by the service. */
    fun handleRequest(req: CameraRequest): Unit = runOnMain {
        try {
            val triggerKeyToggle: Boolean = try {
                QuickBarsApp.showToastOnEntityTrigger
            } catch (e: Exception) {
                true
            }

            showToastOnToggle = when (req.showToggleToast) {
                true -> true
                false -> triggerKeyToggle
                null -> triggerKeyToggle
            }

            // ── Explicit Close Actions ──────────────────────────────────────────
            val action = req.action?.lowercase()
            if (action == "close_all") {
                hideAll()
                return@runOnMain
            }
            if (action == "close") {
                val target = req.slot ?: req.cameraEntity ?: req.cameraAlias
                hide(target)
                return@runOnMain
            }

            // ── Multi-Camera Group Request (Grid / Split Dock) ──────────────────
            if (req.cameras != null && req.cameras.isNotEmpty()) {
                handleGroupRequest(req)
                return@runOnMain
            }

            // ── Single Camera Request ───────────────────────────────────────────
            handleSingleRequest(req)
        } catch (t: Throwable) {
            Log.e(TAG, "handleRequest failed: ${t.message}", t)
            showToast("Failed to open camera")
        }
    }

    /** Handles group requests combining multiple cameras into a single overlay window. */
    private fun handleGroupRequest(req: CameraRequest) {
        val subRequests = req.cameras ?: return
        val subSpecs = mutableListOf<CameraPipSpec>()

        for ((index, subReq) in subRequests.withIndex()) {
            val safeRtsp = asValidRtspOrNull(subReq.rtspUrl)
            val subSpec = if (safeRtsp != null) {
                // Secondary feeds in a group automatically mute to avoid audio chaos
                val mute = if (index > 0) true else (subReq.muteAudio ?: req.muteAudio ?: false)
                buildAdhocRtspSpec(subReq.copy(rtspUrl = safeRtsp, muteAudio = mute))
            } else {
                val entity = resolveCameraEntity(
                    cameraEntityId = subReq.cameraEntity?.takeIf { it.isNotBlank() },
                    aliasOrName    = subReq.cameraAlias?.takeIf { it.isNotBlank() }
                ) ?: continue
                val mute = if (index > 0) true else (subReq.muteAudio ?: req.muteAudio ?: false)
                buildCameraSpecWithOverrides(entity, subReq.copy(muteAudio = mute))
            }
            subSpecs.add(subSpec)
        }

        if (subSpecs.isEmpty()) {
            showToast("No playable cameras found in group")
            return
        }

        val layout = req.layout ?: if (subSpecs.size == 2) "side_by_side" else "grid"
        val defaultCorner = "TOP_RIGHT"
        val corner = mapCornerOverride(req.position) ?: defaultCorner
        val slotKey = req.slot ?: corner
        val (wDp, hDp) = chooseSize(req, "MEDIUM", isGroup = true, groupCount = subSpecs.size, layout = layout)
        val autoHide = req.autoHideSec ?: 45
        val title = req.customTitle?.takeIf { !it.isNullish() }?.trim() ?: "Cameras (${subSpecs.size})"

        val groupSpec = CameraPipSpec(
            url = subSpecs.first().url,
            authToken = subSpecs.first().authToken,
            entityId = "group:" + subSpecs.joinToString("+") { it.entityId },
            title = title,
            widthDp = wDp,
            heightDp = hDp,
            marginDp = 20,
            showTitle = req.showTitle ?: true,
            cornerPosition = corner,
            autoHideTimeout = autoHide,
            slotKey = slotKey,
            isGroup = true,
            groupSpecs = subSpecs,
            groupLayout = layout
        )

        if (showToastOnToggle && !activeOverlays.containsKey(slotKey)) {
            showToast("$title → PIP")
        }
        show(groupSpec)
    }

    /** Handles standard single camera requests. */
    private fun handleSingleRequest(req: CameraRequest) {
        // ── Ad-hoc RTSP stream ──────────────────────────────────────────────
        val safeRtsp = asValidRtspOrNull(req.rtspUrl)
        if (safeRtsp != null) {
            try {
                val spec = buildAdhocRtspSpec(req.copy(rtspUrl = safeRtsp))
                showSingleSpec(spec, req)
            } catch (_: Throwable) {
                showToast("Failed to open RTSP stream")
            }
            return
        }

        // ── Home Assistant Camera Entity ────────────────────────────────────
        val entity = resolveCameraEntity(
            cameraEntityId = req.cameraEntity?.takeIf { it.isNotBlank() },
            aliasOrName    = req.cameraAlias?.takeIf { it.isNotBlank() }
        ) ?: return

        val spec = buildCameraSpecWithOverrides(entity, req)

        val playable = spec.url.startsWith("http://", ignoreCase = true)
                || spec.url.startsWith("https://", ignoreCase = true)
                || spec.url.startsWith("rtsp://", ignoreCase = true)

        if (!playable) {
            showToast("Stream not found")
            return
        }

        showSingleSpec(spec, req)
    }

    private fun showSingleSpec(baseSpec: CameraPipSpec, req: CameraRequest) {
        // Resolve target slot key
        val targetSlot = req.slot ?: mapCornerOverride(req.position) ?: allocateAvailableSlot(baseSpec.cornerPosition)
        val corner = mapCornerOverride(targetSlot) ?: baseSpec.cornerPosition

        // Auto-mute secondary streams if another overlay is already active, unless user explicitly unmuted
        val shouldMute = if (req.muteAudio != null) {
            req.muteAudio
        } else if (activeOverlays.isNotEmpty()) {
            true
        } else {
            baseSpec.rtspProfile.muteAudio
        }

        val finalSpec = baseSpec.copy(
            cornerPosition = corner,
            slotKey = targetSlot,
            rtspProfile = baseSpec.rtspProfile.copy(muteAudio = shouldMute)
        )

        if (!activeOverlays.containsKey(targetSlot) && showToastOnToggle) {
            showToast("${finalSpec.title} → PIP")
        }
        show(finalSpec)
    }

    /** Finds an available corner slot, or falls back to defaultCorner. */
    private fun allocateAvailableSlot(preferredCorner: String): String {
        if (!activeOverlays.containsKey(preferredCorner)) {
            return preferredCorner
        }
        val slots = listOf("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT")
        return slots.firstOrNull { !activeOverlays.containsKey(it) } ?: preferredCorner
    }

    @MainThread
    fun show(spec: CameraPipSpec): Unit = runOnMain {
        if (!Settings.canDrawOverlays(context)) return@runOnMain

        val slotKey = spec.slotKey

        // If this slot is already showing:
        val existing = activeOverlays[slotKey]
        if (existing != null) {
            if (existing.spec.entityId == spec.entityId) {
                hide(slotKey) // toggle off
                return@runOnMain
            } else {
                hide(slotKey) // replace stream in this slot
            }
        }

        // Hardware Decoder Concurrency Guard: Limit to max 4 concurrent streams
        if (activeOverlays.size >= 4) {
            val oldestKey = activeOverlays.keys.firstOrNull()
            if (oldestKey != null) {
                Log.w(TAG, "Exceeded 4 concurrent streams; recycling oldest slot: $oldestKey")
                hide(oldestKey)
            }
        }

        // Dedicated Compose lifecycle for this overlay surface
        val lifecycleOwner = ComposeViewLifecycleOwner()
        lifecycleOwner.create()

        val hideHandler = Handler(Looper.getMainLooper())
        val hideRunnable = Runnable { hide(slotKey) }

        // Fresh Compose View
        val themed = ContextThemeWrapper(context, R.style.Theme_HAQuickBars)
        val view = ComposeView(themed).apply {
            tag = spec
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow
            )
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    lifecycleOwner.attachToView(this@apply)
                    setContent {
                        MaterialTheme {
                            val align = when (spec.cornerPosition) {
                                "TOP_RIGHT" -> Alignment.TopEnd
                                "BOTTOM_LEFT" -> Alignment.BottomStart
                                "BOTTOM_RIGHT" -> Alignment.BottomEnd
                                else -> Alignment.TopStart
                            }
                            Box(Modifier.wrapContentSize(), contentAlignment = align) {
                                Box(Modifier.padding(12.dp)) {
                                    CameraPipOverlay(spec = spec, onClose = { hide(slotKey) })
                                }
                            }
                        }
                    }
                    Choreographer.getInstance().postFrameCallback { lifecycleOwner.resume() }
                    v.removeOnAttachStateChangeListener(this)
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }

        // Layout Parameters
        val density = context.resources.displayMetrics.density
        val padPx = (12f * 2f * density).roundToInt()
        val wPx = (spec.widthDp * density).roundToInt() + padPx
        val hPx = (spec.heightDp * density).roundToInt() + padPx

        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val finalW = wPx.coerceAtMost((screenW * 0.95f).toInt())
        val finalH = hPx.coerceAtMost((screenH * 0.95f).toInt())

        val params = WindowManager.LayoutParams(
            finalW,
            finalH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            windowAnimations = 0
            gravity = when (spec.cornerPosition) {
                "TOP_RIGHT"    -> Gravity.TOP or Gravity.END
                "BOTTOM_LEFT"  -> Gravity.BOTTOM or Gravity.START
                "BOTTOM_RIGHT" -> Gravity.BOTTOM or Gravity.END
                else           -> Gravity.TOP or Gravity.START
            }
            x = 24; y = 24; alpha = 1f
        }

        try {
            windowManager.addView(view, params)
            activeOverlays[slotKey] = ActivePipInstance(
                slotKey = slotKey,
                spec = spec,
                view = view,
                lifecycleOwner = lifecycleOwner,
                hideHandler = hideHandler,
                hideRunnable = hideRunnable
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error adding camera overlay to WindowManager for slot $slotKey", e)
            try { lifecycleOwner.destroy() } catch (_: Throwable) {}
            return@runOnMain
        }

        // Auto-hide countdown
        val timeout = spec.autoHideTimeout
        if (timeout > 0) {
            hideHandler.postDelayed(hideRunnable, timeout * 1000L)
        }
    }

    /**
     * Closes a specific camera overlay by slotKey or entityId.
     * If target is null and only 1 overlay exists, closes it; if multiple exist, closes all.
     */
    @MainThread
    fun hide(target: String? = null): Unit = runOnMain {
        if (target == null) {
            if (activeOverlays.size == 1) {
                val singleKey = activeOverlays.keys.first()
                teardownOverlay(singleKey)
            } else {
                hideAll()
            }
            return@runOnMain
        }

        // Match by exact slotKey first
        if (activeOverlays.containsKey(target)) {
            teardownOverlay(target)
            return@runOnMain
        }

        // Match normalized corner override
        val cornerKey = mapCornerOverride(target)
        if (cornerKey != null && activeOverlays.containsKey(cornerKey)) {
            teardownOverlay(cornerKey)
            return@runOnMain
        }

        // Match by entityId or alias inside the spec
        val matchKey = activeOverlays.entries.firstOrNull { (_, instance) ->
            instance.spec.entityId.equals(target, ignoreCase = true) ||
            instance.spec.title.equals(target, ignoreCase = true) ||
            instance.spec.groupSpecs.any { it.entityId.equals(target, ignoreCase = true) }
        }?.key

        if (matchKey != null) {
            teardownOverlay(matchKey)
        }
    }

    /** Closes all active camera overlays. */
    @MainThread
    fun hideAll(): Unit = runOnMain {
        val keys = activeOverlays.keys.toList()
        for (key in keys) {
            teardownOverlay(key)
        }
    }

    private fun teardownOverlay(slotKey: String) {
        val instance = activeOverlays.remove(slotKey) ?: return
        instance.hideHandler.removeCallbacks(instance.hideRunnable)

        val view = instance.view
        val owner = instance.lifecycleOwner

        try { owner.pause() } catch (_: Throwable) {}
        try { view.setContent { } } catch (_: Throwable) {}

        view.post {
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeViewImmediate(view)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "removeViewImmediate failed for $slotKey, fallback to removeView", t)
                try { windowManager.removeView(view) } catch (_: Throwable) {}
            }
            try { owner.destroy() } catch (_: Throwable) {}
        }
    }

    @MainThread
    fun destroy() = runOnMain {
        hideAll()
    }

    // ── Internals & Resolution Helpers ─────────────────────────────────────

    private fun String?.isNullish(): Boolean =
        this == null || this.isBlank() || this.trim().equals("null", ignoreCase = true)

    private fun asValidRtspOrNull(raw: String?): String? {
        val url = raw?.trim() ?: return null
        if (!url.startsWith("rtsp://", ignoreCase = true)) return null
        val u = Uri.parse(url)
        return if (!u.host.isNullOrBlank()) url else null
    }

    private fun buildAdhocRtspSpec(req: CameraRequest): CameraPipSpec {
        val raw = requireNotNull(req.rtspUrl) { "rtspUrl is required" }.trim()
        val sanitized = sanitizeRtspUserInfo(raw)

        val entityId = buildRtspEntityId(req.copy(rtspUrl = sanitized))
        val defaultCorner = "TOP_LEFT"
        val defaultSize = "MEDIUM"
        val corner = mapCornerOverride(req.position) ?: defaultCorner
        val (wDp, hDp) = chooseSize(req, defaultSize)
        val autoHide = req.autoHideSec ?: 30
        val showTitle = req.showTitle ?: true
        val title = req.customTitle?.takeIf { !it.isNullish() }?.trim() ?: "Camera"

        val transport = when (req.rtspTransport?.lowercase()) {
            "udp" -> TransportProtocol.UDP
            "tcp" -> TransportProtocol.TCP
            "auto" -> TransportProtocol.AUTO
            else -> TransportProtocol.TCP
        }

        val latency = when (req.rtspLatency?.lowercase()) {
            "low" -> StreamLatency.LOW_LATENCY
            "high" -> StreamLatency.HIGH_STABILITY
            "balanced" -> StreamLatency.BALANCED
            else -> StreamLatency.BALANCED
        }

        val profile = RtspProfile(
            transport = transport,
            latency = latency,
            useSoftwareDecoder = req.useSoftwareDecoder ?: false,
            muteAudio = req.muteAudio ?: false
        )

        return CameraPipSpec(
            url = sanitized,
            authToken = null,
            entityId = entityId,
            title = title,
            widthDp = wDp,
            heightDp = hDp,
            marginDp = 20,
            showTitle = showTitle,
            cornerPosition = corner,
            autoHideTimeout = autoHide,
            rtspProfile = profile,
            slotKey = req.slot ?: corner
        )
    }

    private fun sanitizeRtspUserInfo(url: String): String {
        if (!url.startsWith("rtsp://", ignoreCase = true)) return url
        val withoutScheme = url.substring(7)
        val at = withoutScheme.lastIndexOf('@')
        if (at <= 0) return url

        val userInfo = withoutScheme.substring(0, at)
        val rest = withoutScheme.substring(at + 1)

        val colonIdx = userInfo.indexOf(':')
        val userRaw = if (colonIdx >= 0) userInfo.substring(0, colonIdx) else userInfo
        val passRaw = if (colonIdx >= 0) userInfo.substring(colonIdx + 1) else null

        val userEnc = encodeUserInfoComponent(userRaw)
        val passEnc = passRaw?.let { encodeUserInfoComponent(it) }

        val rebuiltUserInfo = if (passEnc != null) "$userEnc:$passEnc" else userEnc
        return "rtsp://$rebuiltUserInfo@$rest"
    }

    private fun encodeUserInfoComponent(s: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val sb = StringBuilder(s.length * 3)
        for (ch in s) {
            if (unreserved.indexOf(ch) >= 0) {
                sb.append(ch)
            } else {
                val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                for (b in bytes) {
                    val v = b.toInt() and 0xFF
                    sb.append('%')
                    sb.append("0123456789ABCDEF"[v ushr 4])
                    sb.append("0123456789ABCDEF"[v and 0x0F])
                }
            }
        }
        return sb.toString()
    }

    private fun buildRtspEntityId(req: CameraRequest): String {
        req.cameraAlias?.takeIf { it.isNotBlank() }?.let { return "rtsp:alias:$it" }
        val u = Uri.parse(requireNotNull(req.rtspUrl))
        val host = u.host ?: "unknown"
        val port = if (u.port != -1) u.port else 554
        val path = (u.encodedPath ?: "").ifEmpty { "/" }
        return "rtsp:url:$host:$port$path"
    }

    private fun resolveCameraEntity(
        cameraEntityId: String?,
        aliasOrName: String?
    ): EntityItem? {
        val sem = SavedEntitiesManager(context)
        val cams = sem.loadEntities().filter { it.id.startsWith("camera.") }

        val e = when {
            !cameraEntityId.isNullOrBlank() ->
                cams.firstOrNull { it.id.equals(cameraEntityId, ignoreCase = true) }

            !aliasOrName.isNullOrBlank() -> {
                val q = aliasOrName.trim().lowercase()
                cams.firstOrNull {
                    it.cameraAlias?.lowercase() == q ||
                            it.customName?.lowercase()  == q ||
                            it.friendlyName?.lowercase()== q
                }
            }
            else -> null
        }

        if (e == null) {
            Toast.makeText(context, "Camera not found: ${cameraEntityId ?: aliasOrName ?: "(none)"}", Toast.LENGTH_SHORT).show()
            return null
        }
        return e
    }

    private fun buildCameraSpecWithOverrides(
        entity: EntityItem,
        req: CameraRequest
    ): CameraPipSpec {
        val rtsp = req.rtspUrl?.takeIf { it.isNotBlank() }
        val (finalUrl, finalToken) = if (rtsp != null) {
            rtsp to null
        } else {
            var baseUrl = SecurePrefsManager.getHAUrl(context)
            val authToken = SecurePrefsManager.getHAToken(context)
            if (baseUrl != null && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                baseUrl = if (baseUrl.contains(":")) "http://$baseUrl" else "http://$baseUrl:8123"
            }
            "${baseUrl}/api/camera_proxy_stream/${entity.id}" to authToken
        }

        val defaultCorner = (entity.lastKnownState["pip_corner"] as? String) ?: "TOP_LEFT"
        val defaultSize = (entity.lastKnownState["pip_size"] as? String) ?: "MEDIUM"
        val defaultTitle = (entity.lastKnownState["show_title"] as? Boolean) ?: true
        val defaultAuto = ((entity.lastKnownState["auto_hide_timeout"] as? Number)?.toInt())
            ?: ((entity.lastKnownState["auto_hide_timeout"] as? String)?.toIntOrNull())
            ?: 30

        val corner = mapCornerOverride(req.position) ?: defaultCorner
        val (wDp, hDp) = chooseSize(req, defaultSize)
        val autoHide = req.autoHideSec ?: defaultAuto
        val showTitle = req.showTitle ?: defaultTitle

        val cn = entity.customName
        val fn = entity.friendlyName
        val safeTitle = when {
            !req.customTitle.isNullish() -> req.customTitle!!.trim()
            !cn.isNullOrBlank() && !cn.equals("null", true) -> cn
            !fn.isNullOrBlank() && !fn.equals("null", true) -> fn
            else -> "Camera"
        }

        val transport = when (req.rtspTransport?.lowercase()) {
            "udp" -> TransportProtocol.UDP
            "tcp" -> TransportProtocol.TCP
            "auto" -> TransportProtocol.AUTO
            else -> TransportProtocol.TCP
        }

        val latency = when (req.rtspLatency?.lowercase()) {
            "low" -> StreamLatency.LOW_LATENCY
            "high" -> StreamLatency.HIGH_STABILITY
            "balanced" -> StreamLatency.BALANCED
            else -> StreamLatency.BALANCED
        }

        val profile = RtspProfile(
            transport = transport,
            latency = latency,
            useSoftwareDecoder = req.useSoftwareDecoder ?: false,
            muteAudio = req.muteAudio ?: false
        )

        return CameraPipSpec(
            url = finalUrl,
            authToken = finalToken,
            entityId = entity.id,
            title = safeTitle,
            widthDp = wDp,
            heightDp = hDp,
            marginDp = 20,
            showTitle = showTitle,
            cornerPosition = corner,
            autoHideTimeout = autoHide,
            rtspProfile = profile,
            slotKey = req.slot ?: corner
        )
    }

    private fun mapCornerOverride(pos: String?): String? = when (pos?.lowercase()) {
        null, ""       -> null
        "top_left"     -> "TOP_LEFT"
        "top_right"    -> "TOP_RIGHT"
        "bottom_left"  -> "BOTTOM_LEFT"
        "bottom_right" -> "BOTTOM_RIGHT"
        else           -> null
    }

    private fun chooseSize(
        req: CameraRequest,
        defaultSize: String,
        isGroup: Boolean = false,
        groupCount: Int = 1,
        layout: String = "grid"
    ): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density

        if (req.sizePxW != null && req.sizePxH != null) {
            val wDp = (req.sizePxW / density).roundToInt().coerceIn(120, 3840)
            val hDp = (req.sizePxH / density).roundToInt().coerceIn(90, 2160)
            return wDp to hDp
        }

        val base = when (req.size?.lowercase()) {
            "small"  -> 200 to 112
            "medium" -> 280 to 158
            "large"  -> 360 to 202
            else -> when (defaultSize.uppercase()) {
                "SMALL"  -> 200 to 112
                "MEDIUM" -> 280 to 158
                "LARGE"  -> 360 to 202
                else     -> 280 to 158
            }
        }

        if (!isGroup || groupCount <= 1) {
            return base
        }

        return when {
            layout == "side_by_side" || (layout == "auto" && groupCount == 2) -> {
                (base.first * 2 + 8) to base.second
            }
            layout == "stacked" -> {
                base.first to (base.second * 2 + 8)
            }
            else -> { // 2x2 grid
                if (groupCount <= 2) {
                    (base.first * 2 + 8) to base.second
                } else {
                    (base.first * 2 + 8) to (base.second * 2 + 8)
                }
            }
        }
    }
}
