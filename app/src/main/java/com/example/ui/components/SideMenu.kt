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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.R
import com.example.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Apple Dark Liquid Glass Design Tokens for Navigation Drawer.
 * High-contrast dark frosted obsidian with specular top-lit hairline rim.
 */
private object AppleDrawerGlassTokens {
    val GlassSurface = Color(0xEA11151E)
    val GlassSelected = Color(0x2B0A84FF)
    val SelectedBorder = Color(0x665AC8FA)
    val Separator = Color(0x1AFFFFFF)

    val SpecularBorder = Brush.verticalGradient(
        listOf(
            Color(0x4DFFFFFF),
            Color(0x21FFFFFF),
            Color(0x12FFFFFF)
        )
    )

    val TextPrimary = Color(0xFFF5F7FA)
    val TextSecondary = Color(0xFFC5CDDC)
    val TextMuted = Color(0xFF7D8799)
    val IconColor = Color(0xFFA9B3C7)
    val IconActive = PrimaryLight
    val StatusOnline = SuccessGreen
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

    var isAssistantExpanded by remember { mutableStateOf(true) }
    var isIntelligenceExpanded by remember { mutableStateOf(true) }
    var isSettingsExpanded by remember { mutableStateOf(true) }

    val drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("kavya_white_liquid_glass_drawer")
            .shadow(
                elevation = 24.dp,
                shape = drawerShape,
                spotColor = Color(0x66000000),
                ambientColor = Color(0x33000000)
            )
            .border(
                border = BorderStroke(1.dp, AppleDrawerGlassTokens.SpecularBorder),
                shape = drawerShape
            ),
        color = AppleDrawerGlassTokens.GlassSurface,
        shape = drawerShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = 14.dp, bottom = 14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .border(1.5.dp, Color(0x55FFFFFF), CircleShape)
                        .clip(CircleShape)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_kavya_avatar),
                        contentDescription = "Kavya Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Kavya",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.2).sp
                        ),
                        color = AppleDrawerGlassTokens.TextPrimary
                    )
                    Text(
                        text = "Personal Assistant",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleDrawerGlassTokens.TextMuted
                    )
                }

                StatusBadge(
                    text = "Ready",
                    isActive = true,
                    activeColor = AppleDrawerGlassTokens.StatusOnline
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            HorizontalDivider(
                color = AppleDrawerGlassTokens.Separator,
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            // Scrollable navigation sections
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(vertical = 8.dp)
            ) {
                MenuSectionHeader(
                    title = "WORKSPACE",
                    isExpanded = isAssistantExpanded,
                    onToggle = { isAssistantExpanded = !isAssistantExpanded }
                )
                AnimatedVisibility(visible = isAssistantExpanded) {
                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        DrawerItemGlass(
                            icon = Icons.Default.Home,
                            label = "Home",
                            isSelected = currentRoute == "home",
                            onClick = { navigateTo(navController, drawerState, scope, "home") }
                        )
                        DrawerItemGlass(
                            icon = Icons.AutoMirrored.Filled.Chat,
                            label = "Chats",
                            isSelected = currentRoute == "chat",
                            onClick = { navigateTo(navController, drawerState, scope, "chat") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.Timeline,
                            label = "Activity Timeline",
                            isSelected = currentRoute == "activity",
                            onClick = { navigateTo(navController, drawerState, scope, "activity") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.Favorite,
                            label = "Favourites",
                            isSelected = currentRoute == "favourites",
                            onClick = { navigateTo(navController, drawerState, scope, "favourites") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(
                    color = AppleDrawerGlassTokens.Separator,
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))

                MenuSectionHeader(
                    title = "VISION & CAPABILITIES",
                    isExpanded = isIntelligenceExpanded,
                    onToggle = { isIntelligenceExpanded = !isIntelligenceExpanded }
                )
                AnimatedVisibility(visible = isIntelligenceExpanded) {
                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        DrawerItemGlass(
                            icon = Icons.Default.AutoAwesome,
                            label = "Vision & Local Models",
                            isSelected = currentRoute == "jarvis_intelligence",
                            testTag = "drawer_item_vision",
                            onClick = { navigateTo(navController, drawerState, scope, "jarvis_intelligence") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.ScreenShare,
                            label = "Screen Share & Orb",
                            isSelected = currentRoute == "screenshare",
                            onClick = { navigateTo(navController, drawerState, scope, "screenshare") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.TouchApp,
                            label = "Visual Action Control",
                            isSelected = currentRoute == "visual_action_control",
                            onClick = { navigateTo(navController, drawerState, scope, "visual_action_control") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.Hub,
                            label = "Integrations Hub",
                            isSelected = currentRoute == "kucp_dashboard",
                            testTag = "drawer_item_integrations",
                            onClick = { navigateTo(navController, drawerState, scope, "kucp_dashboard") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.Alarm,
                            label = "Routines & Alarms",
                            isSelected = currentRoute == "routines",
                            onClick = { navigateTo(navController, drawerState, scope, "routines") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.Psychology,
                            label = "Agent Memory",
                            isSelected = currentRoute == "memory",
                            onClick = { navigateTo(navController, drawerState, scope, "memory") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.MusicNote,
                            label = "Media & Music",
                            isSelected = currentRoute == "music",
                            onClick = { navigateTo(navController, drawerState, scope, "music") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.AutoGraph,
                            label = "Kavya Evolution",
                            isSelected = currentRoute == "evolution",
                            onClick = { navigateTo(navController, drawerState, scope, "evolution") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(
                    color = AppleDrawerGlassTokens.Separator,
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))

                MenuSectionHeader(
                    title = "SETTINGS & SYSTEM",
                    isExpanded = isSettingsExpanded,
                    onToggle = { isSettingsExpanded = !isSettingsExpanded }
                )
                AnimatedVisibility(visible = isSettingsExpanded) {
                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        DrawerItemGlass(
                            icon = Icons.Default.VpnKey,
                            label = "AI Provider Hub",
                            isSelected = currentRoute == "ai_api_hub",
                            onClick = { navigateTo(navController, drawerState, scope, "ai_api_hub") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.RecordVoiceOver,
                            label = "Voice Studio",
                            isSelected = currentRoute == "voice_settings",
                            onClick = { navigateTo(navController, drawerState, scope, "voice_settings") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.Language,
                            label = "Language",
                            isSelected = currentRoute == "language",
                            onClick = { navigateTo(navController, drawerState, scope, "language") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.Palette,
                            label = "Appearance",
                            isSelected = currentRoute == "appearance",
                            onClick = { navigateTo(navController, drawerState, scope, "appearance") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.PrivacyTip,
                            label = "Privacy & Vault",
                            isSelected = currentRoute == "privacy",
                            onClick = { navigateTo(navController, drawerState, scope, "privacy") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.Security,
                            label = "Permissions",
                            isSelected = currentRoute == "permissions",
                            onClick = { navigateTo(navController, drawerState, scope, "permissions") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.Settings,
                            label = "General Settings",
                            isSelected = currentRoute == "settings",
                            onClick = { navigateTo(navController, drawerState, scope, "settings") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.Build,
                            label = "Advanced Settings",
                            isSelected = currentRoute == "advanced",
                            onClick = { navigateTo(navController, drawerState, scope, "advanced") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.BugReport,
                            label = "Debug Dashboard",
                            isSelected = currentRoute == "debug_dashboard",
                            onClick = { navigateTo(navController, drawerState, scope, "debug_dashboard") }
                        )
                        DrawerItemGlass(
                            icon = Icons.Default.Help,
                            label = "Help & Support",
                            isSelected = currentRoute == "help_support",
                            onClick = { navigateTo(navController, drawerState, scope, "help_support") }
                        )
                    }
                }
            }

            HorizontalDivider(
                color = AppleDrawerGlassTokens.Separator,
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
                        text = "Kavya • v2.5",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                        color = AppleDrawerGlassTokens.TextPrimary
                    )
                    Text(
                        text = "All systems local-ready",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = AppleDrawerGlassTokens.TextMuted
                    )
                }

                IconButton(
                    onClick = { scope.launch { drawerState.close() } },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x1FFFFFFF))
                        .border(1.dp, Color(0x2EFFFFFF), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Menu",
                        tint = AppleDrawerGlassTokens.TextPrimary,
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
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp
            ),
            color = AppleDrawerGlassTokens.TextMuted
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = if (isExpanded) "Collapse section" else "Expand section",
            tint = AppleDrawerGlassTokens.TextMuted,
            modifier = Modifier
                .size(16.dp)
                .rotate(rotation)
        )
    }
}

@Composable
private fun DrawerItemGlass(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    testTag: String? = null,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) AppleDrawerGlassTokens.GlassSelected else Color.Transparent
    val textColor = if (isSelected) AppleDrawerGlassTokens.TextPrimary else AppleDrawerGlassTokens.TextSecondary
    val iconColor = if (isSelected) AppleDrawerGlassTokens.IconActive else AppleDrawerGlassTokens.IconColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) AppleDrawerGlassTokens.SelectedBorder else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(19.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
