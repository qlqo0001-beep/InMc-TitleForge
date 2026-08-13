# InMc-TitleForge 개발 규칙

> 칭호(Title) · 인장(Seal) · 닉네임(Nickname) 통합 관리 플러그인
> Paper / Kotlin / Gradle. **TPS·MSPT 무영향이 1순위 전제입니다.**

이 저장소에 코드를 추가·수정할 때 반드시 지키는 규칙입니다.
사용자용 문서는 [README.md](README.md), 최초 설계 기록은 [PLAN.md](PLAN.md) 를 참고하세요.

---

## 1. 스레드 규칙 (최우선)

1. **메인 스레드에서 JDBC·파일 I/O·네트워크 호출 금지.** 예외 없음. 저장소 접근은 `Sched.async` 안에서만.
2. Bukkit 엔티티/인벤토리 API는 **반드시** 메인(또는 해당 엔티티 리전) 스레드에서.
   비동기 → `Sched.entity(player) { }` 로 복귀.
3. `Thread.sleep`, `Future.get()`, `join()` 을 메인 스레드에서 호출하지 않습니다.
4. 반복 태스크는 **표시 갱신용 티커 1개만** 허용합니다(`display/DisplayTicker.kt`).
   새 태스크를 만들지 말고 여기에 얹으며, 주기는 설정 가능해야 하고 **내용이 바뀐 경우에만**
   전송해야 합니다. 해당 기능이 모두 꺼져 있으면 티커 자체를 만들지 않습니다.

## 2. 데이터 규칙

5. 조회는 **항상 캐시**. DB는 로드/저장 경로에서만 등장합니다.
6. 상태 변경은 `profile.mutate { }` 로 감싸 dirty 플래그를 남기고, 저장은 배치에 맡깁니다
   (중요 조작만 즉시 저장).
7. 스텟 합계는 변경 시점에만 재계산(`profile.recalculate()`)하고 결과를 캐시합니다.
   조회 시 계산하지 않습니다.
8. `Settings` 는 불변 스냅샷입니다. reload 는 새 객체로 통째 교체하며,
   참조를 필드에 오래 보관하지 않습니다.

## 3. Paper API 규칙

9. Spigot/Bukkit 대체 API가 있으면 Paper 쪽을 씁니다:
   `Component`(String 대신), `AsyncChatEvent`(`AsyncPlayerChatEvent` 대신),
   `AsyncScheduler`/`RegionScheduler`(`BukkitScheduler` 대신),
   `RegistryAccess`(deprecated Registry 상수 대신).
10. 문자열 색 코드(`§`, `&`)를 코드에 직접 쓰지 않습니다. 모든 텍스트는 **MiniMessage** 로 파싱합니다.
11. 유저에게 보이는 문장은 코드에 하드코딩하지 않고 `messages.yml` 키로 관리합니다.
12. 외부 플러그인(PlaceholderAPI, Vault, MythicLib, MMOItems) 클래스는 **훅 클래스 안에서만**
    참조합니다. 존재 확인 후에만 훅을 로드해 `NoClassDefFoundError` 를 원천 차단합니다.

## 4. 도메인 규칙

13. 칭호와 인장은 `BadgeType` 하나로 분기합니다. 인장 전용 코드를 복제하지 않습니다.
14. **인장은 어떤 경로로도 스텟을 가질 수 없습니다.** 저장 시점에 강제로 비웁니다(방어적 정규화).
15. 장착 슬롯 3종(`STAT`/`DISPLAY`/`SEAL`)은 서로 독립입니다.
    한 슬롯 변경이 다른 슬롯을 건드리지 않습니다.
16. 스텟은 `StatRegistry` 한 곳에서만 정의합니다. **바닐라 28종은 코드 내장**
    (외부 플러그인 없이 항상 동작), 그 외는 `stats.yml`. 저장·GUI·플레이스홀더·명령어가
    레지스트리를 따라오도록 유지하며 하드코딩된 스텟 분기를 만들지 않습니다.
    - **16-a.** 스텟 값 맵의 키는 **스텟 id 문자열**입니다. 등록되지 않은 id 도 버리지 않고
      보존합니다 (설정 실수로 저장된 값이 지워지면 안 됩니다).
    - **16-b.** **MMOItems 연동은 선택 사항입니다.** 연동이 없어도 바닐라 스텟만으로 서버가
      완전히 돌아가야 하며, GUI 는 각 스텟의 종류(바닐라/MMO/가상)와 연동 여부를 항상 명시합니다.
17. `AttributeModifier` 는 반드시 `titleforge:` 네임스페이스 키로 부착하고,
    재적용 전 같은 네임스페이스만 골라 제거합니다. 타 플러그인의 모디파이어를 건드리지 않습니다.
18. 최대 체력 감소 시 현재 체력 클램프를 반드시 수행합니다(즉사 방지).

## 5. UX·안전 규칙

19. 파괴적 동작(삭제, 전체 지급, 닉네임 초기화)은 확인 단계를 거칩니다.
20. GUI 클릭은 기본 전부 취소(`setCancelled(true)`) 후 명시적으로 허용된 동작만 수행합니다.
    아이템 복사 경로를 만들지 않습니다.
21. 유저 입력(닉네임, 칭호 ID)은 화이트리스트 정규식으로 검증합니다.
    칭호·인장 ID 는 `^[a-z0-9_가-힣]{1,32}$` (소문자 영문·숫자·밑줄·완성형 한글).
22. 유저가 입력한 문자열을 MiniMessage 로 파싱할 때는 **관리자 입력에만** 서식 태그를 허용하고,
    일반 유저 닉네임은 서식 태그를 이스케이프합니다(색상 주입 방지).

---

## 검증 방법

컴파일이 필요 없는 정적 검증기와, 순수 로직 단위 테스트를 함께 둡니다.

```bash
pip install pyyaml            # verify_gui.py 는 PyYAML 이 필요합니다
python tools/verify_gui.py    # 메시지·설정 키, stats.yml 검증, 슬롯 배치 시뮬레이션
./gradlew test                # StatRegistry / StatLayout / StatValueParser / Stats / DurationParser
```

`tools/verify_gui.py` 가 잡아내는 것:

1. 코드가 참조하는 `messages.yml` 키 누락 (`Gui.item` 의 `.name`/`.lore` 규칙 포함)
2. `messages.yml` 의 미사용 키 (오타·리팩터링 잔재)
3. `Settings` 가 읽는 `config.yml` 키 누락
4. 스텟 편집 창 슬롯 충돌 / 범위 초과 / 배치 누락 (배치도를 표로 출력)
5. 각 메뉴의 하드코딩 슬롯이 창 크기를 벗어나는지
6. GUI 안에서 `open()` 을 직접 호출하는 곳이 없는지 (반드시 `openLater()`)
7. `stats.yml` 의 kind/분류/필수 필드가 올바른지, id 규칙과 중복 여부
8. 스텟이 늘어난 상태에서도 편집 GUI 슬롯이 충돌하지 않는지
9. 문자열 조합으로 만들어지는 메시지 경로 존재 여부
   (`gui.button.slot-${EquipSlot.id}.name`, `gui.lore.stat-kind-name-${StatKind.id}`).
   조합 대상 id 를 **enum 정의에서 직접 읽어오므로**, enum 에 상수를 추가하고
   `messages.yml` 을 빠뜨리면 여기서 잡힙니다.

**검증기를 고칠 때 주의** — `config.yml` 키 검사는 루트 `config.` 를 통한 읽기만 절대 경로로
봅니다. `node.getInt("amount")` 처럼 하위 `ConfigurationSection` 에서 읽는 것은 **상대 키**라
최상위에 존재하지 않는 것이 정상입니다. 수신자를 구분하지 않으면 전부 오탐이 됩니다.

## 빌드

```bash
./gradlew build     # build/libs/InMc-TitleForge-1.0.0.jar (shadowJar)
```

`gradle.properties` 의 `paperApiVersion` 이 대상 서버 버전과 맞는지 확인하세요.
`build.bat` 은 테스트를 건너뛰는 빠른 빌드입니다.
