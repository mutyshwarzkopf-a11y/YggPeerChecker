package com.example.yggpeerchecker.ui.system

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.example.yggpeerchecker.ui.theme.ThemeManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(
    modifier: Modifier = Modifier,
    themeManager: ThemeManager,
    initialTabIndex: Int = 0,
    onTabChanged: (Int) -> Unit = {}
) {
    val tabs = listOf("Config", "Logs")
    val pagerState = rememberPagerState(
        initialPage = initialTabIndex,
        pageCount = { tabs.size }
    )
    val coroutineScope = rememberCoroutineScope()

    // Уведомляем о смене вкладки
    LaunchedEffect(pagerState.currentPage) {
        onTabChanged(pagerState.currentPage)
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                    text = { Text(title) }
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> ConfigTab(themeManager = themeManager)
                1 -> LogsTab()
            }
        }
    }
}
