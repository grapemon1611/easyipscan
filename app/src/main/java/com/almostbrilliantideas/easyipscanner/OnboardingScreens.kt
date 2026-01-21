package com.almostbrilliantideas.easyipscanner

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val BrandBlue = Color(0xFF000099)

@Composable
fun WifiWarningScreen(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val exo2FontFamily = FontFamily(
        Font(R.font.exo2_semibold, FontWeight.SemiBold)
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App branding header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = BrandBlue,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "EasyIP Scan",
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = exo2FontFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Warning icon
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Title
            Text(
                text = "No WiFi Connection",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Info cards
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    InfoRow(
                        text = "EasyIP Scan discovers devices on your local network (LAN)"
                    )
                    InfoRow(
                        text = "Mobile data cannot access your local network \u2014 WiFi or Ethernet required"
                    )
                    InfoRow(
                        text = "Connect to WiFi to scan for devices"
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Primary button - Open WiFi Settings
            Button(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandBlue
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "Open WiFi Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Secondary button - Dismiss
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "I Understand",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = BrandBlue
                )
            }
        }
    }
}

@Composable
private fun InfoRow(text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = BrandBlue.copy(alpha = 0.1f),
            modifier = Modifier.size(8.dp).offset(y = 8.dp)
        ) {}
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CaptivePortalWarningScreen(
    onOpenPortalLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    val exo2FontFamily = FontFamily(
        Font(R.font.exo2_semibold, FontWeight.SemiBold)
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App branding header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = BrandBlue,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "EasyIP Scan",
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = exo2FontFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Warning icon
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color(0xFFFF9800) // Orange for captive portal warning
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Title
            Text(
                text = "Captive Portal Detected",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Info cards
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    InfoRow(
                        text = "Your WiFi network requires login (hotel, airport, coffee shop, etc.)"
                    )
                    InfoRow(
                        text = "Network scanning is disabled until you authenticate"
                    )
                    InfoRow(
                        text = "Complete the login to access your local network"
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Primary button - Open Portal Login
            Button(
                onClick = onOpenPortalLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandBlue
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "Open Network Login",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Secondary button - Dismiss
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "I Understand",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = BrandBlue
                )
            }
        }
    }
}

@Composable
fun CaptivePortalEmptyState(
    modifier: Modifier = Modifier,
    onOpenPortalLogin: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = Color(0xFFFF9800).copy(alpha = 0.7f) // Orange for captive portal
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Network Login Required",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Complete the captive portal login to scan your network",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onOpenPortalLogin,
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandBlue
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = "Open Network Login",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun NoNetworkEmptyState(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "No local network detected",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Connect to WiFi to scan devices on your LAN",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandBlue
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = "Open WiFi Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
