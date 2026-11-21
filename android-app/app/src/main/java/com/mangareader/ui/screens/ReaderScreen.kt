package com.mangareader.ui.screens

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.BitmapDecodeGuard
import com.mangareader.CdnSecurityChallengeException
import com.mangareader.model.ChapterPage
import com.mangareader.model.ImageDiagnostic
import com.mangareader.model.ImageErrorKind
import com.mangareader.model.ReaderChapter
import com.mangareader.model.ReaderSettings
import com.mangareader.model.ReadingMode
import com.mangareader.ui.components.ImageErrorCard
import com.mangareader.ui.theme.AmoledPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException

private val AUTOSCROLL_SPEEDS = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f)
private val BACKOFF_DELAYS_MS = longArrayOf(400L, 800L, 1600L)

@Composable
fun ReaderScreen(
    chapter: ReaderChapter?,
    currentPageIndex: Int,
    isBookmarked: Boolean,
    settings: ReaderSettings,
    sessionVersion: Int,
    hasPrefetchedNext: Boolean,
    okHttpClient: OkHttpClient,
    onPageChanged: (Int) -> Unit,
    onToggleBookmark: () -> Unit,
    onRefreshSession: () -> Unit,
    onEvictSingleImageCache: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onOpenNextChapter: () -> Unit,
    onOpenSettings: () -> Unit,
    onUpdateAutoscroll: (enabled: Boolean, speed: Float) -> Unit,
    onNotify: (String) -> Unit,
) {
    if (chapter == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AmoledPalette.PureBlack)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = AmoledPalette.Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Kein Kapitel geöffnet",
                        color = AmoledPalette.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Wähle ein Lesezeichen aus der Bibliothek oder extrahiere ein Kapitel im Browser-Tab.",
                        color = AmoledPalette.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        return
    }

    // Bildschirm während des Lesens / Autoscrolls wach halten
    val view = LocalView.current
    DisposableEffect(view, settings.autoscrollEnabled) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    var zoomScale by remember(chapter.id) { mutableFloatStateOf(1.0f) }
    var autoscrollPaused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledPalette.PureBlack)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Kompakte Reader-Kopfzeile
            Surface(
                color = AmoledPalette.Surface,
                border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = chapter.title,
                            color = AmoledPalette.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${chapter.chapterLabel} · Seite ${currentPageIndex + 1} / ${chapter.pages.size}",
                            color = AmoledPalette.TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    IconButton(onClick = { zoomScale = (zoomScale - 0.25f).coerceAtLeast(1.0f) }) {
                        Icon(Icons.Outlined.ZoomOut, contentDescription = "Verkleinern", tint = AmoledPalette.TextSecondary)
                    }
                    IconButton(onClick = { zoomScale = (zoomScale + 0.25f).coerceAtMost(4.0f) }) {
                        Icon(Icons.Outlined.ZoomIn, contentDescription = "Vergrößern", tint = AmoledPalette.TextSecondary)
                    }
                    IconButton(onClick = onRefreshSession) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Sitzung erneuern", tint = AmoledPalette.TextSecondary)
                    }
                    IconButton(onClick = onToggleBookmark) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "Lesezeichen",
                            tint = if (isBookmarked) AmoledPalette.AccentSoft else AmoledPalette.TextSecondary,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Einstellungen", tint = AmoledPalette.TextSecondary)
                    }
                }
            }

            // Reader-Fläche (Webtoon Vertikal oder Manga Blätter-Modus)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(AmoledPalette.PureBlack)
            ) {
                if (settings.readingMode == ReadingMode.WEBTOON) {
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentPageIndex)

                    LaunchedEffect(listState) {
                        snapshotFlow { listState.firstVisibleItemIndex }
                            .distinctUntilChanged()
                            .collect { idx ->
                                if (idx in chapter.pages.indices) {
                                    onPageChanged(idx)
                                }
                            }
                    }

                    // Autoscroll-Schleife im Webtoon-Modus
                    LaunchedEffect(settings.autoscrollEnabled, settings.autoscrollSpeed, autoscrollPaused) {
                        if (!settings.autoscrollEnabled || autoscrollPaused) return@LaunchedEffect
                        while (isActive) {
                            listState.scrollBy(settings.autoscrollSpeed * 3.2f)
                            delay(16L)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AmoledPalette.PureBlack),
                    ) {
                        items(chapter.pages, key = { "${chapter.id}-${it.index}" }) { page ->
                            HardenedReaderPage(
                                page = page,
                                chapterUrl = chapter.chapterUrl,
                                sessionVersion = sessionVersion,
                                zoomScale = zoomScale,
                                okHttpClient = okHttpClient,
                                onEvictSingleImageCache = onEvictSingleImageCache,
                                onRefreshSession = onRefreshSession,
                                onOpenBrowser = { onOpenInBrowser(chapter.chapterUrl) },
                                onNotify = onNotify,
                            )
                        }
                        item(key = "chapter-end-footer") {
                            ChapterEndFooter(
                                chapter = chapter,
                                hasPrefetchedNext = hasPrefetchedNext,
                                onOpenNextChapter = onOpenNextChapter,
                                onOpenBrowser = { onOpenInBrowser(chapter.chapterUrl) },
                            )
                        }
                    }
                } else {
                    val pagerState = rememberPagerState(
                        initialPage = currentPageIndex.coerceIn(0, (chapter.pages.size - 1).coerceAtLeast(0)),
                        pageCount = { chapter.pages.size },
                    )

                    LaunchedEffect(pagerState) {
                        snapshotFlow { pagerState.currentPage }
                            .distinctUntilChanged()
                            .collect { onPageChanged(it) }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AmoledPalette.PureBlack)
                            .pointerInput(pagerState.currentPage, chapter.pages.size) {
                                detectTapGestures { offset ->
                                    val width = size.width.toFloat()
                                    if (width <= 0f) return@detectTapGestures
                                    val ratio = offset.x / width
                                    when {
                                        ratio < 0.22f && pagerState.currentPage > 0 -> {
                                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                        }
                                        ratio > 0.78f && pagerState.currentPage < chapter.pages.lastIndex -> {
                                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                        }
                                    }
                                }
                            },
                    ) { pageIdx ->
                        HardenedReaderPage(
                            page = chapter.pages[pageIdx],
                            chapterUrl = chapter.chapterUrl,
                            sessionVersion = sessionVersion,
                            zoomScale = zoomScale,
                            okHttpClient = okHttpClient,
                            onEvictSingleImageCache = onEvictSingleImageCache,
                            onRefreshSession = onRefreshSession,
                            onOpenBrowser = { onOpenInBrowser(chapter.chapterUrl) },
                            onNotify = onNotify,
                        )
                    }
                }

                // Schwebende Autoscroll-Steuerung (Floating Pill)
                if (settings.readingMode == ReadingMode.WEBTOON && settings.autoscrollEnabled) {
                    Surface(
                        color = AmoledPalette.Surface.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, AmoledPalette.AccentSoft.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            IconButton(onClick = { autoscrollPaused = !autoscrollPaused }) {
                                Icon(
                                    imageVector = if (autoscrollPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                                    contentDescription = if (autoscrollPaused) "Fortsetzen" else "Pausieren",
                                    tint = AmoledPalette.AccentSoft,
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    val idx = AUTOSCROLL_SPEEDS.indexOfFirst { it == settings.autoscrollSpeed }
                                    val nextSpeed = AUTOSCROLL_SPEEDS[(idx + 1) % AUTOSCROLL_SPEEDS.size]
                                    onUpdateAutoscroll(true, nextSpeed)
                                },
                                border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "${settings.autoscrollSpeed}x",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = AmoledPalette.TextPrimary,
                                )
                            }

                            IconButton(onClick = {
                                autoscrollPaused = false
                                onUpdateAutoscroll(false, settings.autoscrollSpeed)
                            }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Autoscroll beenden", tint = AmoledPalette.TextSecondary)
                            }
                        }
                    }
                }
            }
        }

        // Software-Dimmer Overlay (nicht-blockierend)
        if (settings.dimmerEnabled && settings.dimmerStrength > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = settings.dimmerStrength.coerceIn(0f, 0.80f)))
            )
        }

        // Sepia / Blaulichtfilter Overlay (nicht-blockierend)
        if (settings.sepiaEnabled && settings.sepiaStrength > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFF9800).copy(alpha = (settings.sepiaStrength * 0.35f).coerceIn(0f, 0.30f)))
            )
        }
    }
}

private sealed interface PageLoadResult {
    data object Loading : PageLoadResult
    data class Success(val bitmap: Bitmap) : PageLoadResult
    data class Failure(val diagnostic: ImageDiagnostic) : PageLoadResult
}

@Composable
private fun HardenedReaderPage(
    page: ChapterPage,
    chapterUrl: String,
    sessionVersion: Int,
    zoomScale: Float,
    okHttpClient: OkHttpClient,
    onEvictSingleImageCache: (String) -> Unit,
    onRefreshSession: () -> Unit,
    onOpenBrowser: () -> Unit,
    onNotify: (String) -> Unit,
) {
    var retryNonce by remember(page.url) { mutableIntStateOf(0) }
    var bypassCacheNonce by remember(page.url) { mutableIntStateOf(0) }
    var result by remember(page.url) { mutableStateOf<PageLoadResult>(PageLoadResult.Loading) }

    LaunchedEffect(page.url, sessionVersion, retryNonce, bypassCacheNonce) {
        result = PageLoadResult.Loading
        val forceBypass = bypassCacheNonce > 0 || sessionVersion > 0
        if (bypassCacheNonce > 0) {
            onEvictSingleImageCache(page.url)
        }

        result = loadAndDecodePageWithBackoff(
            okHttpClient = okHttpClient,
            page = page,
            refererUrl = chapterUrl,
            forceBypassCache = forceBypass,
            onRetryNotify = onNotify,
        )
    }

    when (val state = result) {
        is PageLoadResult.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp)
                    .background(AmoledPalette.PureBlack),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = AmoledPalette.AccentSoft,
                        trackColor = AmoledPalette.SurfaceElevated,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (bypassCacheNonce > 0) "Seite ${page.index + 1} · Cache-Bypass aktiv..." else "Seite ${page.index + 1} wird geladen...",
                        color = AmoledPalette.TextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        is PageLoadResult.Success -> {
            Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = page.label,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AmoledPalette.PureBlack)
                    .graphicsLayer(
                        scaleX = zoomScale,
                        scaleY = zoomScale,
                    ),
            )
        }

        is PageLoadResult.Failure -> {
            ImageErrorCard(
                pageIndex = page.index,
                imageUrl = page.url,
                diagnostic = state.diagnostic,
                onRetry = { retryNonce++ },
                onCacheBypass = {
                    bypassCacheNonce++
                    onNotify("Seite ${page.index + 1}: Cache-Bypass aktiv — lade frisch vom Server...")
                },
                onRefreshSession = onRefreshSession,
                onOpenBrowser = onOpenBrowser,
                onCopiedUrl = { onNotify("Bild-URL in Zwischenablage kopiert.") },
            )
        }
    }
}

private suspend fun loadAndDecodePageWithBackoff(
    okHttpClient: OkHttpClient,
    page: ChapterPage,
    refererUrl: String,
    forceBypassCache: Boolean,
    onRetryNotify: (String) -> Unit,
): PageLoadResult = withContext(Dispatchers.IO) {
    for (attempt in 0..BACKOFF_DELAYS_MS.size) {
        try {
            val reqBuilder = Request.Builder()
                .url(page.url)
                .header("Referer", refererUrl)

            if (forceBypassCache) {
                reqBuilder
                    .cacheControl(CacheControl.Builder().noCache().noStore().build())
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
            }

            okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val diagHeader = response.header("X-Manga-Diagnostic").orEmpty()
                    val receivedType = response.header("X-Manga-Received-Content-Type")
                    val diagnostic = classifyHttpFailure(response.code, diagHeader, receivedType)

                    if (diagnostic.shouldAutoRetry && attempt < BACKOFF_DELAYS_MS.size) {
                        val waitMs = BACKOFF_DELAYS_MS[attempt]
                        withContext(Dispatchers.Main) {
                            onRetryNotify("Seite ${page.index + 1}: HTTP ${response.code} — Retry ${attempt + 1}/3 in $waitMs ms...")
                        }
                        delay(waitMs)
                        return@use
                    }
                    return@withContext PageLoadResult.Failure(diagnostic)
                }

                val contentType = response.header("Content-Type")
                val bytes = response.body?.bytes() ?: ByteArray(0)

                // Frühe Null-Bitmap- & Content-Type-Prüfung (text/html & application/xml -> sofort Open in Browser)
                val bitmap = try {
                    BitmapDecodeGuard.decodeOrDiagnose(
                        imageUrl = page.url,
                        bytes = bytes,
                        headerContentType = contentType,
                    )
                } catch (challenge: CdnSecurityChallengeException) {
                    Log.w("ReaderScreen", "Security/CDN challenge for page ${page.index + 1}: ${challenge.receivedContentType}")
                    return@withContext PageLoadResult.Failure(
                        buildChallengeDiagnostic(challenge.receivedContentType, response.code)
                    )
                }

                if (bitmap == null) {
                    return@withContext PageLoadResult.Failure(
                        ImageDiagnostic(
                            kind = ImageErrorKind.DECODE_ERROR,
                            httpStatus = response.code,
                            receivedContentType = contentType?.substringBefore(';')?.trim(),
                            title = "Bild konnte nicht dekodiert werden (Null Bitmap)",
                            description = "Der Bild-Decoder (BitmapFactory) erhielt beschädigte oder unvollständige Bilddaten — häufig durch einen defekten Cache-Eintrag.",
                            suggestion = "Nutze „Cache-Bypass für dieses Bild“, um diese Datei ohne Cache frisch vom Server zu laden.",
                            shouldAutoRetry = false,
                            suggestCacheBypass = true,
                            suggestRefreshSession = true,
                            suggestOpenBrowser = true,
                        )
                    )
                }

                return@withContext PageLoadResult.Success(bitmap)
            }
        } catch (timeout: SocketTimeoutException) {
            if (attempt < BACKOFF_DELAYS_MS.size) {
                delay(BACKOFF_DELAYS_MS[attempt])
                continue
            }
            return@withContext PageLoadResult.Failure(
                ImageDiagnostic(
                    kind = ImageErrorKind.TIMEOUT,
                    title = "Zeitüberschreitung beim Bildabruf",
                    description = "Der Bildserver hat nicht innerhalb des Zeitlimits (15s/20s) geantwortet.",
                    suggestion = "Prüfe deine Verbindung oder versuche es erneut.",
                    shouldAutoRetry = false,
                    suggestOpenBrowser = true,
                )
            )
        } catch (e: Exception) {
            return@withContext PageLoadResult.Failure(
                ImageDiagnostic(
                    kind = ImageErrorKind.UNKNOWN,
                    title = "Netzwerk- oder Ladefehler",
                    description = e.localizedMessage ?: "Das Bild konnte nicht geladen werden.",
                    suggestion = "Versuche es erneut oder öffne die Seite im Browser.",
                    shouldAutoRetry = false,
                    suggestOpenBrowser = true,
                )
            )
        }
    }

    PageLoadResult.Failure(
        ImageDiagnostic(
            kind = ImageErrorKind.UNKNOWN,
            title = "Bild konnte nach mehreren Versuchen nicht geladen werden",
            description = "Alle automatischen Wiederholungsversuche sind fehlgeschlagen.",
            suggestion = "Öffne die Seite im Browser oder nutze den Cache-Bypass.",
            shouldAutoRetry = false,
            suggestOpenBrowser = true,
            suggestCacheBypass = true,
        )
    )
}

private fun buildChallengeDiagnostic(receivedContentType: String, httpStatus: Int?): ImageDiagnostic {
    val isXml = receivedContentType.contains("xml", ignoreCase = true)
    return ImageDiagnostic(
        kind = ImageErrorKind.HTML_CHALLENGE,
        httpStatus = httpStatus,
        receivedContentType = receivedContentType,
        title = if (isXml) {
            "Sicherheits-/CDN-Prüfung ($receivedContentType erkannt)"
        } else {
            "Sicherheits-/CDN-Prüfung ($receivedContentType erkannt)"
        },
        description = if (isXml) {
            "Statt eines Bildes (image/*) lieferte der Server eine XML-Antwort ($receivedContentType, z. B. CDN-/AccessDenied-Challenge), wodurch der Bild-Decoder abgebrochen wurde."
        } else {
            "Statt eines Bildes (image/*) lieferte der Server eine HTML-Sicherheitsseite ($receivedContentType, z. B. Cloudflare-/WAF-Prüfung)."
        },
        suggestion = "Öffne die Seite direkt im Browser, um die Sicherheits-/CDN-Prüfung abzuschließen und frische Cookies zu erhalten.",
        shouldAutoRetry = false,
        suggestOpenBrowser = true,
        suggestRefreshSession = true,
        suggestCacheBypass = true,
    )
}

private fun classifyHttpFailure(
    status: Int,
    diagHeader: String,
    receivedContentType: String?,
): ImageDiagnostic {
    if (
        diagHeader.equals("HTML_CHALLENGE", ignoreCase = true) ||
        diagHeader.equals("XML_CHALLENGE", ignoreCase = true) ||
        status == 403
    ) {
        val detected = receivedContentType?.takeIf { it.isNotBlank() }
            ?: if (diagHeader.equals("XML_CHALLENGE", ignoreCase = true)) "application/xml" else "text/html"
        return buildChallengeDiagnostic(detected, status)
    }
    return when (status) {
        404 -> ImageDiagnostic(
            kind = ImageErrorKind.HTTP_404,
            httpStatus = 404,
            title = "Bild nicht gefunden (HTTP 404)",
            description = "Die Bilddatei existiert nicht auf dem Server oder wurde verschoben.",
            suggestion = "Öffne die Kapitel-Seite im Browser, um zu prüfen, ob die Quelle aktualisiert wurde.",
            shouldAutoRetry = false,
            suggestOpenBrowser = true,
        )
        429 -> ImageDiagnostic(
            kind = ImageErrorKind.HTTP_429,
            httpStatus = 429,
            title = "Rate-Limit erreicht (HTTP 429)",
            description = "Zu viele parallele Anfragen an den Bildserver.",
            suggestion = "Warte kurz und lade die Seite danach erneut.",
            shouldAutoRetry = true,
        )
        in 500..504 -> ImageDiagnostic(
            kind = ImageErrorKind.HTTP_5XX,
            httpStatus = status,
            title = "Server vorübergehend nicht erreichbar (HTTP $status)",
            description = "Der Bild-CDN-Knoten meldet einen temporären Serverfehler.",
            suggestion = "Versuche es in wenigen Sekunden erneut.",
            shouldAutoRetry = true,
        )
        else -> ImageDiagnostic(
            kind = ImageErrorKind.UNKNOWN,
            httpStatus = status,
            title = "HTTP-Fehler $status",
            description = "Die Bilddatei konnte nicht geladen werden.",
            suggestion = "Nutze „Erneut versuchen“ oder öffne die Quelle im Browser.",
            shouldAutoRetry = false,
            suggestOpenBrowser = true,
        )
    }
}

@Composable
private fun ChapterEndFooter(
    chapter: ReaderChapter,
    hasPrefetchedNext: Boolean,
    onOpenNextChapter: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    Surface(
        color = AmoledPalette.Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Ende von ${chapter.chapterLabel}",
                color = AmoledPalette.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (chapter.nextChapterUrl != null) {
                Text(
                    text = if (hasPrefetchedNext) {
                        "Folgekapitel wurde im Hintergrund vorgeladen und startet sofort."
                    } else {
                        "Nächstes Kapitel erkannt."
                    },
                    color = if (hasPrefetchedNext) AmoledPalette.AccentCyan else AmoledPalette.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onOpenNextChapter,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmoledPalette.AccentPrimary,
                        contentColor = AmoledPalette.TextPrimary,
                    ),
                ) {
                    Text(if (hasPrefetchedNext) "Nächstes Kapitel (vorgeladen)" else "Nächstes Kapitel laden")
                }
            } else {
                Text(
                    text = "Kein automatisches Folgekapitel erkannt.",
                    color = AmoledPalette.TextMuted,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onOpenBrowser,
                    border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
                ) {
                    Text("Im Browser weiterblättern")
                }
            }
        }
    }
}
