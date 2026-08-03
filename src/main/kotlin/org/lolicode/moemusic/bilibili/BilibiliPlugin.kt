package org.lolicode.moemusic.bilibili

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.plugin.Plugin
import org.lolicode.moemusic.api.plugin.PluginProvider
import org.lolicode.moemusic.api.plugin.ServerRuntimeContext

object BilibiliPlugin : Plugin {
    const val PLUGIN_ID = "moemusic-bilibili-source"
    const val SOURCE_ID = "bilibili"

    override val id: String = PLUGIN_ID
    override val displayName: LocalizedText = LocalizedText.key("plugin.moemusic.bilibili")
    override val version: String = "1.0.0"
    override val supportedApiVersions: String = ">=2.0.0 <3.0.0"

    override fun onServerRuntimeLoad(ctx: ServerRuntimeContext) {
        ctx.registerMusicSource(BilibiliSource())
        ctx.logger.info("Registered Bilibili MoeMusic source '{}'.", SOURCE_ID)
    }
}

class BilibiliPluginProvider : PluginProvider {
    override fun plugins(): Iterable<Plugin> = listOf(BilibiliPlugin)
}
