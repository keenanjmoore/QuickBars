package dev.trooped.tvquickbars.background

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.MainThread
import dev.trooped.tvquickbars.BuildConfig
import dev.trooped.tvquickbars.camera.CameraPipController
import dev.trooped.tvquickbars.camera.CameraRequest
import dev.trooped.tvquickbars.data.AppIdProvider
import dev.trooped.tvquickbars.ha.*
import dev.trooped.tvquickbars.ha.ws.TargetMatcher
import dev.trooped.tvquickbars.notification.NotificationController
import dev.trooped.tvquickbars.persistence.AppPrefs
import dev.trooped.tvquickbars.persistence.SecurePrefsManager
import dev.trooped.tvquickbars.data.CategoryItem
import dev.trooped.tvquickbars.data.EntityItem
import dev.trooped.tvquickbars.notification.NotificationSpec
import dev.trooped.tvquickbars.persistence.SavedEntitiesManager
import dev.trooped.tvquickbars.services.QuickBarService
import dev.trooped.tvquickbars.ui.EntityIconMapper
import dev.trooped.tvquickbars.utils.EntityActionExecutor
import dev.trooped.tvquickbars.utils.EntityStateKeys
import dev.trooped.tvquickbars.utils.EntityStateUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random


/**
 * Manages the background WebSocket connection to Home Assistant.
 *
 * This object is responsible for:
 * - Establishing and maintaining a persistent WebSocket connection.
 * - Handling automatic reconnections with backoff strategies.
 * - Listening for network changes to trigger reconnections.
 * - Receiving and processing entity state updates from Home Assistant.
 * - Throttling rapid entity updates to avoid overwhelming the system.
 * - Subscribing to specific Home Assistant events (e.g., `quickbars.open`).
 * - Providing a client instance for sending commands to Home Assistant.
 * - Managing memory by periodically cleaning up stale data (e.g., throttle map entries).
 * - Exposing connection status and last successful connection time.
 * - Reloading QuickBar configurations in the [QuickBarService] upon starting.
 * - Processing special entity attributes (e.g., for climate, fan, light domains) to ensure
 *   consistent state representation and persistence.
 *
 * It implements [HomeAssistantListener] to react to events from the [HomeAssistantClient].
 * The connection lifecycle is managed within a [CoroutineScope] using [Dispatchers.IO].
 */
object BackgroundHaConnectionManager : HomeAssistantListener {
    private const val TAG = "BgHaConn"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var client: HomeAssistantClient? = null
    @Volatile private var running = false

    // Backoff (seconds): 1,2,4,8,16 (cap 30s) + jitter
    private var backoffSec = 1

    private val updateThrottleMap = ConcurrentHashMap<String, Long>()
    private val UPDATE_THROTTLE_MS = 250L // Min time between updates for same entity
    private val lastDiskSaveMap = ConcurrentHashMap<String, Long>()
    private val DISK_SAVE_DEBOUNCE_MS = 5000L

    private var lastSuccessfulConnectionTime = 0L
    private val wsReconnectCount = AtomicInteger(0)
    @Volatile private var lastCameraTitle: String = ""
    @Volatile private var savedEntitiesMgr: SavedEntitiesManager? = null
    @Volatile private var lastPublishedOpacity: Float = -1f

    private var cleanupJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var appCtxRef: Context? = null
    private var fallbackCamera: CameraPipController? = null
    private var fallbackNotifications: NotificationController? = null

    /**
     * Returns the timestamp of the last successful connection in milliseconds since epoch.
     * Returns 0 if no successful connection has been established yet.
     */
    fun getLastSuccessfulConnectionTime(): Long {
        return lastSuccessfulConnectionTime
    }

    fun touchLastSuccessfulConnectionTime() {
        lastSuccessfulConnectionTime = System.currentTimeMillis()
    }

    fun getWsReconnectCount(): Int = wsReconnectCount.get()

    fun setLastCameraTitle(title: String) {
        if (title.isNotBlank()) {
            lastCameraTitle = title
        }
    }

    private fun getSavedEntitiesManager(ctx: Context): SavedEntitiesManager {
        savedEntitiesMgr?.let { return it }
        return SavedEntitiesManager(ctx.applicationContext).also { savedEntitiesMgr = it }
    }


    private fun performMemoryCleanup() {
        // Clear throttle map entries older than 5 minutes
        val now = System.currentTimeMillis()
        val initialSize = updateThrottleMap.size
        updateThrottleMap.entries.removeIf { (_, timestamp) -> now - timestamp > 5 * 60 * 1000 }

        if (initialSize != updateThrottleMap.size) {
            Log.d(TAG, "Memory cleanup: removed ${initialSize - updateThrottleMap.size} throttle entries")
        }
    }

    fun isRunning() = running

    fun getClient(): HomeAssistantClient? = client

    fun start(context: Context) {
        appCtxRef = context.applicationContext
        Log.d(TAG, "Starting background connection manager. Already running: $running")
        if (running) return
        running = true
        registerNetworkCallback(context)

        // Force reload QuickBars in the service
        val reloadIntent = Intent("ACTION_RELOAD_TRIGGER_KEYS")
        reloadIntent.putExtra("FORCE_FULL_RELOAD", true)
        reloadIntent.setPackage(context.packageName)
        context.sendBroadcast(reloadIntent)

        // Start the cleanup job when the manager starts
        cleanupJob = scope.launch {
            while (isActive) {
                delay(30 * 60 * 1000) // Every 30 minutes
                performMemoryCleanup()
            }
        }

        scope.launch {
            connectLoop(context.applicationContext)
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping background connection manager")
        running = false
        try { unregisterNetworkCallback() } catch (_: Throwable) {}

        // Cancel the cleanup job explicitly
        cleanupJob?.cancel()
        cleanupJob = null

        scope.coroutineContext.cancelChildren()
        client?.disconnect()
        client = null
        mainHandler.post {
            try { fallbackCamera?.destroy() } catch (_: Throwable) {}
            fallbackCamera = null
            try { fallbackNotifications?.onDestroy() } catch (_: Throwable) {}
            fallbackNotifications = null
        }
        HAStateStore.setConnection(ConnectionState.Disconnected)
    }

    private suspend fun connectLoop(appCtx: Context) {
        var reconnectCount = 0
        while (running) {
            reconnectCount++
            Log.d(TAG, "Connection attempt #$reconnectCount")

            // Skip connecting if client is already connected
            if (client?.isConnected() == true) {
                Log.d(TAG, "Client already connected, skipping connection attempt")
                delay(5000) // Check again in 5 seconds
                continue
            }

            val url = SecurePrefsManager.getHAUrl(appCtx)
            val token = SecurePrefsManager.getHAToken(appCtx)
            if (url.isNullOrBlank() || token.isNullOrBlank()) {
                Log.w(TAG, "Missing HA url/token; sleeping.")
                HAStateStore.setConnection(ConnectionState.Error(ConnectionState.Reason.AUTH_FAILED))
                delay(10_000)
                continue
            }

            try {
                val c = HomeAssistantClient(url, token, this, appCtx, useEntitySubscription = true)
                client = c
                c.connect()

                // Wait until connected or failed/time out
                val state = withTimeoutOrNull(10_000) {
                    c.connectionState
                        .filterNot { it is ConnectionState.Connecting }
                        .first()
                }

                when (state) {
                    is ConnectionState.Connected -> {
                        lastSuccessfulConnectionTime = System.currentTimeMillis()
                        if (reconnectCount > 1) {
                            wsReconnectCount.incrementAndGet()
                        }
                        Log.d(TAG, "Successfully connected to HA!")
                        HAStateStore.setConnection(state)
                        backoffSec = 1

                        val mirrorJob = c.connectionState
                            .onEach { s -> HAStateStore.setConnection(s) }
                            .launchIn(scope)

                        publishTelemetryNow(appCtx, trigger = "connect")

                        // Suspend until genuinely disconnected or zombie detected
                        try {
                            waitUntilDisconnected(c, appCtx)
                        } finally {
                            mirrorJob.cancel()
                        }
                    }
                    is ConnectionState.Error -> {
                        Log.d(TAG, "Connection failed: ${state.reason}. Will retry with backoff.")
                        HAStateStore.setConnection(state)
                        delayWithBackoff()
                    }
                    else -> { // timeout or null
                        Log.d(TAG, "Connection timed out. Will retry with backoff.")
                        HAStateStore.setConnection(ConnectionState.Error(ConnectionState.Reason.TIMEOUT))
                        delayWithBackoff()
                    }
                }
                Log.d(TAG, "Connect loop exited, running=$running")
            } catch (t: Throwable) {
                Log.e(TAG, "connectLoop error", t)
                HAStateStore.setConnection(ConnectionState.Error(ConnectionState.Reason.UNKNOWN))
                delayWithBackoff()
            } finally {
                client?.disconnect()
                client = null
            }
        }
    }

    private suspend fun waitUntilDisconnected(c: HomeAssistantClient, appCtx: Context) {
        var lastPingElapsed = SystemClock.elapsedRealtime()
        var lastTelemetryElapsed = SystemClock.elapsedRealtime()
        val pingIntervalMs = 5 * 60 * 1000L

        while (running && c.isConnected()) {
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastPingElapsed >= pingIntervalMs) {
                    if (!c.ping()) {
                        Log.w(TAG, "Ping send failed, breaking connection loop")
                        break
                    }
                    lastPingElapsed = now
                }
                // Check zombie connection (>6 minutes without any HA frame/pong after ping)
                if (now - c.lastPongElapsedMs > 6 * 60 * 1000L) {
                    Log.w(TAG, "No WS activity/pong for >6 minutes; reconnecting")
                    break
                }

                val telemetryMs = AppPrefs.getTelemetryIntervalSec(appCtx) * 1000L
                if (now - lastTelemetryElapsed >= telemetryMs) {
                    publishTelemetryNow(appCtx, trigger = "heartbeat")
                    lastTelemetryElapsed = now
                }
                delay(2000)
            } catch (e: Exception) {
                Log.e(TAG, "Error in connection monitoring", e)
                break
            }
        }
    }

    fun publishTelemetryNow(ctx: Context, trigger: String = "heartbeat") {
        val c = client ?: return
        if (!c.isConnected()) return
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val displayOn = pm?.isInteractive ?: true
        val activePips = HAStateStore.activePipIds.value
        val activeArr = JSONArray()
        activePips.forEach { activeArr.put(it) }

        val freeRamMb = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            (mi.availMem / (1024L * 1024L)).toInt()
        }.getOrDefault(0)

        val payload = JSONObject().apply {
            put("app_id", AppIdProvider.get(ctx))
            put("device_name", TargetMatcher.getDeviceName(ctx))
            put("device_slug", TargetMatcher.getDeviceSlug(ctx))
            put("model", Build.MODEL ?: "")
            put("sdk", Build.VERSION.SDK_INT)
            put("version", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("status", "online")
            put("trigger", trigger)
            put("dnd", AppPrefs.isDndEnabled(ctx))
            put("quiet_hours", AppPrefs.isCurrentlyInQuietHours(ctx))
            put("quiet_hours_enabled", AppPrefs.isQuietHoursEnabled(ctx))
            put("auto_dock", AppPrefs.isAutoDockEnabled(ctx))
            put("default_auto_hide", AppPrefs.getDefaultCameraAutoHideSeconds(ctx))
            put("pip_max_slots", AppPrefs.getPipMaxSlots(ctx))
            put("default_opacity", AppPrefs.getDefaultPipOpacity(ctx).toDouble())
            put("last_camera", lastCameraTitle)
            put("free_ram_mb", freeRamMb)
            put("ws_reconnects", wsReconnectCount.get())
            put("rtsp_latency", AppPrefs.getRtspLatencyMode(ctx) ?: "auto")
            put("display_on", displayOn)
            put("active_pip_count", activePips.size)
            put("active_cameras", activeArr)
            put("a11y_bound", QuickBarService.serviceInstance != null)
            put("foreground_app", HAStateStore.foregroundApp.value ?: "")
            put("uptime_sec", SystemClock.elapsedRealtime() / 1000L)
        }
        c.fireEvent("quickbars.telemetry", payload)
    }

    /** Publishes structured runtime diagnostics (active slots, codecs, RAM, recent errors) to HA. */
    fun publishDiagnosticsNow(ctx: Context) {
        val c = client ?: return
        if (!c.isConnected()) return
        mainHandler.post {
            val controller = QuickBarService.serviceInstance?.getCameraController() ?: fallbackCamera
            val diag = controller?.getDiagnosticsSnapshot()
            val mi = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(mi)

            val payload = JSONObject().apply {
                put("app_id", AppIdProvider.get(ctx))
                put("device_name", TargetMatcher.getDeviceName(ctx))
                put("version", BuildConfig.VERSION_NAME)
                put("version_code", BuildConfig.VERSION_CODE)
                put("a11y_bound", QuickBarService.serviceInstance != null)
                put("free_ram_mb", (mi.availMem / (1024L * 1024L)).toInt())
                put("total_ram_mb", (mi.totalMem / (1024L * 1024L)).toInt())
                put("ws_reconnects", wsReconnectCount.get())
                put("active_slots", JSONArray(diag?.activeSlots ?: emptyList<String>()))
                put("codecs", JSONObject(diag?.codecs ?: emptyMap<String, String>()))
                put("recent_errors", JSONArray(diag?.recentErrors ?: emptyList<String>()))
            }
            scope.launch {
                c.fireEvent("quickbars.diagnostics_result", payload)
            }
        }
    }

    fun handleRemoteFocus(action: String) {
        mainHandler.post {
            val svcCam = QuickBarService.serviceInstance?.getCameraController()
            val cam = if (svcCam != null && svcCam.hasActivePips()) svcCam else fallbackCamera
            when (action.lowercase()) {
                "dismiss", "close" -> cam?.dismissMostRecentPip()
                "expand", "toggle_fullscreen", "fullscreen" -> cam?.toggleExpandMostRecentPip()
                "cycle", "rotate", "move" -> cam?.cycleActivePipCorner()
                "close_all" -> {
                    svcCam?.hideAll()
                    fallbackCamera?.hideAll()
                }
            }
        }
    }

    fun fireQuickbarsAction(ctx: Context, cid: String?, actionId: String, label: String? = null) {
        val c = client ?: return
        val data = JSONObject().apply {
            put("cid", cid ?: "")
            put("action_id", actionId)
            if (!label.isNullOrBlank()) put("label", label)
            val myId = AppIdProvider.get(ctx)
            if (myId.isNotBlank()) put("id", myId)
        }
        scope.launch {
            c.fireEvent("quickbars.action", data)
        }
    }

    private suspend fun delayWithBackoff() {
        val jitter = Random.nextLong(0, 300)
        delay(backoffSec * 1000L + jitter)
        backoffSec = min(backoffSec * 2, 30)
    }

    // ——— HomeAssistantListener ———
    override fun onEntitiesFetched(categories: List<CategoryItem>) {
        HAStateStore.setCategories(categories)
    }
    override fun onEntityStateUpdated(entityId: String, newState: String, attributes: JSONObject) {
        val ctx = appCtxRef ?: client?.getContext()

        // Sync QuickBars opacity slider helper directly on startup & live changes
        if (entityId == "input_number.quickbars_pip_opacity" && ctx != null) {
            val raw = newState.toFloatOrNull()
            if (raw != null && !raw.isNaN()) {
                val norm = (if (raw > 1.0f) raw / 100.0f else raw).coerceIn(0.25f, 1.0f)
                if (kotlin.math.abs(norm - lastPublishedOpacity) >= 0.01f) {
                    lastPublishedOpacity = norm
                    AppPrefs.setDefaultPipOpacity(ctx, norm)
                    applyLiveOpacityToActivePips(norm)
                }
            }
        }

        // Get current entity from the store (or upsert watched entities seeded by subscribe_entities)
        val cur = HAStateStore.entitiesById.value[entityId]
        if (cur == null) {
            if (entityId.startsWith("camera.") ||
                entityId.startsWith("input_boolean.quickbars_") ||
                entityId.startsWith("input_number.quickbars_") ||
                entityId.startsWith("input_select.quickbars_")
            ) {
                HAStateStore.upsertWatchedEntity(entityId, newState, attributes, isSaved = false)
                return
            }
            val savedMgr = ctx?.let { getSavedEntitiesManager(it) }
            val isSaved = savedMgr?.loadEntities()?.any { it.id == entityId } == true
            if (isSaved) {
                HAStateStore.upsertWatchedEntity(entityId, newState, attributes, isSaved = true)
            }
            return
        }

        // Only apply throttling to non-saved entities
        if (!cur.isSaved) {
            val now = System.currentTimeMillis()
            val lastUpdate = updateThrottleMap[entityId] ?: 0L
            if (now - lastUpdate < UPDATE_THROTTLE_MS) {
                return
            }
            updateThrottleMap[entityId] = now
        }

        val updatedEntity = cur.copy(
            state = newState,
            attributes = attributes,
            customName = cur.customName,
            customIconOnName = cur.customIconOnName,
            customIconOffName = cur.customIconOffName,
            isSelected = cur.isSelected,
            isSaved = cur.isSaved,
            isActionable = cur.isActionable,
            isAvailable = cur.isAvailable,
            lastKnownState = cur.lastKnownState,
            pressActions = cur.pressActions,
            defaultPressActionsApplied = cur.defaultPressActionsApplied,
            pressTargets = cur.pressTargets,
            requireConfirmation = cur.requireConfirmation,
            overrideService = cur.overrideService,
            overrideServiceData = cur.overrideServiceData
        )

        processSpecialEntityAttributes(updatedEntity)
        HAStateStore.updateEntity(entityId, updatedEntity)
    }

    override fun onEntityStateChanged(entityId: String, newState: String) {
        val cur = HAStateStore.entitiesById.value[entityId]
        if (cur != null) {
            HAStateStore.updateEntity(entityId, cur.copy(state = newState))
        }
    }

    override fun onQuickBarAliasTriggered(alias: String) {
        // Bounce to the running service (main thread), same pattern as camera
        QuickBarService.serviceInstance?.runOnMain {
            QuickBarService.serviceInstance?.onQuickBarAliasTriggered(alias)
        } ?: Log.w("BgHaConn", "Service not running; quickbar alias ignored: $alias")
    }

    override fun onCameraAliasTrigger(cameraAlias: String) {
        if (cameraAlias.isBlank()) return

        // Legacy alias → wrap to CameraRequest and route via the new handler
        val req = CameraRequest(cameraAlias = cameraAlias)
        QuickBarService.serviceInstance?.runOnMain {
            QuickBarService.serviceInstance?.handleCameraRequest(req)
        } ?: Log.w("BgHaConn", "Service not running; camera alias ignored: $cameraAlias")
    }

    fun onScreenStateChanged(ctx: Context, isScreenOff: Boolean) {
        if (isScreenOff) {
            mainHandler.post {
                QuickBarService.serviceInstance?.hideAutoCloseCameraPips()
                fallbackCamera?.hideAutoCloseOverlays()
            }
        }
        publishTelemetryNow(ctx, trigger = if (isScreenOff) "screen_off" else "screen_on")
    }

    fun applyLiveOpacityToActivePips(opacity: Float) {
        mainHandler.post {
            QuickBarService.serviceInstance?.updateActivePipOpacity(opacity)
            fallbackCamera?.updateActiveOpacity(opacity)
        }
    }

    @MainThread
    private fun getOrCreateFallbackCamera(ctx: Context): CameraPipController {
        fallbackCamera?.let { return it }
        val appCtx = ctx.applicationContext
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return CameraPipController(
            context = appCtx,
            windowManager = wm,
            runOnMain = { r -> if (Looper.myLooper() == Looper.getMainLooper()) r() else mainHandler.post(r) },
            showToast = { msg -> Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show() },
            hostKey = "fallback"
        ).also { fallbackCamera = it }
    }

    @MainThread
    private fun getOrCreateFallbackNotifications(ctx: Context): NotificationController {
        fallbackNotifications?.let { return it }
        val appCtx = ctx.applicationContext
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return NotificationController(
            context = appCtx,
            windowManager = wm,
            runOnMain = { r -> if (Looper.myLooper() == Looper.getMainLooper()) r() else mainHandler.post(r) },
            serviceScope = scope
        ).also {
            it.onServiceConnected()
            fallbackNotifications = it
        }
    }

    override fun onNotifyReceived(spec: NotificationSpec) {
        Log.i(TAG, "onPromptNotifyReceived: $spec")
        val svc = QuickBarService.serviceInstance
        if (svc != null) {
            QuickBarService.handleNotificationFromHa(spec)
        } else {
            val ctx = appCtxRef ?: client?.getContext()
            if (ctx != null) {
                mainHandler.post {
                    Log.i(TAG, "Using fallback NotificationController (QuickBarService unbound)")
                    getOrCreateFallbackNotifications(ctx).enqueue(spec)
                }
            } else {
                Log.w(TAG, "No context available; notification ignored: $spec")
            }
        }
    }

    override fun onCameraRequest(req: CameraRequest) {
        val svc = QuickBarService.serviceInstance
        if (svc != null) {
            svc.runOnMain {
                fallbackCamera?.hideAll()
                svc.handleCameraRequest(req)
            }
        } else {
            val ctx = appCtxRef ?: client?.getContext()
            if (ctx != null) {
                mainHandler.post {
                    Log.i(TAG, "Using fallback CameraPipController (QuickBarService unbound)")
                    getOrCreateFallbackCamera(ctx).handleRequest(req)
                }
            } else {
                Log.w(TAG, "No context available; camera request ignored: $req")
            }
        }
    }

    /**
     * Process special entity attributes just like QuickBarOverlay does
     */
    private fun processSpecialEntityAttributes(entity: EntityItem) {
        val context = appCtxRef ?: client?.getContext() ?: return
        val savedEntitiesManager = getSavedEntitiesManager(context)

        // First, ensure entity has valid icons before any other processing
        if (entity.customIconOnName.isNullOrEmpty()) {
            entity.customIconOnName = EntityIconMapper.getDefaultOnIconForEntityName(entity.id)
        }

        // Track original saved state
        val wasSaved = entity.isSaved

        // Process different entity types
        when {
            entity.id.startsWith("climate.") -> {
                if (entity.state != "off" && entity.state != "unavailable" && entity.state != "unknown") {
                    // Save current values
                    val tempBefore = entity.lastKnownState[EntityStateKeys.LAST_AC_TEMP]
                    val modeBefore = entity.lastKnownState[EntityStateKeys.LAST_AC_MODE]

                    try {
                        EntityStateUtils.captureClimateState(entity, savedEntitiesManager)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error capturing climate state: ${e.message}", e)
                    }

                    // Restore values if they were lost
                    if (entity.lastKnownState[EntityStateKeys.LAST_AC_TEMP] == null && tempBefore != null) {
                        entity.lastKnownState[EntityStateKeys.LAST_AC_TEMP] = tempBefore
                    }

                    if (entity.lastKnownState[EntityStateKeys.LAST_AC_MODE] == null && modeBefore != null) {
                        entity.lastKnownState[EntityStateKeys.LAST_AC_MODE] = modeBefore
                    }

                    // Ensure isSaved flag wasn't lost
                    if (wasSaved && !entity.isSaved) {
                        entity.isSaved = true
                    }

                    // Debounce disk writes to at most once every 5s per entity (offloaded to scope IO)
                    if (entity.isSaved) {
                        val now = System.currentTimeMillis()
                        val prev = lastDiskSaveMap[entity.id] ?: 0L
                        if (now - prev >= DISK_SAVE_DEBOUNCE_MS) {
                            lastDiskSaveMap[entity.id] = now
                            val copyForDisk = entity.copy(lastKnownState = entity.lastKnownState.toMutableMap())
                            scope.launch { savedEntitiesManager.saveEntity(copyForDisk) }
                        }
                    }
                }
            }
            entity.id.startsWith("fan.") -> {
                if (entity.state == "on") {
                    // Save current speed
                    val speedBefore = entity.lastKnownState[EntityStateKeys.LAST_FAN_SPEED]

                    try {
                        EntityStateUtils.captureFanSpeed(entity, savedEntitiesManager)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error capturing fan speed: ${e.message}", e)
                    }

                    // Restore speed if lost
                    if (entity.lastKnownState[EntityStateKeys.LAST_FAN_SPEED] == null && speedBefore != null) {
                        entity.lastKnownState[EntityStateKeys.LAST_FAN_SPEED] = speedBefore
                    }

                    // Ensure isSaved flag wasn't lost
                    if (wasSaved && !entity.isSaved) {
                        entity.isSaved = true
                    }

                    // Debounce disk writes to at most once every 5s per entity (offloaded to scope IO)
                    if (entity.isSaved) {
                        val now = System.currentTimeMillis()
                        val prev = lastDiskSaveMap[entity.id] ?: 0L
                        if (now - prev >= DISK_SAVE_DEBOUNCE_MS) {
                            lastDiskSaveMap[entity.id] = now
                            val copyForDisk = entity.copy(lastKnownState = entity.lastKnownState.toMutableMap())
                            scope.launch { savedEntitiesManager.saveEntity(copyForDisk) }
                        }
                    }
                }
            }
            entity.id.startsWith("light.") -> {
                // Track light attribute changes - this already works correctly
                val attrs = entity.attributes
                if (attrs != null && entity.state == "on") {
                    val brightness = attrs.optInt("brightness", -1)
                    val colorTemp = attrs.optInt("color_temp", -1)

                    if (brightness > 0 || colorTemp > 0) {
                        Log.d(TAG, "💡 Light update: ${entity.id} → " +
                                (if (brightness > 0) "brightness: ${(brightness * 100 / 255)}%, " else "") +
                                (if (colorTemp > 0) "temp: $colorTemp" else ""))
                    }
                }
            }
        }
        // Final check to ensure isSaved wasn't lost
        if (wasSaved && !entity.isSaved) {
            entity.isSaved = true
        }
    }


    // ——— Network awareness: reconnect as soon as the network is back ———
    private var cm: ConnectivityManager? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallback(ctx: Context) {
        if (netCb != null) return
        cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        netCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Nudge the loop to reconnect quickly
                scope.launch {
                    client?.disconnect()
                }
            }
        }
        cm?.registerDefaultNetworkCallback(netCb!!)
    }

    private fun unregisterNetworkCallback() {
        netCb?.let { cb ->
            cm?.unregisterNetworkCallback(cb)
        }
        netCb = null
        cm = null
    }

    // Public API for commands from UI:
    fun callService(domain: String, service: String, entityId: String, data: JSONObject? = null) {
        client?.callService(domain, service, entityId, data)
    }
}