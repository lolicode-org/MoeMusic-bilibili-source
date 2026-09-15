package org.lolicode.moemusic.bilibili.platform

import net.minecraftforge.fml.common.Mod
import org.lolicode.moemusic.api.MoeMusicApi
import org.lolicode.moemusic.bilibili.BilibiliPlugin

/**
 * Minecraft Forge mod entrypoint.
 *
 * Instantiated by Forge FML during mod loading to register [BilibiliPlugin]
 * with MoeMusic's public plugin API.
 */
@Mod(BilibiliPlugin.MOD_ID)
class ForgeEntrypoint {
    init {
        MoeMusicApi.registerPlugin(BilibiliPlugin)
    }
}
