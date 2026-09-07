package com.dashcam.editor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.dashcam.editor.ui.AppModel
import com.dashcam.editor.ui.DashcamTheme
import com.dashcam.editor.ui.EditorScreen
import com.dashcam.editor.ui.LibraryScreen
import com.dashcam.editor.ui.Screen

class MainActivity : ComponentActivity() {

    /** 系统分享入口送来的视频 Uri，由首页消费（导入后直接进编辑） */
    private val shareUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        handleShare(intent)
        setContent {
            DashcamTheme {
                AppRoot(shareUris)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    .orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) shareUris.value = uris
    }
}

@Composable
private fun AppRoot(shareUris: androidx.compose.runtime.MutableState<List<Uri>>) {
    val app = remember { AppModel() }
    when (app.screen) {
        Screen.Library -> LibraryScreen(app, shareUris)
        Screen.Edit -> EditorScreen(app, onBack = { app.screen = Screen.Library })
    }
}
