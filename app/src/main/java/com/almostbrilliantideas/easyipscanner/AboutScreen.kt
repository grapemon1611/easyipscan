package com.almostbrilliantideas.easyipscanner

import com.almostbrilliantideas.easyipscanner.BuildConfig
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
fun AboutScreen() {
    val context = LocalContext.current

    val exo2FontFamily = FontFamily(
        Font(R.font.exo2_semibold, FontWeight.SemiBold)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // App Icon placeholder - using text as icon
        Surface(
            modifier = Modifier.size(80.dp),
            shape = MaterialTheme.shapes.large,
            color = androidx.compose.ui.graphics.Color(0xFF000099)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "IP",
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = exo2FontFamily,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // App Name
        Text(
            text = "EasyIP Scan\u2122",
            style = MaterialTheme.typography.headlineLarge,
            fontFamily = exo2FontFamily,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        // Version - pulled dynamically from BuildConfig
        Text(
            text = "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Tagline
        Text(
            text = "Fast local network scanning for technicians and power users.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Links Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Privacy Policy Link
                LinkItem(
                    text = "Privacy Policy",
                    onClick = { openPrivacyPolicy(context) }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )

                // Support Link
                LinkItem(
                    text = "Support",
                    onClick = { openSupportEmail(context) }
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Copyright
        Text(
            text = "\u00A9 2026 Almost Brilliant Ideas, Inc.\nAll rights reserved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun LinkItem(
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = androidx.compose.ui.graphics.Color(0xFF000099),
            textDecoration = TextDecoration.Underline
        )

        Text(
            text = "\u203A", // Right arrow character
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Opens the privacy policy URL in the default browser.
 */
private fun openPrivacyPolicy(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://easyipscan.app/privacy-policy.html"))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No browser found to open link", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Opens the default email client to compose a support email.
 * Falls back to a toast message if no email client is installed.
 */
private fun openSupportEmail(context: Context) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:support@easyipscan.app")
        putExtra(Intent.EXTRA_SUBJECT, "EasyIP Scan Support")
    }

    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // No email client installed - show fallback message
        Toast.makeText(
            context,
            "No email app found. Contact: support@easyipscan.app",
            Toast.LENGTH_LONG
        ).show()
    }
}
