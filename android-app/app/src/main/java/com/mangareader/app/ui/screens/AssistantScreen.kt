package com.mangareader.app.ui.screens

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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.app.data.chat.ChatMessage
import com.mangareader.app.data.chat.ChatModelId
import com.mangareader.app.data.chat.ChatPersonaId
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
import com.mangareader.app.ui.theme.DarkSurfaceElevated
import com.mangareader.app.ui.theme.DisplayFontFamily
import com.mangareader.app.ui.theme.ErrorColor
import com.mangareader.app.ui.theme.HairlineBorder
import com.mangareader.app.ui.theme.MonoFontFamily
import com.mangareader.app.ui.theme.PureAmoledBlack
import com.mangareader.app.ui.theme.TextSecondary
import com.mangareader.app.ui.theme.WarningAmber
import com.mangareader.app.ui.viewmodel.MangaViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AssistantScreen(
    viewModel: MangaViewModel,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.chatMessages.collectAsState()
    val selectedPersona by viewModel.selectedPersona.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val isStreaming by viewModel.isAssistantStreaming.collectAsState()
    val hasApiKey by viewModel.hasApiKey.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyDraft by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureAmoledBlack)
    ) {
        // --- Header: Title, Model Speed Switch, Key, Reset & Persona Cards (GitHub ChatView style) ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Manga-Assistent",
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 21.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Surface(
                    onClick = { showApiKeyDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    color = DarkSurfaceElevated,
                    border = BorderStroke(1.dp, ButtonBorder),
                    modifier = Modifier.height(34.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = "Gemini API-Key konfigurieren",
                            tint = if (hasApiKey) AccentPrimaryLight else WarningAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (hasApiKey) "API-Key" else "Key setzen",
                            fontFamily = BodyFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            color = if (hasApiKey) Color(0xFFD5CFF0) else Color(0xFFFDE68A)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Segmented Model Speed Selector
                Surface(
                    color = DarkSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF241F3A))
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        ChatModelId.entries.forEach { model ->
                            val selected = selectedModel == model
                            Surface(
                                onClick = { viewModel.selectModel(model) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) AccentPrimary else Color.Transparent,
                                modifier = Modifier.height(32.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                ) {
                                    if (model == ChatModelId.FAST) {
                                        Icon(
                                            imageVector = Icons.Outlined.Bolt,
                                            contentDescription = null,
                                            tint = if (selected) Color.White else Color(0xFFA7A0C4),
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                    Text(
                                        text = model.label,
                                        fontFamily = BodyFontFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp,
                                        color = if (selected) Color.White else Color(0xFFA7A0C4)
                                    )
                                }
                            }
                        }
                    }
                }

                val canReset = messages.isNotEmpty() || isStreaming
                Surface(
                    onClick = { if (canReset) viewModel.clearChatHistory() },
                    enabled = canReset,
                    shape = RoundedCornerShape(10.dp),
                    color = DarkSurfaceElevated.copy(alpha = if (canReset) 1f else 0.45f),
                    border = BorderStroke(1.dp, ButtonBorder.copy(alpha = if (canReset) 1f else 0.45f)),
                    modifier = Modifier.height(38.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            tint = Color(0xFFD5CFF0).copy(alpha = if (canReset) 1f else 0.45f),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Neuer Chat",
                            fontFamily = BodyFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = Color(0xFFD5CFF0).copy(alpha = if (canReset) 1f else 0.45f)
                        )
                    }
                }
            }

            // 3 Persona Cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChatPersonaId.entries.forEach { persona ->
                    val active = selectedPersona == persona
                    Surface(
                        onClick = { viewModel.selectPersona(persona) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (active) Color(0xFF191430) else Color(0xFF110E1E),
                        border = BorderStroke(
                            1.dp,
                            if (active) AccentPrimary else HairlineBorder
                        ),
                        modifier = Modifier.width(196.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = persona.label,
                                fontFamily = DisplayFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                color = if (active) Color.White else Color(0xFFA9A2C7),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = persona.subtitle,
                                fontFamily = BodyFontFamily,
                                fontSize = 11.sp,
                                color = Color(0xFF8E87AB),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = HairlineBorder)

        if (!hasApiKey) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1C150C),
                border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.55f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Optional: Gemini-API-Key hinterlegen. Browser, Reader & Bibliothek laufen ohne Key.",
                        fontFamily = BodyFontFamily,
                        fontSize = 12.sp,
                        color = Color(0xFFFEF3C7),
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        onClick = { showApiKeyDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF2B2010),
                        border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.6f)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Text(
                                text = "Key",
                                fontFamily = BodyFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                color = Color(0xFFFDE68A)
                            )
                        }
                    }
                }
            }
        }

        // Chat Messages or Empty Starter Prompts
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF151128),
                                border = BorderStroke(1.dp, Color(0xFF2C254A)),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.SmartToy,
                                        contentDescription = null,
                                        tint = AccentPrimaryLight,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${selectedPersona.label} bereit",
                                fontFamily = DisplayFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Stelle eine Frage oder starte direkt mit einem Thema. Der Gesprächsverlauf bleibt über mehrere Rückfragen hinweg erhalten.",
                                fontFamily = BodyFontFamily,
                                fontSize = 13.sp,
                                color = Color(0xFF9D96BA),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    items(selectedPersona.starterPrompts) { prompt ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkSurface,
                            border = BorderStroke(1.dp, Color(0xFF241F3A)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.sendChatMessage(prompt) }
                        ) {
                            Text(
                                text = prompt,
                                fontFamily = BodyFontFamily,
                                fontSize = 13.sp,
                                color = Color(0xFFDBD5F2),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatBubbleRow(
                            message = msg,
                            isStreaming = isStreaming && msg.id == messages.lastOrNull()?.id
                        )
                    }
                }
            }
        }

        // Bottom Combined Framed Composer
        HorizontalDivider(thickness = 1.dp, color = HairlineBorder)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkBackground)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkCardBg)
                    .border(1.dp, Color(0xFF2A2444), RoundedCornerShape(16.dp))
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 38.dp)
                        .heightIn(max = 120.dp)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = "Frage an ${selectedPersona.label} stellen…",
                            fontFamily = BodyFontFamily,
                            fontSize = 13.sp,
                            color = Color(0xFF7C7599),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        textStyle = TextStyle(
                            fontFamily = BodyFontFamily,
                            fontSize = 14.sp,
                            color = Color.White
                        ),
                        cursorBrush = SolidColor(AccentPrimaryLight),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank() && !isStreaming) {
                                    viewModel.sendChatMessage(inputText)
                                    inputText = ""
                                }
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (isStreaming) {
                    Surface(
                        onClick = { viewModel.stopAssistantStreaming() },
                        shape = RoundedCornerShape(10.dp),
                        color = DangerSurface,
                        border = BorderStroke(1.dp, DangerBorder),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Stop,
                                contentDescription = "Antwort stoppen",
                                tint = DangerText,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Stopp",
                                fontFamily = BodyFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                color = DangerText
                            )
                        }
                    }
                } else {
                    val canSend = inputText.isNotBlank()
                    Button(
                        onClick = {
                            if (canSend) {
                                viewModel.sendChatMessage(inputText)
                                inputText = ""
                            }
                        },
                        enabled = canSend,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = Color.White,
                            disabledContainerColor = AccentPrimary.copy(alpha = 0.45f),
                            disabledContentColor = Color.White.copy(alpha = 0.6f)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = "Senden",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Senden",
                            fontFamily = BodyFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }

    if (showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            containerColor = DarkCardBg,
            title = {
                Text(
                    text = "Gemini API-Schlüssel",
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Der Schlüssel wird nur lokal im privaten App-Speicher abgelegt und direkt an die Gemini-API gesendet.",
                        fontFamily = BodyFontFamily,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = apiKeyDraft,
                        onValueChange = { apiKeyDraft = it },
                        label = { Text("API-Schlüssel (AIza...)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimaryLight,
                            unfocusedBorderColor = Color(0xFF2A2444),
                            focusedContainerColor = DarkInputBg,
                            unfocusedContainerColor = DarkInputBg,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveApiKey(apiKeyDraft)
                        apiKeyDraft = ""
                        showApiKeyDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentPrimary,
                        contentColor = Color.White
                    )
                ) {
                    Text("Speichern")
                }
            },
            dismissButton = {
                Row {
                    if (hasApiKey) {
                        TextButton(
                            onClick = {
                                viewModel.clearApiKey()
                                showApiKeyDialog = false
                            }
                        ) {
                            Text("Löschen", color = ErrorColor)
                        }
                    }
                    TextButton(onClick = { showApiKeyDialog = false }) {
                        Text("Abbrechen", color = TextSecondary)
                    }
                }
            }
        )
    }
}

@Composable
private fun ChatBubbleRow(
    message: ChatMessage,
    isStreaming: Boolean
) {
    val isUser = message.role == "user"
    val timeText = remember(message.createdAt) {
        SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(message.createdAt))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (message.isError) DangerSurface else Color(0xFF17132B),
                border = BorderStroke(
                    1.dp,
                    if (message.isError) DangerBorder else Color(0xFF2E274E)
                ),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (message.isError) Icons.Outlined.WarningAmber else Icons.Outlined.SmartToy,
                        contentDescription = null,
                        tint = if (message.isError) DangerText else AccentPrimaryLight,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = when {
                isUser -> AccentPrimary
                message.isError -> DangerSurface
                else -> DarkSurface
            },
            border = if (isUser) null else BorderStroke(
                1.dp,
                if (message.isError) DangerBorder else CardBorder
            ),
            modifier = Modifier.widthIn(max = 285.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = message.text.ifBlank { if (isStreaming) "Schreibt…" else "" },
                    fontFamily = BodyFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = when {
                        isUser -> Color.White
                        message.isError -> DangerText
                        else -> Color(0xFFECE8FA)
                    }
                )
                Row(
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = timeText,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                        color = if (isUser) Color.White.copy(alpha = 0.78f) else Color(0xFF8C85A8)
                    )
                    if (!isUser && !message.modelId.isNullOrBlank()) {
                        val modeLabel = if (message.modelId == ChatModelId.FAST.id) "Schnell" else "Standard"
                        Text(
                            text = " · $modeLabel",
                            fontFamily = MonoFontFamily,
                            fontSize = 10.sp,
                            color = Color(0xFF8C85A8)
                        )
                    }
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1E1838),
                border = BorderStroke(1.dp, Color(0xFF3B3166)),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = Color(0xFFD8CEFF),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
