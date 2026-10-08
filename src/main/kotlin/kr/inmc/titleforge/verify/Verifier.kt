package kr.inmc.titleforge.verify

import kr.inmc.core.util.Text
import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.api.TitleForgeApi
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.player.EquipSlot
import org.bukkit.entity.Player
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `/it verify` — 칭호 지급·장착·해제·회수, 닉네임 형식, 표시 이름, 이름표, PAPI 토큰을 서버 안에서 실제로 돌려 확인한다
 * (드랍·상점 검증기와 같은 틀, 2026-10-08).
 *
 * - 검증하는 사람이 **갖고 있지 않은** 칭호 하나를 잠깐 주고 장착했다가 원래대로 되돌린다(장착했던 것도 복구). 다 가졌으면 건너뛴다.
 * - 닉네임은 바꾸지 않는다 — 형식 검사만.
 */
class Verifier(private val plugin: TitleForgePlugin) {

    data class Result(val name: String, val failure: String?) {
        val skipped: Boolean get() = failure?.startsWith(SKIP) == true
    }

    private class Check(val name: String, val run: (TitleForgePlugin, Player) -> String?)

    fun run(player: Player) {
        val results = CHECKS.map { check ->
            val failure = try {
                check.run(plugin, player)
            } catch (t: Throwable) {
                "검증기 오류: " + t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            }
            Result(check.name, failure)
        }
        player.closeInventory()

        val failures = results.filter { it.failure != null && !it.skipped }
        val skips = results.filter { it.skipped }
        player.sendMessage(
            Text.render(
                "<gold>칭호 검증</gold> <gray>— 통과 <green>${results.size - failures.size - skips.size}</green> · 실패 <red>${failures.size}</red>" +
                    (if (skips.isEmpty()) "" else " · 건너뜀 ${skips.size}") + "</gray>",
            ),
        )
        for (f in failures) player.sendMessage(net.kyori.adventure.text.Component.text(" ✘ " + f.name + " — " + f.failure, net.kyori.adventure.text.format.NamedTextColor.RED))
        for (s in skips) player.sendMessage(net.kyori.adventure.text.Component.text(" – " + s.name + " — " + s.failure!!.removePrefix(SKIP).trim(), net.kyori.adventure.text.format.NamedTextColor.GRAY))

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = java.io.File(plugin.dataFolder, "verify/titleforge-$stamp.txt")
        val text = buildString {
            appendLine("# InMc-TitleForge 검증 - ${LocalDateTime.now()} - ${player.name}")
            for (r in results) {
                appendLine((if (r.failure == null) "PASS " else if (r.skipped) "SKIP " else "FAIL ") + r.name + (r.failure?.let { " — $it" } ?: ""))
            }
        }
        plugin.server.asyncScheduler.runNow(plugin) {
            file.parentFile.mkdirs()
            kr.inmc.core.util.AtomicFiles.write(file, text)
        }
        player.sendMessage(Text.render("<gray>결과 파일: <white>plugins/${plugin.name}/verify/${file.name}</white></gray>"))
    }

    companion object {
        const val SKIP = "건너뜀:"

        private fun ok(condition: Boolean, failure: String): String? = if (condition) null else failure

        private val CHECKS: List<Check> = listOf(
            Check("칭호 정의 — 칭호가 하나 이상 있다") { _, _ ->
                ok(TitleForgeApi.all(BadgeType.TITLE).isNotEmpty(), "칭호가 하나도 없습니다")
            },
            Check("지급 → 보유 → 표시 칭호로 장착 → 원래대로 → 회수") { _, p ->
                val uuid = p.uniqueId
                val badge = TitleForgeApi.all(BadgeType.TITLE).firstOrNull { !TitleForgeApi.has(uuid, BadgeType.TITLE, it.id) }
                    ?: return@Check "$SKIP 모든 칭호를 이미 갖고 있습니다"
                val before = TitleForgeApi.equipped(uuid, EquipSlot.DISPLAY)?.id
                ok(TitleForgeApi.grant(p, BadgeType.TITLE, badge.id), "'${badge.id}' 지급이 실패했습니다")
                    ?: ok(TitleForgeApi.has(uuid, BadgeType.TITLE, badge.id), "줬는데 보유로 안 나옵니다")
                    ?: ok(TitleForgeApi.equip(p, EquipSlot.DISPLAY, badge.id), "장착이 실패했습니다")
                    ?: ok(TitleForgeApi.equipped(uuid, EquipSlot.DISPLAY)?.id == badge.id, "장착했는데 ${TitleForgeApi.equipped(uuid, EquipSlot.DISPLAY)?.id}")
                    ?: run {
                        TitleForgeApi.equip(p, EquipSlot.DISPLAY, before)
                        ok(TitleForgeApi.revoke(p, BadgeType.TITLE, badge.id), "회수가 실패했습니다")
                            ?: ok(!TitleForgeApi.has(uuid, BadgeType.TITLE, badge.id), "회수했는데 아직 보유입니다")
                            ?: ok(TitleForgeApi.equipped(uuid, EquipSlot.DISPLAY)?.id == before, "원래 장착(${before})으로 안 돌아왔습니다")
                    }
            },
            Check("닉네임 형식 — 빈 이름·너무 긴 이름은 거절") { pl, p ->
                ok(!pl.nicknames.validateFormat(p, ""), "빈 닉네임이 통과했습니다")
                    ?: ok(!pl.nicknames.validateFormat(p, "a".repeat(64)), "64자 닉네임이 통과했습니다")
            },
            Check("표시 이름 — displayNameOf 가 비어 있지 않고 이름표가 그려진다") { _, p ->
                val display = TitleForgeApi.displayNameOf(p.uniqueId)
                ok(!display.isNullOrBlank(), "표시 이름이 비었습니다")
                    ?: ok(Text.plain(TitleForgeApi.nameplate(p)).isNotBlank(), "이름표가 비었습니다")
            },
            Check("능력치 — 정의가 읽히고 내 합산이 계산된다") { _, p ->
                ok(TitleForgeApi.statDefinitions().isNotEmpty() || TitleForgeApi.stats(p.uniqueId).isEmpty(), "능력치 정의 없이 값이 있습니다")
            },
            Check("PAPI — 토큰이 값을 준다") { pl, p ->
                if (pl.server.pluginManager.getPlugin("PlaceholderAPI") == null) return@Check "$SKIP PlaceholderAPI 가 없습니다"
                val value = pl.placeholders.apply(p, "%titleforge_nickname%")
                ok(!value.contains("%titleforge_nickname%"), "nickname 토큰이 풀리지 않았습니다: $value")
            },
        )
    }
}
