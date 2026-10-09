package com.neojou.mystudy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.neojou.mystudy.study.FileBrowserIcon
import com.neojou.mystudy.study.StudyMode
import com.neojou.mystudy.study.StudyPane
import com.neojou.tools.LogLevel
import com.neojou.tools.MyLog
import com.neojou.tools.ui.menu.MyTopMenuBar
import com.neojou.tools.ui.menu.MyTopMenuItem

/**
 * Log tag used by [HomeScreen] for logging UI events.
 */
private const val TAG = "Home"

/**
 * Primary application shell.
 *
 * Hosts [MyTopMenuBar] and the study surface. The file-browser icon at the upper left
 * opens the vault tree. The tree root shows only the vault folder's last path segment.
 * About stays the first menu item.
 */
@Composable
fun HomeScreen(about: AboutRequest) {
    var mode by remember { mutableStateOf(StudyMode.Home) }
    var fileBrowserOpen by remember { mutableStateOf(true) }
    val topMenus = listOf(
        MyTopMenuItem(
            id = "about",
            label = "About",
            onClick = about::show,
        ),
        MyTopMenuItem(
            id = "wiki",
            label = "Wiki",
            children = listOf(
                MyTopMenuItem(id = "browse", label = "Browse", onClick = { mode = StudyMode.Browse }),
                MyTopMenuItem(id = "ask", label = "Ask", onClick = { mode = StudyMode.Ask }),
            ),
        ),
        MyTopMenuItem(
            id = "settings",
            label = "Settings",
            onClick = { mode = StudyMode.Settings },
        ),
    )

    LaunchedEffect(Unit) {
        MyLog.add(TAG, "Enter", LogLevel.DEBUG)
    }

    Scaffold(
        topBar = {
            MyTopMenuBar(
                items = topMenus,
                leading = {
                    IconButton(onClick = { fileBrowserOpen = !fileBrowserOpen }) {
                        FileBrowserIcon(Modifier.size(20.dp))
                    }
                },
            )
        },
    ) { innerPadding ->
        StudyPane(
            mode = mode,
            fileBrowserOpen = fileBrowserOpen,
            onMode = { mode = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/**
 * About content shared by the in-app menu and the macOS application menu.
 *
 * Two lines only: product name, then `Version` plus [AppVersion.NAME].
 * Click outside the card or press Escape to dismiss.
 */
@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(min = 320.dp, max = 480.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 168.dp)
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = AppVersion.APP_NAME,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Version ${AppVersion.NAME}",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

