# InMc-TitleForge

칭호(Title) · 인장(Seal) · 닉네임(Nickname)을 한 플러그인에서 관리합니다.
Paper 26.1+ / Java 25 / Kotlin / Gradle.

전체 설계와 개발 규칙은 [PLAN.md](PLAN.md) 를 참고하세요.

---

## 핵심 개념

| 개념 | 설명 |
|---|---|
| **칭호** | 모을수록 강해지는 수집형 요소. **보유스텟**(가지고만 있어도 적용) + **장착스텟**(장착 시 추가) |
| **인장** | 칭호와 동일한 구조의 별도 시스템. **명예 표시 전용, 스텟 없음** |
| **3개 슬롯** | `표시 칭호`(보이는 것) / `능력치 칭호`(스텟만) / `인장` — 서로 독립이며 동시 장착 |
| **닉네임** | 인게임 표시 이름 변경. 허용 문자·길이·비용·쿨타임 전부 설정 가능 |

표시 칭호와 능력치 칭호를 다르게 둘 수 있습니다.
예) 보이는 건 `[전설의 개척자]`, 실제 스텟은 `[광전사]`.

## 빌드

```bash
./gradlew build
# build/libs/InMc-TitleForge-1.0.0.jar
```

`gradle.properties` 의 `paperApiVersion` 이 서버 버전과 맞는지 확인하세요.

## 명령어

`/it` 을 입력하면 권한에 맞는 명령어 목록이 출력됩니다. (별칭: `/titleforge`, `/tf`, `/칭호`)

### 유저

```
/it menu                     메인 GUI
/it title | /it seal         칭호 · 인장 보관함
/it info [플레이어]           내 정보
/it equip <칭호ID>            능력치 슬롯 장착
/it show  <칭호ID>            표시 슬롯 장착
/it seal  <인장ID>            인장 장착
/it unequip <stat|show|seal>  해제
/it nick                     닉네임 변경 (팝업 입력)
```

### 관리자 (`titleforge.admin`)

```
/it create <title|seal> <ID> [표시이름...]
/it delete <title|seal> <ID>
/it edit <title|seal> <ID> name|lore|rarity|icon|permission|hidden|order <값...>
/it edit <title|seal> <ID> stat <equip|own> <스텟> <수치>
/it give|take <플레이어> <title|seal> <ID>     (오프라인 지원)
/it giveall <title|seal> <ID>
/it setnick <플레이어> <닉네임> | /it resetnick <플레이어>
/it admin                                     관리 GUI
/it reload
```

## 권한

| 권한 | 기본값 | 설명 |
|---|---|---|
| `titleforge.use` | 모두 | 명령어·GUI 사용 |
| `titleforge.nickname` | 모두 | 닉네임 변경 |
| `titleforge.info.other` | OP | 타인 정보 조회 |
| `titleforge.admin` | OP | 관리 전체 |

칭호별로 `permission` 을 지정하면 해당 권한이 있어야 장착할 수 있습니다.

## 플레이스홀더 (PlaceholderAPI)

```
%titleforge_nickname%          현재 닉네임 (없으면 실제 아이디)
%titleforge_realname%          실제 아이디
%titleforge_title_display%     표시 칭호
%titleforge_title_stat%        능력치 칭호
%titleforge_seal%              인장
%titleforge_nameplate%         인장 + 표시칭호 + 닉네임
%titleforge_title_count%       보유 칭호 수
%titleforge_title_total%       전체 칭호 수
%titleforge_title_percent%     수집률
%titleforge_seal_count/total/percent%
%titleforge_stat_<스텟ID>%      스텟 총합
%titleforge_stat_equip_<스텟ID>%  장착분
%titleforge_stat_own_<스텟ID>%    보유분
%titleforge_has_title_<ID>%    yes / no
%titleforge_has_seal_<ID>%     yes / no
```

`_mini` 접미사(`%titleforge_title_display_mini%`)를 붙이면 MiniMessage 원문을 그대로 돌려줍니다.

## 스텟 목록

바닐라 Attribute 로 실제 적용되는 값:
`max_health`, `attack_damage`, `attack_speed`, `armor`, `armor_toughness`,
`knockback_resistance`, `movement_speed`, `max_absorption`, `luck`

수치만 보관하고 API·플레이스홀더로 노출되는 커스텀 값:
`crit_chance`, `crit_damage`, `exp_bonus`, `drop_bonus`

## 다른 플러그인에서 사용하기

```kotlin
import kr.inmc.titleforge.api.TitleForgeApi

TitleForgeApi.stat(player.uniqueId, StatType.MAX_HEALTH)
TitleForgeApi.owned(player.uniqueId, BadgeType.TITLE)
TitleForgeApi.grant(player, BadgeType.TITLE, "first_join")
TitleForgeApi.nameplate(player)
```

이벤트: `BadgeGrantEvent`, `BadgeEquipEvent`, `NicknameChangeEvent` (모두 취소 가능)

## 설정 요약

- `storage.type`: `SQLITE`(기본) 또는 `MYSQL`
- `nickname.*`: 허용 문자(한글/영문/숫자/공백/추가문자), 최소·최대 길이, 한글 2칸 계산 여부, 금지어, 중복 금지, 쿨타임, Vault 비용, 아이템 비용
- `display.*`: displayName(기본 켜짐), 탭·채팅·네임태그(기본 꺼짐)
- `title.collection-milestones`: 칭호 N개 보유 시 추가 스텟
