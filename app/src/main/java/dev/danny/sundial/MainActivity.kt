package dev.danny.sundial

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.danny.sundial.ui.SundialRoot
import dev.danny.sundial.ui.theme.SundialTheme

/** What the app was asked to do when it was launched from outside. */
sealed interface LaunchAction {
    data class OpenEvent(val calendarId: String, val eventId: String) : LaunchAction
    data class ImportIcs(val uri: Uri) : LaunchAction
}

class MainActivity : ComponentActivity() {

    private var launchAction by mutableStateOf<LaunchAction?>(null)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Reminders simply stay silent if this is declined. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchAction = actionFrom(intent)

        val container = (application as SundialApp).container

        setContent {
            SundialTheme {
                SundialRoot(
                    container = container,
                    launchAction = launchAction,
                    onLaunchActionHandled = { launchAction = null },
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        actionFrom(intent)?.let { launchAction = it }
    }

    private fun actionFrom(intent: Intent?): LaunchAction? {
        if (intent == null) return null

        val calendarId = intent.getStringExtra(EXTRA_OPEN_CALENDAR_ID)
        val eventId = intent.getStringExtra(EXTRA_OPEN_EVENT_ID)
        if (calendarId != null && eventId != null) {
            return LaunchAction.OpenEvent(calendarId, eventId)
        }

        if (intent.action == Intent.ACTION_VIEW) {
            // The OAuth return arrives here as ACTION_VIEW with sundial://returned,
            // fired by the "Return to Sundial" button on the loopback page. Without
            // this guard it is indistinguishable from a file handed over by a file
            // manager, so it is routed to the .ics importer, which tries to
            // openInputStream() a sundial:// URI, gets null, and reports "That file
            // could not be read." -- i.e. a successful sign-in ends on a file error.
            // Nothing to do here but come to the front; the token exchange is already
            // running on the loopback server's thread.
            val uri = intent.data
            if (uri != null && uri.scheme != RETURN_SCHEME) {
                return LaunchAction.ImportIcs(uri)
            }
        }
        return null
    }

    companion object {
        const val EXTRA_OPEN_CALENDAR_ID = "sundial.open_calendar_id"
        const val EXTRA_OPEN_EVENT_ID = "sundial.open_event_id"

        /** Scheme of the OAuth return intent; must match the manifest intent-filter. */
        const val RETURN_SCHEME = "sundial"
    }
}
