# InMc-TitleForge — 가상 TextDisplay 기반 이름표 시스템 전환 계획

> **목표:** 현재 `실제 TextDisplay 1개 + 실제 Player Passenger` 구조를  
> **클라이언트 전용 가상 TextDisplay 1개 + 패킷 기반 Passenger 표시** 구조로 전환한다.
>
> 핵심 목표는 다음 3가지를 동시에 만족하는 것이다.
>
> 1. **월드/차원 이동을 항상 정상적으로 허용**
> 2. **HMCCosmetics 등 다른 치장 플러그인의 실제/가상 탑승 엔티티와 위치·탑승 관계 충돌 방지**
> 3. **TPS/MSPT 영향을 최소화하고, 플레이어 이동 시 이름표 위치 패킷을 매 틱 전송하지 않음**
>
> 현재 TitleForge는 플레이어당 최대 2개의 TextDisplay를 실제 passenger로 운용하며, 이동 위치 갱신 패킷을 사용하지 않는 성능 중심 구조다. 이 구조 자체의 장점은 유지하되, **실제 서버 passenger라는 병목만 제거**한다.

---

## 1. 현재 구조와 변경 이유

### 1.1 현재 구조

현재 `NametagService`는 `TextDisplay`를 실제 Bukkit 엔티티로 생성한 뒤 Player에 passenger로 연결한다.

```text
Server

Player
├── TextDisplay (shared)
└── TextDisplay (others)
```

기존 설계의 장점은 분명하다.

- 플레이어 이동 시 별도의 위치 계산이 필요 없음
- TextDisplay 이동 패킷을 직접 전송하지 않음
- 플레이어당 표시 엔티티 수를 최소화
- `TextDisplay`의 줄바꿈으로 여러 줄을 하나의 엔티티에서 처리
- 내용 변경 시에만 표시 데이터를 갱신

현재 계획서도 이 특성을 성능 최우선 원칙으로 명시하고 있다.

### 1.2 현재 구조의 근본적인 문제

Paper 26.2 API는 **passenger를 가진 Player의 cross-dimension teleport를 지원하지 않으며 `false`를 반환할 수 있음**을 명시한다. `teleportAsync()`도 동일한 제약을 가진다.

따라서:

```text
Player
└── 실제 TextDisplay passenger
```

상태에서:

```java
player.teleport(otherWorldLocation);
```

을 수행하는 구조는 월드 이동의 안정성을 확보할 수 없다.

현재 구현은 이를 우회하기 위해 teleport 시 `detachFor()`를 실행해 이름표 엔티티를 제거한 뒤 다시 붙이는 방식을 사용한다. 그러나 이 방법은:

- teleport lifecycle이 복잡해짐
- 다른 플러그인의 teleport와 상호작용
- passenger 엔티티 재생성
- 순간적인 표시 상태 변화
- 외부 치장 passenger와의 관계 복구

등의 추가 책임을 가진다.

---

# 2. 목표 아키텍처

## 2.1 서버 측

TitleForge는 더 이상 실제 `TextDisplay` Bukkit 엔티티를 생성하지 않는다.

```text
Server

Player
└── 실제 TitleForge Passenger 없음
```

TitleForge는 대신 다음의 **가상 엔티티 상태**만 관리한다.

```kotlin
data class VirtualTextDisplay(
    val entityId: Int,
    val uuid: UUID,
    val owner: UUID,
    val content: Component,
    val layer: Layer
)
```

필수 상태:

- virtual entity ID
- UUID
- 소유 플레이어 UUID
- 표시 Layer
- 현재 렌더 결과
- 현재 시청자 목록
- 생성 여부
- 마지막 전송된 메타데이터 버전/해시

---

## 2.2 클라이언트 측

클라이언트에는 실제 TextDisplay처럼 보이도록 패킷을 전송한다.

```text
Client

Player
└── Virtual TextDisplay
```

그리고 Player의 passenger로 보이도록 **클라이언트의 SetPassengers 상태만 조작**한다.

중요한 점은 다음과 같다.

```text
서버 Passenger 관계
없음

클라이언트 Passenger 관계
있음
```

따라서 Paper 서버가 실제 Player passenger를 발견하지 못한다.

결과적으로:

```text
Player + 가상 TextDisplay
```

는 월드 이동 제약을 발생시키지 않는다.

---

# 3. HMCCosmetics에서 가져올 핵심 설계

HMCCosmetics는 패킷 엔티티를 별도의 상태 객체로 관리하고, 위치·시청자·엔티티 ID를 직접 관리한다. 또한 패킷을 통해 이동 및 탑승 상태를 전송한다.

특히 `UserEntity`는:

- 가상 엔티티 ID 목록
- 시청자 목록
- 위치
- 마지막 위치 갱신 시간

을 별도로 관리하며, 패킷으로 엔티티를 갱신한다.

TitleForge에서는 여기서 한 단계 더 단순화한다.

### HMCCosmetics식 구조

```text
Fake Entity
+
독립 위치 관리
+
필요한 경우 이동 패킷
+
Mount 패킷
```

### TitleForge 목표 구조

```text
Fake TextDisplay
+
Client Passenger
+
플레이어 이동 패킷 전송 없음
+
내용 변경 시 Metadata만 전송
```

즉 TitleForge에서는 **HMCCosmetics의 패킷 엔티티 관리 방식만 가져오고, 지속적인 위치 teleport는 최대한 제거​**한다.

---

# 4. 핵심 설계 원칙

## 4.1 평상시 위치 갱신 금지

다음 방식은 사용하지 않는다.

```text
PlayerMoveEvent
→ TextDisplay 위치 계산
→ TeleportEntityPacket
```

또한 다음도 사용하지 않는다.

```text
DisplayTicker
→ 매 tick
→ TextDisplay 위치 갱신
```

목표는 다음과 같다.

```text
Player 이동
    ↓
클라이언트가 passenger를 자동 추적
    ↓
TitleForge 위치 패킷 = 0
```

이것이 실제 Passenger 방식이 가졌던 가장 큰 성능 이점을 유지하는 핵심이다.

---

# 5. Virtual TextDisplay 수명주기

## 5.1 Spawn

플레이어가 이름표 표시 대상이 되는 순간:

```text
1. Virtual entity ID 할당
2. TextDisplay Spawn packet
3. TextDisplay Metadata packet
4. Player → Virtual TextDisplay Mount packet
```

초기 위치는 Player 현재 위치를 기준으로 생성한다.

높이 조정은 기존 `shared-height-offset` / `others-height-offset` 정책을 그대로 사용한다. 현재 설정 구조는 이 오프셋을 이미 분리해서 관리하고 있다.

---

## 5.2 Metadata

현재 Bukkit TextDisplay에 적용하고 있는 표시 속성을 모두 패킷 메타데이터로 옮긴다.

대상:

- text
- billboard
- transformation
- background
- text opacity
- shadow
- see-through
- brightness
- line width
- view range
- 기타 현재 설정값

중요한 원칙:

```text
텍스트가 같음
→ Metadata packet 전송하지 않음
```

```text
텍스트 변경
→ Metadata packet 1회
```

즉 현재 계획서의

> 내용이 변경될 때만 전송

원칙을 그대로 유지한다.

---

# 6. Passenger 패킷 처리

여기가 **이번 전환에서 가장 중요한 부분**이다.

단순히:

```text
SetPassengers(Player, [TitleForgeId])
```

만 보내면 안 된다.

다른 치장 플러그인이 이미 Player에 passenger를 붙이고 있을 경우 기존 passenger 목록을 덮어써버릴 수 있기 때문이다.

## 6.1 잘못된 방법

```text
기존:
Player
├── Cosmetic A
└── Cosmetic B

TitleForge:
SetPassengers(Player, [TitleForge])
```

결과:

```text
Player
└── TitleForge
```

기존 치장의 클라이언트 표시 관계가 사라질 수 있다.

---

## 6.2 목표

최종 클라이언트 passenger 목록은:

```text
Player
├── Cosmetic A
├── Cosmetic B
└── TitleForge
```

가 되어야 한다.

따라서 TitleForge는 **기존 passenger 목록을 보존하고 자신의 virtual entity ID만 추가**한다.

개념:

```text
existingPassengers
+
titleForgeEntityId
=
mergedPassengers
```

---

# 7. 다른 치장 플러그인과의 충돌 방지

특히 HMCCosmetics처럼 별도의 패킷 passenger를 사용하는 플러그인과 호환하려면 **outgoing SetPassengers 패킷을 감시하는 계층**이 필요하다.

## 7.1 PassengerMergeInterceptor

새 패킷 계층:

```text
packet/
├─ PacketDisplayManager
├─ VirtualEntityManager
├─ DisplayMetadataEncoder
└─ PassengerMergeInterceptor
```

`PassengerMergeInterceptor`의 역할:

```text
Outgoing SetPassengers packet
        ↓
vehicleId 확인
        ↓
TitleForge가 관리하는 Player인가?
        ↓
YES
        ↓
TitleForge virtual entityId가 이미 있는가?
        ├─ YES → 그대로 통과
        └─ NO  → passenger list에 추가
```

이벤트가 발생하지 않는 평상시에는 아무 작업도 하지 않는다.

즉:

- 매 tick polling 없음
- PlayerMoveEvent 기반 패킷 없음
- 모든 패킷 가로채기/수정 없음
- **SetPassengers 패킷이 발생한 경우에만 최소 작업**

을 원칙으로 한다.

---

# 8. View별 상태 관리

현재 TitleForge는 `shared`와 `others`를 시청자별로 다르게 표시한다.

현재 구조:

```text
shared-lines
→ 본인 포함 모두

others-lines
→ 다른 플레이어에게만
```

을 그대로 유지해야 한다.

따라서 virtual entity도 **viewer별 생성 여부를 관리해야 한다.**

```kotlin
data class ViewerState(
    val viewer: UUID,
    var sharedVisible: Boolean,
    var othersVisible: Boolean,
    var sharedSpawned: Boolean,
    var othersSpawned: Boolean,
    var lastMetadataHash: Long
)
```

예:

```text
자기 자신

Player
└── Virtual Shared Display

다른 플레이어

Player
├── Virtual Shared Display
└── Virtual Others Display
```

현재 기능을 그대로 재현한다.

---

# 9. Visibility 처리

현재 `hide-when-not-visible` 기능과 `visibility-check-ticks`가 이미 존재한다.

이 기능은 기존과 동일하게 유지한다.

중요한 점은:

```text
Visibility false
```

일 때 엔티티 위치를 보내지 않고:

```text
Entity Destroy packet
```

만 보낸다.

다시 보일 때:

```text
Spawn
+
Metadata
+
Passenger merge
```

를 한 번 전송한다.

따라서 보이지 않는 엔티티에 대한 지속적인 패킷 비용이 발생하지 않는다.

---

# 10. 월드 이동 처리

이 구조에서는 Player가 실제 passenger를 갖고 있지 않기 때문에 **Player teleport 자체를 TitleForge가 막지 않는다.**

따라서 기존의:

```kotlin
detachFor(player)
```

기반 cross-world 예외 처리를 제거할 수 있다.

## 목표 흐름

```text
PlayerTeleportEvent
        ↓
일반 Player teleport 진행
        ↓
World 변경
        ↓
기존 viewer의 virtual entity 제거
        ↓
새 World에서 virtual entity Spawn
        ↓
Metadata
        ↓
Passenger merge
```

중요:

**Player 이동을 TitleForge가 직접 수행하지 않는다.**

TitleForge는 표시 상태만 재구성한다.

---

# 11. 월드 이동 시 패킷 순서

권장 순서:

```text
1. 기존 virtual TextDisplay Destroy
2. Player의 정상 teleport 완료
3. 대상 World 확인
4. Virtual TextDisplay Spawn
5. Metadata
6. Passenger merge
```

이를 통해 클라이언트가 이전 월드의 가상 엔티티를 남겨두는 문제를 방지한다.

---

# 12. DisplayTicker 역할 변경

기존 `DisplayTicker`는 유지하되 역할을 축소한다.

## 기존

```text
DisplayTicker
├─ 표시 상태
├─ 텍스트 갱신
├─ 만료 검사
├─ 위치 관련 lifecycle
└─ teleport lifecycle
```

## 변경 후

```text
DisplayTicker
├─ 표시 상태 변경 감지
├─ 텍스트 변경
├─ Placeholder cache refresh
├─ 만료 처리
└─ 필요 시 Virtual Display metadata 갱신
```

### 절대 하지 않는 것

```text
DisplayTicker
└─ Player 위치 계산
└─ TextDisplay teleport
└─ 이동 패킷 반복 전송
```

이 구조를 통해 기존의 **상시 반복 태스크 1개** 원칙을 유지한다. 현재 README도 표시 티커를 이름표/탭리스트의 공용 반복 태스크로 정의하고 있다.

---

# 13. 이동 이벤트 사용 여부

### 원칙

`PlayerMoveEvent`를 이름표 이동 목적으로 사용하지 않는다.

이것은 매우 중요하다.

```text
PlayerMoveEvent
→ 이름표 이동 ❌
```

대신 passenger 관계를 클라이언트가 처리하게 한다.

### 사용할 이벤트

필요한 lifecycle에 한정한다.

```text
PlayerJoinEvent
PlayerQuitEvent
PlayerTeleportEvent
PlayerChangedWorldEvent
```

필요에 따라:

```text
PlayerGameModeChangeEvent
PlayerRespawnEvent
PlayerDeathEvent
```

도 표시 lifecycle 재생성용으로만 사용한다.

---

# 14. Entity ID 관리

Virtual TextDisplay는 실제 서버 엔티티가 아니므로 자체 ID 관리가 필요하다.

## 요구사항

- 실제 서버 엔티티 ID와 충돌하지 않는 값
- 플레이어/세션별 안정적인 관리
- viewer별 클라이언트 상태 추적
- 제거 후 재사용 가능
- 서버 재시작 시 모든 ID 초기화

권장:

```kotlin
class VirtualEntityIdAllocator {
    fun allocate(): Int
    fun release(id: Int)
}
```

단, **Entity ID 충돌 가능성을 실제 클라이언트 시야 기준으로 검증**해야 한다.

무작정 큰 정수를 사용하는 방식은 최종 구현에서 검증 대상에 포함한다.

---

# 15. UUID 관리

Virtual TextDisplay는 UUID도 필요하지만, 서버 저장 데이터와 연결할 이유는 없다.

```text
UUID
→ packet entity identity 용도
```

권장:

```text
플레이어의 nametag virtual entity 생성 시 UUID.randomUUID()
```

또는 세션 단위 deterministic UUID.

서버 재시작 후 복구할 필요는 없다.

---

# 16. Packet Layer 설계

프로젝트의 기존 도메인 코드가 패킷 구현에 직접 의존하지 않도록 추상화한다.

```text
display/
├─ NametagService
└─ packet/
   ├─ PacketDisplayBackend
   ├─ VirtualDisplay
   ├─ VirtualEntityIdAllocator
   ├─ DisplayMetadataEncoder
   ├─ PassengerMergeInterceptor
   └─ ProtocolLibPacketBackend
```

## PacketDisplayBackend API 예시

```kotlin
interface PacketDisplayBackend {

    fun spawn(display: VirtualDisplay, viewer: Player)

    fun updateMetadata(
        display: VirtualDisplay,
        viewer: Player
    )

    fun destroy(
        display: VirtualDisplay,
        viewer: Player
    )

    fun attachToPlayer(
        display: VirtualDisplay,
        viewer: Player
    )
}
```

추가로 월드 전환:

```kotlin
fun respawn(
    display: VirtualDisplay,
    viewer: Player
)
```

를 제공한다.

---

# 17. ProtocolLib 사용 정책

패킷 조작은 Bukkit/Paper의 고수준 Entity API만으로 구현하기 어렵기 때문에 **Packet Layer에 ProtocolLib 기반 구현을 둔다.**

권장 구조:

```text
NametagService
      ↓
PacketDisplayBackend
      ↓
ProtocolLib
      ↓
Minecraft Client
```

Nametag 도메인 로직은 ProtocolLib 클래스를 직접 참조하지 않는다.

이를 통해:

- 패킷 코드 분리
- 향후 PacketEvents/NMS 대체 가능
- 테스트 용이
- 서버 버전별 변경 범위 축소

를 얻는다.

ProtocolLib 의존 여부는 최종 배포 정책에 따라 `depend` / `softdepend`를 결정한다.

**단, 가상 이름표가 활성화된 상태에서 Packet Backend가 없으면 기능을 자동으로 비활성화하는 안전장치가 필요하다.**

---

# 18. 현재 `NametagService.kt` 변경 범위

현재 `NametagService`가 실제 Bukkit `TextDisplay`를 spawn하고 passenger를 관리하는 구조이므로, 이 파일의 책임을 다음처럼 바꾼다.

## 제거

```text
World.spawn(TextDisplay)
player.addPassenger(display)
player.removePassenger(display)
display.remove()
display.vehicle 검사
```

## 추가

```text
VirtualDisplay 생성
PacketDisplayBackend 호출
ViewerState 관리
Metadata 변경 감지
Spawn/Destroy lifecycle 관리
```

## 유지

```text
shared / others 분리
height offset
view-range
see-through
background
text-shadow
visibility
placeholder 결과
내용 변경 감지
```

즉 **이름표의 데이터/렌더 결정 로직은 그대로 유지하고, 엔티티 구현체만 교체​**한다.

---

# 19. 현재 `PlayerListener.kt` 변경

현재 teleport 시 `detachFor()`를 호출하는 로직은 제거한다.

새 구조:

```text
onTeleport
    ├─ same world
    │   └─ 아무것도 하지 않음
    │
    └─ cross world
        └─ NametagService.prepareWorldTransition()
```

하지만 `prepareWorldTransition()`은 Player teleport 자체를 수행하지 않는다.

역할은 단순히:

```text
old virtual entity state 정리
```

뿐이다.

실제 재생성은:

```text
PlayerChangedWorldEvent
```

이후 수행한다.

---

# 20. 치장 플러그인과의 충돌 정책

이번 시스템의 중요한 요구사항이다.

## TitleForge가 절대 하지 않는 것

```text
player.getPassengers().clear()
player.eject()
player.removePassenger(otherPluginEntity)
```

즉 **다른 플러그인의 실제 Passenger는 건드리지 않는다.**

## TitleForge가 관리하는 것

오직:

```text
TitleForge virtual entity ID
```

뿐이다.

---

# 21. 다른 플러그인이 Passenger 패킷을 다시 보내는 경우

이 경우가 가장 중요하다.

예:

```text
HMC
→ SetPassengers(Player, [HMC_ID])

TitleForge
→ SetPassengers(Player, [TitleForge_ID])
```

마지막 패킷이 이전 목록을 덮어쓰면 문제가 생긴다.

따라서 `PassengerMergeInterceptor`가 항상:

```text
original passenger IDs
+
TitleForge ID
```

를 만들어 보내야 한다.

예:

```text
원본:
[1001, 1002]

TitleForge:
[90001]

최종:
[1001, 1002, 90001]
```

이렇게 하면 서로의 passenger를 삭제하지 않는다.

---

# 22. 다중 Passenger 위치 문제

가상 Passenger를 사용한다고 해서 위치가 자동으로 원하는 좌표가 되는 것은 아니다.

Minecraft 클라이언트의 passenger 렌더링 규칙에 따라 passenger attachment point가 적용되므로 **다음 항목을 실서버에서 검증해야 한다.**

### 검증 대상

```text
Player
├── Cosmetic
└── TitleForge TextDisplay
```

### 확인할 것

- 동일 높이에서 겹치는가
- TextDisplay가 위/아래로 밀리는가
- passenger 순서에 따라 위치가 달라지는가
- TextDisplay Transformation translation으로 정밀 보정 가능한가
- HMC passenger가 추가/삭제될 때 TitleForge 위치가 변하는가

**이 테스트가 통과하기 전에는 "모든 치장과 위치가 완전히 독립된다"고 가정하지 않는다.**

---

# 23. 위치 보정 전략

목표는 **Player 이동 중 위치 패킷을 보내지 않는 것**이다.

따라서 위치 보정은 다음 우선순위로 처리한다.

### 1차

TextDisplay의 `Transformation.translation`을 이용해 머리 위 위치를 맞춘다.

```text
Player
    ↑
Transformation translation
    ↑
TextDisplay
```

### 2차

Passenger attachment point에 문제가 있을 경우에도 가능한 한 Display Metadata만 수정한다.

### 금지

```text
PlayerMoveEvent
→ TeleportEntity packet
```

이 방식은 최후의 수단으로도 사용하지 않는다.

---

# 24. 성능 목표

## 목표 상태

플레이어 이동 중:

```text
TitleForge CPU
≈ 0에 가까운 추가 위치 계산

TitleForge 위치 패킷
= 0
```

## 이동하지 않을 때

DisplayTicker는 텍스트/표시 상태가 실제로 변경된 플레이어만 업데이트한다.

```text
display state unchanged
→ packet 0
```

```text
text changed
→ metadata 1회
```

```text
visibility changed
→ spawn/destroy 1회
```

이 구조를 통해 현재 계획서의 **"내용이 바뀐 경우에만 전송"** 원칙을 유지한다.

---

# 25. View Distance 관리

현재 `view-range` 설정은 32.0이다.

가상 엔티티에서는 서버가 실제 엔티티 트래킹을 해주지 않으므로 TitleForge가 viewer 관리 책임을 가진다.

### 목표

```text
너무 멀어짐
→ Destroy packet

다시 가까워짐
→ Spawn + Metadata + Mount
```

단, 이 계산을 매 tick 하지 않는다.

현재 `visibility-check-ticks`와 표시 티커 주기를 활용한다.

### 추가 최적화

viewer가 가까운지 확인하는 것은:

- 제곱 거리 비교
- 월드 동일 여부
- `canSee`
- hide-when-not-visible

순으로 빠르게 탈락시키며, 비싼 raycast는 필요한 조합만 수행한다.

---

# 26. 서버/클라이언트 상태 불일치 복구

패킷 기반 엔티티의 가장 큰 문제는 **클라이언트 상태와 서버 캐시의 불일치**다.

따라서 다음 이벤트에서 재동기화한다.

```text
PlayerJoin
PlayerChangedWorld
PlayerRespawn
PlayerGameModeChange
Plugin Reload
```

또한 필요한 경우:

```text
NametagService.resync(viewer)
```

를 제공한다.

### 수동 복구 조건

```text
Virtual entity가 있다고 캐시됨
하지만 viewer에게 실제로 spawn되지 않음
→ Spawn 재전송
```

반대로:

```text
Destroy 해야 하는데 클라이언트에 남아 있음
→ Destroy 재전송
```

---

# 27. `/it reload`

리로드 시 실제 엔티티를 제거/생성하는 기존 방식 대신:

```text
1. 모든 viewer에게 Virtual Entity Destroy
2. Settings reload
3. 렌더 결과 재계산
4. 필요한 플레이어만 Spawn
5. Metadata
6. Passenger merge
```

로 동작시킨다.

리로드 중 Player teleport는 막지 않는다.

---

# 28. 서버 종료

실제 Bukkit 엔티티가 없기 때문에 서버 종료 시 world entity cleanup이 필요하지 않다.

종료 시:

```text
패킷 상태 캐시만 clear
```

하면 된다.

이것은 기존의:

```text
퇴장 후 엔티티 제거
서버 시작 후 유령 엔티티 청소
```

부담을 크게 줄인다.

---

# 29. 기존 설정과 API 호환성

기존 설정은 최대한 변경하지 않는다.

유지:

```yaml
display:
  nametag:
    enabled: true
    shared-lines: [...]
    others-lines: [...]
    shared-height-offset: 0.72
    others-height-offset: 0.4
    view-range: 32.0
    see-through: false
    text-shadow: true
    background: true
    background-color: 1073741824
    hide-vanilla: true
    hide-when-not-visible: true
```

현재 이름표 설정은 위 구조를 사용하고 있다.

추가할 수 있는 옵션:

```yaml
display:
  nametag:
    backend: packet
```

또는 서버 운영 중 안전한 fallback을 위해:

```yaml
display:
  nametag:
    backend: auto
```

를 제공할 수 있다.

---

# 30. 권장 Backend 정책

최종 구현에서는 다음 두 계층을 분리한다.

```text
NametagService
       ↓
NametagBackend
       ├─ PacketBackend
       └─ LegacyEntityBackend
```

### PacketBackend

- 기본
- 본 계획의 목표 구현
- 실제 서버 passenger 없음
- 월드 이동 문제 해결

### LegacyEntityBackend

- 디버깅/호환성 목적
- 기존 실제 TextDisplay 방식
- cross-world passenger 제약 존재

운영 서버의 기본값은:

```text
PacketBackend
```

로 한다.

---

# 31. 구현 단계

## Phase 1 — Packet Backend 기반 구축

- [ ] `packet` 패키지 추가
- [ ] `VirtualDisplay`
- [ ] `VirtualEntityIdAllocator`
- [ ] `PacketDisplayBackend`
- [ ] ProtocolLib adapter
- [ ] TextDisplay Spawn packet
- [ ] Metadata packet
- [ ] Destroy packet
- [ ] Mount packet

완료 조건:

```text
한 플레이어에게 가상 TextDisplay 1개가 정상적으로 표시됨
```

---

## Phase 2 — 기존 NametagService 연결

- [ ] 실제 Bukkit TextDisplay 생성 제거
- [ ] `addPassenger()` 제거
- [ ] `removePassenger()` 제거
- [ ] `vehicle` 검증 제거
- [ ] `shared/others` viewer 상태 연결
- [ ] 기존 텍스트 생성 로직 유지

완료 조건:

```text
현재 이름표가 동일하게 보임
```

---

## Phase 3 — Passenger Merge

- [ ] `PassengerMergeInterceptor`
- [ ] outgoing SetPassengers 감시
- [ ] 기존 passenger 보존
- [ ] TitleForge virtual ID 병합
- [ ] 중복 ID 방지
- [ ] 제거 시 TitleForge ID만 제거

완료 조건:

```text
HMCCosmetics + TitleForge
동시에 정상 표시
```

---

## Phase 4 — 월드 이동

- [ ] `PlayerTeleportEvent`에서 `detachFor()` 제거
- [ ] cross-world lifecycle 정리
- [ ] `PlayerChangedWorldEvent` 재생성
- [ ] 이전 월드 virtual entity destroy
- [ ] 새 월드 spawn
- [ ] metadata
- [ ] mount merge

완료 조건:

```text
Overworld → Nether
Nether → Overworld
Overworld → End
End → Overworld
```

모두 정상 작동.

---

## Phase 5 — 위치/치장 호환성 검증

다음 조합을 모두 테스트한다.

```text
TitleForge 단독
TitleForge + CMI
TitleForge + HMCCosmetics
TitleForge + 일반 ArmorStand cosmetic
TitleForge + 여러 passenger cosmetic
```

검증:

- [ ] 이름표가 위/아래로 밀리지 않음
- [ ] 인장과 칭호가 겹치지 않음
- [ ] 치장 추가/제거 후 위치 유지
- [ ] passenger 순서 변경 후 위치 유지
- [ ] 월드 이동 후 위치 동일
- [ ] 본인/타인 표시 조건 유지

---

# 32. 성능 검증

기존과 새로운 구조를 비교한다.

## 기존

```text
실제 TextDisplay
+
실제 Passenger
```

## 신규

```text
가상 TextDisplay
+
Packet Passenger
```

측정 대상:

- MSPT
- TPS
- packet count
- join 비용
- world change 비용
- 50명
- 100명
- 200명
- 300명

특히 **플레이어가 계속 이동하는 상황**을 별도 측정한다.

### 성공 조건

```text
이동 중 TitleForge가 TextDisplay 이동 패킷을 지속 전송하지 않음
```

그리고:

```text
텍스트가 변화하지 않는 상황
→ metadata packet 0
```

이어야 한다.

---

# 33. 정확성 검증

## 이름표

- [ ] shared 1개
- [ ] others 1개
- [ ] 최대 플레이어당 virtual entity 2개
- [ ] 줄바꿈 정상
- [ ] MiniMessage 정상
- [ ] PlaceholderAPI 정상
- [ ] 본인 표시 정상
- [ ] 타인 표시 정상

## 시야

- [ ] hide-when-not-visible 정상
- [ ] visibility-check-ticks 정상
- [ ] 벽 뒤 숨김 정상
- [ ] view-range 정상

## Lifecycle

- [ ] join
- [ ] quit
- [ ] respawn
- [ ] world change
- [ ] teleport
- [ ] reload
- [ ] plugin disable

---

# 34. 가장 중요한 호환성 테스트

특히 `SetPassengers` 병합은 다른 packet-based cosmetic plugin과 충돌할 수 있으므로 반드시 아래 시나리오를 검증한다.

### 테스트 A

```text
HMC Cosmetic → mount packet
TitleForge → mount packet
```

### 테스트 B

```text
TitleForge → mount packet
HMC Cosmetic → mount packet
```

### 테스트 C

```text
HMC Cosmetic 제거
```

### 테스트 D

```text
TitleForge 이름표 내용 변경
```

### 테스트 E

```text
World Change
```

### 성공 조건

어느 순서에서도:

```text
Player
├── HMC Cosmetic
└── TitleForge TextDisplay
```

상태가 유지되어야 한다.

---

# 35. 실패 시 fallback

가상 TextDisplay 구현에서 다음 문제가 발생하면 즉시 fallback할 수 있어야 한다.

```text
패킷 생성 실패
ProtocolLib 없음
버전 호환 실패
Metadata serializer 변경
client rendering 오류
```

Fallback:

```text
PacketBackend 비활성화
→ 사용자에게 경고
→ Legacy backend 활성화 여부에 따라 기존 구현 사용
```

단, Legacy backend는 Paper 26.2 cross-world passenger 문제를 해결하지 못한다는 점을 로그로 명확히 표시한다. Paper의 공식 API 제약 때문이다.

---

# 36. 기존 파일 기준 예상 변경 범위

### 주요 수정

```text
src/main/kotlin/kr/inmc/titleforge/display/NametagService.kt
src/main/kotlin/kr/inmc/titleforge/display/DisplayTicker.kt
src/main/kotlin/kr/inmc/titleforge/listener/PlayerListener.kt
```

### 신규

```text
display/packet/PacketDisplayBackend.kt
display/packet/VirtualDisplay.kt
display/packet/VirtualEntityIdAllocator.kt
display/packet/DisplayMetadataEncoder.kt
display/packet/PassengerMergeInterceptor.kt
display/packet/ProtocolLibPacketBackend.kt
```

필요에 따라:

```text
display/packet/ViewerState.kt
display/packet/PacketDisplayRegistry.kt
```

추가.

현재 프로젝트의 핵심 이름표 책임은 `NametagService`와 `DisplayTicker`에 있고, teleport lifecycle은 `PlayerListener`에 있으므로 이 세 부분을 중심으로 수정하는 것이 최소 변경 범위다.

---

# 37. 최종 시스템 동작

## 일반 플레이

```text
Player
  │
  │ 실제 서버 passenger 없음
  │
  ▼
Client

Player
└── Virtual TextDisplay
```

Player 이동:

```text
Player 이동
→ Client가 passenger 이동
→ TitleForge 이동 패킷 없음
```

---

## 텍스트 변경

```text
Title / Seal / Nickname 변경
        ↓
NametagService 렌더 결과 변경
        ↓
Metadata packet 1회
```

---

## 치장 변경

```text
Cosmetic plugin
        ↓
Passenger 변경
        ↓
SetPassengers packet
        ↓
PassengerMergeInterceptor
        ↓
기존 passenger + TitleForge ID
```

---

## 월드 이동

```text
PlayerTeleportEvent
        ↓
Player 실제 이동 허용
        ↓
PlayerChangedWorldEvent
        ↓
Virtual Display 재생성
        ↓
Metadata
        ↓
Passenger merge
```

---

# 38. 최종 목표

최종 TitleForge의 이름표 시스템은 다음 조건을 만족해야 한다.

```text
✅ 플레이어당 최대 2개의 가상 TextDisplay
✅ 실제 서버 Entity 없음
✅ 실제 서버 Passenger 없음
✅ PlayerMoveEvent 기반 이동 패킷 없음
✅ 매 tick TextDisplay teleport 없음
✅ 텍스트 변경 시에만 Metadata 갱신
✅ 월드 이동 정상
✅ CMI/치장 플러그인의 실제 Passenger와 독립
✅ HMC류 packet passenger와 호환
✅ shared/others viewer 분리 유지
✅ hide-when-not-visible 유지
✅ 기존 config/API 최대한 유지
✅ TPS/MSPT 최우선
```

---

# 39. 핵심 설계 결정

> **TitleForge는 "Passenger를 사용하는 이름표"라는 개념은 유지하되, Passenger 관계의 소유권을 서버에서 클라이언트로 이동한다.**

즉:

```text
기존

Server
Player
└── TextDisplay


변경

Server
Player

Client
Player
└── Virtual TextDisplay
```

이 변경으로:

- 실제 Player passenger로 인한 Paper 26.2 cross-world teleport 제한을 제거하고,
- 치장 플러그인이 서버 passenger 구조를 변경해도 TitleForge가 실제 엔티티 위치를 물려받지 않으며,
- passenger의 자동 위치 추적이라는 장점을 그대로 사용하여 평상시 이동 패킷을 발생시키지 않는 것을 목표로 한다. Paper 26.2는 실제 passenger를 가진 Player의 cross-world teleport를 지원하지 않는다고 명시하고 있으며, HMCCosmetics는 패킷 엔티티/탑승 상태를 별도로 관리하는 사례를 제공한다.

---

# 40. 구현 시 절대 지키는 규칙

1. **PlayerMoveEvent에서 이름표를 teleport하지 않는다.**
2. **DisplayTicker에서 이름표 위치를 매 tick 갱신하지 않는다.**
3. **다른 플러그인의 실제 passenger를 제거하지 않는다.**
4. **SetPassengers 패킷을 무조건 덮어쓰지 않는다.**
5. **TitleForge virtual entity ID만 자기 소유로 관리한다.**
6. **텍스트가 바뀌지 않았다면 Metadata packet을 보내지 않는다.**
7. **월드 이동은 Player의 기본 teleport lifecycle을 방해하지 않는다.**
8. **패킷 구현과 이름표 도메인 로직을 분리한다.**
9. **Paper/ProtocolLib API 변경에 대비해 Packet Backend를 별도 계층으로 둔다.**
10. **실제 서버에서 HMC 및 다른 치장 플러그인과 함께 검증하기 전에는 완전한 호환성을 가정하지 않는다.**

---

## 결론

이 계획은 기존 TitleForge의 핵심 성능 설계인 **"플레이어당 TextDisplay 최소화 + 위치 패킷 제거 + 내용 변경 시에만 갱신"​**을 유지하면서, 현재 구조의 유일하게 치명적인 문제인 **실제 Player passenger에 의한 cross-world teleport 제한**을 제거하는 방향이다.

HMCCosmetics의 `UserEntity`처럼 가상 엔티티 상태를 별도로 관리하는 방식을 참고하되, TitleForge는 이름표 목적에 맞게 더 단순화하여 **이동 위치를 지속적으로 teleport하지 않고 클라이언트 passenger 추적에 맡기는 것**을 핵심 최적화로 삼는다.

**최종 목표 아키텍처:**

```text
                TitleForge
                     │
               NametagService
                     │
             VirtualDisplay
                     │
            PacketDisplayBackend
                     │
          ┌──────────┴──────────┐
          │                     │
      TextDisplay            Mount
       packets              packets
          │                     │
          └──────────┬──────────┘
                     ▼
                  Client

                  Player
                     │
                     └── Virtual TextDisplay
```

이 구조를 채택하면 **월드 이동, 치장 충돌, 성능** 세 가지 문제를 하나의 원인인 "실제 서버 passenger"를 제거하는 것으로 동시에 해결하는 것이 최종 목표다.