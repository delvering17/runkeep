# UI 문구 확인 범위

현재 문구 기준은 [UI 문구 점검](UI_COPY_AUDIT.md), 자동 검사 설정과 공개 CI 상태는 [검증 범위](VALIDATION.md)에 있다.

## 에뮬레이터 화면 확인

아래는 2026-10-10 당시 문구 정리 단계의 preview 에뮬레이터 화면 기록이다. 현재 공개 main의 화면 실행 결과가 아니다. 화면은 360×800dp, 기본 글꼴 및 시스템 글꼴 배율 2.0으로 확인했다. 사용자 운동 데이터 없이 새 앱 상태에서 진행했다.

| 확인 경로 | 결과·화면 |
| --- | --- |
| 달리기 시작 | 장식 배너·중복 설명·`선택` 접미사 없이 입력 대상/값/시작 동작 표시. [기본](screenshots/issue-12/01-start-phone.png), [글꼴 2배](screenshots/issue-12/07-start-font2.png) |
| 목표 거리 시트 | 정상 범위·빠른 선택·직접 입력 설명 제거. 5km 적용과 취소 시 기존 값 보존 확인. [기본](screenshots/issue-12/02-distance-sheet.png), [글꼴 2배](screenshots/issue-12/08-distance-font2.png) |
| 목표 페이스 시트 | 분/초 입력과 합성 값·단위·액션은 유지하고 중복 설명 제거. [화면](screenshots/issue-12/03-pace-sheet.png) |
| 빈 기록 | `기록 없음`만 표시하며 해당 기록이 없는 목표 거리 집계 안내 제거. [화면](screenshots/issue-12/04-history-empty.png) |
| 설정·계획 | 중복 제목/설명과 비어 있는 저장 계획 제목 제거. [설정](screenshots/issue-12/05-settings.png), [계획](screenshots/issue-12/06-plans.png) |
| 코스 목록·편집 | 좁은 화면에서 만들기/가져오기 버튼 전체가 줄바꿈. preview에서 불가능한 지도 조작 안내 제거. [목록](screenshots/issue-12/09-courses.png), [편집](screenshots/issue-12/10-course-editor.png) |
| GPS·음성 진단, 데이터·권한 | 본문 중복 제목과 구현 이름 제거, 상태·복구 동작과 데이터 영향 안내 유지. [진단](screenshots/issue-12/11-diagnostics.png), [데이터·권한](screenshots/issue-12/12-data-privacy.png) |


## 확인하지 않은 실행 환경

저장된 기록, 운동 중/복구, 심박수 가져오기·권한 실패, 지도 인증 실패 분기는 전체 소스 점검에 포함했으며 실제 화면 실행은 확인하지 않았다. 실제 Fold 접힘 자세·멀티윈도우, 실기기 TalkBack 음성 탐색, 카카오 native 지도·야외 GPS·이어폰 안내도 이번 에뮬레이터 검증 범위 밖이다.

권한·오류·복구·파괴적 확인과 입력 대상/단위/수치의 접근성 이름은 코드에서 보존했다. 이것은 TalkBack 실측 결과를 의미하지 않는다.
