package kr.inmc.titleforge.api.event

import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.player.EquipSlot
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/** 칭호/인장 지급 직전에 호출. 취소하면 지급되지 않는다. */
class BadgeGrantEvent(
    val target: OfflinePlayer,
    val badge: Badge,
) : Event(), Cancellable {

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLER_LIST

    companion object {
        @JvmStatic
        val HANDLER_LIST = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLER_LIST
    }
}

/** 장착/해제 직전에 호출. [badge] 가 null 이면 해제. */
class BadgeEquipEvent(
    val player: Player,
    val slot: EquipSlot,
    val badge: Badge?,
) : Event(), Cancellable {

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLER_LIST

    companion object {
        @JvmStatic
        val HANDLER_LIST = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLER_LIST
    }
}

/** 닉네임 변경 직전에 호출. [newNickname] 을 수정하거나 취소할 수 있다. */
class NicknameChangeEvent(
    val player: Player,
    val oldNickname: String?,
    var newNickname: String?,
) : Event(), Cancellable {

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLER_LIST

    companion object {
        @JvmStatic
        val HANDLER_LIST = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLER_LIST
    }
}
