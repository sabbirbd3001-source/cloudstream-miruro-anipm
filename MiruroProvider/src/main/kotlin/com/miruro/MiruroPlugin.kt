package com.miruro

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MiruroPlugin : BasePlugin() {
    override fun load() {
        // Register the Miruro (miruro.cx) provider
        registerMainAPI(MiruroProvider())
    }
}
