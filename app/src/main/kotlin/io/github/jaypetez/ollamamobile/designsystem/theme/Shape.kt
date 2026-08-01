package io.github.jaypetez.ollamamobile.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

private val ExtraSmall = RoundedCornerShape(6.dp)
private val Small = RoundedCornerShape(10.dp)
private val Medium = RoundedCornerShape(14.dp)
private val Large = RoundedCornerShape(20.dp)
private val ExtraLarge = RoundedCornerShape(28.dp)

/** Slightly rounder than Material's defaults, so cards read as cards on a near-black surface. */
val OllamaShapes = Shapes(
    extraSmall = ExtraSmall,
    small = Small,
    medium = Medium,
    large = Large,
    extraLarge = ExtraLarge,
)
