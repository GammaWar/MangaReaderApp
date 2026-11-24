package com.mangareader.app.ui.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.mangareader.app.MangaReaderApplication
import com.mangareader.app.ui.components.ImageErrorView
import com.mangareader.app.ui.theme.AccentPrimaryLight
import com.mangareader.app.ui.theme.MonoFontFamily
import com.mangareader.app.ui.theme.PureAmoledBlack
import java.io.File

/**
 * Flicker-free Coil manga page renderer on a pure AMOLED (#000000) canvas.
 *
 * Uses stable explicit memory/disk cache keys so prefetched pages render instantaneously
 * and 1-click single-image cache bypass reliably evicts & overwrites poisoned entries.
 */
@Composable
fun ReaderMangaPage(
    pageIndex: Int,
    imageUrl: String,
    chapterUrl: String,
    isOffline: Boolean,
    sessionRefreshKey: Int,
    contentScale: ContentScale,
    loadingHeightDp: Int?,
    onInvalidateSingleImageCache: (imageUrl: String, pageIndex: Int, onDone: () -> Unit) -> Unit,
    onBypassSucceeded: (pageIndex: Int) -> Unit,
    onRefreshSession: () -> Unit,
    onOpenInBrowser: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var retryKey by remember(imageUrl) { mutableIntStateOf(0) }
    var bypassNonce by remember(imageUrl) { mutableIntStateOf(0) }
    var pendingBypassNotify by remember(imageUrl) { mutableStateOf(false) }

    val imageModel = remember(imageUrl, chapterUrl, isOffline, retryKey, bypassNonce, sessionRefreshKey) {
        if (isOffline) {
            File(imageUrl)
        } else {
            val attemptNonce = retryKey + bypassNonce * 1000 + sessionRefreshKey * 10_000
            val forceFresh = attemptNonce > 0
            ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(false) // Flackerfreies Rendering ohne Aufblitzen
                .setHeader("Referer", chapterUrl)
                .setParameter(MangaReaderApplication.PARAM_IMAGE_URL, imageUrl, memoryCacheKey = null)
                .setParameter("attempt_nonce", attemptNonce, memoryCacheKey = null)
                .diskCacheKey(imageUrl)
                .memoryCacheKey(if (forceFresh) "$imageUrl#$attemptNonce" else imageUrl)
                .apply {
                    if (bypassNonce > 0 || sessionRefreshKey > 0) {
                        setHeader(MangaReaderApplication.HEADER_BYPASS_CACHE, "1")
                    }
                    if (forceFresh) {
                        diskCachePolicy(CachePolicy.WRITE_ONLY)
                        memoryCachePolicy(CachePolicy.WRITE_ONLY)
                    } else {
                        diskCachePolicy(CachePolicy.ENABLED)
                        memoryCachePolicy(CachePolicy.ENABLED)
                    }
                }
                .build()
        }
    }

    SubcomposeAsyncImage(
        model = imageModel,
        contentDescription = "Seite ${pageIndex + 1}",
        modifier = modifier,
        contentScale = contentScale,
        onSuccess = {
            if (pendingBypassNotify) {
                pendingBypassNotify = false
                onBypassSucceeded(pageIndex)
            }
        },
        onError = {
            pendingBypassNotify = false
        },
        loading = {
            val boxModifier = if (loadingHeightDp != null) {
                Modifier
                    .fillMaxWidth()
                    .height(loadingHeightDp.dp)
                    .background(PureAmoledBlack)
            } else {
                Modifier
                    .fillMaxSize()
                    .background(PureAmoledBlack)
            }
            Box(
                modifier = boxModifier,
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = AccentPrimaryLight,
                        trackColor = Color(0xFF2A2444),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (bypassNonce > 0) {
                            "Seite ${pageIndex + 1} · Cache-Bypass aktiv…"
                        } else {
                            "Seite ${pageIndex + 1} wird geladen…"
                        },
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = Color(0xFF8D86A8)
                    )
                }
            }
        },
        error = { state ->
            Box(
                modifier = (if (loadingHeightDp != null) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                    .background(PureAmoledBlack),
                contentAlignment = Alignment.Center
            ) {
                ImageErrorView(
                    pageIndex = pageIndex,
                    imageUrl = imageUrl,
                    throwable = state.result.throwable,
                    onRetry = { retryKey++ },
                    onCacheBypass = {
                        pendingBypassNotify = true
                        onInvalidateSingleImageCache(imageUrl, pageIndex) {
                            bypassNonce++
                        }
                    },
                    onRefreshSession = onRefreshSession,
                    onOpenInBrowser = onOpenInBrowser,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    )
}
