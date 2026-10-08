package dylan.android.ui

/**
 * The two resting places of the player surface.
 *
 * Anchors are **pixels**, measured from the top of the surface: [Full] is the surface flush with the
 * top of the window, [Mini] is the surface pushed down so that only its first [MINI_BAR_HEIGHT]
 * strip — the collapsed bar — is left showing above the navigation bar.
 *
 * It lives in its own file because it is the only type here: [NpPlayerSurface] is the surface's
 * behaviour, this is the vocabulary it moves between.
 */
internal enum class NpAnchor {
    Full,
    Mini,
}
