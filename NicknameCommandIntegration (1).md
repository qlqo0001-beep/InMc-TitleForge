# TitleForge — NicknameCommandIntegration

> 범용 닉네임 명령어 자동완성 및 명령어 인자 해석 통합 설계서
>
> 검증 기준: Paper 26.2 Stable API / Folia 26.2 계열 / Leaf 26.2 계열
> 
> 목적: 외부 플러그인의 소스 코드나 API를 수정하지 않고 TitleForge의 닉네임을 가능한 범위에서 타 플러그인의 명령어 TAB 자동완성과 명령어 인자 해석에 통합한다.

---

## 0. 이번 검증에서 수정한 핵심 사항

기존 초안에는 몇 가지 중요한 과장이 있었다. 이번 문서는 다음 사항을 반영해 수정했다.

1. `AsyncPlayerSendSuggestionsEvent`는 실제로 `getSuggestions()` / `setSuggestions(Suggestions)`를 제공하므로 Brigadier suggestion을 직접 교체할 수 있다.
2. `AsyncPlayerSendCommandsEvent`는 플레이어에게 전달되는 `RootCommandNode` 생성 시점의 이벤트이며 command tree를 관찰/변경할 수 있는 핵심 지점이다. 단, 기본 구현에서는 전체 tree 교체를 하지 않는다.
3. Paper 26.2의 실제 Brigadier argument resolver 계층에는 `PlayerProfileListResolver`, `PlayerSelectorArgumentResolver` 등이 존재한다. 따라서 단순히 `ArgumentType`의 문자열 이름을 비교하는 것보다 Paper resolver/argument type을 이용하는 것이 정확하다.
4. `AsyncTabCompleteEvent`는 별도의 fallback으로 사용할 수 있으며 `getCompletions()` / `setCompletions()` 및 rich `Completion` API를 제공한다.
5. `BasicCommand`는 일반적으로 command를 Brigadier의 greedy-string 표현으로 연결하므로 `BasicCommand`만으로 각각의 인자 의미를 복원할 수 없다.
6. `PlayerCommandPreprocessEvent`는 실제 실행 직전에 문자열을 변경하는 범용 수단이지만, Brigadier command가 이미 별도의 해석 경로를 사용하는 경우 완전한 범용 해결책이라고 볼 수 없다.
7. Packet API는 핵심 해결책이 아니라 최후의 fallback으로 두는 것이 맞다.
8. Folia에서는 전역 main-thread를 가정하는 설계를 피해야 한다.
9. Leaf 26.2는 Paper fork이므로 Paper API를 우선하고 Leaf API를 core에 강제하지 않는다.

Paper 26.2 Stable API 문서에는 `AsyncPlayerSendCommandsEvent`, `AsyncPlayerSendSuggestionsEvent`, Paper Brigadier argument resolver 계층 등이 실제로 확인된다. citeturn1search1turn1search3

---

# 1. 목표

TitleForge가 다음 두 동작을 제공한다.

```text
/test <TAB>
→ 나인
```

그리고 사용자가:

```text
/test 나인
```

을 실행했을 때 외부 플러그인이 실제 플레이어를 대상으로 동작하도록 한다.

핵심은 **자동완성 표시와 명령어 실행 해석을 하나의 기능으로 묶되, 서로 다른 처리 계층으로 구현하는 것**이다.

---

# 2. 현실적인 지원 범위

모든 플러그인의 모든 명령어를 100% 자동으로 이해하는 것은 불가능하다.

특히 다음과 같은 명령어는 표준 API만으로 의미를 확정하기 어렵다.

```text
/test <string>
```

외부 플러그인이 내부에서 `string`을 Player 이름으로 해석하더라도 command tree에는 단순 문자열 argument로만 나타날 수 있다.

따라서 지원 우선순위는 다음과 같다.

```text
1. 명확한 Player/PlayerProfile argument
2. Brigadier command tree + argument resolver 분석
3. Brigadier Suggestions 변환
4. Paper AsyncTabCompleteEvent
5. Bukkit TabCompleteEvent
6. PlayerCommandPreprocessEvent 기반 alias resolution
7. PacketEvents / ProtocolLib fallback
8. 필요할 경우 플러그인별 optional adapter
```

---

# 3. 최종 권장 아키텍처

```text
                         TitleForge
                              │
                       NicknameManager
                              │
                       NicknameCache
                              │
                     NicknameResolver
                              │
              ┌───────────────┴───────────────┐
              │                               │
       Command Resolution                Suggestions
              │                               │
 PlayerCommandPreprocessEvent       AsyncPlayerSendSuggestionsEvent
              │                               │
 CommandArgumentAnalyzer            SuggestionTransformer
              │                               │
 BrigadierCommandAnalyzer           AsyncTabCompleteEvent
              │                               │
              └───────────────┬───────────────┘
                              │
                     Bukkit completion fallback
                              │
                       Packet fallback
                      ┌───────┴───────┐
                      │               │
                 PacketEvents    ProtocolLib
```

---

# 4. 닉네임은 실제 Minecraft 이름을 변경하지 않는다

TitleForge가 해야 하는 것은 GameProfile의 이름을 변경하는 것이 아니다.

다음 데이터를 별도로 관리한다.

```text
UUID → Real Name
UUID → Nickname
Nickname → UUID
Real Name → UUID
```

최종 식별자는 UUID다.

닉네임 변경은 서버의 실제 Player 이름, 인증 프로필, UUID를 변경하지 않는다.

---

# 5. NicknameResolver

모든 command integration은 하나의 resolver를 사용한다.

```java
public interface NicknameResolver {

    UUID resolveUuid(String input);

    Player resolveOnlinePlayer(String input);

    String getNickname(UUID uuid);

    String getRealName(UUID uuid);

    boolean isNickname(String input);

    boolean hasNickname(UUID uuid);
}
```

실제 프로젝트에서는 nullability 정책에 맞춰 `@Nullable`, `Optional` 등을 적용한다.

---

# 6. 해석 우선순위

기본적으로 실제 이름을 닉네임보다 우선한다.

예:

```text
Player A
Real Name = Alex
Nickname  = Steve

Player B
Real Name = Steve
Nickname  = John
```

입력:

```text
/test Steve
```

결과:

```text
Player B
```

정책:

```text
Real Name > Nickname
```

닉네임 자체의 중복은 TitleForge에서 방지하는 것이 가장 안전하다.

---

# 7. NicknameCache

TAB completion은 매우 빈번하게 호출될 수 있으므로 DB를 직접 조회하지 않는다.

권장 구조:

```java
ConcurrentHashMap<UUID, String> nicknameByUuid;
ConcurrentHashMap<String, UUID> uuidByNickname;
ConcurrentHashMap<String, UUID> uuidByRealName;
```

검색용 key는 normalized value를 별도로 유지한다.

---

# 8. Thread safety

Paper 26.2의 suggestion 이벤트는 비동기 이벤트다. 따라서 suggestion 처리에서 Bukkit의 동기 전용 상태에 의존하지 않는다.

자동완성 처리 중 금지:

```text
DB query
File I/O
Network I/O
World modification
Entity modification
동기 전용 Bukkit API 호출
```

허용:

```text
immutable String
UUID
ConcurrentHashMap
불변 command metadata
```

---

# 9. Paper 26.2 — Brigadier command tree

Paper 26.2에서 중요한 실제 타입:

```java
com.mojang.brigadier.CommandDispatcher
com.mojang.brigadier.tree.CommandNode
com.mojang.brigadier.tree.RootCommandNode
com.mojang.brigadier.tree.ArgumentCommandNode
com.mojang.brigadier.context.ParseResults
com.mojang.brigadier.context.CommandContext
com.mojang.brigadier.suggestion.Suggestions
com.mojang.brigadier.suggestion.Suggestion
com.mojang.brigadier.suggestion.SuggestionsBuilder
```

Paper 측 command source:

```java
io.papermc.paper.command.brigadier.CommandSourceStack
```

Paper 26.2 API의 class hierarchy에서도 `ArgumentResolver` 계층과 Paper Brigadier command API가 확인된다. citeturn1search3

---

# 10. Paper command argument resolver를 적극 활용

Paper 26.2에는 다음과 같은 resolver 계층이 존재한다.

```text
io.papermc.paper.command.brigadier.argument.resolvers.ArgumentResolver<T>

PlayerProfileListResolver
PlayerSelectorArgumentResolver
EntitySelectorArgumentResolver
```

특히:

```java
io.papermc.paper.command.brigadier.argument.resolvers.PlayerProfileListResolver
io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
```

등이 실제 API에 존재한다. citeturn1search3turn1search5

따라서 단순히 다음처럼 처리하는 것은 권장하지 않는다.

```java
argumentType.getClass().getSimpleName().contains("Player")
```

가능하면 실제 Paper argument/resolver 계층을 기준으로 판정한다.

---

# 11. Player argument 판정 전략

다음 순서로 판정한다.

```text
A. Paper argument/resolver가 Player 계열임을 확인
        ↓
B. Player selector argument인지 확인
        ↓
C. 명확한 profile/player argument인지 확인
        ↓
D. plugin command metadata가 별도로 알려주는 경우 확인
        ↓
E. 불확실하면 String argument로 취급
```

**일반 `StringArgumentType`은 Player argument가 아니다.**

---

# 12. StringArgumentType을 추측하지 않는다

다음 타입만 보고:

```java
com.mojang.brigadier.arguments.StringArgumentType
```

Player argument라고 판단하면 안 된다.

다음은 모두 가능하다.

```text
/test <player>
/test <item>
/test <world>
/test <message>
/test <id>
```

따라서 의미가 명확하지 않으면 TitleForge는 개입하지 않는다.

---

# 13. BasicCommand의 실제 특성

Paper 26.2의:

```java
io.papermc.paper.command.brigadier.BasicCommand
```

은 Bukkit-style `String[] args` 명령을 Brigadier와 연결한다.

공식 문서상 등록된 표현은 보통:

```text
/commandlabel <greedy_string>
```

형태다. 또한 `execute(CommandSourceStack, String[])`와 `suggest(CommandSourceStack, String[])`를 제공한다. citeturn0search0

따라서 `BasicCommand`를 사용하는 외부 플러그인의 내부 인자 의미를 command tree만 보고 완벽하게 복원할 수 없다.

이 경우 suggestion 결과와 실제 command preprocess resolution을 함께 활용해야 한다.

---

# 14. AsyncPlayerSendCommandsEvent

실제 Paper 26.2 이벤트:

```java
com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent
```

API 문서상:

```text
플레이어에게 명령어 정보를 전달하기 위한 Brigadier RootCommandNode가 생성될 때 발생
```

한다. 생성자도 실제 API에 존재한다. citeturn1search1

용도:

```text
command tree 관찰
argument metadata cache
permission/command visibility 분석
suggestion 분석에 필요한 구조 저장
```

---

# 15. Command tree를 통째로 교체하지 않는다

다음 방식은 기본 구현에서 금지한다.

```text
외부 command tree 복제
        ↓
TitleForge가 수정
        ↓
기존 tree 통째로 교체
```

이유:

```text
permission requirement
redirect
suggestion provider
command execution
command source
plugin lifecycle
```

등의 동작을 손상시킬 가능성이 있다.

기본 구현은 **관찰과 최소 변경**을 원칙으로 한다.

---

# 16. AsyncPlayerSendSuggestionsEvent

Paper 26.2의 실제 이벤트:

```java
com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent
```

실제 API에는 다음 메서드가 존재한다.

```java
Suggestions getSuggestions();
void setSuggestions(Suggestions suggestions);
```

또한 입력 buffer를 얻을 수 있다. 따라서 **서버가 생성한 Brigadier Suggestions를 TitleForge가 직접 수정할 수 있다.** citeturn1search11turn1search1

이것이 TAB 자동완성 통합의 핵심이다.

---

# 17. Suggestion 변환

권장 interface:

```java
public interface SuggestionTransformer {

    Suggestions transform(
        Player player,
        String buffer,
        Suggestions suggestions
    );
}
```

구현:

```java
public final class NicknameSuggestionTransformer
    implements SuggestionTransformer {
}
```

---

# 18. 기존 suggestion을 보존한다

기존 suggestion을 전부 지우고 nickname만 넣지 않는다.

예:

```text
기존:
Steve123
Alex
@p

TitleForge:
나인
```

결과는 정책에 따라:

```text
Steve123
Alex
@p
나인
```

또는 실제 Player suggestion을 nickname으로 치환하는 방식이 될 수 있다.

기본 권장 정책은 **기존 suggestion을 최대한 보존하면서 Player 이름에 해당하는 suggestion만 nickname으로 표시**하는 것이다.

---

# 19. Suggestion range

Brigadier `Suggestion`에는 입력 범위 정보가 포함된다.

따라서 nickname으로 바꿀 때 반드시:

```text
range
text
tooltip
```

을 고려해야 한다.

예:

```text
/test St<TAB>
```

에서:

```text
Steve123 → 나인
```

으로 바꾸더라도 `St`가 교체되어야 할 range를 유지해야 한다.

---

# 20. Tooltip

기존 suggestion에 tooltip이 있으면 가능한 한 보존한다.

TitleForge가 새 nickname suggestion을 생성할 경우 기본적으로 tooltip은 추가하지 않는다.

---

# 21. 닉네임을 suggestion으로 추가하는 조건

다음 조건을 만족할 때만 임의로 nickname suggestion을 추가한다.

```text
1. 현재 command가 확실히 Player argument를 요구함
2. 현재 argument 위치를 정확히 파악함
3. 현재 prefix를 파악할 수 있음
4. nickname cache가 준비되어 있음
5. 해당 nickname이 유효한 대상 Player를 가리킴
```

하나라도 불확실하면 기존 suggestion을 유지한다.

---

# 22. 일반 String command에 nickname 목록을 강제 삽입하지 않는다

예:

```text
/test <string>
```

에서:

```text
/test <TAB>
```

할 때 모든 닉네임을 넣으면 안 된다.

이것은 다음과 같은 command에서 치명적인 오작동을 일으킬 수 있다.

```text
/item <item-id>
/world <world-name>
/config <key>
```

---

# 23. Player selector

다음은 그대로 유지한다.

```text
@p
@a
@e
@s
@r
```

Paper 26.2에는 selector argument resolver 계층이 별도로 존재하므로 selector와 일반 Player profile argument를 구분한다. citeturn1search3

---

# 24. UUID argument

UUID를 요구하는 argument에는 nickname을 주입하지 않는다.

```text
/test <uuid>
```

은:

```text
/test 나인
```

으로 자동 변환하지 않는다.

필요하다면 별도 명령어 alias 문법으로 지원한다.

---

# 25. 명령어 실행 시 닉네임 해석

실행 계층에서는:

```java
org.bukkit.event.player.PlayerCommandPreprocessEvent
```

을 활용한다.

예:

```text
/test 나인
```

→ event command string 분석

→ Player argument라고 확정

→ `NicknameResolver.resolveUuid("나인")`

→ 실제 이름/UUID 기반 argument 생성

→ `event.setMessage(modifiedCommand)`

→ 기존 command dispatch 흐름 유지

---

# 26. dispatchCommand 재호출을 기본 방식으로 사용하지 않는다

다음은 기본적으로 사용하지 않는다.

```java
event.setCancelled(true);
Bukkit.dispatchCommand(player, modifiedCommand);
```

문제:

```text
중복 이벤트
권한 흐름 변화
재귀 가능성
command source 변화
다른 plugin command rewrite와 충돌
```

가능하면 원래 command event의 command string만 수정한다.

---

# 27. 중요한 한계 — PlayerCommandPreprocessEvent의 범용성

`PlayerCommandPreprocessEvent`는 강력하지만 모든 command parser를 이해하는 것은 아니다.

특히 외부 플러그인이:

```text
직접 CommandMap 호출
자체 parser
자체 packet command 처리
별도 command execution path
```

등을 사용하면 이 계층에서 해결되지 않을 수 있다.

따라서 **실행 해석과 TAB 표시를 각각 독립적으로 설계**해야 한다.

---

# 28. 명령어 토큰화

다음 방식은 사용하지 않는다.

```java
command.split(" ")
```

문제:

```text
quoted argument
escaped character
greedy string
반복 공백
```

가능한 경우 Brigadier parse 결과와 argument range를 사용한다.

---

# 29. AsyncTabCompleteEvent fallback

Paper 26.2의 실제 이벤트:

```java
com.destroystokyo.paper.event.server.AsyncTabCompleteEvent
```

이벤트는 비동기 completion을 지원하며:

```java
getCompletions()
setCompletions(List<String>)
completions()
completions(List<Completion>)
getBuffer()
getSender()
isCommand()
```

등을 제공한다. citeturn1search0

특히 rich completion:

```java
AsyncTabCompleteEvent.Completion
```

에는 suggestion과 tooltip을 표현할 수 있다.

---

# 30. AsyncTabCompleteEvent의 중요한 동작

Paper 문서에 따르면 async completion이 비어 있지 않으면 일반적인 동기 `Command.tabComplete(...)` 또는 현재 Player 이름 기반 completion이 호출되지 않는다. 또한 async event 이후 synchronous `TabCompleteEvent`가 발생할 수 있다. citeturn1search0

따라서 TitleForge는 이 이벤트에서 무조건 전체 completion을 교체하기보다:

```text
기존 completion 보존
        ↓
필요한 nickname만 치환/추가
        ↓
setCompletions/completions 적용
```

방식을 사용한다.

---

# 31. TabCompleteEvent fallback

기존 Bukkit/Paper 동기 completion 계층도 fallback으로 유지한다.

다만 최신 Paper에서 Brigadier completion이 이미 처리되는 명령에 대해 무조건 이 이벤트를 최우선으로 가정하지 않는다.

즉:

```text
Brigadier path
Bukkit/Paper legacy path
```

를 독립적인 backend로 취급한다.

---

# 32. Completion 중복 변환 방지

예:

```text
Steve123
 ↓
나인
 ↓
다음 completion event
 ↓
나인 → 다시 resolve 시도
```

를 방지해야 한다.

가장 간단한 방법:

```text
suggestion text가 이미 nickname인지 검사
```

추가로 필요한 경우 request/buffer 기반의 짧은 수명 상태를 사용한다.

Player/Suggestions 객체를 장기 보관하지 않는다.

---

# 33. Packet API

표준 API에서 처리되지 않는 특수 plugin을 위한 최후의 fallback이다.

권장:

```text
PacketEvents
```

대체:

```text
ProtocolLib
```

core에는 다음 abstraction만 노출한다.

```java
public interface PacketProvider {

    boolean isAvailable();

    void enable();

    void disable();
}
```

---

# 34. PacketEventsProvider

```java
public final class PacketEventsProvider
    implements PacketProvider {
}
```

PacketEvents 타입은 provider 내부에만 존재하도록 한다.

목적:

```text
command suggestion packet 관찰
        ↓
suggestion 변환
```

---

# 35. ProtocolLibProvider

```java
public final class ProtocolLibProvider
    implements PacketProvider {
}
```

PacketEvents가 없는 환경에서만 사용할 수 있도록 한다.

둘을 동시에 동일 packet에 적용하지 않는다.

---

# 36. Packet API를 최우선으로 하지 않는 이유

Packet 계층은:

```text
protocol 변경
packet 구조 변경
library version 변경
fork 차이
유지보수 비용
```

의 영향을 받는다.

따라서:

```text
Paper API
    ↓
Brigadier
    ↓
Paper/Bukkit completion
    ↓
Packet fallback
```

이 가장 안전하다.

---

# 37. 특정 플러그인 API는 기본 의존성으로 추가하지 않는다

기본 설계는 다음 플러그인을 특별 취급하지 않는다.

```text
CMI
Lands
MMOItems
ItemsAdder
```

이들은 테스트 대상으로 사용할 수 있지만 core에는 API dependency를 추가하지 않는다.

---

# 38. Plugin Compatibility Adapter

표준 command/suggestion 계층으로 해결되지 않는 플러그인만 optional adapter를 둔다.

```java
public interface PluginCompatibilityAdapter {

    boolean supports(Plugin plugin);

    void onEnable();

    void onDisable();

    boolean transformCommand(...);

    Suggestions transformSuggestions(...);
}
```

adapter가 없어도 TitleForge core는 정상 동작해야 한다.

---

# 39. Folia 26.2 설계

Folia에서는 전역 main thread를 가정하지 않는다.

닉네임 cache는 다음처럼 서버 thread와 독립적으로 사용할 수 있어야 한다.

```text
UUID
String
immutable data
ConcurrentHashMap
```

명령어 suggestion 이벤트에서 region/entity/world 조작을 하지 않는다.

---

# 40. Folia에서 피해야 할 것

```text
Bukkit.getScheduler().runTask(...)를 핵심 로직에 사용
Player 객체 장기 보관
World 객체 장기 보관
async suggestion에서 동기 entity/world API 호출
async suggestion에서 DB 조회
```

필요한 Player 데이터는 cache 또는 이벤트가 제공하는 데이터만 사용한다.

---

# 41. Leaf 26.2

Leaf 저장소의 최신 release 페이지에서 `Leaf 26.2`가 latest release로 표시되며, Leaf 프로젝트는 Paper fork다. Leaf 공식 저장소는 26.2 API에 대해 다음 dependency 예시를 제공한다.

```gradle
compileOnly("cn.dreeam.leaf:leaf-api:26.2.local-SNAPSHOT")
```

그리고 Java 25 toolchain을 사용한다. citeturn0search5turn0search10

TitleForge는 Leaf 전용 API를 core에 강제하지 않고 Paper API를 우선한다.

---

# 42. Platform capability detection

서버 이름 문자열을 직접 비교하는 방식은 피한다.

```java
if (Bukkit.getName().equals("Leaf")) {
    ...
}
```

대신:

```java
public record PlatformCapabilities(
    boolean brigadierSuggestions,
    boolean commandTreeEvents,
    boolean asyncTabCompletion,
    boolean packetProvider
) {}
```

형태로 실제 기능을 확인한다.

---

# 43. Console

기본 기능은 Player command에 집중한다.

필요하면:

```java
org.bukkit.event.server.ServerCommandEvent
```

을 별도 처리한다. Paper 26.2 API에는 `getCommand()`와 `setCommand(String)`이 존재한다. citeturn1search12

기본값:

```yaml
console:
  enabled: false
```

---

# 44. Command Block

기본적으로 지원하지 않는다.

이유:

```text
자동화 command
반복 실행
저장된 command
운영용 command
예상하지 못한 alias 치환
```

등의 위험이 있기 때문이다.

---

# 45. Configuration 제안

```yaml
nickname-command-integration:

  enabled: true

  resolution:
    enabled: true
    online-only: true
    real-name-priority: true

  command:
    enabled: true
    preprocess: true
    brigadier-analysis: true

  tab-completion:
    enabled: true
    brigadier-suggestions: true
    async: true
    bukkit-fallback: true
    add-missing-nicknames: true
    replace-real-player-suggestions: true
    show-real-name-alongside-nickname: false

  packet:
    enabled: true
    provider: auto

  console:
    enabled: false

  command-block:
    enabled: false

  debug:
    command: false
    suggestions: false
    brigadier: false
    packet: false
```

---

# 46. 클래스 구조

```text
com.yourpackage.titleforge
│
├── nickname
│   ├── NicknameResolver.java
│   ├── NicknameManager.java
│   ├── NicknameCache.java
│   └── NicknameNormalizer.java
│
├── command
│   ├── CommandNicknameResolver.java
│   ├── CommandArgumentAnalyzer.java
│   ├── ArgumentTarget.java
│   ├── BrigadierCommandAnalyzer.java
│   └── ArgumentTypeClassifier.java
│
├── suggestion
│   ├── SuggestionTransformer.java
│   ├── NicknameSuggestionTransformer.java
│   ├── BrigadierSuggestionListener.java
│   ├── AsyncTabCompleteListener.java
│   └── TabCompleteListener.java
│
├── brigadier
│   ├── CommandTreeListener.java
│   └── CommandTreeCache.java
│
├── packet
│   ├── PacketProvider.java
│   ├── PacketEventsProvider.java
│   └── ProtocolLibProvider.java
│
└── platform
    ├── PlatformAdapter.java
    ├── PaperPlatformAdapter.java
    └── FoliaPlatformAdapter.java
```

---

# 47. Listener 구성

```text
NicknameCommandIntegration
│
├── PlayerCommandListener
├── BrigadierSuggestionListener
├── AsyncTabCompleteListener
├── TabCompleteListener
└── CommandTreeListener
```

각 listener는 하나의 책임만 가진다.

---

# 48. PlayerCommandListener

```java
public final class PlayerCommandListener implements Listener {

    @EventHandler(
        priority = EventPriority.LOWEST,
        ignoreCancelled = true
    )
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // command 분석
        // 확실한 Player argument만 resolve
        // event.setMessage(...)
    }
}
```

다른 command rewrite plugin과 충돌할 수 있으므로 실제 서버에서 EventPriority 조합을 검증한다.

---

# 49. BrigadierSuggestionListener

```java
public final class BrigadierSuggestionListener implements Listener {

    @EventHandler
    public void onSuggestions(
        AsyncPlayerSendSuggestionsEvent event
    ) {
        // cache-only transformation
        // event.setSuggestions(...)
    }
}
```

실제 Paper 26.2 API에서 `getSuggestions()` / `setSuggestions()`가 확인되므로 이 이벤트는 직접적인 suggestion transformation에 사용할 수 있다. citeturn1search11

---

# 50. CommandTreeListener

```java
public final class CommandTreeListener implements Listener {

    @EventHandler
    public void onCommands(
        AsyncPlayerSendCommandsEvent<?> event
    ) {
        // RootCommandNode 관찰
        // 필요한 metadata cache
    }
}
```

전체 tree 교체는 하지 않는다.

---

# 51. 자동완성 전체 처리 흐름

```text
사용자
  │
  │ /test na<TAB>
  ▼
Minecraft client
  │
  ▼
Brigadier command suggestion request
  │
  ▼
Paper command dispatcher
  │
  ▼
AsyncPlayerSendSuggestionsEvent
  │
  ▼
TitleForge
  │
  ├─ 현재 buffer 확인
  ├─ command tree 확인
  ├─ Player argument인지 판정
  ├─ 기존 Suggestions 분석
  ├─ Real Name → Nickname 치환
  └─ 필요시 nickname 추가
  │
  ▼
setSuggestions(...)
  │
  ▼
Minecraft client
  │
  ▼
나인
```

---

# 52. TAB 이후 실제 실행 흐름

```text
사용자
  │
  │ /test 나인
  ▼
PlayerCommandPreprocessEvent
  │
  ▼
CommandArgumentAnalyzer
  │
  ▼
Player argument 확정
  │
  ▼
NicknameResolver
  │
  ▼
나인 → UUID
  │
  ▼
실제 command argument 생성
  │
  ▼
기존 command dispatcher
  │
  ▼
외부 plugin
```

---

# 53. 가장 중요한 안전장치

다음은 변환하지 않는다.

```text
일반 String argument
UUID argument
selector
message argument 내부의 nickname
불명확한 argument
```

즉:

```text
확실함 → 변환
불확실함 → 원본 유지
```

이다.

---

# 54. 테스트 시나리오

## 54.1 Player argument

```text
/test <player>
```

```text
/test 나인
```

→ 실제 Player 대상으로 실행

## 54.2 실제 이름

```text
/test Steve123
```

→ 기존 동작 유지

## 54.3 일반 string

```text
/test <string>
```

→ nickname을 강제로 추가하지 않음

## 54.4 message

```text
/test 나인 안녕하세요 나인
```

→ 첫 번째 Player argument만 변환

## 54.5 selector

```text
/test @p
```

→ 그대로 유지

## 54.6 UUID

```text
/test <uuid>
```

→ nickname으로 변환하지 않음

## 54.7 collision

```text
Real Name > Nickname
```

---

# 55. 성능 목표

자동완성에서 nickname lookup은:

```text
O(1)
```

을 목표로 한다.

허용:

```text
온라인 Player 수 기준 filtering
ConcurrentHashMap 조회
불변 metadata 조회
```

금지:

```text
DB query
파일 읽기
네트워크 요청
무거운 reflection
전체 command tree 매 요청 재분석
```

---

# 56. Reload

```text
/titleforge reload
```

시:

```text
1. configuration reload
2. nickname cache refresh
3. command metadata cache refresh
4. packet provider 상태 재평가
5. debug 설정 갱신
```

가능하면 listener 자체를 매번 unregister/register하지 않고 configuration reference를 교체한다.

---

# 57. Disable

```text
Packet provider disable
Command tree cache clear
Nickname cache clear
Temporary request state clear
```

을 수행한다.

---

# 58. 의존성 전략

Paper 26.2를 기본 compile target으로 둔다.

Leaf는 Paper API와 호환되는 기능이면 별도 dependency를 추가하지 않는다.

Leaf 전용 API가 실제로 필요한 경우에만 optional adapter를 추가한다.

Leaf 공식 저장소는 26.2 API dependency로 `cn.dreeam.leaf:leaf-api:26.2.local-SNAPSHOT` 예시를 제공한다. citeturn0search10

PacketEvents/ProtocolLib은 optional dependency로 둔다.

---

# 59. 성공 기준

```text
[1] /test <TAB>
    → 닉네임 표시

[2] /test 닉네임
    → 실제 Player 해석

[3] /test 실제이름
    → 기존 동작 유지

[4] 일반 String argument
    → 자동 변환 없음

[5] message 내부 nickname
    → 자동 변환 없음

[6] selector
    → 자동 변환 없음

[7] UUID argument
    → 자동 변환 없음

[8] Paper 26.2
    → 정상

[9] Folia 26.2
    → thread violation 없음

[10] Leaf 26.2
     → Paper 호환 계층으로 정상

[11] PacketEvents/ProtocolLib 없음
     → core 기능 정상
```

---

# 60. 알려진 한계

## 60.1 일반 String argument

```text
/test <string>
```

외부 plugin이 내부적으로 해당 string을 Player 이름으로 사용하더라도 표준 command tree만으로는 그 의미를 확정할 수 없다.

## 60.2 자체 parser

외부 plugin이 표준 Brigadier/Bukkit command flow를 우회하면 자동 분석이 어려워진다.

## 60.3 자체 packet

일반적인 command suggestion path를 사용하지 않는 plugin은 Packet fallback 또는 plugin-specific adapter가 필요할 수 있다.

## 60.4 내부 API 직접 호출

다른 plugin이 내부적으로:

```java
Bukkit.getPlayerExact(input)
```

등을 직접 호출하는 경우 TitleForge가 그 내부 호출을 일반적인 API만으로 자동 변경할 수 없다.

---

# 61. 구현 단계

## Phase 1 — Nickname layer

```text
NicknameCache
NicknameResolver
NicknameNormalizer
```

## Phase 2 — Command resolution

```text
PlayerCommandPreprocessEvent
CommandArgumentAnalyzer
```

## Phase 3 — Brigadier analysis

```text
CommandTreeListener
CommandTreeCache
ArgumentTypeClassifier
BrigadierCommandAnalyzer
```

## Phase 4 — Brigadier suggestions

```text
AsyncPlayerSendSuggestionsEvent
SuggestionTransformer
```

## Phase 5 — Bukkit/Paper fallback

```text
AsyncTabCompleteEvent
TabCompleteEvent
```

## Phase 6 — Packet fallback

```text
PacketEvents
ProtocolLib
```

## Phase 7 — Platform verification

```text
Paper 26.2
Folia 26.2
Leaf 26.2
```

---

# 62. 최종 판단

TitleForge가 모든 plugin에 API를 추가할 필요는 없다.

가장 현실적인 구조는:

```text
                 Paper 26.2
                     │
                 Brigadier
                     │
       AsyncPlayerSendSuggestionsEvent
                     │
             Suggestion transform
                     │
          AsyncTabCompleteEvent
                     │
       PlayerCommandPreprocessEvent
                     │
              Nickname resolve
                     │
              기존 plugin
```

그리고 표준 계층으로 해결되지 않는 경우에만:

```text
PacketEvents / ProtocolLib
```

또는:

```text
Plugin Compatibility Adapter
```

를 사용한다.

---

# 63. 최종 개발 지침

```text
1. Paper 26.2 Stable을 기본 compile target으로 사용한다.
2. Brigadier command tree를 1차 분석 계층으로 사용한다.
3. Paper 26.2의 AsyncPlayerSendSuggestionsEvent를 핵심 suggestion 변환 지점으로 사용한다.
4. AsyncPlayerSendCommandsEvent는 command tree 관찰/metadata 확보에 사용한다.
5. Paper의 Player/PlayerProfile 계열 argument resolver를 우선 활용한다.
6. 일반 StringArgumentType을 Player argument로 추측하지 않는다.
7. Suggestion의 range/text/tooltip을 최대한 보존한다.
8. 기존 suggestion을 불필요하게 삭제하지 않는다.
9. 자동완성과 실제 command resolution을 반드시 함께 구현한다.
10. PlayerCommandPreprocessEvent에서 확실한 nickname만 resolve한다.
11. command를 취소하고 dispatchCommand로 재실행하는 방식을 기본으로 사용하지 않는다.
12. async suggestion 처리 중 DB/파일/네트워크 I/O를 하지 않는다.
13. Folia에서 global main-thread를 가정하지 않는다.
14. Leaf 전용 NMS를 core에 넣지 않는다.
15. PacketEvents/ProtocolLib은 optional fallback으로 격리한다.
16. 외부 plugin API dependency를 기본 구현에 추가하지 않는다.
17. 불확실하면 변환하지 않는다.
18. command tree 전체를 무분별하게 복제/교체하지 않는다.
19. Player 객체를 장기 캐시하지 않는다.
20. reload/disable에서 cache와 packet provider를 정리한다.
21. Paper/Folia/Leaf 각각 실제 서버에서 command/TAB/async/thread 동작을 검증한다.
```

---

# 64. 결론

핵심은 **닉네임을 서버의 실제 이름으로 바꾸는 것**이 아니라, TitleForge가 하나의 Alias 계층으로 동작하는 것이다.

```text
TitleForge Nickname
        │
        ├── Suggestion 표시
        │       ↓
        │   /test 나인<TAB>
        │
        └── Command resolution
                ↓
            /test 나인
                ↓
            UUID resolve
                ↓
          기존 command system
```

Paper 26.2에서 확인된 공식 API를 기준으로 하면 `AsyncPlayerSendSuggestionsEvent`가 가장 중요한 자동완성 통합 지점이고, `AsyncPlayerSendCommandsEvent`와 Paper의 Brigadier argument resolver 계층을 이용해 해당 argument가 실제 Player 계열인지 판별하는 구조가 가장 정확하다. citeturn1search1turn1search3turn1search11

`AsyncTabCompleteEvent`는 legacy/Bukkit-style completion을 위한 보조 계층으로 사용하고, Packet API는 표준 경로에서 해결되지 않는 예외적인 구현에만 사용한다. citeturn1search0

이렇게 구현하면 특정 plugin에 API를 일일이 추가하지 않고도 **표준적인 Player argument를 사용하는 대부분의 명령어에서 닉네임 TAB 자동완성 + 실행 시 닉네임 해석**을 하나의 범용 기능으로 제공할 수 있다.

---

## 참고 API

- Paper 26.2 API — BasicCommand: https://jd.papermc.io/paper/26.2/io/papermc/paper/command/brigadier/BasicCommand.html
- Paper 26.2 API — 전체 클래스: https://jd.papermc.io/paper/26.2/allclasses-index.html
- Paper 26.2 API — AsyncPlayerSendCommandsEvent / AsyncPlayerSendSuggestionsEvent: https://jd.papermc.io/paper/26.2/index-all.html
- Paper 26.2 API — AsyncTabCompleteEvent: https://jd.papermc.io/paper/26.2/com/destroystokyo/paper/event/server/AsyncTabCompleteEvent.html
- Leaf repository: https://github.com/Winds-Studio/Leaf

