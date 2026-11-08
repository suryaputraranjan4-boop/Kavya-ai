package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Pure White Liquid Glass Design Tokens.
 * STRICT ENFORCEMENT: Absolutely no purple, pink, magenta, or violet tints.
 */
private object WhiteLiquidGlassTokens {
    val GlassSurface = Color(0x2EFFFFFF)
    val GlassSurfaceSubtle = Color(0x1FFFFFFF)
    val GlassSelected = Color(0x140F172A)
    val GlassHover = Color(0x0A0F172A)
    val Separator = Color(0x140F172A)
    
    val GlossyWhiteBorder = Brush.linearGradient(
        listOf(
            Color(0x66FFFFFF),
            Color(0x40FFFFFF),
            Color(0x26FFFFFF),
            Color(0x40FFFFFF)
        )
    )
    val SubtleBorder = Color(0x1F000000)
    
    // Near-black typography & slate iconography
    val TextPrimary = Color(0xFF0F172A)      // Slate 900
    val TextSecondary = Color(0xFF334155)    // Slate 700
    val TextMuted = Color(0xFF64748B)        // Slate 500
    val IconColor = Color(0xFF1E293B)        // Slate 800
    val IconActive = Color(0xFF020617)       // Slate 950
    
    // Status Indicator
    val StatusOnline = Color(0xFF10B981)     // Crisp Emerald Green
    val StatusGlow = Color(0x3310B981)
}

@Composable
fun KavyaSideMenu(
    navController: NavController,
    drawerState: DrawerState,
    scope: CoroutineScope
) {
    val scrollState = rememberScrollState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "home"

    // Section expansion states (all expanded by default for direct discovery)
    var isAssistantExpanded by remember { mutableStateOf(true) }
    var isIntelligenceExpanded by remember { mutableStateOf(true) }
    var isSettingsExpanded by remember { mutableStateOf(true) }

    // Outer floating White Liquid Glass surface with rounded right edges
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("kavya_white_liquid_glass_drawer")
            .shadow(
                elevation = 20.dp,
                shape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp),
                spotColor = Color(0x38000000),
                ambientColor = Color(0x1A000000)
            )
            .border(
                border = BorderStroke(1.2.dp, WhiteLiquidGlassTokens.GlossyWhiteBorder),
                shape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp)
            ),
        color = WhiteLiquidGlassTokens.GlassSurface,
        shape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 42.dp, bottom = 18.dp)
        ) {
            // ==========================================
            // HEADER: Avatar + Holographic KAVYA + Status
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Kavya Anime Avatar with glossy white ring
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .shadow(8.dp, CircleShape, spotColor = Color(0x26000000))
                            .border(2.dp, Color.White, CircleShape)
                            .clip(CircleShape)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_kavya_avatar),
                            contentDescription = "Kavya Avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        // Holographic Metallic KAVYA Logo Text
                        Text(
                            text = "KAVYA",
                            style = TextStyle(
                                fontSize = 21.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 3.5.sp,
                                brush = Brush.linearGradient(
                                    listOf(
                                        Color(0xFF0F172A),
                                        Color(0xFF475569),
                                        Color(0xFF1E293B),
                                        Color(0xFF64748B),
                                        Color(0xFF0F172A)
                                    )
                                )
                            )
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = "NEURAL COMPANION",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                color = WhiteLiquidGlassTokens.TextMuted
                            )
                        )
                    }

                    // Online indicator pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x1FFFFFFF))
                            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(WhiteLiquidGlassTokens.StatusOnline)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "READY",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.8.sp,
                                    color = WhiteLiquidGlassTokens.TextSecondary
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Thin subtle separator
            HorizontalDivider(
                color = WhiteLiquidGlassTokens.Separator,
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            // ==========================================
            // SCROLLABLE ORGANIZED MENU SECTIONS
            // ==========================================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(vertical = 10.dp)
            ) {
                // SECTION 1: ASSISTANT & CONVERSATIONS
                MenuSectionHeader(
                    title = "ASSISTANT & CONVERSATIONS",
                    isExpanded = isAssistantExpanded,
                    onToggle = { isAssistantExpanded = !isAssistantExpanded }
                )
                AnimatedVisibility(visible = isAssistantExpanded) {
                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.Home,
                            label = "Home",
                            isSelected = currentRoute == "home",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "home")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.AutoMirrored.Filled.Chat,
                            label = "Chats",
                            isSelected = currentRoute == "chat",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "chat")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.Timeline,
                            label = "Activity Timeline",
                            isSelected = currentRoute == "activity",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "activity")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.Favorite,
                            label = "Favourites",
                            isSelected = currentRoute == "favourites",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "favourites")
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(
                    color = WhiteLiquidGlassTokens.Separator,
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))

                // SECTION 2: INTELLIGENCE & AUTOMATIONS
                MenuSectionHeader(
                    title = "INTELLIGENCE & POWERS",
                    isExpanded = isIntelligenceExpanded,
                    onToggle = { isIntelligenceExpanded = !isIntelligenceExpanded }
                )
                AnimatedVisibility(visible = isIntelligenceExpanded) {
                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.Alarm,
                            label = "Routines & Alarms",
                            isSelected = currentRoute == "routines",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "routines")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.Psychology,
                            label = "Agent Memory",
                            isSelected = currentRoute == "memory",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "memory")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.ScreenShare,
                            label = "Live Screen & Vision",
                            isSelected = currentRoute == "screenshare",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "screenshare")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.MusicNote,
                            label = "Media & Music",
                            isSelected = currentRoute == "music",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "music")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.AutoGraph,
                            label = "Kavya Evolution",
                            isSelected = currentRoute == "evolution",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "evolution")
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(
                    color = WhiteLiquidGlassTokens.Separator,
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))

                // SECTION 3: SETTINGS & SYSTEM
                MenuSectionHeader(
                    title = "SETTINGS & SYSTEM",
                    isExpanded = isSettingsExpanded,
                    onToggle = { isSettingsExpanded = !isSettingsExpanded }
                )
                AnimatedVisibility(visible = isSettingsExpanded) {
                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.VpnKey,
                            label = "AI Provider Hub",
                            isSelected = currentRoute == "ai_api_hub",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "ai_api_hub")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.RecordVoiceOver,
                            label = "Voice & Audio",
                            isSelected = currentRoute == "voice_settings",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "voice_settings")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.Language,
                            label = "Language",
                            isSelected = currentRoute == "language",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "language")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.Palette,
                            label = "Appearance",
                            isSelected = currentRoute == "appearance",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "appearance")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.PrivacyTip,
                            label = "Privacy & Vault",
                            isSelected = currentRoute == "privacy",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "privacy")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.Security,
                            label = "Permissions",
                            isSelected = currentRoute == "permissions",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "permissions")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.Settings,
                            label = "General Settings",
                            isSelected = currentRoute == "settings",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "settings")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.Build,
                            label = "Advanced Settings",
                            isSelected = currentRoute == "advanced",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "advanced")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.BugReport,
                            label = "Debug Dashboard",
                            isSelected = currentRoute == "debug_dashboard",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "debug_dashboard")
                            }
                        )
                        DrawerItemWhiteGlass(
                            icon = Icons.Default.Help,
                            label = "Help & Support",
                            isSelected = currentRoute == "help_support",
                            onClick = {
                                navigateTo(navController, drawerState, scope, "help_support")
                            }
                        )
                    }
                }
            }

            // ==========================================
            // FOOTER: White Liquid Glass Version & Meta
            // ==========================================
            HorizontalDivider(
                color = WhiteLiquidGlassTokens.Separator,
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Kavya AI • v2.5",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = WhiteLiquidGlassTokens.TextPrimary
                        )
                    )
                    Text(
                        text = "Liquid Glass Architecture",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            color = WhiteLiquidGlassTokens.TextMuted
                        )
                    )
                }

                IconButton(
                    onClick = { scope.launch { drawerState.close() } },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x1FFFFFFF))
                        .border(1.dp, Color(0x26FFFFFF), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Menu",
                        tint = WhiteLiquidGlassTokens.TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuSectionHeader(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "ArrowRotation"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp,
                color = WhiteLiquidGlassTokens.TextMuted
            )
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = if (isExpanded) "Collapse section" else "Expand section",
            tint = WhiteLiquidGlassTokens.TextMuted,
            modifier = Modifier
                .size(16.dp)
                .rotate(rotation)
        )
    }
}

@Composable
private fun DrawerItemWhiteGlass(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        WhiteLiquidGlassTokens.GlassSelected
    } else {
        Color.Transparent
    }

    val textColor = if (isSelected) {
        WhiteLiquidGlassTokens.TextPrimary
    } else {
        WhiteLiquidGlassTokens.TextSecondary
    }

    val iconColor = if (isSelected) {
        WhiteLiquidGlassTokens.IconActive
    } else {
        WhiteLiquidGlassTokens.IconColor
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) Color(0x1F000000) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left accent indicator for selected item
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(WhiteLiquidGlassTokens.TextPrimary)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = textColor
            )
        )
    }
}

private fun navigateTo(
    navController: NavController,
    drawerState: DrawerState,
    scope: CoroutineScope,
    route: String
) {
    scope.launch {
        drawerState.close()
    }
    navController.navigate(route) {
        launchSingleTop = true
    }
}
