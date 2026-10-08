package kr.inmc.titleforge.nickname

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * 화면 속 플레이어 머리 이름에서 실명을 닉네임으로 — [kr.inmc.titleforge.hook.GuiHeadNames] 가 쓴다. 패킷과 무관한 순수 계산이라 테스트한다.
 */
object HeadNames {

    fun plain(name: Component): String = PlainTextComponentSerializer.plainText().serialize(name)

    /** 바꿨으면 새 이름, 이름에 실명이 없으면 null. */
    fun rename(name: Component, realName: String, nickname: Component): Component? {
        if (realName.isEmpty()) return null
        val plain = PlainTextComponentSerializer.plainText().serialize(name)
        if (!plain.contains(realName)) return null
        // 이름 그 자체면 통째로 바꾼다 — 그라데이션처럼 글자마다 조각나 있으면 글자 바꾸기가 조각 사이를 못 넘는다. 모양은 첫 글자의 것.
        if (plain == realName) return Component.text().style(firstStyle(name)).append(nickname).build()
        val replaced = name.replaceText(TextReplacementConfig.builder().matchLiteral(realName).replacement(nickname).build())
        return replaced.takeIf { it != name }
    }

    /** 첫 글자가 든 조각까지 내려가며 합친 모양(아래 조각의 값이 이긴다). */
    private fun firstStyle(component: Component): Style {
        var node = component
        var style = node.style()
        while ((node as? TextComponent)?.content().isNullOrEmpty() && node.children().isNotEmpty()) {
            node = node.children().first()
            style = style.merge(node.style())
        }
        return style
    }
}
