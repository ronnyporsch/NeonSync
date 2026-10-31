package neonsync.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import neonsync.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val application = application as NeonSyncApplication
        application.consent.attach(this)
        setContent { App(application.app, Modifier.safeDrawingPadding()) }
    }

    override fun onDestroy() {
        (application as NeonSyncApplication).consent.detach()
        super.onDestroy()
    }
}
