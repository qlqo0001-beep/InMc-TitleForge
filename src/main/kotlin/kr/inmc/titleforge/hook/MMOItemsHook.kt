package kr.inmc.titleforge.hook

import org.bukkit.inventory.ItemStack
import java.lang.reflect.Method
import java.util.logging.Logger

/**
 * MMOItems 아이템 식별.
 *
 * MMOItems 아이템은 바닐라 `minecraft:custom_data` 안에 아래 두 문자열을 넣어둔다.
 * ```
 * MMOITEMS_ITEM_TYPE : "CONSUMABLE"
 * MMOITEMS_ITEM_ID   : "닉네임변경권"
 * ```
 *
 * 읽는 방법을 두 가지 준비해 둔다.
 *  1. **MythicLib `NBTItem`** — 있으면 이걸 쓴다. 정식 경로라 가장 안전하다.
 *  2. **[org.bukkit.inventory.meta.ItemMeta.getAsString]** — MythicLib 이 없거나 API 가
 *     달라졌을 때 쓰는 대비책. 바닐라 API 만 쓰므로 외부 플러그인 버전에 영향받지 않는다.
 *
 * 둘 다 실패하는 상황은 사실상 없지만, 그때는 [available] 이 false 가 되고 호출자가
 * 기동 시 경고를 남긴다.
 */
class MMOItemsHook private constructor(
    private val logger: Logger,
    /** MythicLib 경로. 준비되지 않았으면 null 이고 NBT 문자열 파싱으로 대체된다. */
    private val nbt: NbtAccess?,
) {

    class NbtAccess(val get: Method, val getString: Method)

    @Volatile
    private var nbtBroken = false

    val available: Boolean get() = true

    /** 지금 어느 경로로 읽고 있는지. 기동 로그에 남긴다. */
    val mode: String get() = if (nbt != null && !nbtBroken) "MythicLib NBTItem" else "아이템 NBT 문자열"

    /** 아이템이 지정한 MMOItems 타입/ID 와 일치하는지. MMOItems 아이템이 아니면 false. */
    fun matches(item: ItemStack, type: String, id: String): Boolean {
        val actualType = readTag(item, TYPE_TAG) ?: return false
        if (!actualType.equals(type.trim(), ignoreCase = true)) return false
        val actualId = readTag(item, ID_TAG) ?: return false
        return actualId.equals(id.trim(), ignoreCase = true)
    }

    /** 진단용. 손에 든 아이템이 어떤 MMOItems 아이템인지 돌려준다. */
    fun describe(item: ItemStack): String? {
        val type = readTag(item, TYPE_TAG) ?: return null
        val id = readTag(item, ID_TAG) ?: return null
        return "$type / $id"
    }

    private fun readTag(item: ItemStack, tag: String): String? {
        if (nbt != null && !nbtBroken) {
            readViaNbtItem(item, tag)?.let { return it }
        }
        return readViaItemString(item, tag)
    }

    private fun readViaNbtItem(item: ItemStack, tag: String): String? {
        val access = nbt ?: return null
        return runCatching {
            val handle = access.get.invoke(null, item) ?: return null
            (access.getString.invoke(handle, tag) as? String)?.takeIf { it.isNotBlank() }
        }.getOrElse {
            // 한 번 실패하면 이후로는 대비책만 쓴다. 매 아이템마다 예외를 내지 않는다.
            nbtBroken = true
            logger.warning("MythicLib NBT 읽기에 실패해 NBT 문자열 파싱으로 전환합니다: ${it.message}")
            null
        }
    }

    /**
     * `ItemMeta#getAsString()` 결과에서 태그 값을 뽑는다.
     *
     * 출력 예: `...custom_data:{MMOITEMS_ITEM_ID:"닉네임변경권",MMOITEMS_ITEM_TYPE:"CONSUMABLE"}...`
     */
    private fun readViaItemString(item: ItemStack, tag: String): String? {
        if (!item.hasItemMeta()) return null
        val raw = runCatching { item.itemMeta?.asString }.getOrNull() ?: return null
        if (!raw.contains(tag)) return null
        return tagPattern(tag).find(raw)?.let { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
        }
    }

    private fun tagPattern(tag: String): Regex =
        patternCache.getOrPut(tag) {
            // "값" / '값' / 따옴표 없는 값 세 형태를 모두 받는다.
            Regex("""${Regex.escape(tag)}\s*:\s*(?:"([^"]*)"|'([^']*)'|([^,}\s]+))""")
        }

    private val patternCache = HashMap<String, Regex>()

    companion object {
        private const val TYPE_TAG = "MMOITEMS_ITEM_TYPE"
        private const val ID_TAG = "MMOITEMS_ITEM_ID"

        private val NBT_ITEM_CLASSES = listOf(
            "io.lumine.mythic.lib.api.item.NBTItem",
            "io.lumine.mythic.lib.api.item.nbt.NBTItem",
        )

        /**
         * 연동 준비.
         *
         * MythicLib 을 찾지 못해도 **null 을 돌려주지 않는다.** 값은 바닐라 NBT 에 들어 있어
         * 외부 API 없이도 읽을 수 있기 때문이다.
         */
        fun setup(logger: Logger): MMOItemsHook {
            val access = runCatching {
                val nbtItemClass = NBT_ITEM_CLASSES.firstNotNullOfOrNull { name ->
                    runCatching { Class.forName(name) }.getOrNull()
                } ?: return@runCatching null

                // 타입도 ID 도 문자열 태그다. getType() 같은 부가 메서드에 의존하지 않는다.
                NbtAccess(
                    get = nbtItemClass.getMethod("get", ItemStack::class.java),
                    getString = nbtItemClass.getMethod("getString", String::class.java),
                )
            }.getOrNull()

            return MMOItemsHook(logger, access)
        }
    }
}
