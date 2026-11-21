package com.mangareader.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.CacheUsageSnapshot
import com.mangareader.data.AppPreferencesRepository
import com.mangareader.formatBytesDe
import com.mangareader.model.ReaderSettings
import com.mangareader.model.ReadingMode
import com.mangareader.ui.theme.AmoledPalette
import kotlin.math.roundToInt

private val CACHE_PRESET_GB = listOf(1, 2, 4, 8, 16, 32)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    settings: ReaderSettings,
    cacheUsage: CacheUsageSnapshot,
    onDismiss: () -> Unit,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onPruneToLimit: () -> Unit,
    onClearAllCache: () -> Unit,
) {
    var sliderLimitMb by remember(settings.cacheLimitMb) {
        mutableFloatStateOf(settings.cacheLimitMb.toFloat())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AmoledPalette.Surface,
        contentColor = AmoledPalette.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Reader- & Cache-Einstellungen",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AmoledPalette.TextPrimary,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Lesemodus
            Text("Lesemodus", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AmoledPalette.TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingMode.entries.forEach { mode ->
                    val selected = settings.readingMode == mode
                    if (selected) {
                        Button(
                            onClick = { onUpdateSettings { it.copy(readingMode = mode) } },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AmoledPalette.AccentPrimary,
                                contentColor = AmoledPalette.TextPrimary,
                            ),
                        ) { Text(mode.label, fontSize = 12.sp) }
                    } else {
                        OutlinedButton(
                            onClick = { onUpdateSettings { it.copy(readingMode = mode) } },
                            border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
                        ) { Text(mode.label, fontSize = 12.sp) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = AmoledPalette.BorderHairline)

            // 1. Leseverlauf
            SettingToggleRow(
                title = "Leseverlauf aufzeichnen",
                subtitle = "Speichert gelesene Kapitel und die letzte Seite lokal",
                checked = settings.historyEnabled,
                onCheckedChange = { checked -> onUpdateSettings { it.copy(historyEnabled = checked) } },
            )

            // 2. Software-Dimmer & Sepia / Blaulichtfilter
            SettingToggleRow(
                title = "Software-Dimmer",
                subtitle = "Abdunkelung für AMOLED-Nachtlesen (${(settings.dimmerStrength * 100).roundToInt()} %)",
                checked = settings.dimmerEnabled,
                onCheckedChange = { checked -> onUpdateSettings { it.copy(dimmerEnabled = checked) } },
            )
            if (settings.dimmerEnabled) {
                Slider(
                    value = settings.dimmerStrength,
                    onValueChange = { v -> onUpdateSettings { it.copy(dimmerStrength = v) } },
                    valueRange = 0f..0.80f,
                    colors = SliderDefaults.colors(
                        thumbColor = AmoledPalette.AccentSoft,
                        activeTrackColor = AmoledPalette.AccentPrimary,
                    ),
                )
            }

            SettingToggleRow(
                title = "Sepia- / Blaulichtfilter",
                subtitle = "Warmer Augenschutz-Filter (${(settings.sepiaStrength * 100).roundToInt()} %)",
                checked = settings.sepiaEnabled,
                onCheckedChange = { checked -> onUpdateSettings { it.copy(sepiaEnabled = checked) } },
            )
            if (settings.sepiaEnabled) {
                Slider(
                    value = settings.sepiaStrength,
                    onValueChange = { v -> onUpdateSettings { it.copy(sepiaStrength = v) } },
                    valueRange = 0.10f..0.80f,
                    colors = SliderDefaults.colors(
                        thumbColor = AmoledPalette.WarningText,
                        activeTrackColor = AmoledPalette.WarningText,
                    ),
                )
            }

            // 3. Autoscroll
            SettingToggleRow(
                title = "Autoscroll (Webtoon-Modus)",
                subtitle = "Freihändiges vertikales Scrollen mit schwebender Steuerung",
                checked = settings.autoscrollEnabled,
                onCheckedChange = { checked -> onUpdateSettings { it.copy(autoscrollEnabled = checked) } },
            )

            // 4. Automatisches Pre-Fetching
            SettingToggleRow(
                title = "Automatisches Pre-Fetching",
                subtitle = "Lädt das Folgekapitel auf den letzten 2 Seiten im Hintergrund vor",
                checked = settings.autoPrefetchEnabled,
                onCheckedChange = { checked -> onUpdateSettings { it.copy(autoPrefetchEnabled = checked) } },
            )

            HorizontalDivider(color = AmoledPalette.BorderHairline)
            Spacer(modifier = Modifier.height(14.dp))

            // Konfigurierbarer Bild-Cache (Standard 4 GB, Max 32 GB, manuelles Löschen)
            Text(
                text = "Bild-Cache-Limit (512 MB – 32 GB)",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AmoledPalette.TextPrimary,
            )
            Text(
                text = "Belegt: ${formatBytesDe(cacheUsage.totalBytes)} / ${formatBytesDe(sliderLimitMb.toLong() * 1024L * 1024L)} · Keine automatische 31-Tage-Löschung",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = AmoledPalette.TextSecondary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Slider(
                value = sliderLimitMb,
                onValueChange = { sliderLimitMb = it },
                onValueChangeFinished = {
                    val clamped = sliderLimitMb.roundToInt().coerceIn(
                        AppPreferencesRepository.MIN_CACHE_LIMIT_MB,
                        AppPreferencesRepository.MAX_CACHE_LIMIT_MB
                    )
                    onUpdateSettings { it.copy(cacheLimitMb = clamped) }
                },
                valueRange = AppPreferencesRepository.MIN_CACHE_LIMIT_MB.toFloat()..AppPreferencesRepository.MAX_CACHE_LIMIT_MB.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = AmoledPalette.AccentSoft,
                    activeTrackColor = AmoledPalette.AccentPrimary,
                ),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CACHE_PRESET_GB.forEach { gb ->
                    val mb = gb * 1024
                    val active = settings.cacheLimitMb == mb
                    OutlinedButton(
                        onClick = {
                            sliderLimitMb = mb.toFloat()
                            onUpdateSettings { it.copy(cacheLimitMb = mb) }
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (active) AmoledPalette.AccentSoft else AmoledPalette.BorderHairline
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "$gb GB",
                            fontSize = 12.sp,
                            color = if (active) AmoledPalette.AccentSoft else AmoledPalette.TextSecondary,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onPruneToLimit,
                    border = BorderStroke(1.dp, AmoledPalette.BorderHairline),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Auf Limit bereinigen", fontSize = 12.sp)
                }
                Button(
                    onClick = onClearAllCache,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmoledPalette.DangerBorder,
                        contentColor = AmoledPalette.TextPrimary,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cache leeren", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = AmoledPalette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = AmoledPalette.TextSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AmoledPalette.PureBlack,
                checkedTrackColor = AmoledPalette.AccentSoft,
            ),
        )
    }
}
