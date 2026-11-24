package com.mangareader.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.mangareader.app.data.cache.ChapterCacheManager
import com.mangareader.app.data.model.AppTab
import com.mangareader.app.data.model.BookmarkItem
import com.mangareader.app.data.model.DownloadedChapter
import com.mangareader.app.ui.theme.AccentPrimary
import com.mangareader.app.ui.theme.AccentPrimaryLight
import com.mangareader.app.ui.theme.BodyFontFamily
import com.mangareader.app.ui.theme.ButtonBorder
import com.mangareader.app.ui.theme.CardBorder
import com.mangareader.app.ui.theme.DangerBorder
import com.mangareader.app.ui.theme.DangerSurface
import com.mangareader.app.ui.theme.DangerText
import com.mangareader.app.ui.theme.DarkBackground
import com.mangareader.app.ui.theme.DarkCardBg
import com.mangareader.app.ui.theme.DarkInputBg
import com.mangareader.app.ui.theme.DarkSurface
import com.mangareader.app.ui.theme.DarkSurfaceVariant
import com.mangareader.app.ui.theme.DisplayFontFamily
import com.mangareader.app.ui.theme.ErrorColor
import com.mangareader.app.ui.theme.HairlineBorder
import com.mangareader.app.ui.theme.MonoFontFamily
import com.mangareader.app.ui.theme.PureAmoledBlack
import com.mangareader.app.ui.theme.TextPrimary
import com.mangareader.app.ui.theme.TextSecondary
import com.mangareader.app.ui.theme.TextTertiary
import com.mangareader.app.ui.theme.WarningAmber
import com.mangareader.app.ui.viewmodel.MangaViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatRelativeTimestamp(timestamp: Long): String {
    val diffMin = ((System.currentTimeMillis() - timestamp) / 60_000L).coerceAtLeast(0L)
    return when {
        diffMin < 1L -> "Gerade eben"
        diffMin < 60L -> "Vor $diffMin Min."
        diffMin < 24L * 60L -> "Vor ${diffMin / 60L} Std."
        else -> SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(timestamp))
    }
}

@Composable
fun LibraryScreen(
    viewModel: MangaViewModel,
    modifier: Modifier = Modifier
) {
    val bookmarks by viewModel.bookmarks.collectAsState()
    val history by viewModel.history.collectAsState()
    val downloads by viewModel.downloadedChapters.collectAsState()
    val preferences by viewModel.readerPreferences.collectAsState()
    val cacheStats by viewModel.cacheStats.collectAsState()
    val cacheLimitMb by viewModel.cacheLimitMb.collectAsState()
    val isCacheBusy by viewModel.isCacheBusy.collectAsState()
    val isExtracting by viewModel.isExtracting.collectAsState()

    val focusManager = LocalFocusManager.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var quickUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.refreshCacheStats()
        viewModel.refreshDownloads()
    }

    val limitBytes = ChapterCacheManager.mbToBytes(cacheLimitMb)
    val cacheBarColor = when {
        cacheStats.usagePercent >= 90f -> ErrorColor
        cacheStats.usagePercent >= 75f -> WarningAmber
        else -> AccentPrimaryLight
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureAmoledBlack)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Header + Segmented Controls + Image Cache Indicator + Quick-URL Row
            item(key = "library-header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Bibliothek",
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            color = Color.White
                        )
                        Text(
                            text = "Lesezeichen, chronologischer Leseverlauf und Offline-Kapitel.",
                            fontFamily = BodyFontFamily,
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    }

                    // 3-Column Segmented Control (zero pill badges)
                    Surface(
                        color = DarkSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF241F3A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            LibrarySegmentButton(
                                selected = selectedTab == 0,
                                icon = Icons.Outlined.Bookmarks,
                                label = "Lesezeichen",
                                count = bookmarks.size,
                                onClick = { selectedTab = 0 },
                                modifier = Modifier.weight(1f)
                            )
                            LibrarySegmentButton(
                                selected = selectedTab == 1,
                                icon = Icons.Outlined.History,
                                label = "Verlauf",
                                count = history.size,
                                onClick = { selectedTab = 1 },
                                modifier = Modifier.weight(1f)
                            )
                            LibrarySegmentButton(
                                selected = selectedTab == 2,
                                icon = Icons.Outlined.DownloadDone,
                                label = "Offline",
                                count = downloads.size,
                                onClick = {
                                    selectedTab = 2
                                    viewModel.refreshDownloads()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Compact Image Cache Storage Indicator
                    Surface(
                        color = DarkCardBg,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, HairlineBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Storage,
                                        contentDescription = null,
                                        tint = AccentPrimaryLight,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Bild-Cache",
                                        fontFamily = BodyFontFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = " · ${ChapterCacheManager.formatBytesDe(cacheStats.totalBytes)} / ${ChapterCacheManager.formatBytesDe(limitBytes)}",
                                        fontFamily = MonoFontFamily,
                                        fontSize = 11.sp,
                                        color = Color(0xFFCFCBE2),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                val pctStr = String.format(Locale.US, "%.1f", cacheStats.usagePercent).replace('.', ',')
                                val pctColor = when {
                                    cacheStats.usagePercent >= 90f -> DangerText
                                    cacheStats.usagePercent >= 75f -> Color(0xFFFCD34D)
                                    else -> Color(0xFF9C95BA)
                                }
                                Text(
                                    text = "$pctStr % · ${cacheStats.fileCount} ${if (cacheStats.fileCount == 1) "Datei" else "Dateien"}",
                                    fontFamily = MonoFontFamily,
                                    fontSize = 11.sp,
                                    color = pctColor
                                )
                            }

                            LinearProgressIndicator(
                                progress = { (cacheStats.usagePercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = cacheBarColor,
                                trackColor = Color(0xFF241F3A)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (cacheStats.usagePercent >= 90f) {
                                        "Limit fast erreicht"
                                    } else {
                                        "Limit: ${ChapterCacheManager.formatCacheLimitLabel(cacheLimitMb)} · Manuell"
                                    },
                                    fontFamily = BodyFontFamily,
                                    fontSize = 11.sp,
                                    color = if (cacheStats.usagePercent >= 90f) DangerText else Color(0xFF9C95BA)
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        onClick = { viewModel.setSettingsOpen(true) },
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF18142C),
                                        border = BorderStroke(1.dp, Color(0xFF2C2647)),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            modifier = Modifier.padding(horizontal = 10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Tune,
                                                contentDescription = null,
                                                tint = Color(0xFFD8D1F5),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "Limit ändern",
                                                fontFamily = BodyFontFamily,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 12.sp,
                                                color = Color(0xFFD8D1F5)
                                            )
                                        }
                                    }

                                    val canClear = !isCacheBusy && cacheStats.totalBytes > 0L
                                    Surface(
                                        onClick = { if (canClear) viewModel.clearCacheManually() },
                                        enabled = canClear,
                                        shape = RoundedCornerShape(8.dp),
                                        color = DangerSurface.copy(alpha = if (canClear) 1f else 0.45f),
                                        border = BorderStroke(1.dp, DangerBorder.copy(alpha = if (canClear) 0.85f else 0.35f)),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            modifier = Modifier.padding(horizontal = 10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.DeleteOutline,
                                                contentDescription = null,
                                                tint = DangerText.copy(alpha = if (canClear) 1f else 0.45f),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "Leeren",
                                                fontFamily = BodyFontFamily,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 12.sp,
                                                color = DangerText.copy(alpha = if (canClear) 1f else 0.45f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Compact Direct Chapter-URL Quick Entry
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkInputBg)
                                .border(1.dp, Color(0xFF2A2444), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (quickUrl.isEmpty()) {
                                Text(
                                    text = "Kapitel-URL direkt öffnen (https://...)",
                                    fontFamily = MonoFontFamily,
                                    fontSize = 12.sp,
                                    color = Color(0xFF6E678C),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = quickUrl,
                                onValueChange = { quickUrl = it },
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
                                        if (quickUrl.isNotBlank() && !isExtracting) {
                                            focusManager.clearFocus()
                                            viewModel.openDirectUrlAndExtract(quickUrl)
                                            quickUrl = ""
                                        }
                                    }
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.openDirectUrlAndExtract(quickUrl)
                                quickUrl = ""
                            },
                            enabled = quickUrl.isNotBlank() && !isExtracting,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPrimary,
                                contentColor = Color.White,
                                disabledContainerColor = AccentPrimary.copy(alpha = 0.45f),
                                disabledContentColor = Color.White.copy(alpha = 0.65f)
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Text(
                                text = if (isExtracting) "Lädt..." else "Lesen",
                                fontFamily = BodyFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = HairlineBorder)
            }

            // History Clear Action & Disabled Notice
            if (selectedTab == 1 && !preferences.isHistoryTrackingEnabled) {
                item(key = "history-disabled-notice") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1C150C),
                        border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = "Der automatische Leseverlauf ist in den Einstellungen deaktiviert. Neue Kapitel werden aktuell nicht protokolliert.",
                            fontFamily = BodyFontFamily,
                            fontSize = 13.sp,
                            color = Color(0xFFFEF3C7),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            if (selectedTab == 1 && history.isNotEmpty()) {
                item(key = "history-clear-row") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Surface(
                            onClick = { viewModel.clearHistory() },
                            shape = RoundedCornerShape(10.dp),
                            color = DangerSurface,
                            border = BorderStroke(1.dp, DangerBorder),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = null,
                                    tint = DangerText,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Verlauf leeren",
                                    fontFamily = BodyFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = DangerText
                                )
                            }
                        }
                    }
                }
            }

            // Tab Content
            when (selectedTab) {
                0 -> {
                    if (bookmarks.isEmpty()) {
                        item(key = "empty-bookmarks") {
                            LibraryEmptyStateCard(
                                icon = Icons.Outlined.Bookmarks,
                                title = "Keine Lesezeichen gespeichert",
                                message = "Tippe im Reader oben rechts auf das Lesezeichen-Symbol, um Kapitel samt exakter Seitenzahl zu merken.",
                                ctaLabel = "Browser öffnen",
                                onCtaClick = { viewModel.setActiveTab(AppTab.BROWSER) }
                            )
                        }
                    } else {
                        items(bookmarks, key = { "bm-${it.id}" }) { item ->
                            LibraryItemCard(
                                item = item,
                                onOpen = { viewModel.openLibraryItem(item) },
                                onDelete = { viewModel.deleteLibraryItem(item.id) }
                            )
                        }
                    }
                }

                1 -> {
                    if (history.isEmpty()) {
                        item(key = "empty-history") {
                            LibraryEmptyStateCard(
                                icon = Icons.Outlined.History,
                                title = "Dein Leseverlauf ist leer",
                                message = "Sobald du ein Kapitel im Reader öffnest, wird dein Fortschritt hier automatisch erfasst.",
                                ctaLabel = "Browser öffnen",
                                onCtaClick = { viewModel.setActiveTab(AppTab.BROWSER) }
                            )
                        }
                    } else {
                        items(history, key = { "hist-${it.id}" }) { item ->
                            LibraryItemCard(
                                item = item,
                                onOpen = { viewModel.openLibraryItem(item) },
                                onDelete = { viewModel.deleteLibraryItem(item.id) }
                            )
                        }
                    }
                }

                else -> {
                    if (downloads.isEmpty()) {
                        item(key = "empty-downloads") {
                            LibraryEmptyStateCard(
                                icon = Icons.Outlined.DownloadDone,
                                title = "Noch keine Offline-Kapitel gespeichert",
                                message = "Öffne ein Kapitel im Reader und tippe auf das Download-Symbol, um alle Seiten lokal abzulegen.",
                                ctaLabel = "Im Browser stöbern",
                                onCtaClick = { viewModel.setActiveTab(AppTab.BROWSER) }
                            )
                        }
                    } else {
                        items(downloads, key = { "dl-${it.id}" }) { chapter ->
                            DownloadChapterCard(
                                chapter = chapter,
                                onRead = { viewModel.openDownloadedChapter(chapter) },
                                onDelete = { viewModel.deleteDownloadedChapter(chapter.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySegmentButton(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) AccentPrimary else Color.Transparent,
        modifier = modifier.height(38.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else Color(0xFFA7A0C4),
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = label,
                fontFamily = BodyFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                color = if (selected) Color.White else Color(0xFFA7A0C4),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = "($count)",
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
                color = if (selected) Color.White.copy(alpha = 0.85f) else Color(0xFF8C85A8),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun LibraryItemCard(
    item: BookmarkItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val formattedDate = remember(item.timestamp) { formatRelativeTimestamp(item.timestamp) }
    val progress = if (item.totalPages > 0) {
        ((item.lastReadPage + 1).toFloat() / item.totalPages.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = DarkSurface,
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            CoverThumbnail(coverPathOrUrl = item.coverImageUrl)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.title.ifBlank { item.chapterTitle },
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.chapterTitle,
                        fontFamily = BodyFontFamily,
                        fontSize = 13.sp,
                        color = Color(0xFFCFCBE2),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (item.totalPages > 0) {
                            "Seite ${item.lastReadPage + 1} von ${item.totalPages} · $formattedDate"
                        } else {
                            formattedDate
                        },
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = Color(0xFF9C95BA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (item.totalPages > 0) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = AccentPrimaryLight,
                        trackColor = Color(0xFF241F3A)
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onOpen,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Weiterlesen",
                            fontFamily = BodyFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }

                    Surface(
                        onClick = onDelete,
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, ButtonBorder),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Entfernen",
                                tint = Color(0xFF9E97BC),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadChapterCard(
    chapter: DownloadedChapter,
    onRead: () -> Unit,
    onDelete: () -> Unit
) {
    val coverPath = remember(chapter.imageFilePaths) { chapter.imageFilePaths.firstOrNull() }
    val dateStr = remember(chapter.downloadTimestamp) {
        SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(chapter.downloadTimestamp))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onRead),
        shape = RoundedCornerShape(16.dp),
        color = DarkSurface,
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            CoverThumbnail(coverPathOrUrl = coverPath)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = chapter.title,
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${chapter.totalPages} Seiten · Vollständig offline · $dateStr",
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = Color(0xFF9C95BA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = chapter.id,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onRead,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Offline lesen",
                            fontFamily = BodyFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }

                    Surface(
                        onClick = onDelete,
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, ButtonBorder),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Download löschen",
                                tint = Color(0xFF9E97BC),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverThumbnail(coverPathOrUrl: String?) {
    Box(
        modifier = Modifier
            .size(width = 76.dp, height = 108.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceVariant)
            .border(1.dp, Color(0xFF2A2444), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (!coverPathOrUrl.isNullOrBlank()) {
            val model = remember(coverPathOrUrl) {
                if (coverPathOrUrl.startsWith("/")) File(coverPathOrUrl) else coverPathOrUrl
            }
            SubcomposeAsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = AccentPrimaryLight
                        )
                    }
                },
                error = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun LibraryEmptyStateCard(
    icon: ImageVector,
    title: String,
    message: String,
    ctaLabel: String,
    onCtaClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        shape = RoundedCornerShape(16.dp),
        color = DarkCardBg,
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(38.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                fontFamily = BodyFontFamily,
                fontSize = 13.sp,
                color = Color(0xFF9D96BA),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = onCtaClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentPrimary,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                modifier = Modifier.height(42.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Language,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = ctaLabel,
                    fontFamily = BodyFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
            }
        }
    }
}
