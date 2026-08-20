package dev.danny.sundial.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.danny.sundial.AppContainer
import dev.danny.sundial.auth.BrowserLauncher
import dev.danny.sundial.auth.SignInStatus

private const val CONSOLE_URL = "https://console.cloud.google.com/apis/credentials"

/**
 * First-run screen. The OAuth client is entered here rather than compiled in, because
 * the loopback redirect flow does not tie the credential to the APK's signature — so
 * this build works with whatever Google Cloud project the user creates.
 */
@Composable
fun SetupScreen(container: AppContainer) {
    val context = LocalContext.current

    var clientId by remember { mutableStateOf(container.auth.clientId.orEmpty()) }
    var clientSecret by remember { mutableStateOf(container.auth.clientSecret.orEmpty()) }
    // Sign-in progress lives in the repository, not here: this composable is
    // destroyed while the user is over in the browser, and anything held in a
    // plain remember{} comes back blank with no explanation.
    val signInStatus by container.auth.signInStatus.collectAsState()
    val busy = signInStatus == SignInStatus.Running
    // Editing a field dismisses the last failure without discarding the repository's
    // state, so the message survives the Activity being recreated but not a retry.
    var dismissedError by remember { mutableStateOf<String?>(null) }
    val latestError = (signInStatus as? SignInStatus.Failed)?.message
    val error = latestError?.takeIf { it != dismissedError }

    LaunchedEffect(signInStatus) {
        // Sync scheduling on sign-in lives in AppContainer: this screen leaves the
        // composition in the same frame signedIn flips, so an effect here is disposed
        // before it runs. All that remains is retiring a finished Success — and if
        // that races the screen swap, the stale status is cleared on the next mount.
        if (signInStatus is SignInStatus.Success) container.auth.clearSignInStatus()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text("Sundial", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Google Calendar on GrapheneOS, with no Play Services.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("One-time Google setup", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    SetupStep(1, "Create a project in the Google Cloud console and enable the Google Calendar API.")
                    SetupStep(2, "On the OAuth consent screen, pick External, add yourself as a user, then set the publishing status to In production — Testing mode expires your sign-in after 7 days.")
                    SetupStep(3, "Under Credentials, create an OAuth client ID of type Desktop app.")
                    SetupStep(4, "Paste the client ID and client secret below.")
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { BrowserLauncher.open(context, CONSOLE_URL) }) {
                        Text("Open the credentials page")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = clientId,
                onValueChange = {
                    clientId = it
                    dismissedError = latestError
                },
                label = { Text("Client ID") },
                placeholder = { Text("....apps.googleusercontent.com") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = clientSecret,
                onValueChange = {
                    clientSecret = it
                    dismissedError = latestError
                },
                label = { Text("Client secret") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Desktop-app secrets are not confidential — Google documents them as " +
                    "embedded in the installed app. It is stored encrypted by the Android Keystore.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    dismissedError = null
                    // A refused save surfaces through signInStatus; starting the flow
                    // anyway would run it against the previously stored credentials.
                    if (container.auth.saveClientCredentials(clientId, clientSecret)) {
                        container.auth.beginSignIn()
                    }
                },
                enabled = !busy && clientId.isNotBlank() && clientSecret.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("Waiting for the browser…")
                } else {
                    Text("Sign in with Google")
                }
            }

            if (busy) {
                TextButton(
                    onClick = { container.auth.cancelSignInFlow() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Sign-in opens your normal browser and comes back to a one-off local " +
                    "server on 127.0.0.1. Nothing is sent anywhere except Google.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetupStep(number: Int, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
