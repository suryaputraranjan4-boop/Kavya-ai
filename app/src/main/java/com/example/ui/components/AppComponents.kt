package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * Top-lit specular hairline rim that gives panels an Apple Liquid Glass sheen.
 */
val AppleGlassBorderBrush: Brush = Brush.verticalGradient(
    colors = listOf(
        Color(0x4DFFFFFF),
        Color(0x1CFFFFFF),
        Color(0x0DFFFFFF)
    )
)

/**
 * Frosted obsidian liquid-glass fill gradient with subtle top inner luminance.
 */
val AppleGlassFillBrush: Brush = Brush.verticalGradient(
    colors = listOf(
        Color(0xD6202634),
        Color(0xCC151923)
    )
)

/**
 * Modifier extension for crisp, Apple-inspired dark Liquid Glass panels.
 */
fun Modifier.glassPanel(
    shape: Shape = RoundedCornerShape(22.dp),
    backgroundColor: Color = SurfaceGlass.copy(alpha = 0.82f),
    borderColor: Color = GlassBorder
): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            colors = listOf(
                backgroundColor.copy(alpha = (backgroundColor.alpha + 0.06f).coerceAtMost(0.96f)),
                backgroundColor
            )
        )
    )
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            colors = listOf(
                borderColor.copy(alpha = (borderColor.alpha * 1.4f).coerceAtMost(0.60f)),
                borderColor.copy(alpha = (borderColor.alpha * 0.45f).coerceAtLeast(0.08f))
            )
        ),
        shape = shape
    )

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    backgroundColor: Color = SurfaceGlass.copy(alpha = 0.78f),
    borderColor: Color = GlassBorder,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "CardPressScale"
    )

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    } else Modifier

    Column(
        modifier = modifier
            .scale(scale)
            .glassPanel(shape = shape, backgroundColor = backgroundColor, borderColor = borderColor)
            .then(clickableModifier)
            .padding(18.dp),
        content = content
    )
}

/**
 * Circular frosted glass icon button meeting 48.dp accessibility touch target.
 */
@Composable
fun AppleGlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TextPrimary,
    active: Boolean = false,
    activeColor: Color = Primary
) {
    val bgColor = if (active) activeColor.copy(alpha = 0.22f) else Color(0xA61C212E)
    val rimColor = if (active) activeColor.copy(alpha = 0.60f) else Color(0x36FFFFFF)
    Box(
        modifier = modifier
            .size(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(bgColor)
                .border(1.dp, rimColor, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) activeColor else tint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Clean iOS/visionOS-style frosted segmented control pill for switching tabs smoothly.
 */
@Composable
fun AppleGlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xB8141822))
            .border(1.dp, AppleGlassBorderBrush, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val pillBg by animateColorAsState(
                targetValue = if (selected) Color(0x3DFFFFFF) else Color.Transparent,
                label = "SegBg"
            )
            val textColor by animateColorAsState(
                targetValue = if (selected) TextPrimary else TextSecondary,
                label = "SegText"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(pillBg)
                    .then(
                        if (selected) {
                            Modifier.border(
                                1.dp,
                                Brush.verticalGradient(listOf(Color(0x52FFFFFF), Color(0x1CFFFFFF))),
                                RoundedCornerShape(12.dp)
                            )
                        } else Modifier
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun QuickActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "ChipPress"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .glassPanel(
                    shape = RoundedCornerShape(16.dp),
                    backgroundColor = SurfaceVariant.copy(alpha = 0.85f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = PrimaryLight,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = TextSecondary
        )
    }
}

@Composable
fun TimelineItem(
    icon: ImageVector,
    iconTint: Color,
    iconBgColor: Color,
    time: String,
    action: String,
    modifier: Modifier = Modifier,
    subText: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = TextPrimary
            )
            if (subText != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = TextSecondary
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = TextTertiary
        )
    }
}

@Composable
fun StatusBadge(
    text: String,
    isActive: Boolean = true,
    activeColor: Color = SuccessGreen,
    inactiveColor: Color = TextTertiary,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background((if (isActive) activeColor else inactiveColor).copy(alpha = 0.14f))
            .border(1.dp, (if (isActive) activeColor else inactiveColor).copy(alpha = 0.30f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isActive) activeColor else inactiveColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
            color = if (isActive) activeColor else inactiveColor
        )
    }
}

@Composable
fun AttachmentOptionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    accentColor: Color = PrimaryLight
) {
    Column(
        modifier = Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.15f))
                .border(1.dp, accentColor.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accentColor,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            ),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Shared dark Apple Liquid Glass attachment sheet used by both HomeScreen and ChatScreen.
 */
@Composable
fun AppleGlassAttachmentPopup(
    visible: Boolean,
    onDismiss: () -> Unit,
    onCameraClick: () -> Unit,
    onPhotosClick: () -> Unit,
    onDocumentsClick: () -> Unit,
    onFilesClick: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 84.dp
) {
    if (!visible) return

    Box(modifier = modifier.fillMaxSize()) {
        // Soft scrim to dismiss
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x4D000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + scaleIn(
                initialScale = 0.92f,
                transformOrigin = TransformOrigin(0.25f, 1f)
            ),
            exit = fadeOut() + scaleOut(
                targetScale = 0.92f,
                transformOrigin = TransformOrigin(0.25f, 1f)
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = bottomPadding)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xEA161B26),
                border = BorderStroke(1.dp, AppleGlassBorderBrush),
                shadowElevation = 18.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Attach to message",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextSecondary
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close attachments",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AttachmentOptionItem(
                            icon = Icons.Default.CameraAlt,
                            label = "Camera",
                            accentColor = PrimaryLight,
                            onClick = {
                                onDismiss()
                                onCameraClick()
                            }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.PhotoLibrary,
                            label = "Photos",
                            accentColor = SuccessGreen,
                            onClick = {
                                onDismiss()
                                onPhotosClick()
                            }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.Description,
                            label = "Docs",
                            accentColor = AccentYellow,
                            onClick = {
                                onDismiss()
                                onDocumentsClick()
                            }
                        )
                        AttachmentOptionItem(
                            icon = Icons.Default.AttachFile,
                            label = "Files",
                            accentColor = AccentPurpleLight,
                            onClick = {
                                onDismiss()
                                onFilesClick()
                            }
                        )
                    }
                }
            }
        }
    }
}
