package dev.trooped.tvquickbars.persistence

import android.content.Context
import dev.trooped.tvquickbars.utils.SecureStore

/**
 * SecurePrefsManager
 *
 * Acts as a wrapper for the SecureStore file.
 */
object SecurePrefsManager {

    /* --------  synchronous façade used by the rest of the app -------- */

    fun saveHAUrl(ctx: Context, url: String) =
        SecureStore.setHAUrl(ctx, url)

    fun getHAUrl(ctx: Context): String? =
        SecureStore.getHAUrl(ctx)

    fun saveHAToken(ctx: Context, token: String) =
        SecureStore.setHAToken(ctx, token)

    fun getHAToken(ctx: Context): String? =
        SecureStore.getHAToken(ctx)

    fun clearCredentials(ctx: Context) =
        SecureStore.clear(ctx)
}