package hackuna.matata

import android.os.Bundle
import com.getcapacitor.BridgeActivity
import hackuna.matata.bridge.StenoPlugin

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(StenoPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
