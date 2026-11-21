package com.mangareader.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.app.data.model.AppTab
import com.mangareader.app.ui.components.SettingsBottomSheet
import com.mangareader.app.ui.screens.AssistantScreen
import com.mangareader.app.ui.screens.BrowserScreen
import com.mangareader.app.ui.screens.LibraryScreen
import com.mangareader.app.ui.screens.ReaderScreen
import com.mangareader.app.ui.theme.AccentPrimaryLight
import com.mangareader.app.ui.theme.BodyFontFamily
import com.mangareader.app.ui.theme.ButtonBorder
import com.mangareader.app.ui.theme.CardBorder
import com.mangareader.app.ui.theme.DarkBackground
import com.mangareader.app.ui.theme.DarkSurface
import com.mangareader.app.ui.theme.DarkSurfaceElevated
import com.mangareader.app.ui.theme.DisplayFontFamily
import com.mangareader.app.ui.theme.HairlineBorder
import com.mangareader.app.ui.theme.MangaReaderAppTheme
import com.mangareader.app.ui.theme.TextMuted
import com.mangareader.app.ui.theme.TextPrimary
import com.mangareader.app.ui.viewmodel.MangaViewModel
import com.mangareader.app.ui.viewmodel.UiEvent
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val viewModel: MangaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        intent?.data?.toString()?.let { incomingUrl ->
            if (incomingUrl.isNotBlank()) {
                viewModel.navigateToUrl(incomingUrl)
                viewModel.setActiveTab(AppTab.BROWSER)
            }
        }

        setContent {
            MangaReaderAppTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { incomingUrl ->
            if (incomingUrl.isNotBlank()) {
                viewModel.navigateToUrl(incomingUrl)
                viewModel.setActiveTab(AppTab.BROWSER)
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MangaViewModel) {
    val activeTab by viewModel.activeTab.collectAsState()
    val currentChapter by viewModel.currentChapter.collectAsState()
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()
    val readerPreferences by viewModel.readerPreferences.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = isSettingsOpen || activeTab != AppTab.LIBRARY) {
        when {
            isSettingsOpen -> viewModel.setSettingsOpen(false)
            activeTab == AppTab.READER || activeTab == AppTab.ASSISTANT -> {
                viewModel.setActiveTab(AppTab.LIBRARY)
            }
            activeTab == AppTab.BROWSER -> {
                viewModel.handleBrowserBack()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collectLatest { event ->
            if (event is UiEvent.ShowSnackbar) {
                snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBackground,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = DarkSurfaceElevated,
                    contentColor = TextPrimary,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        topBar = {
            if (activeTab != AppTab.READER) {
                AppShellTopBar(
                    onBrandClick = { viewModel.setActiveTab(AppTab.LIBRARY) },
                    onOpenSettings = { viewModel.setSettingsOpen(true) }
                )
            }
        },
        bottomBar = {
            if (activeTab != AppTab.READER) {
                AppShellBottomBar(
                    activeTab = activeTab,
                    hasLoadedChapter = currentChapter != null,
                    onSelectTab = { viewModel.setActiveTab(it) }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Keep BrowserScreen mounted in background so WebViews can load next chapters & refresh sessions
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (activeTab == AppTab.BROWSER) {
                            Modifier.padding(innerPadding)
                        } else {
                            Modifier.graphicsLayer { alpha = 0f; translationX = -20000f }
                        }
                    )
            ) {
                BrowserScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (activeTab == AppTab.LIBRARY) {
                LibraryScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding)
                )
            }

            if (activeTab == AppTab.READER) {
                ReaderScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (activeTab == AppTab.ASSISTANT) {
                AssistantScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }

    if (isSettingsOpen) {
        SettingsBottomSheet(
            viewModel = viewModel,
            preferences = readerPreferences,
            showReadingModeControls = true,
            onDismiss = { viewModel.setSettingsOpen(false) }
        )
    }
}

@Composable
private fun AppShellTopBar(
    onBrandClick: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground.copy(alpha = 0.96f))
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onBrandClick)
                    .padding(vertical = 4.dp, horizontal = 2.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_app_brand),
                    contentDescription = "MangaReaderApp Icon",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF2C2647), RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "MangaReaderApp",
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(12.dp),
                color = DarkSurfaceElevated,
                border = BorderStroke(1.dp, ButtonBorder),
                modifier = Modifier.height(38.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = AccentPrimaryLight,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Einstellungen",
                        fontFamily = BodyFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = Color(0xFFECE9F8),
                        maxLines = 1
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = HairlineBorder)
    }
}

private data class NavTabItem(
    val tab: AppTab,
    val label: String,
    val icon: ImageVector
)

private val APP_NAV_ITEMS = listOf(
    NavTabItem(AppTab.LIBRARY, "Bibliothek", Icons.Outlined.Bookmarks),
    NavTabItem(AppTab.BROWSER, "Browser", Icons.Outlined.Language),
    NavTabItem(AppTab.READER, "Reader", Icons.Outlined.AutoStories),
    NavTabItem(AppTab.ASSISTANT, "Assistent", Icons.Outlined.ChatBubbleOutline)
)

@Composable
private fun AppShellBottomBar(
    activeTab: AppTab,
    hasLoadedChapter: Boolean,
    onSelectTab: (AppTab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground.copy(alpha = 0.96f))
            .navigationBarsPadding()
    ) {
        HorizontalDivider(thickness = 1.dp, color = HairlineBorder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            APP_NAV_ITEMS.forEach { item ->
                val selected = activeTab == item.tab
                val tint = if (selected) AccentPrimaryLight else TextMuted
                val interactionSource = remember { MutableInteractionSource() }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onSelectTab(item.tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = tint,
                            modifier = Modifier.size(21.dp)
                        )
                        if (item.tab == AppTab.READER && hasLoadedChapter) {
                            Box(
                                modifier = Modifier
                                    .offset(x = 4.dp, y = (-2).dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(AccentPrimaryLight)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = item.label,
                        fontFamily = BodyFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
