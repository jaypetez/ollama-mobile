package io.github.jaypetez.ollamamobile.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.jaypetez.ollamamobile.feature.chat.ChatRoute as ChatScreenRoute
import io.github.jaypetez.ollamamobile.feature.conversations.ConversationsRoute
import io.github.jaypetez.ollamamobile.feature.devtools.DeveloperToolsRoute
import io.github.jaypetez.ollamamobile.feature.models.ModelDiscoverRoute
import io.github.jaypetez.ollamamobile.feature.models.ModelsRoute
import io.github.jaypetez.ollamamobile.feature.onboarding.OnboardingRoute
import io.github.jaypetez.ollamamobile.feature.servers.ServerDetailRoute
import io.github.jaypetez.ollamamobile.feature.servers.ServersRoute
import io.github.jaypetez.ollamamobile.feature.settings.SettingsRoute
import io.github.jaypetez.ollamamobile.model.ConversationId

/**
 * The app shell: one bottom bar, one back stack, one place that knows how the
 * screens connect.
 *
 * The chat feature owns the name `ChatRoute` for its composable entry point and
 * this package owns `ChatDestination` for the route type; the import alias
 * above keeps the two apart at the one call site that needs both.
 *
 * The individual screens take navigation *callbacks*, not a `NavController`.
 * Passing the controller down would let any screen navigate anywhere, which
 * makes the graph unknowable from this file and makes every screen untestable
 * without a navigation host.
 */
@Composable
fun OllamaMobileApp(
    startDestination: Any,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (currentDestination.isTopLevel()) {
                OllamaBottomBar(
                    currentDestination = currentDestination,
                    onSelect = { destination -> navController.switchTopLevel(destination) },
                )
            }
        },
        // The bars own their own insets and every screen applies `safeDrawing`
        // for itself, so the shell must not apply them a second time. Setting
        // this to zero and consuming the padding below is what stops content
        // being inset twice on a gesture-navigation device.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
    ) { innerPadding ->
        OllamaMobileNavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        )
    }
}

/** The graph itself, separated so it can be hosted without the shell in a test. */
@Composable
fun OllamaMobileNavHost(
    navController: NavHostController,
    startDestination: Any,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<OnboardingDestination> {
            OnboardingRoute(
                onAddServer = {
                    navController.navigate(ServersDestination) {
                        popUpTo<OnboardingDestination> { inclusive = true }
                    }
                },
                onExplore = {
                    navController.navigate(ConversationsDestination) {
                        popUpTo<OnboardingDestination> { inclusive = true }
                    }
                },
            )
        }

        composable<ConversationsDestination> {
            ConversationsRoute(
                onOpenConversation = { id -> navController.navigate(ChatDestination(id.value)) },
                onNewConversation = { navController.navigate(ChatDestination()) },
            )
        }

        composable<ChatDestination> { entry ->
            val route = entry.toRoute<ChatDestination>()
            // `onNewConversation` is deliberately left at its default. The
            // only way to change a type-safe route's argument is to navigate
            // again, which creates a new back-stack entry and therefore a new
            // ChatViewModel — cancelling the stream that just started the
            // conversation. The view model already tracks the thread it created,
            // so rewriting the route buys nothing and costs the answer.
            ChatScreenRoute(
                conversationId = route.conversationId?.let(::ConversationId),
                onBack = navController::navigateUp,
            )
        }

        composable<ServersDestination> {
            ServersRoute(
                onOpenServer = { id -> navController.navigate(ServerDetailDestination(id.value)) },
            )
        }

        composable<ServerDetailDestination> {
            ServerDetailRoute(onBack = navController::navigateUp)
        }

        composable<SettingsDestination> {
            SettingsRoute(
                onOpenDeveloperTools = { navController.navigate(DeveloperToolsDestination) },
                onOpenModels = { navController.navigate(ModelsDestination) },
            )
        }

        composable<ModelsDestination> {
            ModelsRoute(
                onBack = navController::navigateUp,
                onDiscover = { navController.navigate(ModelDiscoverDestination) },
            )
        }

        composable<ModelDiscoverDestination> {
            ModelDiscoverRoute(onBack = navController::navigateUp)
        }

        composable<DeveloperToolsDestination> {
            DeveloperToolsRoute(onBack = navController::navigateUp)
        }
    }
}

@Composable
private fun OllamaBottomBar(
    currentDestination: NavDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentDestination.isOn(destination),
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = stringResource(destination.contentDescriptionRes),
                    )
                },
                label = { Text(text = stringResource(destination.labelRes)) },
            )
        }
    }
}

/**
 * Switches tabs without growing the back stack.
 *
 * `saveState`/`restoreState` are what make a tab remember its scroll position
 * and its half-typed search box; without them, switching away and back is
 * indistinguishable from a fresh launch of that screen.
 */
private fun NavHostController.switchTopLevel(destination: TopLevelDestination) {
    navigate(destination.destination) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.destination::class) } == true

private fun NavDestination?.isTopLevel(): Boolean =
    TopLevelDestination.entries.any { destination -> isOn(destination) }
