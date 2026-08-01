package io.github.jaypetez.ollamamobile.designsystem.component

/** The visual weight of an [OllamaButton]. */
enum class OllamaButtonStyle {
    /** The one thing this screen wants you to do. At most one per screen. */
    Primary,

    /** An equal-weight alternative. */
    Secondary,

    /** Low emphasis; safe to have several. */
    Text,

    /**
     * Deletes something. Rendered in the error colour *and* always paired with
     * a [ConfirmDialog] — colour alone is not a confirmation, and it is
     * invisible to a colour-blind user.
     */
    Destructive,
}
