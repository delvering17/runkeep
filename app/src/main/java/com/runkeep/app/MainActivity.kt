package com.runkeep.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runkeep.app.ui.RunkeepRoot
import com.runkeep.app.ui.RunkeepTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as RunkeepApp
        setContent {
            val settings = app.settings.collectAsStateWithLifecycle().value
            RunkeepTheme(settings.darkTheme) {
                RunkeepRoot(app, intent.action?.contains("PERMISSION") == true)
            }
        }
    }
}
