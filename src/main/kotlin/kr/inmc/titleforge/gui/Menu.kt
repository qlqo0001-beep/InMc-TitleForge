package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Items
import kr.inmc.titleforge.util.Sched
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * 경량 GUI 프레임워크.
 *
 * 클릭은 기본 전부 취소되고, 등록된 핸들러만 동작한다 (맞춤 지침 7.5-20).
 * 아이템은 페이지를 열 때만 생성되며 상시 갱신 태스크는 존재하지 않는다.
 */
abstract class Menu(
    protected val plugin: TitleForgePlugin,
    protected val viewer: Player,
    private val rows: Int,
) : InventoryHolder {

    private val handlers = HashMap<Int, (InventoryClickEvent) -> Unit>()
    private var inventory: Inventory = Bukkit.createInventory(this, rows * 9, Component.empty())

    override fun getInventory(): Inventory = inventory

    protected val size: Int get() = rows * 9

    /** 창 제목. 페이지가 바뀌면 다시 평가된다. */
    protected abstract fun title(): Component

    /** 아이템 배치. [button] / [slot] 으로 채운다. */
    protected abstract fun render()

    open fun onClose() {}

    fun open() {
        inventory = Bukkit.createInventory(this, size, title())
        handlers.clear()
        render()
        viewer.openInventory(inventory)
    }

    /**
     * 다음 틱에 연다.
     *
     * `InventoryClickEvent` / `InventoryCloseEvent` 처리 도중 인벤토리를 교체하면 클라이언트가
     * 어긋날 수 있으므로, 메뉴 전환은 항상 이 메서드를 쓴다.
     */
    fun openLater() {
        Sched.entity(plugin, viewer) {
            if (viewer.isOnline) open()
        }
    }

    /** 같은 창을 그대로 다시 그린다. 제목이 바뀌면 [open] 을 쓸 것. */
    fun redraw() {
        handlers.clear()
        inventory.clear()
        render()
        viewer.updateInventory()
    }

    protected fun button(slot: Int, item: ItemStack, handler: ((InventoryClickEvent) -> Unit)? = null) {
        if (slot !in 0 until size) {
            // 조용히 사라지면 레이아웃 실수를 못 찾는다.
            if (plugin.settings.debug) {
                plugin.logger.warning("[GUI] ${javaClass.simpleName}: 슬롯 $slot 이(가) 범위(0~${size - 1})를 벗어났습니다.")
            }
            return
        }
        inventory.setItem(slot, item)
        if (handler != null) handlers[slot] = handler
    }

    protected fun fill() {
        val filler = Items.filler(plugin.settings.gui.filler)
        for (index in 0 until size) {
            if (inventory.getItem(index) == null) inventory.setItem(index, filler)
        }
    }

    internal fun handle(event: InventoryClickEvent) {
        handlers[event.rawSlot]?.invoke(event)
    }

    /** 모든 Menu 의 클릭/닫기를 한 곳에서 처리하는 리스너. */
    class MenuListener : Listener {

        @EventHandler
        fun onClick(event: InventoryClickEvent) {
            val menu = event.view.topInventory.holder as? Menu ?: return
            event.isCancelled = true
            if (event.clickedInventory !== event.view.topInventory) return
            menu.handle(event)
        }

        @EventHandler
        fun onDrag(event: InventoryDragEvent) {
            if (event.view.topInventory.holder is Menu) event.isCancelled = true
        }

        @EventHandler
        fun onClose(event: InventoryCloseEvent) {
            (event.view.topInventory.holder as? Menu)?.onClose()
        }
    }
}
