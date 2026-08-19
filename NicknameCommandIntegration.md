# InMc-TitleForge Nickname Command Integration

> 대상 브랜치: `claude/title-seal-system-plugin-m2b2lh`
> 기준 커밋: `4a85b229fb674825cb320bdfcdd5de7d00a5ffdd`

## 최종 결론

외부 플러그인마다 API를 추가할 필요 없이, TitleForge 자체의 통합 계층으로 닉네임을 다른 플러그인의 명령어 자동완성에 노출하는 것이 가능하다.

핵심은 **Packet API가 아니라 Paper Brigadier command tree + `AsyncPlayerSendSuggestionsEvent`**다. PacketEvents/ProtocolLib은 최종 fallback 계층으로만 둔다.

```text
NicknameService
    ↓
NicknameIndex
    ↓
Paper Brigadier Command Tree
    ↓
Player Argument Classification
    ↓
AsyncPlayerSendSuggestionsEvent
    ↓
Suggestion Transformer
    ↓
Client
```

실행 호환성까지 확보하려면 별도로:

```text
PlayerCommandPreprocessEvent
    ↓
Player argument인지 확인
    ↓
NicknameIndex
    ↓
nickname → 실제 player name
```

을 적용한다.

---

## 1. 현재 저장소 구조 검토

현재 브랜치에는 이미 다음 요소가 있다.

- `NicknameService`
- `NicknameChangeEvent`
- `ProfileManager`
- `Sched`
- `NameDisplayService`
- `TitleForgePlugin`

`NicknameService.commitNickname()`이 실제 `profile.nickname`을 변경하고 dirty 상태를 만들며 저장/표시 갱신을 수행한다. 따라서 별도의 닉네임 저장소를 만들지 않는다.

`ProfileManager`는 `ConcurrentHashMap<UUID, PlayerProfile>` 캐시와 `cachedProfiles()`를 제공하므로 TAB completion 경로에서 DB 조회를 할 이유가 없다.

`Sched`는 `Bukkit.getAsyncScheduler()`, `Bukkit.getGlobalRegionScheduler()`, `Entity#getScheduler()`를 사용하므로 Folia 대응도 기존 abstraction을 그대로 사용한다.

---

## 2. 현재 compile target 주의

현재 `gradle.properties`:

```properties
paperApiVersion=26.1.2.build.74-stable
```

`build.gradle.kts`는 이 값을 그대로 Paper API dependency에 사용한다.

따라서 실제로 Paper 26.2 API를 사용할 경우 compile target도 26.2로 올려야 한다. 현재 target을 유지한다면 해당 버전에 존재하는 API만 사용해야 한다.

---

## 3. 실제 핵심 API

Paper 26.2 기준 핵심 API:

```text
com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent
com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent
com.destroystokyo.paper.event.server.AsyncTabCompleteEvent
org.bukkit.event.player.PlayerCommandPreprocessEvent
```

자동완성 수정의 중심은:

```text
AsyncPlayerSendSuggestionsEvent
```

이다.

이 이벤트는 플레이어, suggestion buffer, `Suggestions`를 제공하고 `setSuggestions(...)`로 결과를 교체할 수 있으므로 NMS packet 조작 없이 Brigadier completion을 변경할 수 있다.

---

## 4. Player argument 판정

가장 중요한 부분이다.

다음 방식은 금지한다.

```text
모든 StringArgument = Player
```

예를 들어 `<name>`이라는 argument가 실제로는 home 이름일 수도 있다.

판정 우선순위:

```text
1. Paper의 명시적 Player/Profile argument
2. Brigadier command tree metadata
3. 기존 suggestion과 online player의 상관관계
4. PluginCommandAdapter
5. 판단 불가 → 원본 유지
```

Paper 26.2에는 다음 Player/Profile 관련 resolver가 있다.

```text
io.papermc.paper.command.brigadier.argument.ArgumentTypes
io.papermc.paper.command.brigadier.argument.resolvers.PlayerProfileListResolver
io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
```

따라서 명시적으로 Player 계열임을 알 수 있는 경우를 가장 높은 신뢰도로 처리한다.

---

## 5. 권장 클래스 구조

```text
kr.inmc.titleforge.command.integration
├── CommandIntegrationService.kt
├── NicknameIndex.kt
├── CommandTreeAnalyzer.kt
├── CommandTreeCache.kt
├── PlayerArgumentClassifier.kt
├── CommandNicknameResolver.kt
├── SuggestionPipeline.kt
├── NicknameSuggestionTransformer.kt
├── AsyncSuggestionListener.kt
├── AsyncTabCompleteListener.kt
├── LegacyTabCompleteListener.kt
├── PlayerCommandResolverListener.kt
└── adapter
    ├── PluginCommandAdapter.kt
    └── PluginCommandAdapterRegistry.kt
```

Packet이 필요한 경우에만:

```text
command.integration.packet
├── PacketProvider.kt
├── PacketEventsProvider.kt
└── ProtocolLibProvider.kt
```

를 추가한다.

---

## 6. NicknameIndex

자동완성 요청에서 DB를 절대 호출하지 않기 위해 역색인을 만든다.

```kotlin
class NicknameIndex {
    private val nicknameToUuid = ConcurrentHashMap<String, UUID>()
    private val uuidToNickname = ConcurrentHashMap<UUID, String>()

    fun put(uuid: UUID, nickname: String)
    fun remove(uuid: UUID)
    fun resolve(nickname: String): UUID?
    fun nickname(uuid: UUID): String?
}
```

정규화에는 TitleForge의 기존 `NicknameNormalizer`를 재사용한다.

초기화는 `ProfileManager.cachedProfiles()`에서 수행한다.

---

## 7. NicknameIndex 갱신 시점

현재 `NicknameChangeEvent`는 변경 직전에 발생하고 취소 가능하다.

따라서 이벤트 발생만 보고 index를 갱신하면 안 된다.

실제 commit 성공 직후:

```text
profile.nickname 변경
↓
NicknameIndex 갱신
↓
저장/표시 갱신
```

으로 맞추는 것이 안전하다.

reset이면 해당 UUID를 index에서 제거한다.

---

## 8. CommandTreeCache

`AsyncPlayerSendCommandsEvent`에서 전달되는 `RootCommandNode<S>`를 분석하되, `CommandNode` 자체를 장기 보관하지 않는다.

권장 metadata:

```kotlin
data class CommandArgumentMetadata(
    val rootLabel: String,
    val path: List<String>,
    val argumentName: String,
    val classification: ArgumentClassification,
)
```

```kotlin
enum class ArgumentClassification {
    PLAYER,
    PLAYER_PROFILE,
    PLAYER_SELECTOR,
    UNKNOWN,
}
```

cache는:

```text
UUID → immutable command metadata
```

형태로 유지한다.

`Player`, `World`, `Entity`, `CommandSourceStack`, `CommandNode`를 장기 참조로 보관하지 않는다.

---

## 9. Suggestion pipeline

```text
AsyncPlayerSendSuggestionsEvent
        ↓
getBuffer()
        ↓
현재 command / argument 위치 확인
        ↓
CommandTreeCache
        ↓
Player argument 판정
        ↓
NicknameIndex
        ↓
Suggestions 생성/병합
        ↓
setSuggestions()
```

기존 `Suggestion`의 range를 유지해야 한다. 단순히 문자열을 새로 만들어 range를 잃게 만들면 TAB 치환 위치가 깨질 수 있다.

---

## 10. ADD / REPLACE

기본값은 `ADD`가 가장 안전하다.

```yaml
nickname-command-integration:
  suggestion:
    enabled: true
    mode: ADD
```

ADD:

```text
Steve
Alex
나인
```

REPLACE:

```text
Steve
나인
```

REPLACE를 사용하려면 실행 단계에서도 nickname을 실제 player 식별자로 해석할 수 있어야 한다.

---

## 11. 실행 단계

자동완성만 nickname으로 바꾸면 외부 플러그인이 nickname을 실제 Minecraft player name으로 인식하지 못할 수 있다.

따라서:

```text
PlayerCommandPreprocessEvent
        ↓
command label 확인
        ↓
argument path 확인
        ↓
Player argument인지 확인
        ↓
NicknameIndex.resolve(token)
        ↓
실제 이름으로 교체
```

를 fallback으로 사용한다.

단, **전체 문자열 replace는 절대 하지 않는다.**

```kotlin
message.replace(nickname, realName)
```

같은 구현은 금지한다.

정확히 해당 Player argument token만 변경해야 한다.

---

## 12. 실제 이름 우선

nickname과 실제 player name이 충돌할 수 있다.

예:

```text
A real name = Steve
B nickname = Steve
```

입력:

```text
/test Steve
```

에서는 실제 이름을 우선한다.

```text
REAL_PLAYER_NAME > NICKNAME
```

또한 다음은 nickname resolver 대상에서 제외한다.

```text
@p
@a
@s
@r
@e
UUID
```

---

## 13. Unknown command 처리

다음과 같은 command의 `<name>`이 Player인지 일반 문자열인지 알 수 없다면:

```text
/example <name>
```

원본 suggestion과 execution을 유지한다.

**확실하지 않은 command를 강제로 변환하는 것보다 호환성을 유지하는 것이 중요하다.**

---

## 14. PluginCommandAdapter

custom argument나 자체 parser를 사용하는 특수 plugin을 위해 TitleForge 쪽에 adapter를 제공한다.

```kotlin
interface PluginCommandAdapter {
    fun supports(pluginName: String): Boolean

    fun classify(
        command: String,
        argumentPath: List<String>,
    ): ArgumentClassification
}
```

모든 plugin에 adapter를 만드는 것은 목표가 아니다. 표준 Paper/Brigadier metadata로 처리되지 않는 경우만 adapter로 보완한다.

---

## 15. Packet API에 대한 결론

PacketEvents/ProtocolLib으로 최종 suggestion packet을 수정하는 것은 가능하지만, packet만으로 해당 argument의 semantic이 Player인지 보장할 수 없다.

따라서:

```text
Packet API = semantic analyzer
```

로 사용하지 않는다.

올바른 우선순위:

```text
Paper Brigadier
↓
AsyncPlayerSendSuggestionsEvent
↓
semantic classification
↓
필요할 때 Packet fallback
```

Packet provider는 기본적으로 비활성화한다.

---

## 16. Folia 26.2

현재 `Sched`가 이미 다음을 사용한다.

```text
Bukkit.getAsyncScheduler()
Bukkit.getGlobalRegionScheduler()
Entity#getScheduler()
```

따라서 integration에서 별도 Folia scheduler를 만들지 않는다.

Suggestion event에서는 가능하면:

```text
String
UUID
immutable metadata
ConcurrentHashMap
```

만 다룬다.

월드/엔티티 조작이 필요한 경우에만 기존 `Sched.entity()` / `Sched.global()`을 사용한다.

---

## 17. Leaf

Leaf 전용 NMS를 core integration에 넣지 않는다.

```text
Paper API
↓
Folia-safe scheduler abstraction
↓
Leaf
```

구조를 유지한다.

Leaf에서 정말 packet-specific 처리가 필요한 경우에만 optional provider로 격리한다.

---

## 18. Legacy completion

Brigadier 외에도:

```text
AsyncTabCompleteEvent
TabCompleteEvent
```

를 독립 completion 경로로 지원한다.

Brigadier와 legacy event가 항상 고정된 호출 순서라고 가정하지 않는다. 중복 변환 방지용 context/key가 필요하다.

---

## 19. DB 접근 금지

TAB 요청마다 다음을 호출하지 않는다.

```text
storage.findUuidByName()
ProfileManager.resolveBlocking()
SQL
File I/O
Network I/O
```

특히 현재 `ProfileManager.resolveBlocking()`은 cache miss 시 DB 조회가 가능하므로 autocomplete 경로에서 사용하면 안 된다.

---

## 20. Offline nickname

기본값:

```yaml
offline-players:
  enabled: false
```

온라인 플레이어만 대상으로 하면 index lifecycle은 단순하다.

```text
Join → add
Quit → remove
```

현재 `ProfileManager`가 offline cache를 유지할 수 있어도 autocomplete index와 동일한 lifecycle로 묶을 필요는 없다.

---

## 21. 권장 설정

```yaml
nickname-command-integration:
  enabled: true

  suggestion:
    enabled: true
    mode: ADD
    tooltip: false

  execution:
    enabled: true
    preprocess: true

  brigadier:
    enabled: true
    command-tree-cache: true

  legacy:
    async-tab-complete: true
    tab-complete: true

  offline-players:
    enabled: false

  packet:
    enabled: false
    provider: auto

  adapters:
    enabled: true

  debug:
    enabled: false
```

---

## 22. TitleForgePlugin 통합

`TitleForgePlugin`에는:

```kotlin
lateinit var commandIntegration: CommandIntegrationService
    private set
```

를 추가한다.

Lifecycle:

```text
enable → register()
reload → reload()
disable → close()
```

reload에서는 command tree metadata를 비우고 설정을 다시 읽는다. NicknameIndex는 프로필 데이터가 바뀐 것이 아니므로 불필요하게 초기화하지 않는다.

---

## 23. 성능 목표

200명 수준 서버에서도 completion request가 다음 경로를 가져야 한다.

```text
TAB
↓
memory lookup
↓
suggestion transformation
```

목표:

```text
TAB 1회당 DB query = 0
TAB 1회당 File I/O = 0
TAB 1회당 Network I/O = 0
```

---

## 24. 테스트 매트릭스

```text
[ ] 명시적 Player argument
[ ] PlayerProfile argument
[ ] Player selector
[ ] 일반 String argument
[ ] Greedy String
[ ] UUID
[ ] @p / @a / @s / @r / @e
[ ] 실제 이름 == nickname
[ ] 다른 player의 실제 이름 == nickname
[ ] nickname 변경 직후 TAB
[ ] nickname reset 직후 TAB
[ ] reload
[ ] join / quit
[ ] Paper 26.2
[ ] Folia 26.2
[ ] Leaf 26.2
[ ] Leaf 1.21.11
```

---

## 25. 구현 순서

### Phase 1

```text
NicknameIndex
NicknameService commit 연동
CommandNicknameResolver
```

### Phase 2

```text
AsyncPlayerSendCommandsEvent
CommandTreeAnalyzer
CommandTreeCache
PlayerArgumentClassifier
```

### Phase 3

```text
AsyncPlayerSendSuggestionsEvent
SuggestionPipeline
NicknameSuggestionTransformer
```

### Phase 4

```text
AsyncTabCompleteEvent
TabCompleteEvent
```

### Phase 5

```text
PlayerCommandPreprocessEvent
```

### Phase 6

```text
PluginCommandAdapterRegistry
```

### Phase 7

```text
PacketEventsProvider / ProtocolLibProvider
```

Packet은 마지막이다.

---

## 26. 절대 구현하지 말아야 할 방식

```text
모든 외부 plugin에 API 추가
모든 String argument를 Player로 취급
TAB마다 DB 조회
command 전체 문자열 replace
Packet만으로 semantic 판정
Paper/Leaf NMS를 core에 직접 의존
```

---

## 27. 근본적인 한계

이 구조가 호환성을 크게 높여주지만 모든 plugin을 100% 자동 해석할 수는 없다.

외부 plugin이:

```text
custom parser
custom dispatcher
String을 받은 뒤 자체 semantic 결정
GUI 내부에서만 command 생성
```

등을 사용하면 TitleForge가 외부 plugin의 내부 semantic을 알 수 없다.

이 경우 우선순위는:

```text
TitleForge-side adapter
>
Packet fallback
>
외부 plugin 수정
```

이다.

외부 plugin을 수정해야만 하는 구조를 기본 전제로 삼지 않는다.

---

## 28. 최종 아키텍처

```text
                         TitleForgePlugin
                               │
                ┌──────────────┴──────────────┐
                │                             │
        NicknameService                CommandIntegration
                │                             │
                ▼                     ┌───────┴────────┐
          NicknameIndex                │                │
                                  CommandTree        Suggestion
                                   Analyzer           Pipeline
                                       │                │
                └──────────────────────┴────────────────┘
                               │
                    CommandNicknameResolver
                               │
              ┌────────────────┼─────────────────┐
              │                │                 │
          Brigadier          Legacy            Adapter
              │                │                 │
              └────────────────┼─────────────────┘
                               │
                       External Commands
```

---

## 29. 검증 기준 자료

- Paper 26.2 API: https://jd.papermc.io/paper/26.2/
- Paper API index: https://jd.papermc.io/paper/26.2/index-all.html
- `AsyncPlayerSendSuggestionsEvent`: https://jd.papermc.io/paper/26.2/com/destroystokyo/paper/event/brigadier/AsyncPlayerSendSuggestionsEvent.html
- `AsyncPlayerSendCommandsEvent`: https://jd.papermc.io/paper/26.2/com/destroystokyo/paper/event/brigadier/AsyncPlayerSendCommandsEvent.html
- `AsyncTabCompleteEvent`: https://jd.papermc.io/paper/26.2/com/destroystokyo/paper/event/server/AsyncTabCompleteEvent.html
- `ArgumentTypes`: https://jd.papermc.io/paper/26.2/io/papermc/paper/command/brigadier/argument/ArgumentTypes.html
- `PlayerProfileListResolver`: https://jd.papermc.io/paper/26.2/io/papermc/paper/command/brigadier/argument/resolvers/PlayerProfileListResolver.html
- `PlayerSelectorArgumentResolver`: https://jd.papermc.io/paper/26.2/io/papermc/paper/command/brigadier/argument/resolvers/selector/PlayerSelectorArgumentResolver.html
- `PlayerCommandPreprocessEvent`: https://jd.papermc.io/paper/26.2/org/bukkit/event/player/PlayerCommandPreprocessEvent.html
- Leaf releases: https://github.com/Winds-Studio/Leaf/releases

---

## 30. 현재 브랜치 실제 소스와의 연관

이번 설계는 다음 실제 파일의 구조를 기준으로 맞췄다.

```text
build.gradle.kts
gradle.properties
src/main/kotlin/kr/inmc/titleforge/TitleForgePlugin.kt
src/main/kotlin/kr/inmc/titleforge/nickname/NicknameService.kt
src/main/kotlin/kr/inmc/titleforge/player/ProfileManager.kt
src/main/kotlin/kr/inmc/titleforge/util/Sched.kt
src/main/kotlin/kr/inmc/titleforge/api/event/Events.kt
```

특히 현재 NicknameService의 commit 흐름, ProfileManager의 memory cache/DB 분리, Sched의 region scheduler abstraction을 고려했다.
