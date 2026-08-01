package io.github.jaypetez.ollamamobile.designsystem.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing scale.
 *
 * Named steps rather than literals at the call site for the usual design-system
 * reason — a screen assembled from `12.dp` here and `13.dp` there looks subtly
 * broken and nobody can say why — and for one mechanical one: detekt's
 * `MagicNumber` rule fires on a bare `16.dp` in an expression but not on a
 * property declaration, so the tokens are also what keeps static analysis
 * meaningful instead of suppressed file by file.
 */
object Spacing {
    val None = 0.dp
    val Hairline = 1.dp
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
    val Xxl = 32.dp
    val Huge = 48.dp
}

/** Fixed component dimensions. See [Spacing] for why these are named. */
object Sizes {
    /** Google's minimum touch target. Anything interactive is at least this. */
    val MinTouchTarget = 48.dp

    val StatusDot = 10.dp
    val StatusDotRing = 16.dp
    val ListIcon = 24.dp
    val EmptyStateIcon = 56.dp
    val OnboardingIcon = 96.dp
    val InlineProgress = 18.dp
    val InlineProgressStroke = 2.dp
    val DividerThickness = 1.dp
    val SwipeActionWidth = 96.dp
    val MaxContentWidth = 640.dp
    val SheetMaxHeight = 560.dp
}
