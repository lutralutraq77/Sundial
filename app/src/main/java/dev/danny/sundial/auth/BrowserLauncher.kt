package dev.danny.sundial.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens a URL in the user's browser. Google refuses OAuth in embedded WebViews, and a
 * Custom Tab (Vanadium supports them) keeps the browser's own session and cookie jar.
 */
object BrowserLauncher {

    /**
     * Returns false when the device has no browser at all — a stripped-down Android
     * build can genuinely have none, and that must surface as a message rather than an
     * uncaught ActivityNotFoundException.
     */
    fun open(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        val newTask = context !is Activity

        try {
            val customTab = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
            if (newTask) customTab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTab.launchUrl(context, uri)
            return true
        } catch (_: ActivityNotFoundException) {
            // No Custom Tabs provider; fall through to a plain view intent.
        }

        return try {
            val fallback = Intent(Intent.ACTION_VIEW, uri)
            if (newTask) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
