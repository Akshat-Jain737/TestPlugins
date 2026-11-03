package com.vegalease

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class VegaMoviesPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VegaMoviesProvider())
        registerExtractorAPI(VCloud())
    }
}
