package one.aipass.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AI Pass logo/button variants
 */
enum class AiPassVariant {
    /** White background with purple text - for light mode or when connected */
    STANDARD,

    /** Black background with white text - for dark mode or when not connected */
    DARK,

    /** White background with ripple animation - shows active connection */
    CONNECTED,

    /** With "CONNECT" text - call to action when not connected */
    WITH_CONNECT_TEXT,

    /** Dark version with "CONNECT" text */
    DARK_WITH_CONNECT_TEXT
}

/**
 * Compact AI Pass icon — indigo rounded square with "AI" text, matching the landing page style.
 * Full color when connected, dimmed when not connected.
 *
 * @param isAuthenticated Whether the user is authenticated
 * @param onClick Click handler
 * @param size Icon size (default 32dp)
 * @param modifier Modifier for the component
 */
@Composable
fun AiPassCompactButton(
    isAuthenticated: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "scale"
    )

    // Font size scales with icon size (~50% of height)
    val fontSize = (size.value * 0.45f).sp

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(RoundedCornerShape(size * 0.2f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .alpha(if (isAuthenticated) 1f else 0.35f)
    ) {
        // Background with chevron
        Icon(
            painter = androidx.compose.ui.res.painterResource(one.aipass.R.drawable.ic_aipass_bg),
            contentDescription = if (isAuthenticated) "AI Pass - Connected" else "AI Pass - Not Connected",
            tint = Color.Unspecified,
            modifier = Modifier.matchParentSize()
        )
        // "AI" text rendered as real font - matches landing page
        Text(
            text = "AI",
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * AI Pass Connect button/logo component with multiple variants
 *
 * Variants:
 * - STANDARD: White background, purple text
 * - DARK: Black background, white text
 * - CONNECTED: White background, purple text, animated ripples
 * - WITH_CONNECT_TEXT: White background, purple text, "CONNECT" label
 * - DARK_WITH_CONNECT_TEXT: Black background, white text, "CONNECT" label
 *
 * @param variant The visual variant to display
 * @param onClick Optional click handler (if null, component is non-interactive)
 * @param modifier Modifier for the component
 */
@Composable
fun AiPassLogo(
    variant: AiPassVariant = AiPassVariant.STANDARD,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val aiPassPurple = Color(0xFF4F46E5)

    // Determine colors based on variant
    val containerColor = when (variant) {
        AiPassVariant.STANDARD,
        AiPassVariant.CONNECTED,
        AiPassVariant.WITH_CONNECT_TEXT -> Color.White

        AiPassVariant.DARK,
        AiPassVariant.DARK_WITH_CONNECT_TEXT -> Color(0xFF222222)
    }

    val textColor = when (variant) {
        AiPassVariant.STANDARD,
        AiPassVariant.CONNECTED,
        AiPassVariant.WITH_CONNECT_TEXT -> aiPassPurple

        AiPassVariant.DARK,
        AiPassVariant.DARK_WITH_CONNECT_TEXT -> Color.White
    }

    // Interaction states for clickable version
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "scale"
    )

    // Animate icon background color
    val iconBackgroundColor by animateColorAsState(
        targetValue = aiPassPurple,
        animationSpec = tween(durationMillis = 300),
        label = "iconBackgroundColor"
    )

    val showRipples = variant == AiPassVariant.CONNECTED
    val showConnectText = variant == AiPassVariant.WITH_CONNECT_TEXT ||
            variant == AiPassVariant.DARK_WITH_CONNECT_TEXT

    Box(
        modifier = modifier.scale(scale)
    ) {
        // Ripple effect container (only for CONNECTED variant)
        if (showRipples) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(
                        RoundedCornerShape(
                            topStart = 12.dp,
                            topEnd = 0.dp,
                            bottomStart = 12.dp,
                            bottomEnd = 12.dp
                        )
                    )
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "ripple")

                // Create 3 ripples with different delays
                for (i in 0..2) {
                    val rippleSize by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 80f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 3000),
                            repeatMode = RepeatMode.Restart,
                            initialStartOffset = androidx.compose.animation.core.StartOffset(i * 1000)
                        ),
                        label = "ripple$i"
                    )

                    val rippleAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.6f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 3000),
                            repeatMode = RepeatMode.Restart,
                            initialStartOffset = androidx.compose.animation.core.StartOffset(i * 1000)
                        ),
                        label = "rippleAlpha$i"
                    )

                    Box(
                        modifier = Modifier
                            .offset(x = 18.dp)
                            .size(rippleSize.dp)
                            .alpha(rippleAlpha)
                            .background(
                                color = aiPassPurple.copy(alpha = 0.3f),
                                shape = CircleShape
                            )
                            .align(Alignment.CenterStart)
                    )
                }

                // Background pulse effect
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 0.1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 2000),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse"
                )

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(pulseAlpha)
                        .background(
                            color = aiPassPurple.copy(alpha = 0.05f)
                        )
                )
            }
        }

        // Main button/logo content
        Box(
            modifier = Modifier
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 0.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp
                    ),
                    clip = false
                )
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 0.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp
                    )
                )
                .background(
                    color = containerColor
                )
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // AI Pass icon - background + text
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(one.aipass.R.drawable.ic_aipass_bg),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.matchParentSize()
                    )
                    Text(
                        text = "AI",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // "Pass" text
                Text(
                    text = "Pass",
                    color = textColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                // Show "CONNECT" text for variants that need it
                if (showConnectText) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "CONNECT",
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

/**
 * Smart AI Pass button that automatically selects the right variant based on authentication state
 *
 * - Not authenticated: Black background with white text + CloudOff icon
 * - Authenticated: White background with purple text + ripple effect
 *
 * @param isAuthenticated Whether the user is authenticated
 * @param onClick Click handler
 * @param modifier Modifier for the component
 */
@Composable
fun AiPassConnectButton(
    isAuthenticated: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val aiPassPurple = Color(0xFF4F46E5)

    // Interaction states for animations
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "scale"
    )

    // Animate icon background color
    val iconBackgroundColor by animateColorAsState(
        targetValue = aiPassPurple,
        animationSpec = tween(durationMillis = 300),
        label = "iconBackgroundColor"
    )

    // Determine colors based on authentication state
    // NOT authenticated = Black background with white text
    // IS authenticated = White background with purple text
    val containerColor = if (isAuthenticated) {
        Color.White // White background when connected
    } else {
        Color(0xFF222222) // Black background when not connected
    }

    val textColor = if (isAuthenticated) {
        aiPassPurple // Purple text when connected
    } else {
        Color.White // White text when not connected
    }

    Box(
        modifier = modifier.scale(scale)
    ) {
        // Ripple effect container (behind, doesn't affect layout)
        if (isAuthenticated) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(
                        RoundedCornerShape(
                            topStart = 12.dp,
                            topEnd = 0.dp,
                            bottomStart = 12.dp,
                            bottomEnd = 12.dp
                        )
                    )
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "ripple")

                // Create 3 ripples with different delays
                for (i in 0..2) {
                    val rippleSize by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 80f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 3000),
                            repeatMode = RepeatMode.Restart,
                            initialStartOffset = androidx.compose.animation.core.StartOffset(i * 1000)
                        ),
                        label = "ripple$i"
                    )

                    val rippleAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.6f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 3000),
                            repeatMode = RepeatMode.Restart,
                            initialStartOffset = androidx.compose.animation.core.StartOffset(i * 1000)
                        ),
                        label = "rippleAlpha$i"
                    )

                    Box(
                        modifier = Modifier
                            .offset(x = 18.dp)
                            .size(rippleSize.dp)
                            .alpha(rippleAlpha)
                            .background(
                                color = aiPassPurple.copy(alpha = 0.3f),
                                shape = CircleShape
                            )
                            .align(Alignment.CenterStart)
                    )
                }

                // Background pulse effect
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 0.1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 2000),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse"
                )

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(pulseAlpha)
                        .background(
                            color = aiPassPurple.copy(alpha = 0.05f)
                        )
                )
            }
        }

        // Main button content
        Box(
            modifier = Modifier
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 0.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp
                    ),
                    clip = false
                )
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 0.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp
                    )
                )
                .background(
                    color = containerColor
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // AI Pass icon - background + text
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(one.aipass.R.drawable.ic_aipass_bg),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.matchParentSize()
                    )
                    Text(
                        text = "AI",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // "Pass" text
                Text(
                    text = "Pass",
                    color = textColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                // Show offline icon when NOT authenticated
                if (!isAuthenticated) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.CloudOff,
                        contentDescription = "Not connected",
                        tint = textColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * AI Pass status bottom sheet showing connection state and balance.
 *
 * @param isAuthenticated Whether the user is currently authenticated
 * @param remainingBudget Remaining budget (null if not loaded or not authenticated)
 * @param maxBudget Max budget (null if not loaded)
 * @param onLoginClick Called when user taps Connect / Sign In
 * @param onLogoutClick Called when user taps Disconnect
 * @param onDismiss Called when sheet is dismissed
 * @param onDashboardClick Called when user taps Dashboard button
 */
@Composable
fun AiPassStatusDialog(
    isAuthenticated: Boolean,
    remainingBudget: Double? = null,
    maxBudget: Double? = null,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onDismiss: () -> Unit,
    onDashboardClick: (() -> Unit)? = null
) {
    val aiPassIndigo = Color(0xFF4F46E5)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
    // Backdrop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Bottom sheet card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false, onClick = {}),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A1A2E)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Drag handle
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                )

                Spacer(modifier = Modifier.height(24.dp))

                // AI Pass logo
                AiPassLogo(
                    variant = if (isAuthenticated) AiPassVariant.CONNECTED else AiPassVariant.DARK,
                    onClick = null
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isAuthenticated) {
                    // Balance card with "Connected" badge
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.07f)
                        )
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            // Balance centered
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "BALANCE",
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.5.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (remainingBudget != null) "${"$%.2f".format(remainingBudget)}" else "--",
                                    color = Color.White,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            // Connected badge top-right
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF7D),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Connected",
                                    color = Color(0xFF4CAF7D),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Dashboard button
                    if (onDashboardClick != null) {
                        Button(
                            onClick = onDashboardClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = aiPassIndigo,
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                        ) {
                            Text(
                                text = "Open Dashboard",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Disconnect button
                    OutlinedButton(
                        onClick = {
                            onLogoutClick()
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White.copy(alpha = 0.6f)
                        )
                    ) {
                        Text(
                            text = "Disconnect",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    // Not connected state
                    Text(
                        text = "Not Connected",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Connect to AI Pass to unlock the smart assistant, voice input, receipt scanning, and financial advice.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Signup promo
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF4CAF7D).copy(alpha = 0.12f)
                        )
                    ) {
                        Text(
                            text = "\uD83C\uDF81  New to AI Pass? Sign up and get $1.00 free credit!",
                            color = Color(0xFF4CAF7D),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Connect button
                    Button(
                        onClick = {
                            onLoginClick()
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = aiPassIndigo,
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                    ) {
                        Text(
                            text = "Connect to AI Pass",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(56.dp))
            }
        }
    }
    } // end Dialog
}
