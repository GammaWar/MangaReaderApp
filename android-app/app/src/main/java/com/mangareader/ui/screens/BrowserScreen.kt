package com.mangareader.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mangareader.data.UrlChapterAnalyzer
import com.mangareader.model.BrowserTabState
import com.mangareader.ui.theme.AmoledPalette

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    tabs: List<BrowserTabState>,
    activeTabId: String,
    isExtracting: Boolean,
    onSelectTab: (String) -> Unit,
    onAddTab: () -> Unit,
    onCloseTab: (String) -> Unit,
    onUpdateTabMeta: (tabId: String, url: String?, title: String?, isLoading: Boolean?) -> Unit,
    onExtractChapter: (String) -> Unit,
) {
    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
    var addressText by remember(activeTab.id, activeTab.url) { mutableStateOf(activeTab.url) }
    var activeWebView by remember(activeTab.id) { mutableStateOf<WebView?>(null) }

    LaunchedEffect(activeTab.url) {
        addressText = activeTab.url
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledPalette.Background)
    ) {
        // Multi-Tab-Leiste: Breite Tabs (148–220 dp) mit isoliertem 24dp-Schließen-Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AmoledPalette.Surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { tab ->
                key(tab.id) {
                    val selected = tab.id == activeTab.id
                    Surface(
                        color = if (selected) AmoledPalette.SurfaceElevated else AmoledPalette.Background,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (selected) AmoledPalette.AccentSoft else AmoledPalette.BorderHairline
                        ),
                        modifier = Modifier
                            .widthIn(min = 148.dp, max = 220.dp)
                            .clickable { onSelectTab(tab.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = tab.title,
                                color = if (selected) AmoledPalette.TextPrimary else AmoledPalette.TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(AmoledPalette.Surface)
                                    .clickable { onCloseTab(tab.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Tab schließen",
                                    tint = AmoledPalette.TextSecondary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }

            IconButton(
                onClick = onAddTab,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Neuer Tab",
                    tint = AmoledPalette.AccentSoft,
                )
            }
        }

        // Adressleiste & Extraktions-Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AmoledPalette.Surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = { activeWebView?.takeIf { it.canGoBack() }?.goBack() },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Zurück", tint = AmoledPalette.TextSecondary)
            }
            IconButton(
                onClick = { activeWebView?.takeIf { it.canGoForward() }?.goForward() },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Vor", tint = AmoledPalette.TextSecondary)
            }
            IconButton(
                onClick = { activeWebView?.reload() },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Neu laden", tint = AmoledPalette.TextSecondary)
            }

            OutlinedTextField(
                value = addressText,
                onValueChange = { addressText = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        val normalized = normalizeUrlInput(addressText)
                        if (UrlChapterAnalyzer.isSafeExternalUrl(normalized)) {
                            onUpdateTabMeta(activeTab.id, normalized, null, true)
                            activeWebView?.loadUrl(normalized)
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AmoledPalette.AccentSoft,
                    unfocusedBorderColor = AmoledPalette.BorderHairline,
                    focusedContainerColor = AmoledPalette.Background,
                    unfocusedContainerColor = AmoledPalette.Background,
                ),
            )

            Button(
                onClick = {
                    val target = activeWebView?.url ?: activeTab.url
                    CookieManager.getInstance().flush()
                    onExtractChapter(target)
                },
                enabled = !isExtracting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmoledPalette.AccentPrimary,
                    contentColor = AmoledPalette.TextPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesomeMotion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isExtracting) "Lädt..." else "Reader", fontSize = 12.sp)
            }
        }

        if (activeTab.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = AmoledPalette.AccentCyan,
                trackColor = AmoledPalette.Surface,
            )
        }

        // Echte native Android WebView mit Cookie-Synchronisierung für Cloudflare / CDN-Challenges
        key(activeTab.id) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val nextUrl = request?.url?.toString() ?: return true
                                // Blockiere unsichere URL-Schemata (javascript:, file:, etc.)
                                return !UrlChapterAnalyzer.isSafeExternalUrl(nextUrl)
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                onUpdateTabMeta(activeTab.id, url, null, true)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                CookieManager.getInstance().flush()
                                onUpdateTabMeta(activeTab.id, url, view?.title, false)
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                onUpdateTabMeta(activeTab.id, null, title, null)
                            }
                        }

                        if (UrlChapterAnalyzer.isSafeExternalUrl(activeTab.url)) {
                            loadUrl(activeTab.url)
                        }
                        activeWebView = this
                    }
                },
                update = { webView ->
                    activeWebView = webView
                    if (
                        UrlChapterAnalyzer.isSafeExternalUrl(activeTab.url) &&
                        webView.url != activeTab.url &&
                        !activeTab.isLoading
                    ) {
                        webView.loadUrl(activeTab.url)
                    }
                },
            )
        }
    }
}

private fun normalizeUrlInput(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
        return trimmed
    }
    return "https://$trimmed"
}
