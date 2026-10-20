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
        MaterialStore.init(this)
        PageGrab.init(this)
        Illust.init(this)
        Avatars.init(this)
        Memory.init(prefs)
        Mcp.init(prefs)
        Backup.init(prefs)
        ModelFit.init(prefs)
        WebSearch.init(prefs)
        Gallery.init(prefs)
        AdBlock.init(this, prefs)
        Downloads.init(this, prefs)
        YtDlp.init(this, prefs)
        if (intent?.getStringExtra("open") == "downloads") Downloads.open = true
        intent?.getStringExtra("probe_magnet")?.let { MagnetProbe.run(this, it) }
        Skills.init(this, prefs)
        VideoStore.init(this, prefs)
        ClipLink.init(prefs)
        PanStore.init(prefs)
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

    // 回到 App、窗口拿到焦点时看一眼剪贴板（安卓 10 起只有这时读得到）
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ClipLink.check(this)
    }

    // 有的系统（如 HyperOS）焦点回来那一下还读不到剪贴板：回到前台后稍等再看一次
    override fun onResume() {
        super.onResume()
        window.decorView.postDelayed({ if (hasWindowFocus()) ClipLink.check(this) }, 600)
    }

    // 点通知栏「下好了」回到 App：打开下载页
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("open") == "downloads") Downloads.open = true
    }
}
