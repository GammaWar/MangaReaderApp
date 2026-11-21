package com.mangareader.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.CacheUsageSnapshot
import com.mangareader.formatBytesDe
import com.mangareader.model.BookmarkItem
import com.mangareader.model.HistoryItem
import com.mangareader.ui.theme.AmoledPalette
import java.util.Locale

@Composable
fun LibraryScreen(
    bookmarks: List<BookmarkItem>,
    history: List<HistoryItem>,
    cacheUsage: CacheUsageSnapshot,
    isExtracting: Boolean,
    onOpenChapterUrl: (url: String, pageIndex: Int) -> Unit,
    onOpenInBrowser: (url: String) -> Unit,
    onRemoveBookmark: (id: String) -> Unit,
    onRemoveHistory: (id: String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearCache: () -> Unit,
) {
    var selectedSubTab by remember { mutableIntStateOf(0) }
    var quickUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledPalette.Background)
    ) {
        // Header + Bild-Cache-Speicherleiste
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = "Bibliothek",
                color = AmoledPalette.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Lesezeichen, Leseverlauf und lokaler Bild-Cache.",
                color = AmoledPalette.TextSecondary,
                fontSize = 13.sp,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Visueller Speicher-Indikator (Progress-Bar)
            Surface(
                color = AmoledPalette.Surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Storage,
                                contentDescription = null,
                                tint = AmoledPalette.AccentSoft,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Bild-Cache · ${formatBytesDe(cacheUsage.totalBytes)} / ${formatBytesDe(cacheUsage.maxBytes)}",
                                color = AmoledPalette.TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text(
                            text = String.format(
                                Locale.GERMANY,
                                "%.1f %% · %d Dateien",
                                cacheUsage.usagePercent,
                                cacheUsage.fileCount
                            ),
                            color = AmoledPalette.TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val barColor = when {
                        cacheUsage.usagePercent >= 90.0 -> AmoledPalette.DangerText
                        cacheUsage.usagePercent >= 75.0 -> AmoledPalette.WarningText
                        else -> AmoledPalette.AccentSoft
                    }

                    LinearProgressIndicator(
                        progress = { cacheUsage.usageFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = barColor,
                        trackColor = AmoledPalette.SurfaceElevated,
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onOpenSettings,
                            border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Limit ändern", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onClearCache,
                            enabled = cacheUsage.fileCount > 0,
                            border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Leeren", fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Schnellstart: Kapitel-URL direkt laden oder im Browser öffnen
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = quickUrl,
                    onValueChange = { quickUrl = it },
                    placeholder = {
                        Text("Kapitel-URL eingeben (https://...)", fontSize = 13.sp)
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmoledPalette.AccentSoft,
                        unfocusedBorderColor = AmoledPalette.BorderHairline,
                        focusedContainerColor = AmoledPalette.Surface,
                        unfocusedContainerColor = AmoledPalette.Surface,
                    ),
                )
                Button(
                    onClick = {
                        val trimmed = quickUrl.trim()
                        if (trimmed.isNotEmpty()) {
                            onOpenChapterUrl(trimmed, 0)
                        }
                    },
                    enabled = quickUrl.isNotBlank() && !isExtracting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmoledPalette.AccentPrimary,
                        contentColor = AmoledPalette.TextPrimary,
                    ),
                ) {
                    Text(if (isExtracting) "Lädt..." else "Lesen")
                }
            }
        }

        // Sub-Tabs: Lesezeichen & Verlauf
        TabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = AmoledPalette.Background,
            contentColor = AmoledPalette.AccentSoft,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedSubTab]),
                    color = AmoledPalette.AccentSoft,
                )
            },
        ) {
            Tab(
                selected = selectedSubTab == 0,
                onClick = { selectedSubTab = 0 },
                text = { Text("Lesezeichen (${bookmarks.size})") },
                icon = { Icon(Icons.Outlined.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            Tab(
                selected = selectedSubTab == 1,
                onClick = { selectedSubTab = 1 },
                text = { Text("Verlauf (${history.size})") },
                icon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selectedSubTab == 0) {
                if (bookmarks.isEmpty()) {
                    item {
                        EmptyLibraryNotice(
                            title = "Noch keine Lesezeichen",
                            body = "Öffne eine Manga-Seite im Browser-Tab, extrahiere das Kapitel und tippe im Reader auf das Lesezeichen-Symbol.",
                            onOpenBrowser = { onOpenInBrowser("https://mangadex.org") },
                        )
                    }
                } else {
                    items(bookmarks, key = { it.id }) { bm ->
                        LibraryEntryCard(
                            title = bm.title,
                            subtitle = "${bm.chapterLabel} · Seite ${bm.lastReadPage + 1} von ${bm.totalPages}",
                            url = bm.chapterUrl,
                            onContinue = { onOpenChapterUrl(bm.chapterUrl, bm.lastReadPage) },
                            onDelete = { onRemoveBookmark(bm.id) },
                        )
                    }
                }
            } else {
                if (history.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            OutlinedButton(
                                onClick = onClearHistory,
                                border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
                            ) {
                                Text("Verlauf leeren", fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (history.isEmpty()) {
                    item {
                        EmptyLibraryNotice(
                            title = "Kein Leseverlauf vorhanden",
                            body = "Gelesene Kapitel erscheinen hier automatisch mit deiner zuletzt gelesenen Seite.",
                            onOpenBrowser = { onOpenInBrowser("https://mangadex.org") },
                        )
                    }
                } else {
                    items(history, key = { it.id }) { item ->
                        LibraryEntryCard(
                            title = item.title,
                            subtitle = "${item.chapterLabel} · Seite ${item.lastReadPage + 1} von ${item.totalPages}",
                            url = item.chapterUrl,
                            onContinue = { onOpenChapterUrl(item.chapterUrl, item.lastReadPage) },
                            onDelete = { onRemoveHistory(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryEntryCard(
    title: String,
    subtitle: String,
    url: String,
    onContinue: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = AmoledPalette.Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = AmoledPalette.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    color = AmoledPalette.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = url,
                    color = AmoledPalette.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmoledPalette.AccentPrimary,
                    contentColor = AmoledPalette.TextPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Weiter", fontSize = 12.sp)
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Entfernen",
                    tint = AmoledPalette.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun EmptyLibraryNotice(
    title: String,
    body: String,
    onOpenBrowser: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = AmoledPalette.Surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    color = AmoledPalette.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = body,
                    color = AmoledPalette.TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onOpenBrowser,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmoledPalette.AccentPrimary,
                        contentColor = AmoledPalette.TextPrimary,
                    ),
                ) {
                    Text("Im Browser starten")
                }
            }
        }
    }
}
