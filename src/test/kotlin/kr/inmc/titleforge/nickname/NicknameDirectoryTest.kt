package kr.inmc.titleforge.nickname

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 닉네임으로 사람 찾기(디스코드 `/정보 파노`) — 오프라인 포함, 비교는 [NicknameNormalizer]. */
class NicknameDirectoryTest {

    private val pano = UUID(0, 1)
    private val other = UUID(0, 2)

    @Test
    fun `닉네임으로 찾는다 - 서식·대소문자·전각은 같은 것`() {
        val directory = NicknameDirectory()
        directory.fill(listOf(Triple(pano, "Pano_MD", "<gold>파노</gold>"), Triple(other, "Steve", "Ｓｔｅｖｉｅ")))
        assertEquals(pano, directory.uuidOf("파노"))
        assertEquals(pano, directory.uuidOf(" 파노 "))
        assertEquals(other, directory.uuidOf("stevie"))
        assertNull(directory.uuidOf("없는이름"))
    }

    @Test
    fun `두 명이 같은 닉네임이면 고르지 않는다`() {
        val directory = NicknameDirectory()
        directory.put(pano, "Pano_MD", "파노")
        directory.put(other, "Steve", "파노")
        assertNull(directory.uuidOf("파노"))
    }

    @Test
    fun `실명으로도 찾는다 - 화면 속 머리(랜드)`() {
        val directory = NicknameDirectory()
        directory.put(pano, "Pano_MD", "파노")
        assertEquals("파노", kr.inmc.titleforge.util.Text.plain(directory.byRealName("pano_md")!!.nickname))
        directory.put(pano, null, null)
        assertNull(directory.byRealName("Pano_MD"))
    }

    @Test
    fun `닉네임을 지우면 못 찾는다`() {
        val directory = NicknameDirectory()
        directory.put(pano, "Pano_MD", "파노")
        directory.put(pano, null, null)
        assertNull(directory.uuidOf("파노"))
    }
}
