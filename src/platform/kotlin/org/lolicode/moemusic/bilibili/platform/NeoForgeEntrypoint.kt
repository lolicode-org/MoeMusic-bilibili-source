package org.lolicode.moemusic.bilibili.platform

import net.neoforged.fml.common.Mod
import org.lolicode.moemusic.api.MoeMusicApi
import org.lolicode.moemusic.bilibili.BilibiliPlugin

/**
 * NeoForge mod entrypoint.
 *
 * Instantiated by NeoForge FML during mod loading to register [BilibiliPlugin]
 * with MoeMusic's public plugin API.
 */
@Mod(BilibiliPlugin.MOD_ID)
class NeoForgeEntrypoint {
    init {
        MoeMusicApi.registerPlugin(BilibiliPlugin)
    }
}
