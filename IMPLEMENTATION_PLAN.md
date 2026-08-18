# InMc-TitleForge 구현 계획서 (IMPL)

> ## 적용 현황 (2026-08-19)
>
> | 절 | 상태 |
> |---|---|
> | §1~§3 Phase 0~5 (ProtocolLib 가상 표시) | **보류** — 아래 사유 |
> | §4 닉네임 커맨드 통합 (Brigadier) | **미착수** — 전제는 검증됨 |
> | §4-A 실명 충돌 (사전 차단 + 강제 리셋) | **적용 완료** |
> | §4-B `/it nick reset` | **적용 완료** |
> | §4-B 옵션(b) 실명 사전 차단 | **적용 완료** (구현 방식 변경, 아래) |
>
> **Phase 1~3 을 보류한 이유**
> 1. `PLAN.md` §35 와 개선 계획서가 `❌ ProtocolLib 기본 의존성`·`❌ fake entity packet 시스템`
>    을 명시적으로 금지하고 있어 정면 충돌한다.
> 2. 이 문서 §2.5·§7 스스로 *"현재 상태: 미결정 … 결정 전까지 소스는 legacy 만 유지한다"* 고 적고 있다.
> 3. 전제인 "월드 간 이동 불가"가 **검증되지 않았다.** 실제 보고된 증상은 "텔레포트는 되는데
>    이름표가 옛 자리에 남는다"였고, 그건 별도 원인(재사용 불가 엔티티를 지우지 않던 버그)으로
>    이미 수정됐다. 차원 이동이 정말 실패하는지 실서버에서 확인한 뒤 재검토한다.
>
> 대신 보류로 인한 공백을 메우기 위해 **`PlayerChangedWorldEvent` 처리를 추가**했다.
> 기존에는 이 이벤트가 전혀 없어, 텔레포트 이벤트를 거치지 않는 이동 경로에서 옛 월드에
> 이름표가 남을 수 있었다.
>
> **§4-B 옵션(b) 구현 방식 변경** — 계획서는 전체 `name` 을 읽어 Kotlin 에서 정규화 비교(풀스캔)
> 하라고 했으나, 마인크래프트 아이디는 `[a-zA-Z0-9_]{3,16}` 순수 ASCII 라 NFKC·서식 제거가
> 무의미하고 정규화 결과가 곧 소문자다. 따라서 `LOWER(name)` 비교로 **DB 안에서** 끝냈다.
> 전체 행을 애플리케이션으로 가져올 필요가 없다.
>
> **§4-A 오프라인 알림** — 계획서의 "최소(in-memory)" 대신 **DB 컬럼(`nickname_reset_notice`)**
> 을 택했다. 남의 닉네임을 지우는 파괴적 동작(CLAUDE 21)이라 서버 재시작으로 알림이 유실되면
> 당사자가 영영 모른 채 닉네임만 사라진다.

> 이 문서는 다음 3개의 설계 문서를 기반으로, 현행 소스(`src/main/kotlin/kr/inmc/titleforge`)
> 에 실제 반영하기 위해 작성한 **실행 계획**입니다.
>
> - `InMc-TitleForge — 가상 TextDisplay 기반 이름표 시스템 전환 계획` (이하 **문서 A**)
> - `NicknameCommandIntegration (1).md` (이하 **문서 C** — 개정판, 기준)
> - `NicknameCommandIntegration.md` (이하 **문서 B** — 문서 C의 보조/보완)
>
> 준수 규칙: `CLAUDE.md`(개발 규칙), `PLAN.md`는 **동결 문서이므로 수정하지 않는다**.

---

## 0. 핵심 결론 (사용자 확정 사항)

| 항목 | 확정값 |
|---|---|
| Paper compile target | **26.1.2 유지** (**26.2로 올리지 않음**) — 26.1.2에 통합에 필요한 패키지 확인됨 |
| 최우선 과제 | **월드 간 이동이 불가능한 문제 해결** — 문서 A의 가상(패킷) 표시 전환 |
| 문서 A 접근 | 실제 passenger 제거 → **가상 TextDisplay 패킷 백엔드** (텔레포트 제약 원인 제거) |
| 패킷 구현 | **ProtocolLib soft-depend** 우선, 없으면 Legacy 백엔드로 fallback (텔레포트 보장은 Packet에서만) |
| 문서 B/C 접근 | 문서 C 기준 + 문서 B 보완, **얇은 계층**으로 26.1.2 이벤트만 사용 |
| 닉네임 표시값 | 정규화 키 vs 표시 문자열 **분리** → MiniMessage 원문이 명령어에 주입되지 않게 함 |
| 리팩터 제약 | 새 반복 태스크 금지, 메인 스레드 I/O 금지, 전 경로 메모리 캐시, Paper API만 |

**병합 근거:** 사용자 제공 "개선 실행 계획"과 구조적으로 일치함. 아래 항목을 병합함:
- Phase 0(현황 정리·의존성/호환 사전 확인), Phase 1 회귀검증, Phase 2 선택 활성화,
  Phase 3 기본 전환, Phase 5 성능·안정화, 롤백/기능플래그 전략.
- 단, ① `hideWhenNotVisible`에 PlayerMoveEvent 금지(§3 교정), ② 새 반복 태스크 금지,
  ③ 로컬 검증과 실서버 인수 테스트 분리를 적용.
- 패킷 구현 계열은 **미결정 챕터(§2.5)로 두고 옵션 A/B/하이브리드를 함께 기록** →
  실제 구현 방향은 실서버 검증 후 결정.

---

## 1. 왜 지금 방식이 텔레포트를 못 막는가 (근본 원인)

현재 구조는 `PlayerListener.onTeleport(LOWEST)`에서 `nametags.detachFor()`로 실재 passenger를
떼어내고, `DisplayTicker`가 다음 틱에 다시 붙이는 방식입니다.

그러나 이 방법으론 월드(차원) 간 이동을 보장할 수 없습니다.

1. Paper의 **"플레이어가 passenger를 가진 채 차원 이동" 제한 검사는 `PlayerTeleportEvent` 이전 또는
   그와 무관한 경로**(`teleportAsync`, 포탈, 리스폰, 타 플러그인 warp)에서 일어날 수 있어,
   이벤트에서 늦게 detach해도 이미 `teleport()`가 `false`를 반환할 수 있다.
2. detach 직후 우리의 재생성 티커가 **다음 틱에 재탑승**시키므로, 월드 전송이 그 사이 안 끝났을 때
   이동 도중 다시 passenger 상태로 되돌아갈 수 있다.

**결론:** "실제 passenger가 존재하는 것" 자체가 병목이다. detach/다시 붙이기만으로는 교차 세계
이동을 완전히 통제할 수 없다. → 실제 passenger를 **아예** 없앤다.

---

## 2. 목표 아키텍처 (문서 A 반영)

```
TitleForgePlugin
      │
  NametagService  (도메인 오케스트레이터)
      │
  NametagBackend  (인터페이스)
      ├─ LegacyEntityBackend   (현행 실제 TextDisplay + passenger) — fallback
      └─ PacketBackend         (가상 표시, ProtocolLib) — 목표 기본
            ├─ VirtualDisplay      (+ ViewerState)
            ├─ VirtualEntityIdAllocator
            ├─ DisplayMetadataEncoder
            ├─ ProtocolLibPacketBackend   (Spawn/Metadata/Destroy/Mount 전송)
            └─ PassengerMergeInterceptor (outgoing SET_PASSENGERS 병합)
```

서버 측에는 **실제 엔티티/승객이 없다.** 클라이언트에만 가상 TextDisplay가 탑승되어 보인다.

- 서버 passenger 관계: **없음** → 월드 이동 제한이 생길 수 없다.
- 클라이언트 passenger 관계: **있음** (Spawn/Meta/Destroy + Mount 패킷으로 표현).
- 평시 이동 위치 패킷: **0** (클라 passenger 자동 추적).
- 텍스트 불변 시 Metadata 패킷: **0** (해시 비교).
- **새 반복 태스크 없음** (`DisplayTicker`에 월드 재구성만 얹음).

---

## 2.5 패킷 구현 방식 — (미결정) 옵션 보존

> **조사 결과:** 순수 Paper 공개 API만으로는 가상 엔티티/outgoing 패킷을 다룰 수 없다.
> - `io.papermc.paper.packet` / `io.papermc.paper.network` 발신 계층 없음.
> - Paper의 패킷 관련 이벤트(`io.papermc.paper.event.packet.*`)는 **inbound(수신) 전용**.
> - `Player`에 `sendPacket`/`sendRawPacket`/네트워크 핸들러 접근 메서드 없음.
> - `addPassenger`/`showEntity`/`hideEntity`는 **실재 엔티티에만** 동작.
>
> 따라서 **가상 TextDisplay + 클라 passenger + 발신 SetPassengers 병합**은 아래 중 하나로만 가능.
> 여기서는 결정하지 않고 세 옵션을 함께 보존한다.

### 옵션 A — NMS/리플렉션 thin backend (의존성 0)
- 외부 의존 0. Paper Mojang 매핑 기반 `ServerPlayer.connection.send(...)`으로
  `ClientboundAddEntityPacket`/`ClientboundSetEntityDataPacket`/`ClientboundSetPassengersPacket`/
  `ClientboundRemoveEntitiesPacket` 전송, `SynchedEntityData` 직렬화,
  Netty `ChannelDuplexHandler` 주입으로 발신 `SetPassengers` 병합.
- `NametagBackend` 뒤에 완전 격리 + 버전 게이트 + 실패 시 legacy fallback(경고 로그).
- 장점: 의존성 0 / 단점: 버전 취약(NMS 직접 접근), 유지보수 부담 큼.
- **CLAUDE 9(Paper API 우선/not-API)에 대한 '텔레포트 해결을 위한 의도적 예외'로 명시** —
  단일 백엔드로 격리하여 해당 예외가 도메인·이름표 로직으로 새지 않게 한다.

### 옵션 B — ProtocolLib soft-depend
- 있으면 packet, 없으면 legacy fallback(현행). 안정성이 높고 유지보수 용이.
- 의존성(soft) 1개 추가. 텔레포트 보장은 packet 경로에서만.

### 옵션 A+B 하이브리드 — 자동 선택
- `backend: auto`에서 ProtocolLib 존재 시 B, 없으면 A, 둘 다 불가 시 legacy.
- 다중 백엔드 자동 선택으로 유연하지만 두 경로를 모두 유지해야 함.

> **현재 상태: 미결정.** 실서버에서 ProtocolLib 허용 여부와 Paper 매핑 안정성이 확인된 뒤
> 이 절의 옵션 하나로 좁힌다. 그 전까지 소스는 legacy 만 유지한다.

---

## 3. 실행 계획 단계 (텔레포트 우선)

> **교정 3가지 (사용자 개선 계획 병합 시 반드시 반영)**
> 1. `hideWhenNotVisible`은 **PlayerMoveEvent를 쓰지 않는다.** 기존 `DisplayTicker →
>    refreshVisibility()`(독립 `visibility-check-ticks` 주기)를 그대로 재사용한다. 숨김=`destroy()`만,
>    복구=`Spawn+Meta+Mount`. → **이동 중 패킷 0 목표 유지**(문서 A 4.1·13·40.1).
> 2. **새 반복 태스크를 만들지 않는다**(CLAUDE 4). Phase 5의 "패킷 배치"는 `DisplayTicker` 한 틱
>    처리 안에서만 모아 전송.
> 3. **로컬 검증(순수 로직·컴파일)과 실서버 인수 테스트(ProtocolLib 호환·패킷 추적·WireShark)를 분리.**

### Phase 0 — 코드 구조 파악 및 준비 (현황 정리)
- `NametagService` 상 의존성 그래프, `detachFor()` 호출 지점 전수(이벤트·리스폰·명령어).
- 실서버에서 ProtocolLib 5.1.0 + Paper 26.1.2 호환 및 치장 충돌(SET_PASSENGERS) 추적 —
  단, 이는 **실서버 인수 테스트 목록**으로 분리(로컬에서는 불가).
- 산출물: `NametagService` 의존성 그래프, `detachFor` 호출 목록, ProtocolLib 호환 보고서(서버에서).

### Phase 1 — NametagBackend 추상화 (동작 변경 없음 + 회귀)
- `display/NametagBackend.kt` 인터페이스 신규:
  - `spawnDisplay(owner, layer)`, `updateDisplay(owner, layer, component)`,
    `destroy(owner, layer)`, `destroyAll(owner)`, `onWorldChange(owner)`, `cleanup()`.
- 기존 실물 로직을 `display/LegacyEntityBackend.kt`로 이동(기존 `spawn`/`refreshLayer`/`hideVanilla` 등).
- `NametagService`는 백엔드를 구성 주입받아 위임. 설정 `display.nametag.backend`로 `auto|packet|legacy`.
- **검증: 기존 모든 기능이 동일 동작(회귀)**, 실제 엔티티 경로(legacy)와 동치여야 함. 실패 시 즉시 legacy 유지.

### Phase 2 — 가상(패킷) 백엔드 프로토타입 (선택적 활성화)
파일(문서 A 참고):
- `display/packet/VirtualDisplay.kt`
  - 소유주 UUID, 전역 할당 entityId, 가상 UUID, `Layer`, 렌더 결과(표시 문자열),
    `ViewerState`(shared/others 보이기·스폰 bool, lastMetadataHash).
- `display/packet/VirtualEntityIdAllocator.kt`
  - 전역 고유 ID(사용 안 하는 대역) 할당/회수. 스레드 안전.
- `display/packet/DisplayMetadataEncoder.kt`
  - 현재 Bukkit TextDisplay 설정(billboard, see-through, shadow, viewRange, alignment, background,
    높이 offset → translation)을 프로토콜 메타로 변환. adventure `Component` → NMS Component.
- `display/packet/PassengerMergeInterceptor.kt`
  - outgoing `SET_PASSENGERS` 감시: 기존 passenger 리스트 **보존 + TitleForge virtualId 추가**,
    중복 방지. 평상시 0 비용.
- `display/packet/ProtocolLibPacketBackend.kt`
  - ProtocolLib 적재: viewer별로 Spawn(=엔티티Spawn+TextDisplayMetadata)/Mount/Destroy를
    `player.sendPacket()`로 전송.

동작:
```
Spawn(보일 때)  : Spawn + Metadata + Mount(virtualId를 passenger 목록에 merge)
Update(변경)     : Metadata 만 (해시 다를 때 1회)
Hidden(안 보임)  : Destroy 만
WorldChange      : 기존 Destroy → 새 월드에서 Spawn + Metadata + Mount
```

- **활성화:** 설정 `backend: packet`으로 전환 시에만 적용(테스트 서버). 실서버 충분 검증 전까지 기본 전환 보류.
- **단위 테스트:** allocator 고유성·회수, 메타데이터 해시 skip, 패킷 순서, passenger merge 리스트.

### Phase 3 — NametagService / PlayerListener 재구성 + 기본 백엔드 전환
- `NametagService`: Handle을 실물 Display → `VirtualDisplay`로 교체, detach 관련 로직 제거.
- `TeleportGrace` / `detachFor` **제거** (더 이상 실제 passenger가 없으므로).
- `PlayerListener`:
  - `PlayerTeleportEvent`에서 **detach하지 않음** (이동 자체를 막지 않음).
  - `PlayerChangedWorldEvent` / 회귀 / 리스폰(`PlayerRespawnEvent`)에서 `backend.onWorldChange()` 로 표시 재구성.
- `hide-when-not-visible` 유지 — **PlayerMoveEvent 금지**, 기존 `DisplayTicker → refreshVisibility()` 재사용:
  숨김=`destroy()`만, 복구=`Spawn+Meta+Mount` (문서 A 9절).
- **`hide-vanilla`(B4):** `hideVanillaNametag()/restoreVanillaNametag()`는 **공용(`NametagService`)에 두고**
  모든 백엔드에서 호출 → packet 모드에서도 `hide-vanilla: true`가 유지되어야 함(회귀 방지).
- `Packet` 기본 전환 후에도 ProtocolLib 없으면 자동 legacy fallback (단 **텔레포트 보장은 packet에서만**, 경고 로그).
- 위험 완화: 롤백 즉시 가능 — 기본값 전환은 별도 브랜치/기능 플래그로.
- **B5 reload 원자성:** `backend`가 reload에서 바뀌면 기존 백엔드 teardown(`destroyAll`·캐시) → 새 백엔드 재생성. 유령 잔존·새 티커 금지.
- **B6 스레드:** 패킷은 **뷰어별**로 해당 뷰어 소유 리전 스레드(`Sched.entity(viewer)`)에서만 전송(Folia 안전, 해시 비교로 변경 시에만).

### Phase 4 — 의존성 / 설정 / 검증
- `build.gradle.kts`: ProtocolLib **compileOnly** 추가(soft-depend, 셰이딩 안 함). `plugin.yml` softdepend 기재.
- `config.yml` / `Settings`:
  ```yaml
  display:
    nametag:
      enabled: true
      backend: auto     # auto | packet | legacy  (기본 auto — ProtocolLib 있으면 packet)
  ```
- 단위 테스트(순수 계층, 로컬): allocator(고유·회수), metadata 해시 skip, passenger merge 리스트, ViewerState 전이.
- 실서버 인수 테스트(문서 A 33·34): 월드 이동(오버·넷·엔드), 이름표 유지(shared/others, view-range),
  치장 병합 순서 A~E, 텍스트 변경 시 메타 1회, 성능(이동 중 패킷 0, 텍스트 불변 시 메타 0).
  로컬에서 불가한 항목은 반드시 이 목록으로 분리 관리.

### Phase 5 — 성능 최적화 및 안정화
- 패킷 전송 **배치**: `DisplayTicker` 한 틱 처리 안에서만 변경분을 모아 전송 (새 티커 금지).
- `VirtualDisplay` 캐시 정리(퇴장·만료) → 메모리 누수 방지.
- 대규모 인원(50~300) 측정: 이동 중 패킷 0, 텍스트 불변 시 메타 0, TPS 20 유지.
- 디버그 로그는 별도 레벨로 분리.

### 우선순위
1. **Phase 0→3 (가상 표시, 텔레포트 해결)** — 낙감한 위험을 먼저, Phase 2는 별도 브랜치로.
2. 그 다음 문서 C/B **닉네임 커맨드 통합** (아래 §4).

---

## 4. 이후 단계 — 닉네임 커맨드 통합 (문서 C 기준 + B 보충)

### 확인(완료): 26.1.2에 필요한 패키지 존재
- `com.destroystokyo.paper.event.brigadier` (AsyncPlayerSendSuggestions/CommandsEvent)
- `com.destroystokyo.paper.event.server` (AsyncTabCompleteEvent)
- `io.papermc.paper.command.brigadier.argument.resolvers` (PlayerProfileListResolver 등)

→ **Paper 버프 없이 진행 가능.**

### 전체 우선순위
1. Paper Brigadier — `AsyncPlayerSendCommandsEvent` → 트리 메타 분석 → Player/Profile argument 판별
2. `AsyncPlayerSendSuggestionsEvent` → 닉네임 제안 추가(ADD, range 보존)
3. `AsyncTabCompleteEvent` / `TabCompleteEvent` — legacy 보조
4. `PlayerCommandPreprocessEvent` — 실행 시 닉네임 → 실제 이름 해석
5. `PluginCommandAdapter` — 특수 커맨드 대응(옵션)
6. PacketEvents/ProtocolLib — 최종 fallback (초기 미포함)

### 설계·구현 포인트
- `command/integration/NicknameIndex.kt`
  - Key 3종: `nicknameNorm↔UUID`, `realname↔UUID`, `displayString↔UUID`.
  - `NicknameNormalizer` 재사용, `displayString = Text.plain(Text.mini(닉네임Mini))` (원문 아님).
  - 초기화 `ProfileManager.cachedProfiles()`, `ConcurrentHashMap`, O(1), TAB당 조회 0회.
- `command/integration/NicknameResolver.kt` — `isNickname/resolveUuid/...`, **Real Name > Nickname**.
- `SuggestionAugmenter` (`AsyncPlayerSendSuggestionsEvent` 하나의 리스너)
  - 현재 제안이 이미 온라인 플레이어 실이름과 유사 → 그 arg가 Player 후보 → 닉네임 **추가**(range 보존).
  - 유사하지 않으면 원본 유지. selector/@p 등은 절대 추가/변경 안 함.
- `PlayerCommandResolverListener` (`PlayerCommandPreprocessEvent`)
  - **마지막 인자 토큰 하나**만 Index.resolve → 실제 이름 치환. 전체 문자열 replace 금지.
- `config`/`Settings`: `nickname-command-integration:` 블록 (suggestion.mode ADD, offline:false, debug:false).
- `TitleForgePlugin`: `commandIntegration` 배선, enable/reload/disable(config 참고 교체).
- 연동 시점: `NicknameService.commitNickname()` **성공 시점**(취소 가능한 사전 이벤트 아님)에만 인덱스 갱신.

### 문서 C/B와 다른 점 (더 나은 점)
- 무거운 `AsyncPlayerSendCommandsEvent` 트리-캐시 + 정교 classifier + 패킷 어댑터를 **초기에 만들지 않고**,
  **"기존 suggestion과 온라인 플레이어 상관관계" 기반으로 1순위**로 사용 → 코드량·브랜치 리스크 감소,
  커버 넓음.
- 닉네임 표시 문자열과 조회 key를 분리 → MiniMessage 원문이 명령어에 주입되는 것 방지.

---

## 4-A. 이슈: 닉네임이 실제 플레이어 실명과 충돌하는 경우 — 옵션 c (원인→이유→해결방안)

### 원인 (Cause)
- `nickname.unique`(중복 검사)는 **다른 플레이어의 닉네임(`nickname_normalized`)끼리만** 비교한다
  (`SqlStorage.isNicknameTaken`: `WHERE nickname_normalized = ? LIMIT 5`). 플레이어의 **실제 아이디(`name`)
  컬럼은 고려하지 않는다.
- 따라서 `ninesik`이 닉네임 "nine"을 지정한 상태에서, 나중에 **실제 아이디가 `nine`인 계정이 처음 접속**하면:
  - 닉네임 UNIQUE는 NULL을 다수 허용 → DB/조인은 **정상 통과**(자동 처리는 없음).
  - "닉네임 nine인 ninesik"과 "실명 nine인 real nine"이 공존하게 됨.
  - `NicknameIndex`에서 키 "nine"이 `realname → real nine`과 `nicknameNorm → ninesik` 두 곳에 걸림.

### 이유 (Why this is a problem)
- 명령어 해석(Real Name > Nickname)에서 "nine"이 **real nine의 온라인 여부에 따라 다른 플레이어를 가리킨다**
  → **비결정적**. pay/teleport 등 플레이어 인자 명령이 원치 않는 대상에 갈 수 있음.
- ninesik의 닉네임 "nine"은 real nine이 온라인인 동안 **명령어 대상에서 섀도잉**되고,
  표시도 둘 다 "nine"으로 보여 사용자가 혼동.

### 해결방안 (Solution) — 옵션 c (+ 실명 비교 보강)
1. **강제 리셋**: 실명 N인 플레이어가 접속하면(`PlayerJoinEvent` 부근, `Sched.async`) 다음을 조회해 닉네임 보유자 H를 찾는다.
   ```sql
   SELECT uuid FROM tf_player
   WHERE nickname_normalized = Norm(N) AND uuid <> :self
   ```
   (비교는 `NicknameNormalizer` 재사용 — 대소문자·전각 통일)
2. H가 존재하면:
   - `H.nickname = NULL`, `nickname_normalized = NULL` → **원래 닉네임(실명 `H.name`)으로 강제 변경**.
     `markDirty` → `saveProfile` (저장 규칙 유지: `INSERT...IGNORE`+`UPDATE`, `REPLACE` 금지).
   - 온라인이면 `Sched.entity(H_player)`에서 `NameDisplayService.refresh` + 이름표 갱신.
   - `NicknameIndex`에서 H의 `nicknameNorm/displayString` 항목 제거.
   - **알림**: 온라인 → 즉시 *"실제 플레이어 이름과 같아 닉네임 'nine'을 사용할 수 없습니다. 원래 닉네임으로 강제 변경되었습니다."*(messages.yml 키);
     미접속 → **pending 알림 기록** 후 **다음 접속 시**(join/applyState) 동일 메시지 표시.
3. **결정성 확보**: 이후 "nine"은 항상 real nine(실명 우선)으로 해석 → 비결정성 제거. ninesik는 "ninesik"로만 지목.
4. **(보조) 실명 비교**: `isNicknameTaken`에 `name`(정규화) 비교를 추가 → 이후 같은 실명과 겹치는 닉네임 신청을 **사전 차단**.
5. **오프라인 알림 지속성**:
   - 최소: in-memory `pendingForcedReset`(`ConcurrentHashMap<UUID, String>`) → 세션 내 다음 접속 시 알림(서버 재시작 시 알림 유실, 정확성엔 무영향).
   - 선택(DB 영속): `tf_player`에 `nickname_reset_reason TEXT` 컬럼 추가 + 마이그레이션 → 재시작 후에도 알림 보존. **마이그레이션 부담 여부는 구현 시 결정.**
6. **검증**: 실명 nine 첫 접속 시퀀스 / ninesik 온라인·오프라인 두 경우 / 재시작 후 pending 보존 / 실명과 겹치는 닉네임 신청 차단.

---

## 4-B. 닉네임 원래 아이디로 되돌리기 — 사용자 자기 리셋 (nick reset) — 옵션 (b) 유지

### 원인 (Cause)
- 관리자용 `/it resetnick <플레이어>`는 있지만, **일반 유저가 자기 닉네임을 원래 아이디로 되돌리는 경로가 없다.**
  `/it nick`(→ `requestChange`)은 입력창으로 "새 닉네임"을 받는 변경만 지원하며, reset(원래 아이디 복귀)은 커버하지 않는다.
- (참고) 현재 닉네임 중복 검사(`isNicknameTaken`)는 **다른 플레이어의 닉네임(`nickname_normalized`)끼리만** 비교 —
  실명(`name`)과 겹치는 닉네임을 설정 시점에 막는 옵션 (b)는 아직 미구현(계획에만 있음).

### 이유 (Why this is needed)
- 유저가 닉네임을 바꿨다가 "그냥 원래 이름으로 돌리고 싶다"면 **관리자 개입 없이 스스로** 되돌려야 한다.
- 이를 일반 변경(쿨타임·비용)과 동일하게 취급하면 번거로워 reset은 **비용·쿨타임 없는 자기 리셋**이 적절.
- 옵션 c(§4-A)의 강제 리셋 이후에도 유저 스스로 되돌리는 경로가 보장되어야 UX가 완성된다.

### 해결방안 (Solution) — 미구현, 계획서 기록 (소스 반영 시)
1. `NicknameService.resetOwn(player)` 추가:
   - `config.enabled`·`PERMISSION` 검사 → `profile` 없으면 `nickname.disabled`/`general.profile-loading` 처리.
   - `profile.nickname == null`이면 **신규 키 `nickname.no-nickname`** 출력 후 종료.
   - `applyNickname(player, profile, null, touchCooldown = false)` 호출 → **원래 아이디(실명)로 복귀**.
     비용·쿨타임 없음, `nickname.reset` 메시지, 기존 commit 경로 재사용(저장·표시 갱신 포함).
2. 명령어: `/it nick` 의 2번째 인자 `reset` 지원 — `args.getOrNull(1)?.equals("reset", true) == true`면
   `resetOwn(player)`, 아니면 기존 `requestChange(player)`. (탭완성에 `reset` 제안 추가, 옵션)
3. 메시지(CLAUDE 11): `messages.yml`에 `nickname.no-nickname` 키 추가.
4. **옵션 (b) 유지 — 실명 비교 사전 차단(구현 메모)**:
   - 목적: 닉네임 신청이 **이미 알려진 실명**과 겹치지 않게 설정 시점에 차단(4-A item 4 병용).
   - `SqlStorage.isNicknameTaken` 확장:
     (1) 기존 `nickname_normalized` 비교 유지
     (2) `SELECT name FROM tf_player WHERE (? IS NULL OR uuid != ?)` 로 실명을 읽고
         Kotlin에서 `NicknameNormalizer.normalize(name)`과 비교(except=본인 제외).
   - **SQL 정규화 한계:** `NicknameNormalizer`는 NFKC·대소문자·서식 제거라 SQL로 재현 불가 → 실명 비교는
     **Kotlin에서 수행**. 닉네임 변경이 드묾(쿨타임 기본 7일)이라 풀스캔 비용 허용. `Sched.async` 유지.
   - 효과: 실명 "nine"이 이미 저장되어 있으면 닉네임 "nine" 신청을 사전 차단.
     미래 첫 접속 계정은 사전 차단 불가 → **옵션 c(§4-A, 접속 시 강제 리셋)와 병용**.
   - 기존 테스트 무영향: `SqlStorageNicknameTest`의 실명 "Steve"·닉네임 "윤" 조합은 실명 비교에 걸리지 않음.
5. **검증**: 비저장 유저 `/it nick reset`(no-nickname) / 저장 유저 reset→실명 복귀·표시 갱신 / 비용·쿨타임 미소모 /
   실명 "nine" 저장 후 닉네임 "nine" 신청 차단(b) / option c 강제 리셋과 공존.

---

## 5. 제약 요약 (CLAUDE/PLAN 준수 확인)

- `PLAN.md` 동결 → 수정 안 함. 사용자 맞춤 문서만 추가.
- 새 반복 태스크 없음(기존 3개 유지, 이벤트 구동). 패킷 배치는 `DisplayTicker` 안에서만.
- 메인 스레드 JDBC·파일·네트워크 금지, 조회는 캐시.
- **이동 위치 갱신·시야 처리에 PlayerMoveEvent를 쓰지 않는다**(문서 A). 시야 가림은 기존 `refreshVisibility()` 재사용.
- Paper API 우선, 외부 플러그인 클래스 참조는 훅에서만, 셰이딩 relocate 유지.
- `NicknameNormalizer` 한 곳. 유저 입력 서식은 입력 경계에서만 이스케이프(CLAUDE 24).
- 상태 변경은 `markDirty` 기반, 인덱스는 순수 캐시만.
- 닉네임 커맨드 통합은 `NicknameChangeEvent`(취소 가능)가 아니라 `commitNickname()` 성공 시점에만 인덱스 갱신.
- 로컬(순수 로직·컴파일) 검증과 실서버 인수 테스트(ProtocolLib 호환·패킷 추적)를 명확히 분리.

### CLAUDE 규칙 면밀 검토 — 반영 필요 보완 항목 (B1~B8)

**B1 — 신규 설정 키 3곳 동기화 (필수)**
- `display.nametag.backend`, `nickname-command-integration.*` 를 **config.yml + Settings.kt + tools/verify_gui.py**
  세 곳에 동시 반영. 미반영 시 `verify_gui.py` 정적 검증 실패(CLAUDE「검증 방법」).

**B2 — 외부 플러그인(ProtocolLib)은 훅에서만, softdepend 유지**
- ProtocolLib 클래스 참조는 전용 패킷 백엔드 훅 내에서만, `getPlugin("ProtocolLib")` 존재 확인 후 로드(NoClassDefError 차단).
- `plugin.yml`은 `depends`가 아니라 기존 `softdepend` 목록에 추가, `api-version` 유지.

**B3 — 옵션 A(NMS/리플렉션)는 CLAUDE 9의 명시적 예외로 기록**
- NMS는 "Paper API 우선/not-API" 원칙과 배치 → 단일 백엔드로 완전 격리 + 버전 게이트 + legacy fallback(
  경고 로그)로 관리하고, **§2.5에 "텔레포트 해결을 위한 의도적 예외"임을 명시.**

**B4 — `hide-vanilla`는 공용(backend-agnostic)으로 유지**
- `hideVanillaNametag()/restoreVanillaNametag()`(Team `tf_hidden_name`)은 `NametagService`(공용)에 두고
  **모든 백엔드(legacy·packet)에서 호출**한다. 백엔드 안에만 넣으면 packet 모드에서 `hide-vanilla`가 무시됨(회귀).

**B5 — reload 원자성 (CLAUDE 8) + 백엔드 전환**
- `backend` 값이 reload에서 바뀌면 **기존 백엔드 teardown(`destroyAll`·캐시 정리) → 새 백엔드 재생성**을 원자적으로.
- 중간 상태에서 유령 엔티티/패킷 잔존 금지, 새 반복 태스크 생성 금지(CLAUDE 4).

**B6 — 스레드 규칙 (CLAUDE 1-3) — 패킷 송신**
- 패킷 백엔드는 **뷰어마다** 전송 → 각 뷰어 소유 리전 스레드에서(`Sched.entity(viewer)`) 송신(Folia 안전).
- 변경 시에만(해시 비교) 전송해 비용 억제.

**B7 — metrics/bStats**
- `backend` 등 신규 설정이 bStats 콜백에 들어갈 경우 **비메인 스레드에서 불변 `Settings`만 읽도록** 하고
  개인식별 값(닉네임 등) 포함 금지 유지(CLAUDE 12 bStats).

**B8 — messages.yml (CLAUDE 11) — 신규 사용자 문구 최소화**
- 기본은 **무음(log만)** 유지. 사용자 안내를 추가할 경우 `messages.yml` 키를 함께 등록.

---

## 6. 검증 기준

- 단위 테스트: allocator, VirtualDisplay 해시 skip, passenger merge 로직,
  NicknameIndex(정규화·우선순위·표시 원문 주입 방지), suggestion range 보존.
- 수동 서버 테스트(문서 A 33·34):
  - 월드 이동: Overworld→Nether→End→Overworld 정상.
  - 이름표 shared/others, hide-when-not-visible, view-range.
  - 치장(CMI/HMC)과 passenger 병합 순서 A~E 유지.
  - 성능: 이동 중 이동 패킷 0, 텍스트 불변 시 메타 0, 인원 50~300.
- 수동 서버 테스트(문서 C 4·59): `/test <TAB>`→닉네임, 닉네임 실행→실제 플레이어,
  selector/UUID/일반 String 미변환, reload/join/quit.
- **CLAUDE 면밀 검토 반영 확인:**
  - B1: `./gradlew build` + `./gradlew test` + `tools/verify_gui.py` 통과(신규 키 3곳 동기화).
  - B4: packet 모드에서도 `hide-vanilla: true`가 동작(바닐라 실명 숨김 유지).
  - B5: reload로 `backend`를 바꾸면 유령 엔티티/패킷 없이 전환.
  - B6: Folia 환경에서 뷰어 스레드 오류 없음.

---

## 7. 알려진 한계 / 리스크

- 이 환경에서는 실제 서버/온라인 플레이 테스트 불가 → 패킷 레이어가 버전 의존(ProtocolLib),
  실제 서버 검증 전까지 문서 A 40.10(완전 호환 미가정)을 따른다.
- 패킷 계층 방식(A/B/하이브리드) **미확정**(§2.5) → 실서버 검증 후 결정. 결정 전까지 소스 변경 없음(legacy 유지).
- 가상 표시의 TextDisplay 메타 인코딩은 NMS `Component` 변환이 필요해 ProtocolLib의
  `WrappedDataWatcher`/커스텀 메타 구현에 의존한다.
- Packet 미설치 시 legacy fallback이 되지만 **cross-world 텔레포트 보장은 packet에서만** (− 로그 경고).
- 가상 표시에서도 TextDisplay의 줄바꿈(다중 줄)이 한 엔티티로 렌더되어야 한다 (`<newline>` 유지).