package com.mangareader.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.app.data.cache.ChapterCacheManager
import com.mangareader.app.data.model.AppTab
import com.mangareader.app.data.model.ReaderMode
import com.mangareader.app.data.model.ReaderPreferences
import com.mangareader.app.data.model.ReadingDirection
import com.mangareader.app.ui.theme.AccentPrimary
import com.mangareader.app.ui.theme.AccentPrimaryLight
import com.mangareader.app.ui.theme.BodyFontFamily
import com.mangareader.app.ui.theme.DangerBorder
import com.mangareader.app.ui.theme.DangerSurface
import com.mangareader.app.ui.theme.DangerText
import com.mangareader.app.ui.theme.DarkCardBg
import com.mangareader.app.ui.theme.DarkInputBg
import com.mangareader.app.ui.theme.DisplayFontFamily
import com.mangareader.app.ui.theme.ErrorColor
import com.mangareader.app.ui.theme.MonoFontFamily
import com.mangareader.app.ui.theme.WarningAmber
import com.mangareader.app.ui.viewmodel.MangaViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val SectionDividerColor = Color(0xFF231E38)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    viewModel: MangaViewModel,
    preferences: ReaderPreferences,
    showReadingModeControls: Boolean = true,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusManager = LocalFocusManager.current

    val readerMode by viewModel.readerMode.collectAsState()
    val isAutoScrolling by viewModel.isAutoScrolling.collectAsState()
    val currentChapter by viewModel.currentChapter.collectAsState()
    val cacheLimitMb by viewModel.cacheLimitMb.collectAsState()
    val cacheStats by viewModel.cacheStats.collectAsState()
    val isCacheBusy by viewModel.isCacheBusy.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshCacheStats()
    }

    var customGbDraft by remember(cacheLimitMb) {
        mutableStateOf(
            String.format(Locale.US, "%.1f", cacheLimitMb / 1024.0)
                .removeSuffix(".0")
        )
    }

    fun commitCustomGb(raw: String) {
        val parsed = raw.replace(',', '.').trim().toDoubleOrNull()
        if (parsed == null || !parsed.isFinite()) {
            customGbDraft = String.format(Locale.US, "%.1f", cacheLimitMb / 1024.0).removeSuffix(".0")
            return
        }
        val clampedMb = ChapterCacheManager.clampCacheLimitMb((parsed * 1024.0).roundToInt())
        viewModel.updateCacheLimitMb(clampedMb)
        customGbDraft = String.format(Locale.US, "%.1f", clampedMb / 1024.0).removeSuffix(".0")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkCardBg,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF312B4C))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = AccentPrimaryLight,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Reader- & QoL-Einstellungen",
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                }

                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Einstellungen schließen",
                            tint = Color(0xFFB4AED0),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = Color(0xFF25203A))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // 1. Lesemodus & Blätterrichtung
                if (showReadingModeControls) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeadingRow(
                            icon = Icons.AutoMirrored.Outlined.MenuBook,
                            title = "Lesemodus"
                        )

                        Surface(
                            color = DarkInputBg,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, SectionDividerColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                SegmentedOptionButton(
                                    label = "Webtoon (Vertikal)",
                                    selected = readerMode == ReaderMode.VERTICAL_WEBTOON,
                                    selectedContainerColor = AccentPrimary,
                                    onClick = { viewModel.setReaderMode(ReaderMode.VERTICAL_WEBTOON) },
                                    modifier = Modifier.weight(1f)
                                )
                                SegmentedOptionButton(
                                    label = "Manga (Blättern)",
                                    selected = readerMode == ReaderMode.HORIZONTAL_PAGED,
                                    selectedContainerColor = AccentPrimary,
                                    onClick = { viewModel.setReaderMode(ReaderMode.HORIZONTAL_PAGED) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        if (readerMode == ReaderMode.HORIZONTAL_PAGED) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Blätterrichtung",
                                    fontFamily = BodyFontFamily,
                                    fontSize = 13.sp,
                                    color = Color(0xFFB8B2D4)
                                )
                                Surface(
                                    color = DarkInputBg,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, SectionDividerColor)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        SegmentedOptionButton(
                                            label = "Rechts → Links",
                                            selected = preferences.readingDirection == ReadingDirection.RTL,
                                            selectedContainerColor = Color(0xFF262040),
                                            heightDp = 32,
                                            fontSizeSp = 11,
                                            onClick = { viewModel.setReadingDirection(ReadingDirection.RTL) }
                                        )
                                        SegmentedOptionButton(
                                            label = "Links → Rechts",
                                            selected = preferences.readingDirection == ReadingDirection.LTR,
                                            selectedContainerColor = Color(0xFF262040),
                                            heightDp = 32,
                                            fontSizeSp = 11,
                                            onClick = { viewModel.setReadingDirection(ReadingDirection.LTR) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(thickness = 1.dp, color = SectionDividerColor)
                }

                // 2. Helligkeits- & Nachtfilter (QoL 2)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeadingRow(
                        icon = Icons.Outlined.DarkMode,
                        title = "Helligkeits- & Nachtfilter"
                    )

                    SettingToggleRow(
                        title = "Bildschirm abdunkeln (Software-Dimmer)",
                        subtitle = "Dunkelt das Display unter das Hardware-Minimum ab.",
                        checked = preferences.isCustomBrightnessEnabled,
                        onCheckedChange = { viewModel.setCustomBrightness(it) }
                    )

                    if (preferences.isCustomBrightnessEnabled) {
                        val dimPercent = ((1f - preferences.brightnessLevel) * 100f).roundToInt().coerceIn(0, 80)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Abdunkelung",
                                    fontFamily = BodyFontFamily,
                                    fontSize = 12.sp,
                                    color = Color(0xFFB8B2D4)
                                )
                                Text(
                                    text = "$dimPercent %",
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                            }
                            Slider(
                                value = (1f - preferences.brightnessLevel).coerceIn(0f, 0.80f),
                                onValueChange = { dimRatio ->
                                    viewModel.setCustomBrightness(true, (1f - dimRatio).coerceIn(0.20f, 1.0f))
                                },
                                valueRange = 0f..0.80f,
                                colors = SliderDefaults.colors(
                                    thumbColor = AccentPrimaryLight,
                                    activeTrackColor = AccentPrimary,
                                    inactiveTrackColor = Color(0xFF241F3A)
                                )
                            )
                        }
                    }

                    SettingToggleRow(
                        title = "Blaulichtfilter / Sepia",
                        subtitle = "Warmtonfilter für augenschonendes Lesen bei Nacht.",
                        checked = preferences.isNightFilterEnabled,
                        onCheckedChange = { viewModel.setNightFilter(it) }
                    )

                    if (preferences.isNightFilterEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Filterstärke",
                                    fontFamily = BodyFontFamily,
                                    fontSize = 12.sp,
                                    color = Color(0xFFB8B2D4)
                                )
                                Text(
                                    text = "${(preferences.nightFilterStrength * 100).roundToInt()} %",
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = Color(0xFFFDE68A)
                                )
                            }
                            Slider(
                                value = preferences.nightFilterStrength,
                                onValueChange = { viewModel.setNightFilter(true, it) },
                                valueRange = 0.10f..0.80f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFBBF24),
                                    activeTrackColor = Color(0xFFF59E0B),
                                    inactiveTrackColor = Color(0xFF241F3A)
                                )
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = SectionDividerColor)

                // 3. Autoscroll & Bildschirm wach halten (QoL 3)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingToggleRow(
                        title = "Autoscroll / Freihändiges Lesen",
                        subtitle = "Zeigt Play/Pause im Reader sowie die schwebende Geschwindigkeits-Steuerung.",
                        titleIcon = Icons.Outlined.FastForward,
                        checked = preferences.isAutoScrollEnabled,
                        onCheckedChange = { checked ->
                            viewModel.updateReaderPreferences(preferences.copy(isAutoScrollEnabled = checked))
                            if (!checked) viewModel.setAutoScroll(false)
                        }
                    )

                    if (preferences.isAutoScrollEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f).forEach { spd ->
                                val selected = abs(preferences.autoScrollSpeed - spd) < 0.05f
                                Surface(
                                    onClick = { viewModel.setAutoScrollSpeed(spd) },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) AccentPrimary else DarkInputBg,
                                    border = if (selected) null else BorderStroke(1.dp, Color(0xFF2A2444)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "${String.format(Locale.US, "%.1f", spd)}x",
                                            fontFamily = MonoFontFamily,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 12.sp,
                                            color = if (selected) Color.White else Color(0xFFB4AED0)
                                        )
                                    }
                                }
                            }
                        }

                        if (currentChapter != null) {
                            Surface(
                                onClick = {
                                    if (readerMode != ReaderMode.VERTICAL_WEBTOON) {
                                        viewModel.setReaderMode(ReaderMode.VERTICAL_WEBTOON)
                                    }
                                    viewModel.toggleAutoScroll()
                                    viewModel.setActiveTab(AppTab.READER)
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF1D1736),
                                border = BorderStroke(1.dp, Color(0xFF3B3166)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (isAutoScrolling) "Autoscroll jetzt pausieren" else "Autoscroll im Reader starten",
                                        fontFamily = BodyFontFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp,
                                        color = Color(0xFFE5DCFF)
                                    )
                                }
                            }
                        }
                    }

                    SettingToggleRow(
                        title = "Bildschirm wach halten",
                        subtitle = "Verhindert automatisches Ausschalten beim Lesen.",
                        checked = preferences.keepScreenOn,
                        onCheckedChange = { viewModel.setKeepScreenOn(it) }
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = SectionDividerColor)

                // 4. Automatisches Pre-Fetching (QoL 4)
                SettingToggleRow(
                    title = "Automatisches Pre-Fetching",
                    subtitle = "Lädt das nächste Kapitel im Hintergrund vor, sobald du die letzten 2 Seiten erreichst.",
                    titleIcon = Icons.Outlined.CloudDownload,
                    checked = preferences.isPrefetchEnabled,
                    onCheckedChange = { viewModel.setPrefetchEnabled(it) }
                )

                HorizontalDivider(thickness = 1.dp, color = SectionDividerColor)

                // 5. Automatischer Leseverlauf (QoL 1)
                SettingToggleRow(
                    title = "Automatischer Leseverlauf",
                    subtitle = "Speichert gelesene Kapitel und die exakte Seitenzahl automatisch in der Bibliothek.",
                    titleIcon = Icons.Outlined.History,
                    checked = preferences.isHistoryTrackingEnabled,
                    onCheckedChange = { viewModel.setHistoryTrackingEnabled(it) }
                )

                HorizontalDivider(thickness = 1.dp, color = SectionDividerColor)

                // 6. Bild-Cache & Speicherlimit (Standard: 4 GB, Max.: 32 GB, manuelles Löschen)
                val limitBytes = ChapterCacheManager.mbToBytes(cacheLimitMb)
                val usageBarColor = when {
                    cacheStats.usagePercent >= 90f -> ErrorColor
                    cacheStats.usagePercent >= 75f -> WarningAmber
                    else -> AccentPrimaryLight
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeadingRow(
                            icon = Icons.Outlined.Storage,
                            title = "Bild-Cache & Speicherlimit"
                        )
                        Text(
                            text = "Limit: ${ChapterCacheManager.formatCacheLimitLabel(cacheLimitMb)} · Manuell",
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            color = Color(0xFF9C95BA)
                        )
                    }

                    Text(
                        text = "Zwischengespeicherte Seiten verfallen nicht mehr automatisch nach 31 Tagen. Sie bleiben erhalten, bis du sie manuell löschst oder dein eingestelltes Speicherlimit erreicht wird.",
                        fontFamily = BodyFontFamily,
                        fontSize = 12.sp,
                        color = Color(0xFF9C95BA)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${ChapterCacheManager.formatBytesDe(cacheStats.totalBytes)} / ${ChapterCacheManager.formatBytesDe(limitBytes)}",
                                fontFamily = MonoFontFamily,
                                fontSize = 12.sp,
                                color = Color(0xFFCFCBE2)
                            )
                            Text(
                                text = "${cacheStats.fileCount} ${if (cacheStats.fileCount == 1) "Datei" else "Dateien"}",
                                fontFamily = MonoFontFamily,
                                fontSize = 12.sp,
                                color = Color(0xFF9C95BA)
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (cacheStats.usagePercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = usageBarColor,
                            trackColor = Color(0xFF241F3A)
                        )
                    }

                    // Direct GB input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Maximales Cache-Limit",
                            fontFamily = BodyFontFamily,
                            fontSize = 12.sp,
                            color = Color(0xFFB8B2D4)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(76.dp)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DarkInputBg)
                                    .border(1.dp, Color(0xFF312A4E), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                BasicTextField(
                                    value = customGbDraft,
                                    onValueChange = { next ->
                                        customGbDraft = next
                                        val parsed = next.replace(',', '.').trim().toDoubleOrNull()
                                        if (parsed != null && parsed >= 0.5) {
                                            val clampedMb = ChapterCacheManager.clampCacheLimitMb((parsed * 1024.0).roundToInt())
                                            viewModel.updateCacheLimitMb(clampedMb)
                                        }
                                    },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp,
                                        color = Color.White,
                                        textAlign = TextAlign.End
                                    ),
                                    cursorBrush = SolidColor(AccentPrimaryLight),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Decimal,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            commitCustomGb(customGbDraft)
                                            focusManager.clearFocus()
                                        }
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Text(
                                text = "GB",
                                fontFamily = MonoFontFamily,
                                fontSize = 12.sp,
                                color = Color(0xFFA9A2C7)
                            )
                        }
                    }

                    // 3x2 Preset Grid (1 GB .. 32 GB, zero Material-3 checkmarks)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChapterCacheManager.CACHE_LIMIT_PRESETS_MB.chunked(3).forEach { rowPresets ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowPresets.forEach { presetMb ->
                                    val selected = cacheLimitMb == presetMb
                                    Surface(
                                        onClick = { viewModel.updateCacheLimitMb(presetMb) },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (selected) AccentPrimary else DarkInputBg,
                                        border = if (selected) null else BorderStroke(1.dp, Color(0xFF2A2444)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = ChapterCacheManager.formatCacheLimitLabel(presetMb),
                                                fontFamily = MonoFontFamily,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 12.sp,
                                                color = if (selected) Color.White else Color(0xFFB4AED0)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Slider(
                            value = cacheLimitMb.toFloat(),
                            onValueChange = { rawMb ->
                                val snapped = ((rawMb / 512f).roundToInt() * 512).coerceIn(
                                    ChapterCacheManager.MIN_CACHE_LIMIT_MB,
                                    ChapterCacheManager.MAX_CONFIGURABLE_CACHE_LIMIT_MB
                                )
                                viewModel.updateCacheLimitMb(snapped)
                            },
                            valueRange = ChapterCacheManager.MIN_CACHE_LIMIT_MB.toFloat()..ChapterCacheManager.MAX_CONFIGURABLE_CACHE_LIMIT_MB.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = AccentPrimaryLight,
                                activeTrackColor = AccentPrimary,
                                inactiveTrackColor = Color(0xFF241F3A)
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "512 MB",
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                color = Color(0xFF8D86A8)
                            )
                            Text(
                                text = "Standard: 4 GB",
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                color = Color(0xFF8D86A8)
                            )
                            Text(
                                text = "Max. 32 GB",
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                color = Color(0xFF8D86A8)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = { if (!isCacheBusy) viewModel.applyCacheLimitManually() },
                            enabled = !isCacheBusy,
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1D1736),
                            border = BorderStroke(1.dp, Color(0xFF3B3166)),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Auf Limit bereinigen",
                                    fontFamily = BodyFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = Color(0xFFE5DCFF).copy(alpha = if (isCacheBusy) 0.5f else 1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Surface(
                            onClick = { if (!isCacheBusy) viewModel.clearCacheManually() },
                            enabled = !isCacheBusy,
                            shape = RoundedCornerShape(12.dp),
                            color = DangerSurface,
                            border = BorderStroke(1.dp, DangerBorder),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = null,
                                    tint = DangerText.copy(alpha = if (isCacheBusy) 0.5f else 1f),
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Cache leeren",
                                    fontFamily = BodyFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = DangerText.copy(alpha = if (isCacheBusy) 0.5f else 1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeadingRow(
    icon: ImageVector,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AccentPrimaryLight,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = title,
            fontFamily = DisplayFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = Color(0xFFD8D3EE)
        )
    }
}

@Composable
private fun SegmentedOptionButton(
    label: String,
    selected: Boolean,
    selectedContainerColor: Color,
    heightDp: Int = 40,
    fontSizeSp: Int = 13,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) selectedContainerColor else Color.Transparent,
        modifier = modifier.height(heightDp.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(
                text = label,
                fontFamily = BodyFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = fontSizeSp.sp,
                color = if (selected) Color.White else Color(0xFFA9A2C7),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    titleIcon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                if (titleIcon != null) {
                    Icon(
                        imageVector = titleIcon,
                        contentDescription = null,
                        tint = AccentPrimaryLight,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = title,
                    fontFamily = BodyFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = Color.White
                )
            }
            Text(
                text = subtitle,
                fontFamily = BodyFontFamily,
                fontSize = 12.sp,
                color = Color(0xFF9C95BA)
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentPrimary,
                checkedBorderColor = Color(0xFF956BFF),
                uncheckedThumbColor = Color(0xFFA7A0C4),
                uncheckedTrackColor = Color(0xFF1D1930),
                uncheckedBorderColor = Color(0xFF332D4F)
            )
        )
    }
}
