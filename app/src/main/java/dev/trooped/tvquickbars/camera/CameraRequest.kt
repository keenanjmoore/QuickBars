package dev.trooped.tvquickbars.camera

data class CameraRequest(
    val cameraAlias: String? = null,
    val cameraEntity: String? = null,
    val position: String? = null,         // "top_left" | "top_right" | "bottom_left" | "bottom_right"
    val size: String? = null,             // "small" | "medium" | "large"
    val sizePxW: Int? = null,             // custom width in px (if provided)
    val sizePxH: Int? = null,             // custom height in px
    val autoHideSec: Int? = null,         // 0 = never, else seconds
    val showTitle: Boolean? = null,        // overlay title
    val rtspUrl:  String? = null,          // for RTSP stream
    val customTitle: String? = null,       // overrides title if provided/non-blank
    val muteAudio: Boolean? = null,        // RTSP only; true = mute player
    val showToggleToast: Boolean = true,  // default true when not provided
    val rtspTransport: String? = null, // "tcp", "udp", "auto"
    val rtspLatency: String? = null,   // "low", "balanced", "high"
    val useSoftwareDecoder: Boolean? = null,

    // ── Multi-camera & Enhanced Lifecycle Extensions ──
    val action: String? = null,            // "open" | "close" | "close_all" | "toggle"
    val slot: String? = null,              // explicit slot key or corner override
    val layout: String? = null,            // "grid" | "side_by_side" | "stacked"
    val cameras: List<CameraRequest>? = null, // for multi-camera combined group requests
    val allowMultiple: Boolean = true     // if true, does not clobber active cameras in other slots
) {
    init {
        require(sizePxW == null || sizePxW > 0) { "sizePxW must be positive" }
        require(sizePxH == null || sizePxH > 0) { "sizePxH must be positive" }
        require(autoHideSec == null || autoHideSec >= 0) { "autoHideSec must be >= 0" }
    }
}
