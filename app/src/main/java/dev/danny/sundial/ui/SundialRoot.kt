package dev.danny.sundial.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.danny.sundial.AppContainer
import dev.danny.sundial.LaunchAction
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.ui.calendar.CalendarScreen
import dev.danny.sundial.ui.calendar.CalendarViewModel
import dev.danny.sundial.ui.event.EventDetailScreen
import dev.danny.sundial.ui.event.EventEditScreen
import dev.danny.sundial.ui.importer.ImportScreen
import dev.danny.sundial.ui.search.SearchScreen
import dev.danny.sundial.ui.settings.SettingsScreen
import dev.danny.sundial.ui.setup.SetupScreen

private const val ROUTE_CALENDAR = "calendar"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_IMPORT = "import"

private fun eventRoute(calendarId: String, eventId: String) =
    "event/${Uri.encode(calendarId)}/${Uri.encode(eventId)}"

private fun editRoute(calendarId: String, eventId: String) =
    "edit/${Uri.encode(calendarId)}/${Uri.encode(eventId)}"

private fun createRoute(startMillis: Long) = "create/$startMillis"

@Composable
fun SundialRoot(
    container: AppContainer,
    launchAction: LaunchAction?,
    onLaunchActionHandled: () -> Unit,
) {
    val authState by container.auth.state.collectAsStateWithLifecycle()

    // hasCredentials matters too: keys are dropped from the secure store one at a
    // time, so "signed in" with the client secret gone is reachable — and without
    // this check the setup screen would be unreachable in exactly that state.
    if (!authState.signedIn || !authState.hasCredentials) {
        SetupScreen(container = container)
        return
    }

    val navController = rememberNavController()
    val calendarViewModel: CalendarViewModel = viewModel(
        factory = CalendarViewModel.factory(container),
    )
    val calendarState by calendarViewModel.state.collectAsStateWithLifecycle()

    // The .ics URI is held here rather than encoded into a route.
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(launchAction) {
        when (val action = launchAction) {
            is LaunchAction.OpenEvent -> {
                navController.navigate(eventRoute(action.calendarId, action.eventId))
                onLaunchActionHandled()
            }
            is LaunchAction.ImportIcs -> {
                pendingImportUri = action.uri
                navController.navigate(ROUTE_IMPORT)
                onLaunchActionHandled()
            }
            null -> Unit
        }
    }

    NavHost(navController = navController, startDestination = ROUTE_CALENDAR) {

        composable(ROUTE_CALENDAR) {
            CalendarScreen(
                state = calendarState,
                onViewChange = calendarViewModel::setView,
                onSelectDate = calendarViewModel::selectDate,
                onAnchorChange = calendarViewModel::setAnchor,
                onToday = calendarViewModel::goToToday,
                onSync = calendarViewModel::sync,
                onToggleCalendar = calendarViewModel::toggleCalendar,
                onOpenEvent = { event ->
                    navController.navigate(eventRoute(event.calendarId, event.id))
                },
                onCreateEvent = { at ->
                    navController.navigate(createRoute(TimeUtil.millisOf(at)))
                },
                onOpenSearch = { navController.navigate(ROUTE_SEARCH) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                onOpenImport = {
                    pendingImportUri = null
                    navController.navigate(ROUTE_IMPORT)
                },
                onMessageShown = calendarViewModel::consumeMessage,
            )
        }

        composable(
            route = "event/{calendarId}/{eventId}",
            arguments = listOf(
                navArgument("calendarId") { type = NavType.StringType },
                navArgument("eventId") { type = NavType.StringType },
            ),
        ) { entry ->
            val calendarId = entry.arguments?.getString("calendarId").orEmpty()
            val eventId = entry.arguments?.getString("eventId").orEmpty()
            EventDetailScreen(
                container = container,
                calendarId = calendarId,
                eventId = eventId,
                onBack = { navController.popBackStack() },
                onEdit = { event -> navController.navigate(editRoute(event.calendarId, event.id)) },
                onDelete = { event, scope -> calendarViewModel.deleteEvent(event, scope) },
                onRespond = { event, response -> calendarViewModel.respond(event, response) },
            )
        }

        composable(
            route = "edit/{calendarId}/{eventId}",
            arguments = listOf(
                navArgument("calendarId") { type = NavType.StringType },
                navArgument("eventId") { type = NavType.StringType },
            ),
        ) { entry ->
            EventEditScreen(
                container = container,
                calendarId = entry.arguments?.getString("calendarId"),
                eventId = entry.arguments?.getString("eventId"),
                startMillis = System.currentTimeMillis(),
                onBack = { navController.popBackStack() },
                onSaved = {
                    calendarViewModel.showMessage("Event saved")
                    // A series edit drops the local expansion; resync to rebuild it.
                    calendarViewModel.sync()
                    navController.popBackStack()
                },
            )
        }

        composable(
            route = "create/{startMillis}",
            arguments = listOf(navArgument("startMillis") { type = NavType.LongType }),
        ) { entry ->
            EventEditScreen(
                container = container,
                calendarId = null,
                eventId = null,
                startMillis = entry.arguments?.getLong("startMillis") ?: System.currentTimeMillis(),
                onBack = { navController.popBackStack() },
                onSaved = {
                    calendarViewModel.showMessage("Event created")
                    navController.popBackStack()
                },
            )
        }

        composable(ROUTE_SEARCH) {
            SearchScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onOpenEvent = { event ->
                    navController.navigate(eventRoute(event.calendarId, event.id))
                },
            )
        }

        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onSignedOut = { navController.popBackStack(ROUTE_CALENDAR, inclusive = false) },
            )
        }

        composable(ROUTE_IMPORT) {
            ImportScreen(
                container = container,
                initialUri = pendingImportUri,
                onBack = {
                    pendingImportUri = null
                    navController.popBackStack()
                },
            )
        }
    }
}
