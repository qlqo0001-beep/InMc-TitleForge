package kr.inmc.titleforge.hook

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Vault 경제 연동.
 *
 * 이 클래스는 Vault 가 설치되어 있을 때만 로드된다 (맞춤 지침 7.3-12).
 * 외부 클래스 참조를 훅 밖으로 새어나가게 하지 않는다.
 */
class VaultHook private constructor(private val economy: Economy) {

    fun has(player: Player, amount: Double): Boolean =
        runCatching { economy.has(player, amount) }.getOrDefault(false)

    fun withdraw(player: Player, amount: Double): Boolean =
        runCatching { economy.withdrawPlayer(player, amount).transactionSuccess() }.getOrDefault(false)

    fun format(amount: Double): String =
        runCatching { economy.format(amount) }.getOrDefault(amount.toString())

    companion object {
        /** Vault 와 경제 플러그인이 모두 준비된 경우에만 인스턴스를 돌려준다. */
        fun setup(): VaultHook? {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) return null
            val provider = runCatching {
                Bukkit.getServicesManager().getRegistration(Economy::class.java)
            }.getOrNull() ?: return null
            return VaultHook(provider.provider)
        }
    }
}
