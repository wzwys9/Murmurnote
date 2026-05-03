package app.murmurnote.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.murmurnote.android.audio.AudioImporter
import app.murmurnote.android.ui.MurmurnoteApp
import app.murmurnote.android.ui.theme.MurmurnoteTheme
import app.murmurnote.android.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var audioImporter: AudioImporter
    @Inject lateinit var logger: Logger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MurmurnoteTheme {
                MurmurnoteApp()
            }
        }
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEND) {
            // 记录"App 接到外部 Intent"这件事本身——AudioImporter 只在抽到 Uri 时才落日志，
            // 所以单纯的"分享给本 App 但 Uri 抽不到"这种坑这里能看见。
            logger.i("Import", "incoming intent action=${intent.action} type=${intent.type} data=${intent.data}")
        }
        val uri = audioImporter.extractUri(intent) ?: return
        Toast.makeText(this, "正在导入音频…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            audioImporter.importAndProcess(uri)
                .onSuccess { f ->
                    Toast.makeText(this@MainActivity, "已导入 ${f.name}", Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    Toast.makeText(this@MainActivity, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }
}
