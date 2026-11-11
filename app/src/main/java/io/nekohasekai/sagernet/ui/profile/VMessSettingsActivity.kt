package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean

class VMessSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = VMessBean().apply {
        if (intent?.getBooleanExtra("vless", false) == true) {
            alterId = -1
        }
        if (intent?.getBooleanExtra("xhttp", false) == true) {
            // Manual-add "XHTTP" entry: VLESS editor with the transport
            // pre-selected as xhttp (host/path/mode fields appear).
            type = "xhttp"
        }
    }

}