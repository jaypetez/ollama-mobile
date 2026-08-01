package io.github.jaypetez.ollamamobile.feature.onboarding

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaypetez.ollamamobile.common.dispatcher.ApplicationScope
import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Remembers whether the first-run flow has been seen.
 *
 * Deliberately not a row in `:core-storage`'s settings table. This value is
 * read on the critical path of every cold start — it decides the nav graph's
 * start destination while the splash screen is still up — and opening the Room
 * database to answer it would put a schema check in front of the first frame.
 * A single boolean in a one-key preferences file is read in microseconds.
 *
 * The flow does not emit until the disk read has happened, which is what lets
 * the splash screen be held rather than the app flashing the conversation list
 * and then replacing it with onboarding.
 */
@Singleton
class OnboardingStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:IoDispatcher private val io: CoroutineDispatcher,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        private val state = MutableStateFlow<Boolean?>(null)

        val completed: Flow<Boolean> = state.filterNotNull()

        init {
            scope.launch {
                state.value = withContext(io) {
                    preferences().getBoolean(KEY_COMPLETED, false)
                }
            }
        }

        /**
         * Not suspending, and written on the application scope on purpose.
         *
         * The caller is a view model that is about to be torn down by the
         * navigation this call precedes; a `viewModelScope.launch` here would
         * race the destination change and lose the write about as often as not.
         */
        fun markCompleted() {
            state.value = true
            write(true)
        }

        /** Test and developer affordance: makes the next launch show onboarding again. */
        fun reset() {
            state.value = false
            write(false)
        }

        private fun write(completed: Boolean) {
            scope.launch {
                withContext(io) {
                    preferences().edit { putBoolean(KEY_COMPLETED, completed) }
                }
            }
        }

        private fun preferences() = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

        private companion object {
            const val FILE_NAME = "onboarding"
            const val KEY_COMPLETED = "completed"
        }
    }
