package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Sched
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent

/**
 * inmc-core 의 공용 화면(아이템 올리기 · 색 고르기)을 연다(사용자 요청 2026-10-14 — 손에 든 것 대신 올리기, 색은 RGB 로).
 *
 * 이 플러그인의 메뉴 틀([Menu])과 core 메뉴는 따로라, core 화면의 클릭은 **core 의 MenuListener** 가 받는다. 그 리스너는 core 화면을 쓰는
 * 플러그인마다 하나씩 거는 것이라, 다른 INMC 플러그인 없이 타이틀포지만 있으면 아무도 안 걸어 클릭이 막히지 않는다(창의 채움 아이템을 빼 갈 수 있다).
 * 그래서 열기 전에 우리가 걸었는지 보고 없으면 건다 — 같은 클릭은 한 번만 처리된다(core `MenuListener.firstDelivery`).
 * 여는 것은 다음 틱이다([Menu.openLater] 와 같은 이유 — 클릭 처리 도중 인벤토리를 바꾸지 않는다).
 */
internal object CoreMenus {

    fun open(plugin: TitleForgePlugin, viewer: Player, menu: kr.inmc.core.gui.Menu) {
        ensureListener(plugin)
        Sched.entity(plugin, viewer) { if (viewer.isOnline) menu.open(viewer) }
    }

    /** 플래그가 아니라 지금 걸린 리스너를 본다 — 플러그인을 껐다 켜면 Bukkit 이 우리 리스너를 내린다. */
    private fun ensureListener(plugin: TitleForgePlugin) {
        val ours = InventoryClickEvent.getHandlerList().registeredListeners.any { it.plugin === plugin && it.listener is kr.inmc.core.listener.MenuListener }
        if (ours) return
        val host = plugin.server.pluginManager.getPlugin("inmc-core") as? kr.inmc.core.InmcHost ?: return
        plugin.server.pluginManager.registerEvents(kr.inmc.core.listener.MenuListener(host), plugin)
    }
}
