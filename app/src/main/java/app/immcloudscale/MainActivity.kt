package app.immcloudscale

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.immcloudscale.data.pairing.PairLink
import app.immcloudscale.data.pairing.Pairing
import app.immcloudscale.ui.App
import app.immcloudscale.ui.theme.ImmTheme

class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<List<Uri>>(emptyList())
    private val pairLink = mutableStateOf<PairLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            ImmTheme {
                App(shared.value, { shared.value = emptyList() }, pairLink.value) { pairLink.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    @Suppress("DEPRECATION")
    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            pairLink.value = intent.dataString?.let(Pairing::parseLink)
            return
        }
        shared.value = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
    }
}
