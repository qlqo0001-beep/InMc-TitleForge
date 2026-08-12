package kr.inmc.titleforge.util

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID

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

    /**
     * UUID·이름을 이미 알고 있을 때 쓰는 머리 아이템.
     *
     * [head] 는 [OfflinePlayer] 를 거치는데, 캐시에 없는 UUID 면 이름을 얻으려고
     * 플레이어 데이터 파일을 읽는다. 순위 목록처럼 수십 개를 한 번에 만드는 곳에서는
     * 이미 조회해 둔 이름으로 프로필을 직접 만들어 그 경로를 피한다.
     */
    fun head(uuid: UUID, playerName: String, name: Component, lore: List<Component> = emptyList()): ItemStack =
        ItemStack(Material.PLAYER_HEAD).apply {
            editMeta { meta ->
                meta.displayName(Text.clean(name))
                if (lore.isNotEmpty()) meta.lore(lore.map(Text::clean))
                if (meta is SkullMeta) {
                    meta.playerProfile = Bukkit.createProfileExact(uuid, playerName)
                }
            }
        }

    fun filler(material: Material): ItemStack = of(material, Component.empty())

    fun material(name: String?, fallback: Material): Material =
        name?.let { Material.matchMaterial(it.uppercase()) } ?: fallback
}
