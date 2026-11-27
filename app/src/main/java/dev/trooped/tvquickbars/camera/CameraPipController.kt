package dev.trooped.tvquickbars.camera

import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import dev.trooped.tvquickbars.QuickBarsApp
import dev.trooped.tvquickbars.R
import dev.trooped.tvquickbars.background.BackgroundHaConnectionManager
import dev.trooped.tvquickbars.background.HAStateStore
import dev.trooped.tvquickbars.data.EntityItem
import dev.trooped.tvquickbars.persistence.AppPrefs
import dev.trooped.tvquickbars.persistence.SavedEntitiesManager
import dev.trooped.tvquickbars.persistence.SecurePrefsManager
import dev.trooped.tvquickbars.services.ComposeViewLifecycleOwner
import dev.trooped.tvquickbars.utils.QuickBarsTtsHelper
import dev.trooped.tvquickbars.utils.SharedHttpClients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class PipDiagnosticsSnapshot(
    val activeSlots: List<String>,
    val codecs: Map<String, String>,
    val recentErrors: List<String>
)

/**
 * Manages Camera PiP surfaces on Android TV:
 *  - Independent multi-slot corner overlays (TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT)
 *  - Multi-camera combined grid/split overlays
 *  - RAM-aware concurrency cap on 2GB devices + auto-muting safeguards
 *  - Interactive remote focus / expand / corner-cycle & diagnostic snapshots
 */
class CameraPipController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val runOnMain: (block: () -> Unit) -> Unit,
    private val showToast: (String) -> Unit,
    private val hostKey: String = "service"
) {
    private val TAG = "CameraPipController"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val savedEntitiesManager by lazy { SavedEntitiesManager(context.applicationContext) }

    private class ActivePipInstance(
        var slotKey: String,
        val specState: MutableState<CameraPipSpec>,
        val view: ComposeView,
        val layoutParams: WindowManager.LayoutParams,
        val lifecycleOwner: ComposeViewLifecycleOwner,
        var hideRunnable: Runnable,
        val countdownResetMs: MutableState<Long> = mutableStateOf(System.currentTimeMillis()),
        val openedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
        var autoUnfocusRunnable: Runnable? = null
    ) {
        val spec: CameraPipSpec get() = specState.value
    }

    private val activeOverlays = LinkedHashMap<String, ActivePipInstance>()
    private val activeCodecsBySlot = ConcurrentHashMap<String, String>()
    private val recentErrors = ArrayDeque<String>(6)
    @Volatile private var lastPublishedActiveIds: List<String>? = null
    private var showToastOnToggle: Boolean = true

    fun hasActivePips(): Boolean = activeOverlays.isNotEmpty()

    fun isMostRecentExpanded(): Boolean = activeOverlays.values.lastOrNull()?.spec?.isFullscreen == true

    fun mostRecentHasActions(): Boolean = activeOverlays.values.lastOrNull()?.spec?.actions?.isNotEmpty() == true

    fun getDiagnosticsSnapshot(): PipDiagnosticsSnapshot = PipDiagnosticsSnapshot(
        activeSlots = activeOverlays.map { (slot, inst) -> "$slot:${inst.spec.entityId}" },
        codecs = HashMap(activeCodecsBySlot),
        recentErrors = synchronized(recentErrors) { recentErrors.toList() }
    )

    private fun recordError(slotKey: String, msg: String) {
        synchronized(recentErrors) {
            if (recentErrors.size >= 5) recentErrors.removeFirst()
            recentErrors.addLast("[$slotKey] $msg")
        }
    }

    /** Dismisses the most recently opened active PiP overlay. */
    @MainThread
    fun dismissMostRecentPip(): Unit = runOnMain {
        val lastKey = activeOverlays.keys.lastOrNull() ?: return@runOnMain
        hide(lastKey)
    }

    /** Toggles fullscreen expansion in-place with zero RTSP reconnect. */
    @MainThread
    fun toggleExpandMostRecentPip(): Unit = runOnMain {
        val lastEntry = activeOverlays.entries.lastOrNull() ?: return@runOnMain
        val instance = lastEntry.value
        val curSpec = instance.spec
        val expand = !curSpec.isFullscreen
        val nextSize = if (expand) {
            val density = context.resources.displayMetrics.density
            val screenW = context.resources.displayMetrics.widthPixels
            val screenH = context.resources.displayMetrics.heightPixels
            ((screenW / density) * 0.90f).roundToInt().coerceIn(320, 3840) to
                ((screenH / density) * 0.88f).roundToInt().coerceIn(180, 2160)
        } else {
            when {
                curSpec.isGroup && curSpec.groupLayout == "side_by_side" -> 280 * 2 + 8 to 158
                curSpec.isGroup && curSpec.groupSpecs.size > 2 -> 280 * 2 + 8 to 158 * 2 + 8
                else -> 280 to 158
            }
        }
        val toggledSpec = curSpec.copy(
            isFullscreen = expand,
            widthDp = nextSize.first,
            heightDp = nextSize.second
        )
        instance.specState.value = toggledSpec

        val lp = instance.layoutParams
        val density = context.resources.displayMetrics.density
        val padPx = (12f * 2f * density).roundToInt()
        val extraActionsPx = if (toggledSpec.actions.isNotEmpty()) (40f * density).roundToInt() else 0
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        lp.width = ((toggledSpec.widthDp * density).roundToInt() + padPx).coerceAtMost((screenW * 0.95f).toInt())
        lp.height = ((toggledSpec.heightDp * density).roundToInt() + padPx + extraActionsPx).coerceAtMost((screenH * 0.95f).toInt())
        lp.gravity = if (expand) Gravity.CENTER else gravityForCorner(toggledSpec.cornerPosition)
        val mPx = if (expand) 0 else (toggledSpec.marginDp.coerceIn(0, 64) * density).roundToInt()
        lp.x = mPx
        lp.y = mPx

        try {
            if (instance.view.isAttachedToWindow) {
                windowManager.updateViewLayout(instance.view, lp)
            }
            Log.i(TAG, "TOGGLE_EXPAND_INPLACE slot=${instance.slotKey} fullscreen=$expand")
        } catch (t: Throwable) {
            Log.w(TAG, "toggleExpandMostRecentPip failed: ${t.message}")
        }
    }

    /** Moves the most recently opened PiP window cleanly to the next unoccupied screen corner and re-keys slot state. */
    @MainThread
    fun cycleActivePipCorner(): Unit = runOnMain {
        val lastEntry = activeOverlays.entries.lastOrNull() ?: return@runOnMain
        val oldKey = lastEntry.key
        val instance = lastEntry.value
        if (instance.spec.isFullscreen) return@runOnMain
        val corners = listOf("TOP_RIGHT", "BOTTOM_RIGHT", "BOTTOM_LEFT", "TOP_LEFT")
        val curIdx = corners.indexOf(instance.spec.cornerPosition).coerceAtLeast(0)
        val nextCorner = (1..corners.size)
            .map { corners[(curIdx + it) % corners.size] }
            .firstOrNull { c -> activeOverlays.values.none { it.spec.cornerPosition == c } }
            ?: corners[(curIdx + 1) % corners.size]

        val lp = instance.layoutParams
        lp.gravity = gravityForCorner(nextCorner)
        try {
            if (instance.view.isAttachedToWindow) {
                windowManager.updateViewLayout(instance.view, lp)
            }
            if (oldKey != nextCorner && !activeOverlays.containsKey(nextCorner)) {
                activeOverlays.remove(oldKey)
                val codec = activeCodecsBySlot.remove(oldKey)
                if (codec != null) activeCodecsBySlot[nextCorner] = codec
                instance.slotKey = nextCorner
                mainHandler.removeCallbacks(instance.hideRunnable)
                val newHide = Runnable { hide(nextCorner) }
                instance.hideRunnable = newHide
                if (!instance.spec.isPinned && instance.spec.autoHideTimeout > 0) {
                    mainHandler.postDelayed(newHide, instance.spec.autoHideTimeout * 1000L)
                }
                instance.specState.value = instance.spec.copy(cornerPosition = nextCorner, slotKey = nextCorner)
                activeOverlays[nextCorner] = instance
            } else {
                instance.specState.value = instance.spec.copy(cornerPosition = nextCorner)
            }
            syncActiveTelemetry()
            Log.i(TAG, "CYCLE_CORNER from=$oldKey to=$nextCorner")
        } catch (t: Throwable) {
            Log.w(TAG, "cycleActivePipCorner failed: ${t.message}")
        }
    }

    /** Pins or unpins the most recent PiP overlay, cancelling or restoring its auto-hide timer. */
    @MainThread
    fun togglePinMostRecentPip(): Unit = runOnMain {
        val instance = activeOverlays.values.lastOrNull() ?: return@runOnMain
        val nextPinned = !instance.spec.isPinned
        mainHandler.removeCallbacks(instance.hideRunnable)
        if (!nextPinned && instance.spec.autoHideTimeout > 0) {
            mainHandler.postDelayed(instance.hideRunnable, instance.spec.autoHideTimeout * 1000L)
        }
        instance.specState.value = instance.spec.copy(isPinned = nextPinned)
        showToast(if (nextPinned) "Camera pinned" else "Camera unpinned")
    }

    /** Toggles mute/unmute instantly on the active ExoPlayer without reconnecting. */
    @MainThread
    fun toggleMuteMostRecentPip(): Unit = runOnMain {
        val instance = activeOverlays.values.lastOrNull() ?: return@runOnMain
        val curSpec = instance.spec
        val nextMute = !curSpec.rtspProfile.muteAudio
        instance.specState.value = curSpec.copy(
            rtspProfile = curSpec.rtspProfile.copy(muteAudio = nextMute)
        )
        showToast(if (nextMute) "Camera muted" else "Camera audio unmuted")
    }

    /** Snoozes alerts for the active camera via quickbars.action -> HA blueprint Branch F and closes the PiP. */
    @MainThread
    fun snoozeMostRecentPip(): Unit = runOnMain {
        val instance = activeOverlays.values.lastOrNull() ?: return@runOnMain
        val entity = instance.spec.entityId
        BackgroundHaConnectionManager.fireQuickbarsAction(
            ctx = context,
            cid = instance.spec.cid ?: entity,
            actionId = "snooze_15m",
            label = instance.spec.title,
            entityId = entity
        )
        showToast("Snoozed ${instance.spec.title} for 15m")
        hide(instance.slotKey)
    }

    /** Temporarily grants window focus to in-PiP doorbell quick-action pills with 8s auto-release. */
    @MainThread
    fun toggleFocusActionsOnMostRecentPip(): Unit = runOnMain {
        val instance = activeOverlays.values.lastOrNull() ?: return@runOnMain
        if (instance.spec.actions.isEmpty()) return@runOnMain
        val nextFocused = !instance.spec.actionsFocused
        setInstanceFocusable(instance, nextFocused)
    }

    private fun setInstanceFocusable(instance: ActivePipInstance, focusable: Boolean) {
        instance.autoUnfocusRunnable?.let { mainHandler.removeCallbacks(it) }
        instance.autoUnfocusRunnable = null
        val lp = instance.layoutParams
        if (focusable) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            instance.view.isFocusableInTouchMode = true
            val r = Runnable { setInstanceFocusable(instance, false) }
            instance.autoUnfocusRunnable = r
            mainHandler.postDelayed(r, 8000L)
        } else {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try {
            if (instance.view.isAttachedToWindow) {
                windowManager.updateViewLayout(instance.view, lp)
                if (focusable) instance.view.requestFocus()
            }
            instance.specState.value = instance.spec.copy(actionsFocused = focusable)
        } catch (t: Throwable) {
            Log.w(TAG, "setInstanceFocusable($focusable) failed: ${t.message}")
        }
    }

    private fun gravityForCorner(corner: String): Int = when (corner) {
        "TOP_RIGHT" -> Gravity.TOP or Gravity.END
        "BOTTOM_LEFT" -> Gravity.BOTTOM or Gravity.START
        "BOTTOM_RIGHT" -> Gravity.BOTTOM or Gravity.END
        else -> Gravity.TOP or Gravity.START
    }

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
                false -> false
                null -> triggerKeyToggle
            }

            // ── Explicit Close Actions (never blocked by DND or Sleep Guard) ────
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

            // ── Screen Sleep Guard: never leak HW decoders on sleeping TVs ──────
            if (req.autoCloseOnSleep != false) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm != null && !pm.isInteractive) {
                    Log.i(TAG, "Display is off/asleep; skipping PiP open for ${req.cameraEntity ?: req.cameraAlias ?: req.rtspUrl}")
                    return@runOnMain
                }
            }

            // ── DND & Quiet Hours Guard ─────────────────────────────────────────
            val prio = req.priority?.lowercase() ?: "normal"
            val bypassAll = prio == "critical"
            val bypassQuietHours = bypassAll || prio == "high"
            if (!bypassAll && AppPrefs.isDndEnabled(context)) {
                Log.i(TAG, "Camera PiP suppressed by Cinema DND: ${req.cameraEntity ?: req.cameraAlias}")
                return@runOnMain
            }
            if (!bypassQuietHours && AppPrefs.isCurrentlyInQuietHours(context)) {
                Log.i(TAG, "Camera PiP suppressed by Quiet Hours: ${req.cameraEntity ?: req.cameraAlias}")
                return@runOnMain
            }

            // ── Auto-Dock during Fullscreen Video Playback ──────────────────────
            val effectiveReq = if (AppPrefs.isAutoDockEnabled(context) && HAStateStore.isFullscreenMediaApp()) {
                val dockedSize = if (req.size == null && req.sizePxW == null) "small" else req.size
                val dockedPos = if (req.position == null) "top_right" else req.position
                req.copy(size = dockedSize, position = dockedPos)
            } else {
                req
            }

            // ── Optional Spoken Alert via Android TV TTS ────────────────────────
            if (!effectiveReq.speakText.isNullOrBlank()) {
                QuickBarsTtsHelper.speak(context, effectiveReq.speakText.trim())
            }

            // ── Multi-Camera Group Request (Grid / Split Dock) ──────────────────
            if (effectiveReq.cameras != null && effectiveReq.cameras.isNotEmpty()) {
                handleGroupRequest(effectiveReq)
                return@runOnMain
            }

            // ── Single Camera Request ───────────────────────────────────────────
            handleSingleRequest(effectiveReq)
        } catch (t: Throwable) {
            Log.e(TAG, "handleRequest failed: ${t.message}", t)
            showToast("Failed to open camera")
        }
    }

    /** Handles group requests combining multiple cameras into a single overlay window. */
    private fun handleGroupRequest(req: CameraRequest) {
        val subRequests = req.cameras ?: return
        val subSpecs = mutableListOf<CameraPipSpec>()
        val seenIds = LinkedHashSet<String>()

        for ((index, subReq) in subRequests.withIndex()) {
            val safeRtsp = asValidRtspOrNull(subReq.rtspUrl)?.let {
                stripGo2RtcQueryIfNeeded(sanitizeRtspUserInfo(it))
            }
            val mute = if (index > 0) true else (subReq.muteAudio ?: req.muteAudio ?: false)
            val subSpec = if (safeRtsp != null) {
                val ent = subReq.cameraEntity?.takeIf { it.isNotBlank() }?.let {
                    resolveCameraEntity(it, subReq.cameraAlias)
                }
                if (ent != null) {
                    buildCameraSpecWithOverrides(ent, subReq.copy(rtspUrl = safeRtsp, muteAudio = mute))
                } else {
                    buildAdhocRtspSpec(subReq.copy(rtspUrl = safeRtsp, muteAudio = mute))
                }
            } else {
                val entity = resolveCameraEntity(
                    cameraEntityId = subReq.cameraEntity?.takeIf { it.isNotBlank() },
                    aliasOrName = subReq.cameraAlias?.takeIf { it.isNotBlank() }
                ) ?: continue
                buildCameraSpecWithOverrides(entity, subReq.copy(muteAudio = mute))
            }
            if (seenIds.add(subSpec.entityId)) {
                subSpecs.add(subSpec)
            }
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

        val baseGroupSpec = CameraPipSpec(
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
            groupLayout = layout,
            autoCloseOnSleep = req.autoCloseOnSleep ?: true,
            cid = req.cid,
            actions = req.actions
        )
        val groupSpec = applyVisualOptions(baseGroupSpec, req)

        if (showToastOnToggle && !activeOverlays.containsKey(slotKey)) {
            showToast("$title → PIP")
        }
        val onRetrigger = req.onRetrigger ?: if (req.action.equals("toggle", true)) "toggle" else "extend"
        BackgroundHaConnectionManager.setLastCameraTitle(groupSpec.title)
        show(groupSpec, onRetrigger = onRetrigger)
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
        val existingSlot = activeOverlays.entries.firstOrNull {
            it.value.spec.entityId == baseSpec.entityId
        }?.key
        val subtitleSafe = req.subtitleSafe ?: (AppPrefs.isAutoDockEnabled(context) && HAStateStore.isFullscreenMediaApp())
        val rawPreferredCorner = mapCornerOverride(req.position) ?: baseSpec.cornerPosition
        val preferredCorner = if (subtitleSafe && req.position == null) {
            when (rawPreferredCorner) {
                "BOTTOM_RIGHT" -> "TOP_RIGHT"
                "BOTTOM_LEFT" -> "TOP_LEFT"
                else -> rawPreferredCorner
            }
        } else {
            rawPreferredCorner
        }
        val targetSlot = when {
            existingSlot != null -> existingSlot
            !req.slot.isNullOrBlank() -> req.slot
            req.allowMultiple -> allocateAvailableSlot(preferredCorner, subtitleSafe)
            else -> preferredCorner
        }
        val corner = mapCornerOverride(targetSlot) ?: preferredCorner

        val shouldMute = if (req.muteAudio != null) {
            req.muteAudio
        } else if (activeOverlays.isNotEmpty() && existingSlot == null) {
            true
        } else {
            baseSpec.rtspProfile.muteAudio
        }

        val finalSpec = applyVisualOptions(
            baseSpec.copy(
                cornerPosition = corner,
                slotKey = targetSlot,
                rtspProfile = baseSpec.rtspProfile.copy(muteAudio = shouldMute),
                autoCloseOnSleep = req.autoCloseOnSleep ?: baseSpec.autoCloseOnSleep
            ),
            req
        )

        val defaultRetrigger = if (req.action == null || req.action.equals("open", true)) "extend" else "toggle"
        val onRetrigger = req.onRetrigger?.lowercase() ?: defaultRetrigger

        if (!activeOverlays.containsKey(targetSlot) && showToastOnToggle) {
            showToast("${finalSpec.title} → PIP")
        }
        BackgroundHaConnectionManager.setLastCameraTitle(finalSpec.title)
        Log.i(TAG, "OPEN_PIP slot=$targetSlot corner=$corner entity=${finalSpec.entityId} alpha=${finalSpec.opacity} activeCount=${activeOverlays.size + if (existingSlot == null && !activeOverlays.containsKey(targetSlot)) 1 else 0}")
        show(finalSpec, onRetrigger = onRetrigger)
    }

    /** Finds an available corner slot, avoiding bottom corners first when [subtitleSafe] is active. */
    private fun allocateAvailableSlot(preferredCorner: String, subtitleSafe: Boolean = false): String {
        val safePreferred = if (subtitleSafe && preferredCorner.startsWith("BOTTOM_")) {
            if (preferredCorner == "BOTTOM_RIGHT") "TOP_RIGHT" else "TOP_LEFT"
        } else {
            preferredCorner
        }
        if (!activeOverlays.containsKey(safePreferred)) {
            return safePreferred
        }
        val slots = listOf("TOP_RIGHT", "TOP_LEFT", "BOTTOM_RIGHT", "BOTTOM_LEFT")
        return slots.firstOrNull { !activeOverlays.containsKey(it) } ?: preferredCorner
    }

    private fun computeEffectiveMaxSlots(): Int {
        val configured = AppPrefs.getPipMaxSlots(context).coerceIn(1, 4)
        val totalRamMb = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            mi.totalMem / (1024L * 1024L)
        }.getOrDefault(4096L)
        // Shield Tube / low-RAM 2GB TV boxes safely sustain max 2 concurrent hardware RTSP decoders
        return if (totalRamMb in 1..2500) minOf(configured, 2) else configured
    }

    @MainThread
    fun show(spec: CameraPipSpec, onRetrigger: String = "toggle"): Unit = runOnMain {
        if (!Settings.canDrawOverlays(context)) {
            showToast("Overlay permission required")
            return@runOnMain
        }

        val slotKey = spec.slotKey

        val existing = activeOverlays[slotKey]
        if (existing != null) {
            if (existing.spec.entityId == spec.entityId) {
                when (onRetrigger.lowercase()) {
                    "ignore" -> return@runOnMain
                    "extend" -> {
                        mainHandler.removeCallbacks(existing.hideRunnable)
                        existing.countdownResetMs.value = System.currentTimeMillis()
                        val newTimeout = spec.autoHideTimeout
                        if (newTimeout > 0) {
                            mainHandler.postDelayed(existing.hideRunnable, newTimeout * 1000L)
                        }
                        Log.d(TAG, "Extended active PiP countdown in slot $slotKey to ${newTimeout}s")
                        return@runOnMain
                    }
                    "restart" -> hide(slotKey)
                    else -> {
                        hide(slotKey)
                        return@runOnMain
                    }
                }
            } else {
                hide(slotKey)
            }
        }

        // Hardware Decoder Concurrency Guard (RAM-aware cap)
        val maxSlots = computeEffectiveMaxSlots()
        while (activeOverlays.size >= maxSlots) {
            val oldestKey = activeOverlays.keys.firstOrNull() ?: break
            Log.w(TAG, "Reached effective maxSlots ($maxSlots); recycling oldest slot: $oldestKey")
            hide(oldestKey)
        }

        val lifecycleOwner = ComposeViewLifecycleOwner()
        lifecycleOwner.create()

        val openedAtElapsedMs = SystemClock.elapsedRealtime()
        prefetchSnapshotAsync(spec.snapshotUrl, spec.authToken)

        val hideRunnable = Runnable { hide(slotKey) }
        val countdownResetMs = mutableStateOf(System.currentTimeMillis())
        val specState = mutableStateOf(spec)

        val themed = ContextThemeWrapper(context, R.style.Theme_HAQuickBars)
        val view = ComposeView(themed).apply {
            tag = spec
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow
            )
            isFocusableInTouchMode = spec.actions.isNotEmpty()
            if (spec.actions.isNotEmpty()) {
                setOnKeyListener { _, keyCode, ev ->
                    if (ev.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                        hide(slotKey)
                        true
                    } else false
                }
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    lifecycleOwner.attachToView(this@apply)
                    setContent {
                        MaterialTheme {
                            val curSpec = specState.value
                            val align = if (curSpec.isFullscreen) {
                                Alignment.Center
                            } else {
                                when (curSpec.cornerPosition) {
                                    "TOP_RIGHT" -> Alignment.TopEnd
                                    "BOTTOM_LEFT" -> Alignment.BottomStart
                                    "BOTTOM_RIGHT" -> Alignment.BottomEnd
                                    else -> Alignment.TopStart
                                }
                            }
                            Box(Modifier.wrapContentSize(), contentAlignment = align) {
                                Box(Modifier.padding(12.dp)) {
                                    CameraPipOverlay(
                                        spec = curSpec,
                                        countdownResetKey = countdownResetMs.value,
                                        openedAtElapsedMs = openedAtElapsedMs,
                                        onClose = { hide(slotKey) },
                                        onActionClick = { act ->
                                            BackgroundHaConnectionManager.fireQuickbarsAction(
                                                context,
                                                curSpec.cid,
                                                act.id,
                                                act.label,
                                                curSpec.entityId
                                            )
                                            if (act.id.equals("dismiss", ignoreCase = true) ||
                                                act.id.equals("snooze_15m", ignoreCase = true)
                                            ) {
                                                hide(slotKey)
                                            }
                                        },
                                        onCodecActive = { codec ->
                                            if (!codec.isNullOrBlank()) activeCodecsBySlot[slotKey] = codec
                                        },
                                        onFirstFrameMs = { ttff ->
                                            HAStateStore.setLastTtffMs(ttff)
                                            Log.i(TAG, "PIP_TTFF slot=$slotKey entity=${curSpec.entityId} ttffMs=$ttff")
                                        },
                                        onPlaybackError = { err ->
                                            recordError(slotKey, err)
                                        }
                                    )
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

        val density = context.resources.displayMetrics.density
        val padPx = (12f * 2f * density).roundToInt()
        val wPx = (spec.widthDp * density).roundToInt() + padPx
        val extraActionsPx = if (spec.actions.isNotEmpty()) (40f * density).roundToInt() else 0
        val hPx = (spec.heightDp * density).roundToInt() + padPx + extraActionsPx

        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val finalW = wPx.coerceAtMost((screenW * 0.95f).toInt())
        val finalH = hPx.coerceAtMost((screenH * 0.95f).toInt())
        val marginPx = if (spec.isFullscreen) 0 else (spec.marginDp.coerceIn(0, 64) * density).roundToInt()

        val baseFlags =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val finalFlags = if (spec.actions.isNotEmpty()) {
            baseFlags
        } else {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        val params = WindowManager.LayoutParams(
            finalW,
            finalH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            finalFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            windowAnimations = 0
            gravity = if (spec.isFullscreen) Gravity.CENTER else gravityForCorner(spec.cornerPosition)
            x = marginPx
            y = marginPx
            alpha = spec.opacity.coerceIn(0.25f, 1.0f)
        }

        try {
            windowManager.addView(view, params)
            activeOverlays[slotKey] = ActivePipInstance(
                slotKey = slotKey,
                specState = specState,
                view = view,
                layoutParams = params,
                lifecycleOwner = lifecycleOwner,
                hideRunnable = hideRunnable,
                countdownResetMs = countdownResetMs,
                openedAtElapsedMs = openedAtElapsedMs
            )
            syncActiveTelemetry()
        } catch (e: Exception) {
            Log.e(TAG, "Error adding camera overlay to WindowManager for slot $slotKey", e)
            try { view.disposeComposition() } catch (_: Throwable) {}
            try { lifecycleOwner.destroy() } catch (_: Throwable) {}
            return@runOnMain
        }

        val timeout = spec.autoHideTimeout
        if (timeout > 0) {
            mainHandler.postDelayed(hideRunnable, timeout * 1000L)
        }
    }

    @MainThread
    fun hide(target: String? = null): Unit = runOnMain {
        if (target == null) {
            if (activeOverlays.size == 1) {
                teardownOverlay(activeOverlays.keys.first())
            } else {
                hideAll()
            }
            return@runOnMain
        }

        if (activeOverlays.containsKey(target)) {
            teardownOverlay(target)
            return@runOnMain
        }

        val cornerKey = mapCornerOverride(target)
        if (cornerKey != null && activeOverlays.containsKey(cornerKey)) {
            teardownOverlay(cornerKey)
            return@runOnMain
        }

        val matchKey = activeOverlays.entries.firstOrNull { (_, instance) ->
            instance.spec.entityId.equals(target, ignoreCase = true) ||
                instance.spec.title.equals(target, ignoreCase = true) ||
                instance.spec.groupSpecs.any { it.entityId.equals(target, ignoreCase = true) }
        }?.key

        if (matchKey != null) {
            teardownOverlay(matchKey)
        }
    }

    @MainThread
    fun updateActiveOpacity(opacity: Float): Unit = runOnMain {
        val normalized = if (opacity > 1.0f) opacity / 100.0f else opacity
        val clamped = normalized.coerceIn(0.25f, 1.0f)
        for ((slotKey, instance) in activeOverlays) {
            try {
                instance.layoutParams.alpha = clamped
                if (instance.view.isAttachedToWindow) {
                    windowManager.updateViewLayout(instance.view, instance.layoutParams)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "updateActiveOpacity failed for $slotKey: ${t.message}")
            }
        }
        Log.i(TAG, "LIVE_OPACITY alpha=$clamped slots=${activeOverlays.size}")
    }

    @MainThread
    fun hideAutoCloseOverlays(): Unit = runOnMain {
        val keysToClose = activeOverlays.entries
            .filter { it.value.spec.autoCloseOnSleep }
            .map { it.key }
        for (key in keysToClose) {
            teardownOverlay(key)
        }
    }

    @MainThread
    fun hideAll(): Unit = runOnMain {
        val keys = activeOverlays.keys.toList()
        for (key in keys) {
            teardownOverlay(key)
        }
    }

    private fun prefetchSnapshotAsync(snapshotUrl: String?, authToken: String?) {
        if (snapshotUrl.isNullOrBlank()) return
        ioScope.launch {
            try {
                val sep = if (snapshotUrl.contains('?')) '&' else '?'
                val reqBuilder = Request.Builder().url("${snapshotUrl}${sep}t=${System.currentTimeMillis()}")
                if (!authToken.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $authToken")
                }
                SharedHttpClients.mediaFetch.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            putPrefetchedSnapshot(snapshotUrl, bytes)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun syncActiveTelemetry() {
        val activeIds = activeOverlays.values.map { it.spec.entityId }
        BackgroundHaConnectionManager.updateWifiLockForPips(context, activeOverlays.isNotEmpty())
        if (activeIds == lastPublishedActiveIds) return
        lastPublishedActiveIds = activeIds
        HAStateStore.setActivePipIds(activeIds, hostKey)
        BackgroundHaConnectionManager.publishTelemetryNow(context, trigger = "pip_change")
    }

    private fun teardownOverlay(slotKey: String) {
        val instance = activeOverlays.remove(slotKey) ?: return
        activeCodecsBySlot.remove(slotKey)
        mainHandler.removeCallbacks(instance.hideRunnable)
        instance.autoUnfocusRunnable?.let { mainHandler.removeCallbacks(it) }

        val view = instance.view
        val owner = instance.lifecycleOwner

        try { owner.pause() } catch (_: Throwable) {}
        try { view.disposeComposition() } catch (_: Throwable) {}

        // Unconditionally attempt removal so non-attached Views cannot leak a ViewRootImpl
        try {
            windowManager.removeViewImmediate(view)
        } catch (t: Throwable) {
            if (view.isAttachedToWindow) {
                Log.w(TAG, "removeViewImmediate failed for $slotKey, fallback to removeView: ${t.message}")
                try { windowManager.removeView(view) } catch (_: Throwable) {}
            }
        }
        try { owner.destroy() } catch (_: Throwable) {}
        syncActiveTelemetry()
    }

    @MainThread
    fun destroy() = runOnMain {
        hideAll()
    }

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
        val effectiveMute = req.muteAudio ?: false
        val sanitized = formatGo2RtcUrl(sanitizeRtspUserInfo(raw), effectiveMute)

        val entityId = buildRtspEntityId(req.copy(rtspUrl = sanitized))
        val defaultCorner = "TOP_LEFT"
        val defaultSize = "MEDIUM"
        val corner = mapCornerOverride(req.position) ?: defaultCorner
        val (wDp, hDp) = chooseSize(req, defaultSize)
        val autoHide = req.autoHideSec ?: AppPrefs.getDefaultCameraAutoHideSeconds(context)
        val showTitle = req.showTitle ?: true
        val title = req.customTitle?.takeIf { !it.isNullish() }?.trim() ?: "Camera"

        val transport = when (req.rtspTransport?.lowercase()) {
            "udp" -> TransportProtocol.UDP
            "tcp" -> TransportProtocol.TCP
            "auto" -> TransportProtocol.AUTO
            else -> TransportProtocol.TCP
        }

        val latencyStr = req.rtspLatency?.takeIf { it.isNotBlank() } ?: AppPrefs.getRtspLatencyMode(context)
        val latency = when (latencyStr?.lowercase()) {
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

        val snapshotEntityId = req.snapshotEntity?.takeIf { it.startsWith("camera.", ignoreCase = true) }
            ?: req.cameraEntity?.takeIf { it.startsWith("camera.", ignoreCase = true) }
        val (snapshot, snapshotToken) = buildHaSnapshotUrlAndToken(snapshotEntityId)
        val fallbackMjpeg = buildHaMjpegUrl(snapshotEntityId)

        return applyVisualOptions(
            CameraPipSpec(
                url = sanitized,
                authToken = snapshotToken,
                entityId = entityId,
                title = title,
                widthDp = wDp,
                heightDp = hDp,
                marginDp = 20,
                showTitle = showTitle,
                cornerPosition = corner,
                autoHideTimeout = autoHide,
                rtspProfile = profile,
                slotKey = req.slot ?: corner,
                autoCloseOnSleep = req.autoCloseOnSleep ?: true,
                snapshotUrl = snapshot,
                fallbackMjpegUrl = fallbackMjpeg,
                cid = req.cid,
                actions = req.actions
            ),
            req
        )
    }

    private fun normalizedHaBaseUrl(): String? {
        var baseUrl = SecurePrefsManager.getHAUrl(context)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = if (baseUrl.contains(":")) "http://$baseUrl" else "http://$baseUrl:8123"
        }
        return baseUrl.trimEnd('/')
    }

    private fun buildHaSnapshotUrlAndToken(entityId: String?): Pair<String?, String?> {
        val eid = entityId?.trim()?.takeIf { it.startsWith("camera.", ignoreCase = true) } ?: return null to null
        val base = normalizedHaBaseUrl() ?: return null to null
        val token = SecurePrefsManager.getHAToken(context)
        return "$base/api/camera_proxy/$eid" to token
    }

    private fun buildHaMjpegUrl(entityId: String?): String? {
        val eid = entityId?.trim()?.takeIf { it.startsWith("camera.", ignoreCase = true) } ?: return null
        val base = normalizedHaBaseUrl() ?: return null
        return "$base/api/camera_proxy_stream/$eid"
    }

    private fun applyVisualOptions(base: CameraPipSpec, req: CameraRequest): CameraPipSpec {
        val borderColor = req.borderColor?.trim()?.takeIf { it.isNotEmpty() } ?: base.borderColorHex
        val resolvedBorderWidth = when {
            req.borderWidthDp != null -> req.borderWidthDp.coerceIn(0, 8)
            borderColor != null && base.borderWidthDp == 0 -> 2
            else -> base.borderWidthDp.coerceIn(0, 8)
        }
        val fullscreen = req.size.equals("fullscreen", ignoreCase = true) ||
            req.layout.equals("fullscreen", ignoreCase = true) ||
            base.isFullscreen
        val rawOpacity = req.opacity ?: AppPrefs.getDefaultPipOpacity(context)
        val normOpacity = (if (rawOpacity > 1.0f) rawOpacity / 100.0f else rawOpacity).coerceIn(0.25f, 1.0f)
        val subSafe = req.subtitleSafe ?: base.subtitleSafe
        return base.copy(
            borderColorHex = borderColor,
            borderWidthDp = resolvedBorderWidth,
            cornerRadiusDp = (req.cornerRadiusDp ?: base.cornerRadiusDp).coerceIn(0, 24),
            opacity = normOpacity,
            marginDp = (req.marginDp ?: base.marginDp).coerceIn(0, 64),
            showLiveBadge = req.showLiveBadge ?: base.showLiveBadge,
            showTimestamp = req.showTimestamp ?: base.showTimestamp,
            showCountdownBar = req.showCountdownBar ?: base.showCountdownBar,
            isFullscreen = fullscreen,
            cid = req.cid ?: base.cid,
            actions = if (req.actions.isNotEmpty()) req.actions else base.actions,
            priority = req.priority?.lowercase() ?: base.priority,
            subtitleSafe = subSafe
        )
    }

    internal companion object {
        private val snapshotCache = ConcurrentHashMap<String, Pair<Long, ByteArray>>()

        fun putPrefetchedSnapshot(url: String, bytes: ByteArray) {
            if (snapshotCache.size > 6) {
                val oldest = snapshotCache.entries.minByOrNull { it.value.first }?.key
                if (oldest != null) snapshotCache.remove(oldest)
            }
            snapshotCache[url] = SystemClock.elapsedRealtime() to bytes
        }

        fun consumePrefetchedSnapshot(url: String?): ByteArray? {
            if (url.isNullOrBlank()) return null
            val entry = snapshotCache.remove(url) ?: return null
            return if (SystemClock.elapsedRealtime() - entry.first <= 10_000L) entry.second else null
        }

        /**
         * Normalizes go2rtc RTSP URLs for AndroidX Media3 playback.
         *
         * Media3's RtspClient resolves SDP control attributes (`a=control:trackID=0`)
         * relative to `rtsp://.../stream?video` into `.../stream/trackID=0?video`,
         * which go2rtc rejects with `SETUP 400`. Audio muting is enforced via
         * Media3 track selection and player volume instead.
         */
        internal fun formatGo2RtcUrl(rtspUrl: String, muteAudio: Boolean): String {
            return stripGo2RtcQueryIfNeeded(rtspUrl)
        }

        internal fun stripGo2RtcQueryIfNeeded(rtspUrl: String): String {
            val qIdx = rtspUrl.indexOf('?')
            if (qIdx == -1) return rtspUrl
            val beforeQ = rtspUrl.substring(0, qIdx)
            val q = rtspUrl.substring(qIdx + 1)
            if (beforeQ.contains(":8554/") && (q == "video" || q == "audio" || q == "video&audio")) {
                return beforeQ
            }
            return rtspUrl
        }

        internal fun sanitizeRtspUserInfo(url: String): String {
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

        private fun isHexDigit(c: Char): Boolean =
            c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        internal fun encodeUserInfoComponent(s: String): String {
            val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
            val sb = StringBuilder(s.length * 3)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '%' && i + 2 < s.length && isHexDigit(s[i + 1]) && isHexDigit(s[i + 2])) {
                    sb.append(s, i, i + 3)
                    i += 3
                    continue
                }
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
                i++
            }
            return sb.toString()
        }
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
        val cams = savedEntitiesManager.loadEntities().filter { it.id.startsWith("camera.") }

        val e = when {
            !cameraEntityId.isNullOrBlank() ->
                cams.firstOrNull { it.id.equals(cameraEntityId, ignoreCase = true) }

            !aliasOrName.isNullOrBlank() -> {
                val q = aliasOrName.trim().lowercase()
                cams.firstOrNull {
                    it.cameraAlias?.lowercase() == q ||
                        it.customName?.lowercase() == q ||
                        it.friendlyName?.lowercase() == q
                }
            }
            else -> null
        }

        if (e == null && !cameraEntityId.isNullOrBlank() && cameraEntityId.trim().startsWith("camera.", ignoreCase = true)) {
            val trimmedId = cameraEntityId.trim()
            val fromStore = HAStateStore.entitiesById.value[trimmedId]
            return fromStore ?: EntityItem(
                id = trimmedId,
                friendlyName = trimmedId.substringAfter("camera.").replace('_', ' ').replaceFirstChar { it.uppercase() },
                isSaved = false,
                isAvailable = true
            )
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
        val effectiveMute = req.muteAudio ?: false
        val rtsp = req.rtspUrl?.takeIf { it.isNotBlank() }?.let {
            formatGo2RtcUrl(sanitizeRtspUserInfo(it), effectiveMute)
        }
        val cleanBase = normalizedHaBaseUrl() ?: ""
        val authToken = SecurePrefsManager.getHAToken(context)
        val snapshotEntityId = req.snapshotEntity?.takeIf { it.startsWith("camera.", ignoreCase = true) } ?: entity.id
        val (snapshotUrl, snapshotToken) = buildHaSnapshotUrlAndToken(snapshotEntityId)
        val fallbackMjpeg = buildHaMjpegUrl(snapshotEntityId)
        val (finalUrl, finalToken) = if (rtsp != null) {
            rtsp to snapshotToken
        } else {
            "${cleanBase}/api/camera_proxy_stream/${entity.id}" to authToken
        }

        val defaultCorner = (entity.lastKnownState["pip_corner"] as? String) ?: "TOP_LEFT"
        val defaultSize = (entity.lastKnownState["pip_size"] as? String) ?: "MEDIUM"
        val defaultTitle = (entity.lastKnownState["show_title"] as? Boolean) ?: true
        val defaultAuto = ((entity.lastKnownState["auto_hide_timeout"] as? Number)?.toInt())
            ?: ((entity.lastKnownState["auto_hide_timeout"] as? String)?.toIntOrNull())
            ?: AppPrefs.getDefaultCameraAutoHideSeconds(context)

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

        val latencyStr = req.rtspLatency?.takeIf { it.isNotBlank() } ?: AppPrefs.getRtspLatencyMode(context)
        val latency = when (latencyStr?.lowercase()) {
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

        return applyVisualOptions(
            CameraPipSpec(
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
                slotKey = req.slot ?: corner,
                autoCloseOnSleep = req.autoCloseOnSleep ?: true,
                snapshotUrl = snapshotUrl,
                fallbackMjpegUrl = fallbackMjpeg,
                cid = req.cid,
                actions = req.actions
            ),
            req
        )
    }

    private fun mapCornerOverride(pos: String?): String? = when (pos?.trim()?.lowercase()) {
        null, "" -> null
        "top_left" -> "TOP_LEFT"
        "top_right" -> "TOP_RIGHT"
        "bottom_left" -> "BOTTOM_LEFT"
        "bottom_right" -> "BOTTOM_RIGHT"
        else -> null
    }

    private fun chooseSize(
        req: CameraRequest,
        defaultSize: String,
        isGroup: Boolean = false,
        groupCount: Int = 1,
        layout: String = "grid"
    ): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density

        if (req.size.equals("fullscreen", ignoreCase = true) || req.layout.equals("fullscreen", ignoreCase = true)) {
            val screenW = context.resources.displayMetrics.widthPixels
            val screenH = context.resources.displayMetrics.heightPixels
            val wDp = ((screenW / density) * 0.90f).roundToInt().coerceIn(320, 3840)
            val hDp = ((screenH / density) * 0.88f).roundToInt().coerceIn(180, 2160)
            return wDp to hDp
        }

        if (req.sizePxW != null && req.sizePxH != null) {
            val wDp = (req.sizePxW / density).roundToInt().coerceIn(120, 3840)
            val hDp = (req.sizePxH / density).roundToInt().coerceIn(90, 2160)
            return wDp to hDp
        }

        val rawBase = when (req.size?.lowercase()) {
            "small" -> 200 to 112
            "medium" -> 280 to 158
            "large" -> 360 to 202
            else -> when (defaultSize.uppercase()) {
                "SMALL" -> 200 to 112
                "MEDIUM" -> 280 to 158
                "LARGE" -> 360 to 202
                else -> 280 to 158
            }
        }

        val base = when (req.aspectRatio?.trim()?.lowercase()) {
            "4:3", "4/3" -> rawBase.first to (rawBase.first * 3 / 4)
            "1:1", "square" -> rawBase.first to rawBase.first
            else -> rawBase
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
            else -> {
                if (groupCount <= 2) {
                    (base.first * 2 + 8) to base.second
                } else {
                    (base.first * 2 + 8) to (base.second * 2 + 8)
                }
            }
        }
    }
}
