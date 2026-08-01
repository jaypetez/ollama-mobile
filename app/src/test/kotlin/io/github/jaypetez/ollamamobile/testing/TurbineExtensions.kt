package io.github.jaypetez.ollamamobile.testing

import app.cash.turbine.ReceiveTurbine

/**
 * Awaits the first emission satisfying [predicate].
 *
 * `skipItems(n)` is the obvious alternative and it is a flake generator: a
 * `stateIn` view model may or may not have already mapped its first upstream
 * value by the time the test subscribes, so the same assertion needs to skip
 * one item on one run and none on the next. Asserting on the *content* rather
 * than on the emission count removes the race entirely.
 */
suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
