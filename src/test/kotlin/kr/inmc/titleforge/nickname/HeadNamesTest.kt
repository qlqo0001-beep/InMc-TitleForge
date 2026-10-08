package kr.inmc.titleforge.nickname

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 화면 속 머리 이름(랜드 멤버 목록 등)의 실명 → 닉네임. */
class HeadNamesTest {

    private val plain = PlainTextComponentSerializer.plainText()
    private val nick = Component.text("나인")

    @Test
    fun `이름 그 자체면 통째로 바꾸고 모양을 지킨다`() {
        val name = Component.text("NineSik", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
        val out = assertNotNull(HeadNames.rename(name, "NineSik", nick))
        assertEquals("나인", plain.serialize(out))
        assertEquals(NamedTextColor.GOLD, out.style().color())
        assertEquals(TextDecoration.State.FALSE, out.style().decoration(TextDecoration.ITALIC))
    }

    @Test
    fun `글자마다 조각난 이름도 통째로 바꾼다`() {
        // 그라데이션은 글자마다 조각이라 글자 바꾸기가 조각 사이를 못 넘는다.
        val name = Component.text().append("NineSik".map { Component.text(it.toString(), NamedTextColor.AQUA) }).build()
        val out = assertNotNull(HeadNames.rename(name, "NineSik", nick))
        assertEquals("나인", plain.serialize(out))
        assertEquals(NamedTextColor.AQUA, out.style().color())
    }

    @Test
    fun `이름이 글 속에 있으면 그 부분만`() {
        val out = assertNotNull(HeadNames.rename(Component.text("NineSik 님의 땅"), "NineSik", nick))
        assertEquals("나인 님의 땅", plain.serialize(out))
    }

    @Test
    fun `실명이 없으면 손대지 않는다`() {
        assertNull(HeadNames.rename(Component.text("다른 사람"), "NineSik", nick))
        assertNull(HeadNames.rename(Component.text("NineSik"), "", nick))
    }
}
