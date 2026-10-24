package com.tengwear.tengfilemanager.presentation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.tengwear.tengfilemanager.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tengwear.tengfilemanager.core.theme.SeedTheme
import com.tengwear.tengfilemanager.presentation.theme.TENG文件管理器Theme
import com.tengwear.tengfilemanager.presentation.ui.FileManagerApp

/**
 * 唯一 Activity。
 *
 * 三件事：
 *  1. 用 XML 里的 BoxInsetLayout 作为根（boxedEdges="none"，安全区交给 Compose 的 RoundMetrics）；
 *  2. 把 Compose 内容接到 ViewModel 上，返回键交给 Compose 的 BackHandler 处理；
 *  3. 处理"从其他应用打开文件"的 ACTION_VIEW，直接进对应查看器。
 *
 * 不做多 Activity、不做 Fragment：手表上每次页面切换都重建 Activity 会让
 * 目录列表缓存和滚动位置丢失，体感上就是"每次返回都要重新读盘"。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: FileManagerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_main)
        handleViewIntent(intent)

        findViewById<ComposeView>(R.id.round_screen_compose).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // 主题种子色跟着偏好走；默认 SYSTEM = 安卓 Material You 官方色阶
                val prefs by viewModel.prefsState.collectAsStateWithLifecycle()
                TENG文件管理器Theme(seed = prefs.seedTheme) {
                    FileManagerApp(vm = viewModel, onExit = { finish() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** 从通知/其他应用打开文件：不复制文件，直接引用原 Uri 进查看器。 */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        viewModel.openExternalUri(uri, intent.type)
    }
}
