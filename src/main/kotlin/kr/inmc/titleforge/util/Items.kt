package kr.inmc.titleforge.util

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

object Items {

    fun of(
        material: Material,
        name: Component,
        lore: List<Component> = emptyList(),
        glow: Boolean = false,
        amount: Int = 1,
    ): ItemStack = ItemStack(material, amount.coerceIn(1, 64)).apply {
        editMeta { meta ->
            meta.displayName(Text.clean(name))
            if (lore.isNotEmpty()) meta.lore(lore.map(Text::clean))
            if (glow) meta.setEnchantmentGlintOverride(true)
        }
    }

    fun head(owner: OfflinePlayer, name: Component, lore: List<Component> = emptyList()): ItemStack =
        ItemStack(Material.PLAYER_HEAD).apply {
            editMeta { meta ->
                meta.displayName(Text.clean(name))
                if (lore.isNotEmpty()) meta.lore(lore.map(Text::clean))
                if (meta is SkullMeta) meta.owningPlayer = owner
            }
        }

    fun filler(material: Material): ItemStack = of(material, Component.empty())

    fun material(name: String?, fallback: Material): Material =
        name?.let { Material.matchMaterial(it.uppercase()) } ?: fallback
}
