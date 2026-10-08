package kr.inmc.titleforge.hook

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.nickname.HeadNames
import kr.inmc.titleforge.nickname.NicknameDirectory

/**
 * 상자 창 속 플레이어 머리의 이름(실명)을 닉네임으로 바꿔 **보낸다** — 사용자 결정 2026-10-07("랜드의 플레이어 이름 … 타이틀포지의 닉네임으로").
 *
 * 랜드는 멤버 목록에 자기가 저장한 실명을 적고, 바꿔 끼울 설정·API 가 없다(jar 확인 — 표시 이름은 랜드 채팅에만 쓴다).
 * 그래서 플레이어에게 가는 화면만 고친다. 서버의 아이템은 그대로라 그 플러그인의 클릭 판정·저장에 영향이 없다.
 *
 * - 창 번호 0(자기 가방)은 건드리지 않는다 — 창조 모드는 가방 칸을 클라이언트가 보낸 그대로 서버에 써서, 바꾼 이름이 진짜 아이템에 박힌다.
 * - 대상은 닉네임이 있는 사람([NicknameDirectory])의 머리 — 프로필 uuid·프로필 이름으로 알아보고, 둘 다 없으면 이름 전체가 실명인 것만.
 * - 패킷(네티) 스레드에서 돈다. 읽는 것은 [NicknameDirectory] 맵과 설정 스냅샷뿐이다.
 *
 * packetevents 클래스는 이 파일에서만 쓴다(규칙 12) — 플러그인이 있을 때만 만든다.
 */
class GuiHeadNames(private val plugin: TitleForgePlugin) : PacketListener, AutoCloseable {

    private val registered: PacketListenerCommon =
        PacketEvents.getAPI().eventManager.registerListener(this, PacketListenerPriority.NORMAL)

    override fun close() {
        PacketEvents.getAPI().eventManager.unregisterListener(registered)
    }

    override fun onPacketSend(event: PacketSendEvent) {
        val type = event.packetType
        if (type != PacketType.Play.Server.WINDOW_ITEMS && type != PacketType.Play.Server.SET_SLOT) return
        val directory = plugin.nicknameDirectory
        if (directory.isEmpty() || !plugin.settings.display.guiHeadNames) return
        if (type == PacketType.Play.Server.WINDOW_ITEMS) {
            val packet = WrapperPlayServerWindowItems(event)
            if (packet.windowId == 0) return
            var changed = false
            val items = packet.items.map { item -> rename(item, directory)?.also { changed = true } ?: item }
            if (!changed) return
            packet.items = items
        } else {
            val packet = WrapperPlayServerSetSlot(event)
            if (packet.windowId <= 0) return
            packet.item = rename(packet.item, directory) ?: return
        }
        event.markForReEncode(true)
    }

    private fun rename(item: ItemStack, directory: NicknameDirectory): ItemStack? {
        if (item.isEmpty || item.type != ItemTypes.PLAYER_HEAD) return null
        val profile = item.getComponent(ComponentTypes.PROFILE).orElse(null)
        // 누구의 머리인가 — 프로필 uuid, 없으면 프로필 이름. 랜드는 스킨만 담은 프로필(다른 uuid)로 머리를 만들어서(테섭 2026-10-08)
        // 둘 다 안 맞으면 **이름 전체가 실명과 똑같을 때만** 그 사람으로 본다(일부만 같으면 남의 글일 수 있어 손대지 않는다).
        val known = profile?.id?.let(directory::get) ?: profile?.name?.let(directory::byRealName)
        for (key in NAME_COMPONENTS) {
            val name = item.getComponent(key).orElse(null) ?: continue
            val entry = known ?: directory.byRealName(HeadNames.plain(name).trim())?.takeIf { it.realName == HeadNames.plain(name).trim() } ?: continue
            val renamed = HeadNames.rename(name, entry.realName, entry.nickname) ?: continue
            return item.copy().also { it.setComponent(key, renamed) }
        }
        return null
    }

    private companion object {
        /** 플러그인이 붙인 이름 — `custom_name`(대부분) 다음 `item_name`. */
        val NAME_COMPONENTS = listOf(ComponentTypes.CUSTOM_NAME, ComponentTypes.ITEM_NAME)
    }
}
