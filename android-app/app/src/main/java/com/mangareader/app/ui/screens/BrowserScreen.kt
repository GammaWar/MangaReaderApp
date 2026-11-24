package com.mangareader.app.ui.screens

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mangareader.app.data.extractor.ImageExtractorJs
import com.mangareader.app.data.model.AppTab
import com.mangareader.app.ui.theme.AccentPrimary
import com.mangareader.app.ui.theme.AccentPrimaryLight
import com.mangareader.app.ui.theme.AccentSecondary
import com.mangareader.app.ui.theme.ActiveTabBg
import com.mangareader.app.ui.theme.BodyFontFamily
import com.mangareader.app.ui.theme.CardBorder
import com.mangareader.app.ui.theme.DarkBackground
import com.mangareader.app.ui.theme.DarkCardBg
import com.mangareader.app.ui.theme.DarkInputBg
import com.mangareader.app.ui.theme.DarkSurface
import com.mangareader.app.ui.theme.MonoFontFamily
import com.mangareader.app.ui.theme.PureAmoledBlack
import com.mangareader.app.ui.theme.TextSecondary
import com.mangareader.app.ui.viewmodel.MangaViewModel
import com.mangareader.app.ui.viewmodel.UiEvent
import com.mangareader.app.util.AppConstants
import kotlinx.coroutines.flow.collectLatest

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    viewModel: MangaViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val tabs by viewModel.tabs.collectAsState()
    val activeTabId by viewModel.activeWebTabId.collectAsState()
    val currentUrl by viewModel.currentUrl.collectAsState()
    val isWebLoading by viewModel.isWebLoading.collectAsState()
    val webProgress by viewModel.webProgress.collectAsState()
    val isExtracting by viewModel.isExtracting.collectAsState()

    var urlInputText by remember { mutableStateOf(currentUrl) }
    val webViewMap = remember { mutableMapOf<String, WebView>() }
    val canGoBackMap = remember { mutableStateMapOf<String, Boolean>() }
    val canGoForwardMap = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(currentUrl) {
        urlInputText = currentUrl
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collectLatest { event ->
            val currentActiveId = viewModel.activeWebTabId.value
            val activeWebView = webViewMap[currentActiveId]
            when (event) {
                is UiEvent.NavigateWebView -> {
                    activeWebView?.loadUrl(event.url)
                }
                is UiEvent.TriggerExtraction -> {
                    if (activeWebView == null) {
                        viewModel.onExtractionFailed("Keine aktive Webseite geladen.")
                        return@collectLatest
                    }
                    try {
                        activeWebView.evaluateJavascript(ImageExtractorJs.EXTRACTION_SCRIPT) { resultJson ->
                            try {
                                if (!resultJson.isNullOrBlank() && resultJson != "null") {
                                    val result = ImageExtractorJs.parseExtractionJson(resultJson)
                                    if (result.images.isEmpty()) {
                                        viewModel.onExtractionFailed("Keine Manga-Bilder auf dieser Seite gefunden.")
                                    } else {
                                        viewModel.onImagesExtracted(result, activeWebView.url ?: currentUrl)
                                    }
                                } else {
                                    viewModel.onExtractionFailed("Keine Bilddaten auf dieser Seite gefunden.")
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                viewModel.onExtractionFailed("Fehler beim Verarbeiten der Bilddaten: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        viewModel.onExtractionFailed("JavaScript-Extraktion fehlgeschlagen: ${e.message}")
                    }
                }
                is UiEvent.GoBackWebView -> {
                    if (activeWebView != null && activeWebView.canGoBack()) {
                        activeWebView.goBack()
                    } else {
                        viewModel.setActiveTab(AppTab.LIBRARY)
                    }
                }
                else -> {}
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureAmoledBlack)
    ) {
        // --- 1. Multi-Tab Strip (GitHub BrowserView style: 152dp..220dp tabs + isolated 24dp close) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEach { tab ->
                key(tab.id) {
                    val isSelected = tab.id == activeTabId
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) ActiveTabBg else DarkCardBg,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) AccentPrimaryLight else Color(0xFF25203B)
                        ),
                        modifier = Modifier
                            .height(42.dp)
                            .widthIn(min = 152.dp, max = 220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { viewModel.selectTab(tab.id) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(start = 12.dp, end = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Language,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (isSelected) AccentPrimaryLight else Color(0xFF777096)
                            )

                            Spacer(modifier = Modifier.width(7.dp))

                            Text(
                                text = tab.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = BodyFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                color = if (isSelected) Color.White else TextSecondary,
                                modifier = Modifier.weight(1f)
                            )

                            if (tabs.size > 1) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.06f))
                                        .clickable {
                                            webViewMap.remove(tab.id)?.destroy()
                                            canGoBackMap.remove(tab.id)
                                            canGoForwardMap.remove(tab.id)
                                            viewModel.closeTab(tab.id)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Tab schließen",
                                        modifier = Modifier.size(14.dp),
                                        tint = Color(0xFF9C95BA)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                onClick = { viewModel.addNewTab() },
                shape = RoundedCornerShape(12.dp),
                color = DarkCardBg,
                border = BorderStroke(1.dp, Color(0xFF25203B)),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Neuen Browser-Tab öffnen",
                        tint = Color(0xFFC8C2E0),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // --- 2. Framed Toolbar Card (Nav buttons + Monospace URL field + Laden + Source Presets + Extract CTA) ---
        val activeWebView = webViewMap[activeTabId]
        val canBack = canGoBackMap[activeTabId] ?: (activeWebView?.canGoBack() == true)
        val canForward = canGoForwardMap[activeTabId] ?: (activeWebView?.canGoForward() == true)

        Surface(
            color = DarkSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, CardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: Nav Buttons + Rectangular URL Bar + Laden
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ToolbarIconSquareButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Im Tab zurück",
                        enabled = canBack,
                        onClick = { if (activeWebView?.canGoBack() == true) activeWebView.goBack() }
                    )
                    ToolbarIconSquareButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = "Im Tab vorwärts",
                        enabled = canForward,
                        onClick = { if (activeWebView?.canGoForward() == true) activeWebView.goForward() }
                    )
                    ToolbarIconSquareButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "Seite neu laden",
                        enabled = true,
                        onClick = { activeWebView?.reload() }
                    )

                    // Monospace URL Input Box
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkInputBg)
                            .border(1.dp, Color(0xFF2A2444), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (urlInputText.isEmpty()) {
                                Text(
                                    text = "https://... Kapitel-URL",
                                    fontFamily = MonoFontFamily,
                                    fontSize = 12.sp,
                                    color = Color(0xFF6E678C),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = urlInputText,
                                onValueChange = { urlInputText = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontFamily = MonoFontFamily,
                                    fontSize = 12.sp,
                                    color = Color.White
                                ),
                                cursorBrush = SolidColor(AccentPrimaryLight),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go
                                ),
                                keyboardActions = KeyboardActions(
                                    onGo = {
                                        viewModel.navigateToUrl(urlInputText)
                                        focusManager.clearFocus()
                                    }
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (urlInputText.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .clickable { urlInputText = "" },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Clear,
                                    contentDescription = "Leeren",
                                    tint = Color(0xFF8C85A8),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = clipboard.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val pasteText = clip.getItemAt(0).text?.toString() ?: ""
                                        if (pasteText.isNotBlank()) {
                                            urlInputText = pasteText
                                            viewModel.navigateToUrl(pasteText)
                                            focusManager.clearFocus()
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentPaste,
                                contentDescription = "Einfügen",
                                tint = AccentPrimaryLight,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            viewModel.navigateToUrl(urlInputText)
                            focusManager.clearFocus()
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1B1633),
                        border = BorderStroke(1.dp, Color(0xFF342C57)),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = "Laden",
                                fontFamily = BodyFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                color = Color(0xFFECE9F8)
                            )
                        }
                    }
                }

                // Row 2: Sleek Source Shortcuts + Primary Extract CTA
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val presets = listOf(
                            "Natomanga" to "https://www.natomanga.com",
                            "MangaDex" to "https://mangadex.org",
                            "Webtoons" to "https://www.webtoons.com"
                        )
                        presets.forEach { (name, url) ->
                            val selected = currentUrl.contains(name, ignoreCase = true)
                            Surface(
                                onClick = {
                                    urlInputText = url
                                    viewModel.navigateToUrl(url)
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) ActiveTabBg else Color(0xFF0D0B18),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) AccentPrimaryLight else Color(0xFF26213E)
                                ),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                ) {
                                    Text(
                                        text = name,
                                        fontFamily = BodyFontFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp,
                                        color = if (selected) Color.White else TextSecondary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            runCatching { CookieManager.getInstance().flush() }
                            viewModel.triggerExtraction()
                        },
                        enabled = !isExtracting,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = Color.White,
                            disabledContainerColor = AccentPrimary.copy(alpha = 0.6f),
                            disabledContentColor = Color.White.copy(alpha = 0.85f)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        if (isExtracting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Extrahiere…",
                                fontFamily = BodyFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesomeMotion,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Bilder extrahieren",
                                fontFamily = BodyFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = isWebLoading) {
            LinearProgressIndicator(
                progress = { webProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = AccentSecondary,
                trackColor = DarkSurface
            )
        }

        // --- 3. Multi-Tab WebView Pool (Full unobstructed viewport) ---
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            tabs.forEach { tab ->
                key(tab.id) {
                    val isCurrentTab = tab.id == activeTabId
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (isCurrentTab) {
                                    Modifier
                                } else {
                                    Modifier.graphicsLayer { alpha = 0f; translationX = -20000f }
                                }
                            )
                    ) {
                        AndroidView<View>(
                            factory = { ctx ->
                                runCatching {
                                    WebView(ctx).apply {
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )

                                        settings.apply {
                                            javaScriptEnabled = true
                                            domStorageEnabled = true
                                            databaseEnabled = true
                                            loadsImagesAutomatically = true
                                            useWideViewPort = true
                                            loadWithOverviewMode = true
                                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                            userAgentString = AppConstants.USER_AGENT
                                        }

                                        runCatching {
                                            CookieManager.getInstance().setAcceptCookie(true)
                                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                        }

                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                super.onPageStarted(view, url, favicon)
                                                canGoBackMap[tab.id] = view?.canGoBack() == true
                                                canGoForwardMap[tab.id] = view?.canGoForward() == true
                                                if (tab.id == viewModel.activeWebTabId.value) {
                                                    url?.let { viewModel.onWebPageStarted(it) }
                                                }
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                runCatching { CookieManager.getInstance().flush() }
                                                canGoBackMap[tab.id] = view?.canGoBack() == true
                                                canGoForwardMap[tab.id] = view?.canGoForward() == true
                                                val title = view?.title ?: ""
                                                if (tab.id == viewModel.activeWebTabId.value) {
                                                    url?.let { viewModel.onWebPageFinished(it, title) }
                                                }
                                            }
                                        }

                                        webChromeClient = object : WebChromeClient() {
                                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                super.onProgressChanged(view, newProgress)
                                                if (tab.id == viewModel.activeWebTabId.value) {
                                                    viewModel.onWebProgressChanged(newProgress)
                                                }
                                            }
                                        }

                                        loadUrl(tab.url)
                                        webViewMap[tab.id] = this
                                    }
                                }.getOrElse {
                                    TextView(ctx).apply {
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        setBackgroundColor(android.graphics.Color.BLACK)
                                        setTextColor(android.graphics.Color.parseColor("#A29BBE"))
                                        gravity = Gravity.CENTER
                                        text = "Android System WebView ist auf diesem Gerät aktuell nicht verfügbar."
                                    }
                                }
                            },
                            onRelease = { view ->
                                webViewMap.remove(tab.id)
                                canGoBackMap.remove(tab.id)
                                canGoForwardMap.remove(tab.id)
                                if (view is WebView) {
                                    runCatching {
                                        (view.parent as? ViewGroup)?.removeView(view)
                                        view.destroy()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewMap.values.forEach { wv ->
                runCatching {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                }
            }
            webViewMap.clear()
        }
    }
}

@Composable
private fun ToolbarIconSquareButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0D0B18),
        border = BorderStroke(1.dp, Color(0xFF26213E)),
        modifier = Modifier.size(38.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color(0xFFD2CCE8).copy(alpha = if (enabled) 1f else 0.35f),
                modifier = Modifier.size(17.dp)
            )
        }
    }
}
