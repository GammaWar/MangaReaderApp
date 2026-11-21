package com.mangareader.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangareader.app.data.network.DiagnosticKind
import com.mangareader.app.data.network.DiagnosticSeverity
import com.mangareader.app.data.network.ImageDiagnostics
import com.mangareader.app.ui.theme.AccentPrimary
import com.mangareader.app.ui.theme.AccentPrimaryLight

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageErrorView(
    pageIndex: Int,
    imageUrl: String,
    throwable: Throwable,
    onRetry: () -> Unit,
    onCacheBypass: () -> Unit,
    onRefreshSession: () -> Unit,
    onOpenInBrowser: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val diagnostic = remember(throwable) { ImageDiagnostics.classifyImageThrowable(throwable) }

    val surfaceBg: Color
    val borderCol: Color
    val iconCol: Color
    when (diagnostic.severity) {
        DiagnosticSeverity.CRIMSON -> {
            surfaceBg = Color(0xFF1B0D12)
            borderCol = Color(0xFF9F1239)
            iconCol = Color(0xFFFB7185)
        }
        DiagnosticSeverity.AMBER -> {
            surfaceBg = Color(0xFF1C140B)
            borderCol = Color(0xFFB45309)
            iconCol = Color(0xFFFBBF24)
        }
        DiagnosticSeverity.YELLOW -> {
            surfaceBg = Color(0xFF1B180B)
            borderCol = Color(0xFFA16207)
            iconCol = Color(0xFFFDE047)
        }
        DiagnosticSeverity.SLATE -> {
            surfaceBg = Color(0xFF141222)
            borderCol = Color(0xFF2E2A45)
            iconCol = Color(0xFFCBD5E1)
        }
    }

    val statusIcon = when (diagnostic.kind) {
        DiagnosticKind.HTTP_404 -> Icons.Default.ErrorOutline
        DiagnosticKind.HTTP_403, DiagnosticKind.HTML_CHALLENGE -> Icons.Default.Security
        DiagnosticKind.DECODE_ERROR -> Icons.Default.BrokenImage
        DiagnosticKind.HTTP_429 -> Icons.Default.HourglassTop
        DiagnosticKind.HTTP_5XX, DiagnosticKind.TIMEOUT -> Icons.Default.CloudOff
        DiagnosticKind.NETWORK_ERROR -> Icons.Default.Warning
    }

    val isDecodeError = diagnostic.kind == DiagnosticKind.DECODE_ERROR
    val isSecurityChallenge = diagnostic.kind == DiagnosticKind.HTML_CHALLENGE

    val metaStatusLabel = when {
        isSecurityChallenge -> "Sicherheits-/CDN-Prüfung"
        diagnostic.httpStatus != null -> "Status ${diagnostic.httpStatus}"
        isDecodeError -> "Null-Bitmap-Diagnose"
        else -> "Diagnose"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = surfaceBg,
        border = BorderStroke(1.dp, borderCol)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = iconCol,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(24.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    // Clean unboxed metadata row with typographic dot separators
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Seite ${pageIndex + 1} · $metaStatusLabel",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFA7A1C2)
                        )
                        if (!diagnostic.receivedContentType.isNullOrBlank()) {
                            Text(
                                text = "· ${diagnostic.receivedContentType}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFCD34D),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = diagnostic.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF5F3FF)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = diagnostic.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCFCBE2)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = diagnostic.hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9C95BA)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = imageUrl,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF80799E),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
            Spacer(modifier = Modifier.height(14.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isSecurityChallenge && diagnostic.suggestOpenBrowser) {
                    Button(
                        onClick = onOpenInBrowser,
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Im Browser öffnen", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (diagnostic.suggestCacheBypass) {
                    if (isDecodeError) {
                        Button(
                            onClick = onCacheBypass,
                            modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPrimary,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cache-Bypass für dieses Bild", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onCacheBypass,
                            modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AccentPrimaryLight.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = AccentPrimary.copy(alpha = 0.15f),
                                contentColor = Color(0xFFE8DEFF)
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cache-Bypass für dieses Bild", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                val retryIsSecondary = isDecodeError || isSecurityChallenge
                if (retryIsSecondary) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFECE9F8)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Erneut versuchen", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (diagnostic.kind == DiagnosticKind.HTTP_404) Color(0xFF28243A) else AccentPrimary,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (diagnostic.kind == DiagnosticKind.HTTP_404) "Nochmal prüfen" else "Erneut versuchen",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                if (diagnostic.suggestSessionRefresh) {
                    OutlinedButton(
                        onClick = onRefreshSession,
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFFF59E0B).copy(alpha = 0.14f),
                            contentColor = Color(0xFFFEF3C7)
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sitzung erneuern", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (!isSecurityChallenge && diagnostic.suggestOpenBrowser) {
                    OutlinedButton(
                        onClick = onOpenInBrowser,
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFECE9F8)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Im Browser öffnen", style = MaterialTheme.typography.labelMedium)
                    }
                }

                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("Bild-URL Seite ${pageIndex + 1}", imageUrl)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(context, "Bild-URL kopiert", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCFCBE2)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("URL kopieren", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
