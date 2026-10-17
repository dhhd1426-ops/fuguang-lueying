package com.fuguang.lueying

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 状态栏、导航栏透明，背景画面铺到屏幕边缘
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        Yu.init(this)
        Works.init(this)
        Avatars.init(this)
        Memory.init(prefs)
        Mcp.init(prefs)
        Backup.init(prefs)
        ModelFit.init(prefs)
        WebSearch.init(prefs)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            var fullscreen by remember { mutableStateOf(false) }
            FuguangApp(
                prefs = prefs,
                fullscreen = fullscreen,
                setFullscreen = { on ->
                    fullscreen = on
                    if (on) controller.hide(WindowInsetsCompat.Type.systemBars())
                    else controller.show(WindowInsetsCompat.Type.systemBars())
                },
            )
        }
    }
}
