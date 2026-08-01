package io.github.jaypetez.ollamamobile.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.jaypetez.ollamamobile.R
import kotlinx.serialization.Serializable

// Type-safe routes, not string paths.
//
// navigation-compose 2.8 replaced "chat/{conversationId}" with @Serializable
// destination types, and the difference is not cosmetic: with a string path the
// argument key, its type, its nullability and its default exist in three places
// (the route template, the argument list, the read at the other end) and
// nothing checks that they agree. A typo compiles and fails at runtime with a
// null argument. Here the compiler checks it.
//
// The ids are `String` and not the `ConversationId`/`ServerId` value classes
// they stand for. Nav's type-safe layer derives its NavType from the
// kotlinx.serialization descriptor, and a @JvmInline value class that is not
// itself @Serializable has none — making `:core-model` depend on the
// serialization runtime to satisfy a navigation library would be the tail
// wagging the dog. The wrapping happens in the one place that reads the route.
//
// NAMING: the destination types are `...Destination` and each screen's
// composable entry point is `...Route`. Two different things need two different
// names, and the alternative — calling both `ChatRoute` — is a same-package
// collision that resolves silently in the wrong direction.

/**
 * The chat screen.
 *
 * A null [conversationId] means "start a new conversation": a thread is only
 * written to the database once there is something in it, so a route that
 * required an id would force an empty row for every tap that came to nothing.
 */
@Serializable
data class ChatDestination(
    val conversationId: String? = null,
)

@Serializable
data object ConversationsDestination

@Serializable
data object ServersDestination

@Serializable
data class ServerDetailDestination(
    val serverId: String,
)

@Serializable
data object SettingsDestination

@Serializable
data object DeveloperToolsDestination

@Serializable
data object OnboardingDestination

/**
 * The destinations that get a bottom-bar slot.
 *
 * Chat is deliberately not one of them. It is always *about* something — a
 * conversation you picked, or one you just started — so it has a back
 * destination, and a tab whose back button leaves the tab is the navigation bug
 * that every app with a "compose" tab eventually ships.
 */
enum class TopLevelDestination(
    val destination: Any,
    val icon: ImageVector,
    @param:StringRes val labelRes: Int,
    @param:StringRes val contentDescriptionRes: Int,
) {
    Conversations(
        destination = ConversationsDestination,
        icon = Icons.AutoMirrored.Outlined.Chat,
        labelRes = R.string.destination_conversations,
        contentDescriptionRes = R.string.destination_conversations_description,
    ),
    Servers(
        destination = ServersDestination,
        icon = Icons.Outlined.Dns,
        labelRes = R.string.destination_servers,
        contentDescriptionRes = R.string.destination_servers_description,
    ),
    Settings(
        destination = SettingsDestination,
        icon = Icons.Outlined.Settings,
        labelRes = R.string.destination_settings,
        contentDescriptionRes = R.string.destination_settings_description,
    ),
}
