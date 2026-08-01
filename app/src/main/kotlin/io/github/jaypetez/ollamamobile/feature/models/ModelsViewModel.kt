package io.github.jaypetez.ollamamobile.feature.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.data.engine.ModelLifecycleManager
import io.github.jaypetez.ollamamobile.data.engine.UnloadReason
import io.github.jaypetez.ollamamobile.data.repository.LocalModelRepository
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.ModelId
import javax.inject.Inject
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The model manager.
 *
 * Two properties are worth stating because they are what the screen's honesty
 * depends on:
 *
 *  * [ModelsUiState.engineAvailable] comes from the engine binding, not from
 *    the length of the model list. On a `-Pollama.nativeSource=none` build it
 *    is false and every load affordance is off, whatever is on disk.
 *  * A refused [io.github.jaypetez.ollamamobile.model.MemoryVerdict] is carried
 *    into the row rather than filtered out. Hiding a model the device cannot run
 *    would leave the user looking for a file they know they downloaded; showing
 *    it with a disabled button and the shortfall is the answer they can act on.
 */
@HiltViewModel
class ModelsViewModel
    @Inject
    constructor(
        private val localModels: LocalModelRepository,
        private val lifecycle: ModelLifecycleManager,
    ) : ViewModel() {
        private val loading = MutableStateFlow(true)
        private val busy = MutableStateFlow<ModelId?>(null)
        private val pendingDelete = MutableStateFlow<ModelId?>(null)
        private val message = MutableStateFlow<ModelsMessage?>(null)

        val uiState: StateFlow<ModelsUiState> = combine(
            localModels.models,
            lifecycle.resident,
            combine(loading, busy, pendingDelete, message) { isLoading, busyId, deleteId, note ->
                Transient(isLoading, busyId, deleteId, note)
            },
        ) { records, resident, transient ->
            val rows = records.map { it.toUi(resident?.id) }
            ModelsUiState(
                isLoading = transient.loading,
                engineAvailable = localModels.engineAvailable,
                models = rows.toImmutableList(),
                residentModelId = resident?.id,
                busyModelId = transient.busy,
                pendingDelete = rows.firstOrNull { it.id == transient.pendingDelete },
                message = transient.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS), ModelsUiState())

        init {
            refresh()
        }

        /**
         * Rescans the model directory.
         *
         * Genuinely expensive — it parses every GGUF header — so it happens on
         * entry and on demand, never on a recomposition.
         */
        fun refresh() {
            viewModelScope.launch {
                loading.value = true
                localModels.refresh()
                loading.value = false
            }
        }

        fun load(id: ModelId) {
            if (busy.value != null) return
            viewModelScope.launch {
                busy.value = id
                try {
                    lifecycle.ensureLoaded(id)
                } catch (failure: AppErrorException) {
                    message.value = failure.error.toMessage()
                } finally {
                    busy.value = null
                }
            }
        }

        fun unload() {
            viewModelScope.launch {
                busy.value = lifecycle.resident.value?.id
                lifecycle.unload(UnloadReason.REQUESTED)
                busy.value = null
            }
        }

        fun requestDelete(id: ModelId) {
            pendingDelete.value = id
        }

        fun dismissDelete() {
            pendingDelete.value = null
        }

        /** Deleting is irreversible and the file is measured in gigabytes; hence the confirmation. */
        fun confirmDelete() {
            val id = pendingDelete.value ?: return
            pendingDelete.value = null
            viewModelScope.launch {
                busy.value = id
                when (val result = localModels.delete(id)) {
                    is AppResult.Success -> message.value = ModelsMessage(R.string.models_deleted)
                    is AppResult.Failure -> message.value = result.error.toMessage()
                }
                busy.value = null
            }
        }

        /**
         * Copies a GGUF the user picked with the system file picker.
         *
         * The copy is not avoidable: weights are memory-mapped and a Storage
         * Access Framework descriptor promises nothing about being mappable. See
         * `LocalModelRepository.importGguf`.
         */
        fun import(uri: Uri, fileName: String) {
            viewModelScope.launch {
                loading.value = true
                when (val result = localModels.importGguf(uri, fileName)) {
                    is AppResult.Success -> message.value = ModelsMessage(
                        R.string.models_imported,
                        detail = result.value.ref.displayName,
                    )

                    is AppResult.Failure -> message.value = result.error.toMessage()
                }
                loading.value = false
            }
        }

        fun dismissMessage() {
            message.value = null
        }

        /**
         * Maps a typed failure onto a sentence.
         *
         * `AppError.message` is documented as developer-facing, so the mapping
         * goes from error *type* to a resource — except for the memory verdict,
         * whose `explain()` is written for the user and carries the actual
         * shortfall, which is the whole point of it.
         */
        private fun AppError.toMessage(): ModelsMessage = when (this) {
            is AppError.Engine.NotAvailable -> ModelsMessage(R.string.models_error_no_engine, isError = true)

            is AppError.Model.InsufficientMemory -> ModelsMessage(
                R.string.models_error_memory,
                detail = verdict.explain(),
                isError = true,
            )

            is AppError.Model.NotFound -> ModelsMessage(R.string.models_error_missing, isError = true)

            is AppError.Model.Unsupported -> ModelsMessage(
                R.string.models_error_unsupported,
                detail = reason,
                isError = true,
            )

            is AppError.Model.Corrupt -> ModelsMessage(R.string.models_error_corrupt, isError = true)

            is AppError.Engine.LoadFailed -> ModelsMessage(R.string.models_error_load_failed, isError = true)

            else -> ModelsMessage(R.string.models_error_unknown, isError = true)
        }

        /** The four short-lived fields, bundled so `combine` stays inside its arity. */
        private data class Transient(
            val loading: Boolean,
            val busy: ModelId?,
            val pendingDelete: ModelId?,
            val message: ModelsMessage?,
        )

        private companion object {
            const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
        }
    }
