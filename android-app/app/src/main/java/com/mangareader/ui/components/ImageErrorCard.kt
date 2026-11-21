package com.mangareader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.model.ImageDiagnostic
import com.mangareader.model.ImageErrorKind
import com.mangareader.ui.theme.AmoledPalette

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageErrorCard(
    pageIndex: Int,
    imageUrl: String,
    diagnostic: ImageDiagnostic,
    onRetry: () -> Unit,
    onCacheBypass: () -> Unit,
    onRefreshSession: () -> Unit,
    onOpenBrowser: () -> Unit,
    onCopiedUrl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current

    val is404 = diagnostic.kind == ImageErrorKind.HTTP_404
    val isChallenge = diagnostic.kind == ImageErrorKind.HTML_CHALLENGE
    val isDecodeError = diagnostic.kind == ImageErrorKind.DECODE_ERROR

    val containerColor = if (is404) AmoledPalette.DangerBg else AmoledPalette.WarningBg
    val borderColor = if (is404) AmoledPalette.DangerBorder else AmoledPalette.WarningBorder
    val accentColor = if (is404) AmoledPalette.DangerText else AmoledPalette.WarningText

    val icon = when (diagnostic.kind) {
        ImageErrorKind.HTML_CHALLENGE -> Icons.Outlined.Security
        ImageErrorKind.DECODE_ERROR -> Icons.Outlined.BrokenImage
        else -> Icons.Outlined.WarningAmber
    }

    val statusLabel = buildString {
        append("Seite ${pageIndex + 1}")
        when {
            isChallenge -> append(" · Sicherheits-/CDN-Prüfung")
            isDecodeError -> append(" · Null Bitmap / Decode-Fehler")
            diagnostic.httpStatus != null -> append(" · HTTP ${diagnostic.httpStatus}")
        }
        if (!diagnostic.receivedContentType.isNullOrBlank()) {
            append(" · ${diagnostic.receivedContentType}")
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = statusLabel,
                    color = accentColor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = diagnostic.title,
                color = AmoledPalette.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = diagnostic.description,
                color = AmoledPalette.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = diagnostic.suggestion,
                color = AmoledPalette.TextMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = imageUrl,
                color = AmoledPalette.TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(14.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isChallenge) {
                    // Primäraktion bei text/html & application/xml: Im Browser öffnen
                    Button(
                        onClick = onOpenBrowser,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AmoledPalette.AccentPrimary,
                            contentColor = AmoledPalette.TextPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Im Browser öffnen")
                    }
                }

                if (isDecodeError) {
                    // Primäraktion bei Null-Bitmap / defektem Cache: Cache-Bypass für dieses Bild
                    Button(
                        onClick = onCacheBypass,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AmoledPalette.AccentPrimary,
                            contentColor = AmoledPalette.TextPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Cache-Bypass für dieses Bild")
                    }
                } else {
                    OutlinedButton(
                        onClick = onCacheBypass,
                        border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Cache-Bypass")
                    }
                }

                OutlinedButton(
                    onClick = onRetry,
                    border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Erneut versuchen")
                }

                if (diagnostic.suggestRefreshSession) {
                    OutlinedButton(
                        onClick = onRefreshSession,
                        border = BorderStroke(1.dp, AmoledPalette.WarningBorder),
                    ) {
                        Text("Sitzung erneuern", color = AmoledPalette.WarningText)
                    }
                }

                if (!isChallenge) {
                    OutlinedButton(
                        onClick = onOpenBrowser,
                        border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Im Browser öffnen")
                    }
                }

                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(imageUrl))
                        onCopiedUrl()
                    },
                    border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("URL kopieren")
                }
            }
        }
    }
}
