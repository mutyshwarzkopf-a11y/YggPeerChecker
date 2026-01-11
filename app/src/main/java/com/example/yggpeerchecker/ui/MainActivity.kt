package com.example.yggpeerchecker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.yggpeerchecker.ui.checks.ChecksScreen
import com.example.yggpeerchecker.ui.lists.ListsScreen
import com.example.yggpeerchecker.ui.lists.ListsViewModel
import com.example.yggpeerchecker.ui.lists.ListsViewModelFactory
import com.example.yggpeerchecker.ui.system.SystemScreen
import com.example.yggpeerchecker.ui.theme.ThemeManager
import com.example.yggpeerchecker.ui.theme.YggPeerCheckerTheme
import com.example.yggpeerchecker.utils.PersistentLogger

class MainActivity : ComponentActivity() {
    private lateinit var themeManager: ThemeManager
    private lateinit var logger: PersistentLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeManager = ThemeManager(this)
        logger = PersistentLogger(this)

        setContent {
            val themeMode by themeManager.themeMode.collectAsState()
            YggPeerCheckerTheme(themeMode = themeMode) {
                MainScreen(
                    themeManager = themeManager,
                    logger = logger
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    themeManager: ThemeManager,
    logger: PersistentLogger
) {
    // Сохранение выбранной вкладки между recomposition
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // Сохранение выбранной под-вкладки для System
    var systemSubTab by rememberSaveable { mutableIntStateOf(0) }

    // Создание ListsViewModel
    val listsViewModel: ListsViewModel = viewModel(
        factory = ListsViewModelFactory(
            context = androidx.compose.ui.platform.LocalContext.current,
            logger = logger
        )
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("Lists") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Checks") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("System") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            when (selectedTab) {
                0 -> ListsScreen(viewModel = listsViewModel)
                1 -> ChecksScreen(logger = logger)
                2 -> SystemScreen(
                    themeManager = themeManager,
                    initialTabIndex = systemSubTab,
                    onTabChanged = { systemSubTab = it }
                )
            }
        }
    }
}
