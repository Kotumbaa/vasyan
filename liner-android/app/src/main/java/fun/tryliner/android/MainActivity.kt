package `fun`.tryliner.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import `fun`.tryliner.android.ui.LinerApp
import `fun`.tryliner.android.ui.theme.LinerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as LinerApplication).container
        setContent {
            LinerTheme {
                LinerApp(container = container)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as LinerApplication).container.setUiForeground(true)
    }

    override fun onStop() {
        (application as LinerApplication).container.setUiForeground(false)
        super.onStop()
    }
}
