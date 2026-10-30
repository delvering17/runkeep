# 간결한 공통 모바일 조작

공통 모바일 조작의 선택·입력·재정렬 정책이다. 자동 검사 설정과 공개 CI 결과는 [검증 범위](VALIDATION.md)에서 구분한다.

공통 UI와 [코스·페이스 통합 편집](COURSE_PACE_INTEGRATION.md)의 조작 정책이다. Android Compose/Material3의 48dp·선택 의미·ModalBottomSheet를 구현 기준으로 사용한다. One UI의 짧은 보조 액션, COROS의 구간 요약과 Hevy의 별도 핸들 사례는 정보 위계 참고이며 Android 동작을 보장하는 증거가 아니다.

## 선택·입력 계약

- `SelectionRow`는 카드/테두리 없이 이름과 현재값, 다음 진입을 표시한다. 행 전체 최소48dp이며 큰 글꼴은 줄바꿈한다.
- `DomainSelectionSheet`는 안정 key의 radio 목록과 별도 후보, 적용/취소를 제공한다. 8개 이상은 검색을 제공하고 호출자의 `DomainSelectionState`(검색어+LazyListState)를 사용해 재진입 위치를 유지한다. 항목이 사라지면 적용할 수 없다. 시스템 권한·파일 선택은 기존 Android 계약이다.
- 거리 단위와 simulation 구간 종류는 이미 열린 편집 시트 안의 목록으로 선택한다. 모달을 중첩하지 않으며 부모 적용/취소까지 원본은 변경되지 않는다.
- 거리 m·지속시간 s·페이스 s/km, BigDecimal km/m 변환, 직접 숫자 입력·프리셋·증감과 정확한 소수값을 유지한다. 시각용 AM/PM TimePicker를 재사용하지 않는다.
- `IntegerSettingRow`는 정확한 정수 후보를 적용/취소하는 시트다. 재안내·목표 박자는 적용 전 저장/샘플을 갱신하지 않고 적용 후 기존 설정 관찰/샘플 debounce를 사용한다.
- 출발 대기 토글과 직접 케이던스 토글은 즉시 저장하지만 꺼도 `startDelayPresetSeconds`/`cadencePresetSpm`에 마지막 값을 보존한다. 이전 JSON은 기본5초/180spm으로 호환하며 실제 켜진 값은 유지한다. 박자 숫자는 입력 정책이지 건강 권장값이 아니다.
- 목표 이탈 음성이 꺼지면 종속 선택·반복, 거리 음성이 꺼지면 간격, 박자 출력이 꺼지면 직접 박자 입력을 숨긴다. 원래 선택값은 유지한다.

## 재정렬

계획·코스 목록은 이미 긴 누름 드래그와 자동 스크롤이 있었다. 전용48dp 핸들만 touch slop 이후 바로 끌며 행 몸통은 기존 선택/스크롤을 사용한다. 이동 시작/삽입 변경 촉각 피드백과 이웃 행 이동 애니메이션을 제공하고 안정 ID·원본 취소·undo·접근성 위/아래 이동을 유지한다. 코스의 좌표/연결 목표 정책과 native 지도 마커 긴 누름은 바꾸지 않는다. simulation 편집의 독립 취소·저장 계약도 보존한다.

## 선택 전수 점검

| 대상 | 적용 |
|---|---|
| 출발 코스/계획/모드/편집 구간 | [준비 화면](RUN_PREPARATION_UI.md)의 공통 radio 시트·요약 행 |
| 코스 사용 모드/목표 전환/저장 계획·계산할 값/계획 거리·시간 기준 | 공통 요약 행→시트 |
| 거리 단위/simulation 시나리오·구간 종류 | 기존 입력 시트 안의 목록 또는 공통 시트 |
| 기록 보기·정렬·필터·목표/전체·구간 기준·그래프 축 | [기록 화면](RECORD_UI.md)의 동일 API |
| 내보내기 형식/범위 | radio 후보→내보내기→기존 시스템 파일 선택 |
| 숫자 프리셋·카운트다운 토글·접근성 이동 | 도메인 목록이 아닌 값 조정/동작으로 유지 |

## 검증과 제한

종속값 직렬화·꺼짐/복원·legacy/잘못된 저장값 순수 회귀를 추가하고 기존 숫자 정확성/안정ID/undo 테스트를 유지한다. 자동 검사 설정·범위와 공개 CI 상태는 [검증 기록](VALIDATION.md)에 정리한다. GitHub Actions는 JDK 25 / JVM 21 / JUnit 5에서 core·simulation 단위 테스트와 preview/kakao debug·simulation 네 variant 빌드·lint를 수행하도록 설정되어 있다.

실기기 시각·터치·TalkBack·햅틱·IME/Fold, 카카오 인증은 미실행이다.

공식 근거: [Bottom sheets](https://developer.android.com/develop/ui/compose/components/bottom-sheets), [접근성 기본](https://developer.android.com/develop/ui/compose/accessibility/api-defaults), [제스처](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures). 구현 근거는 [공통 모바일 UI 규칙](MOBILE_UI.md)에 있다.
