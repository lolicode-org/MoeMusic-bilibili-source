package org.lolicode.moemusic.bilibili

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.plugin.Plugin
import org.lolicode.moemusic.api.plugin.PluginConfigSpec
import org.lolicode.moemusic.api.plugin.PluginProvider
import org.lolicode.moemusic.api.plugin.ServerRuntimeContext
import org.lolicode.moemusic.api.plugin.pluginConfigSpec

object BilibiliPlugin : Plugin {
    const val PLUGIN_ID = "moemusic-bilibili-source"
    const val CONFIG_ID = "moemusic-bilibili-source"

    /**
     * Minecraft Mod ID for Fabric, Forge, and NeoForge loaders.
     * Must be lowercase alphanumeric with underscores (`^[a-z][a-z0-9_]{1,63}$`).
     */
    const val MOD_ID = "moemusic_bilibili_source"

    const val SOURCE_ID = "bilibili"

    override val id: String = PLUGIN_ID
    override val configId: String = CONFIG_ID
    override val displayName: LocalizedText = LocalizedText.key("plugin.moemusic.bilibili")
    override val version: String = "1.2.0"
    override val supportedApiVersions: String = ">=2.2.0 <3.0.0"

    override val configSpec: PluginConfigSpec<BilibiliConfig> = pluginConfigSpec(::BilibiliConfig) {
        long(
            key = "autoplay_favorite_collection_id",
            getter = { it.autoplayFavoriteCollectionId },
            updater = { config, value -> config.copy(autoplayFavoriteCollectionId = value) },
            validator = { _, value ->
                LocalizedText.key("config.moemusic.bilibili.source.validation.favorite_collection_id")
                    .takeIf { value < 0 }
            },
        )
    }

    override fun onServerRuntimeLoad(ctx: ServerRuntimeContext) {
        val source = BilibiliSource(ctx.loadConfig(configSpec))
        ctx.registerMusicSource(source)
        ctx.onConfigChanged(configSpec, source::updateConfig)
        ctx.logger.info("Registered Bilibili MoeMusic source '{}'.", SOURCE_ID)
    }
}

class BilibiliPluginProvider : PluginProvider {
    override fun plugins(): Iterable<Plugin> = listOf(BilibiliPlugin)
}
