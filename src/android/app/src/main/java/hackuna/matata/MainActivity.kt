package hackuna.matata

import android.os.Bundle
import com.getcapacitor.BridgeActivity
import hackuna.matata.bridge.StenoPlugin

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Локальные плагины регистрируются до super.onCreate().
        registerPlugin(StenoPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
