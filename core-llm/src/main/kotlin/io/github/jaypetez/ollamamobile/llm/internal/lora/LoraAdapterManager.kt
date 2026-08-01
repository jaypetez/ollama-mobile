package io.github.jaypetez.ollamamobile.llm.internal.lora

import io.github.jaypetez.ollamamobile.llm.LoraAdapterSpec
import io.github.jaypetez.ollamamobile.model.ModelRef
import javax.inject.Inject

/**
 * Which LoRA adapters a base model should be loaded with.
 *
 * ## Why the signature is plural before anything implements it
 *
 * `llama_set_adapters_lora(ctx, adapters, n_adapters, scales)` takes an array
 * of adapters and a parallel array of scales, and stacking is an ordinary thing
 * to want — a style adapter at 0.7 with a domain adapter at 0.3. A
 * single-adapter API would have to be broken later to allow that, and every
 * call site changed with it. Shaping it correctly now costs one plural noun.
 *
 * ## Why there is no implementation
 *
 * There is nowhere for an adapter to come from yet: no import flow, no storage
 * layout, no UI. Writing a manager that persists a registry nobody can populate
 * would be code with no caller, and code with no caller is code that is wrong
 * by the time it gets one. The interface exists so the engine has a seam and
 * the shape is settled; [NoOpLoraAdapterManager] fills it.
 */
internal interface LoraAdapterManager {
    /** Adapters to apply on top of [model]. Order matters: it is the array order. */
    suspend fun adaptersFor(model: ModelRef): List<LoraAdapterSpec>

    /** Registers an adapter for [model]. Does not apply it to a loaded session. */
    suspend fun register(model: ModelRef, adapter: LoraAdapterSpec)

    /** Removes a registration by adapter path. */
    suspend fun unregister(model: ModelRef, path: String)
}

/**
 * The binding that ships today: no adapters, ever.
 *
 * [register] and [unregister] silently do nothing rather than throwing, because
 * nothing in the app calls them and a `TODO()` here would be a crash waiting
 * for the first caller instead of a seam waiting for an implementation.
 */
internal class NoOpLoraAdapterManager
    @Inject
    constructor() : LoraAdapterManager {
        override suspend fun adaptersFor(model: ModelRef): List<LoraAdapterSpec> = emptyList()

        override suspend fun register(model: ModelRef, adapter: LoraAdapterSpec) = Unit

        override suspend fun unregister(model: ModelRef, path: String) = Unit
    }
