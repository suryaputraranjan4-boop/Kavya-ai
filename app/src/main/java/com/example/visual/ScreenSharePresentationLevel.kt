package com.example.visual

/**
 * Three distinct presentation levels for Explain Screen in Screen Share:
 *
 * 1. BASIC: Direct on-screen annotations, highlight boxes, pointers, touch-pass-through overlay,
 *    original full-size portrait.
 * 2. STANDARD: Portrait screen preview centered with aspect ratio, external contextual explanation
 *    cards with connecting arrows/pointers, dynamic repositioning, 3D/structural diagrams.
 * 3. ADVANCED: Futuristic visual-intelligence landscape-style workspace (or adaptive horizontal split),
 *    upright portrait preview on left, detailed visual models, wireframe/3D diagrams, flowchart nodes,
 *    and step-by-step technical cards on right.
 */
enum class ScreenSharePresentationLevel(
    val id: String,
    val title: String,
    val subtitle: String,
    val badgeLabel: String
) {
    BASIC(
        id = "BASIC",
        title = "Basic Overlay",
        subtitle = "Full-size portrait with direct on-screen annotations & touch pass-through",
        badgeLabel = "LEVEL 1 • OVERLAY"
    ),
    STANDARD(
        id = "STANDARD",
        title = "Standard Studio",
        subtitle = "Centered portrait screen preview with contextual cards & 3D diagrams",
        badgeLabel = "LEVEL 2 • STUDIO"
    ),
    ADVANCED(
        id = "ADVANCED",
        title = "Advanced Workspace",
        subtitle = "Futuristic split workspace with live frame telemetry, 3D models & flowchart",
        badgeLabel = "LEVEL 3 • WORKSPACE"
    );

    companion object {
        fun fromId(id: String?): ScreenSharePresentationLevel {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: STANDARD
        }
    }
}
