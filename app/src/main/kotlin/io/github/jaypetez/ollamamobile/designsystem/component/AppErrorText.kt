package io.github.jaypetez.ollamamobile.designsystem.component

import androidx.annotation.StringRes
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.model.AppError

/**
 * Maps an [AppError] *type* to a user-facing string resource.
 *
 * `AppError.message` is documented in `:core-model` as developer-facing: it is
 * English, it names internals, and it is written for whoever reads the log.
 * Rendering it would ship an untranslated, unhelpful sentence to the user, so
 * the UI switches on the case instead — and the exhaustive `when` makes a new
 * error case a compile error here rather than a blank dialog on a device.
 *
 * Split one `when` per family rather than one flat one. The flat version is
 * genuinely over the complexity limit, and the families are also the natural
 * seam: everything under `Network` is "something between us and the server",
 * everything under `Policy` is "a setting you chose".
 */
@StringRes
fun AppError.messageRes(): Int = when (this) {
    is AppError.Network -> networkMessageRes()
    is AppError.Storage -> storageMessageRes()
    is AppError.Model -> modelMessageRes()
    is AppError.Engine -> engineMessageRes()
    is AppError.Policy -> policyMessageRes()
    is AppError.Unexpected -> R.string.error_unexpected
}

@StringRes
private fun AppError.Network.networkMessageRes(): Int = when (this) {
    is AppError.Network.Timeout -> R.string.error_network_timeout

    is AppError.Network.Unreachable -> R.string.error_network_unreachable

    is AppError.Network.Tls -> R.string.error_network_tls

    // The status is kept because the recovery differs: 401 means "fix the
    // token" and 404 means "fix the address".
    is AppError.Network.Http -> httpMessageRes(code)

    is AppError.Network.QueueFull -> R.string.error_network_queue_full

    is AppError.Network.Cancelled -> R.string.error_cancelled
}

@StringRes
private fun AppError.Storage.storageMessageRes(): Int = when (this) {
    is AppError.Storage.Io -> R.string.error_storage_io
    is AppError.Storage.NotFound -> R.string.error_storage_not_found
    is AppError.Storage.OutOfSpace -> R.string.error_storage_out_of_space
    is AppError.Storage.Migration -> R.string.error_storage_migration
    is AppError.Storage.SecretUnavailable -> R.string.error_secret_unavailable
}

@StringRes
private fun AppError.Model.modelMessageRes(): Int = when (this) {
    is AppError.Model.NotFound -> R.string.error_model_not_found
    is AppError.Model.Unsupported -> R.string.error_model_unsupported
    is AppError.Model.Corrupt -> R.string.error_model_corrupt
    is AppError.Model.InsufficientMemory -> R.string.error_model_insufficient_memory
}

@StringRes
private fun AppError.Engine.engineMessageRes(): Int = when (this) {
    is AppError.Engine.NotAvailable -> R.string.error_engine_not_available
    is AppError.Engine.LoadFailed -> R.string.error_engine_load_failed
    is AppError.Engine.GenerationFailed -> R.string.error_engine_generation_failed
}

@StringRes
private fun AppError.Policy.policyMessageRes(): Int = when (this) {
    is AppError.Policy.OfflineMode -> R.string.error_policy_offline
    is AppError.Policy.LanOnlyViolation -> R.string.error_policy_lan_only
    is AppError.Policy.LocalNetworkPermissionDenied -> R.string.error_policy_permission_denied
}

@StringRes
private fun httpMessageRes(code: Int): Int = when (code) {
    HTTP_UNAUTHORIZED -> R.string.error_http_unauthorized
    HTTP_FORBIDDEN -> R.string.error_http_forbidden
    HTTP_NOT_FOUND -> R.string.error_http_not_found
    in SERVER_ERROR_RANGE -> R.string.error_http_server
    else -> R.string.error_http_other
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private val SERVER_ERROR_RANGE = 500..599
