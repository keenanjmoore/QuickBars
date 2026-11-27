package dev.trooped.tvquickbars.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks Plus entitlement status (unlocked for community/sideloaded builds).
 */
object PlusStatusManager {
    private val _isPlus = MutableStateFlow(true)
    val isPlus = _isPlus.asStateFlow()
}
