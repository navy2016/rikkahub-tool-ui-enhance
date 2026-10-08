package me.rerere.rikkahub.pipeline

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/** Only in the explicitly enabled Release-derived terminaltest APK, never normal Release. */
class TerminalPipelineTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        Log.i("TerminalPipelineActivity", "created restored=${savedInstanceState != null}")
    }

    override fun onResume() {
        super.onResume()
        Log.i("TerminalPipelineActivity", "resumed")
    }

    override fun onPause() {
        Log.i("TerminalPipelineActivity", "paused finishing=$isFinishing")
        super.onPause()
    }

    override fun onDestroy() {
        Log.i("TerminalPipelineActivity", "destroyed changingConfiguration=$isChangingConfigurations")
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i("TerminalPipelineActivity", "windowFocused=$hasFocus")
    }
}
