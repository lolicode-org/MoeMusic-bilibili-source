package org.lolicode.moemusic.bilibili.platform

import net.fabricmc.api.ModInitializer
import org.lolicode.moemusic.api.MoeMusicApi
import org.lolicode.moemusic.bilibili.BilibiliPlugin

/**
 * Fabric / Quilt mod entrypoint.
 *
 * Called by FabricLoader during game initialization to register [BilibiliPlugin]
 * with MoeMusic's public plugin API.
 */
class FabricEntrypoint : ModInitializer {
    override fun onInitialize() {
        MoeMusicApi.registerPlugin(BilibiliPlugin)
    }
}
