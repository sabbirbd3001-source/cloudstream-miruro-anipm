package com.anipm

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AniPmPlugin : BasePlugin() {
    override fun load() {
        // Register the Ani.pm provider
        registerMainAPI(AniPmProvider())
    }
}
