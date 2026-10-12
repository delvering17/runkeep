# 지도 경로 갱신

## 확인한 동작과 원인 범위

GPS 점마다 전체 경로를 삭제·재생성하는 대신 변경된 조각만 갱신한다. 현재 구현은 [카카오 RunMap](../app/src/kakao/java/com/runkeep/app/ui/RunMap.kt)과 [객체 수명 회귀](../core/src/test/kotlin/com/runkeep/core/MapRouteUpdatesTest.kt)에서 확인할 수 있다. SDK 렌더링 프레임과 실제 깜빡임 해소는 자동 테스트만으로 입증하지 않는다.

## 갱신과 구간 정책

`MapRouteUpdates`는 예정 코스와 각 기록 구간의 SDK 객체·좌표 사본을 별도로 보관한다. 동일 좌표 내용에는 SDK 호출이 없다. 바뀐 조각은 기존 `RouteLine.changeSegments()`로 갱신하며 새 조각만 생성하고 없어진 조각만 개별 `RouteLine.remove()`로 제거한다. 스타일은 예정 코스/실제 기록의 기존 색·두께를 유지한다. 추가 순서에 의존하지 않도록 예정 코스의 zOrder는 0, 실제 기록은 1로 지정한다.

- 한 조각은 최대 256개 좌표다. 인접 조각은 끝점 하나만 공유하여 모든 기존 선분을 한 번씩 표시한다. 좌표를 축약하거나 GPS 수신·화면 반영을 늦추지 않는다.
- 완료된 구간과 꽉 찬 조각은 이후 GPS 추가에서 수정하지 않는다. 새 점은 마지막 조각에 즉시 반영하고 경계에서는 다음 조각만 추가한다.
- 서로 다른 `tracks`는 끝점을 공유하지 않는다. 일시정지/재개·GPS 공백의 구간 분리는 호출자가 제공한 목록 그대로 유지한다. 측정 좌표·거리·DB·RunEngine을 변경하지 않는다.
- 코스 수정, 운동/기록 전환, 목표/전체 표시 범위 축소·복귀는 현재 목록으로 조각을 대조하고 사라진 객체를 제거한다. 동일 경로가 재입력되어도 중복 생성하지 않는다. 운동 ID로 composition을 분리하는 기존 호출자와 공개 `RunMap` 인자 계약을 유지한다.
- 정상 화면 해제에서는 소유한 경로 객체를 제거한 뒤 `MapView.finish()`를 호출한다. SDK의 정상 종료(`onMapDestroy`)·비정상 종료(`onMapError`) 콜백에서는 이미 파괴된 객체에 호출하지 않고 참조만 비운다. 새 지도에는 새 관리자를 만들어 경로를 다시 구성한다. 경로 제거가 실패해도 finally에서 참조 해제와 지도 종료를 수행한다.

현재 위치 라벨, 위치 상태, 48dp 카메라 버튼·접근성, 최초 전체보기와 사용자 조작 이후 카메라 보존은 기존 정책을 사용한다. 최초 맞춤이 끝난 뒤에는 GPS 갱신마다 카메라용 전체 좌표를 펼치지 않는다. preview의 기존 경로 도식과 함수 계약은 호환되므로 preview 소스 수정은 필요하지 않았다.

## 비용과 한계

총 좌표 N개, 기록 구간 S개일 때 좌표 비교는 갱신당 O(N)이다. 호출자가 새 목록을 만드는 비용과 Compose effect 키의 내용 비교도 남아 있다. 변하지 않은 좌표 조각은 복사하거나 SDK 좌표로 변환하지 않는다. 진행 중 GPS 추가의 SDK 전달·새 좌표 사본은 최대 256개로 제한한다. 전체 전환에서는 실제로 변경된 모든 조각을 처리한다.

SDK 객체 수는 예정 코스 및 각 구간별 `ceil((좌표 수 - 1) / 255)`의 합이다. 2개 미만인 구간은 객체를 만들지 않는다. 좌표 사본은 원본과 공유 끝점을 포함한 O(N)이며 화면/지도 수명 종료에서 참조를 해제한다. 긴 경로의 반복 전체 재전송을 피하는 대신 조각 수에 비례하는 SDK 객체를 사용한다. 256은 구현의 전달량 상한이며 실기기 성능 측정으로 최적값을 확정한 수치는 아니다. 조각 경계의 실제 렌더링/LOD와 장시간 native 메모리 사용은 실기기 미검증이다.

## SDK 근거

- [공식 RouteLine 가이드](https://apis.map.kakao.com/android_v2/docs/api-guide/routeline/): 기존 객체의 세그먼트·스타일 변경과 생성 방법.
- [공식 RouteLine 선언](https://apis.map.kakao.com/android_v2/reference/com/kakao/vectormap/route/RouteLine.html): `changeSegments(RouteLineSegment...)`, `changeSegments(List<RouteLineSegment>)`, `remove()`.
- [공식 RouteLineLayer 선언](https://apis.map.kakao.com/android_v2/reference/com/kakao/vectormap/route/RouteLineLayer.html): `addRouteLine(RouteLineOptions)`의 객체 반환과 개별 제거/전체 제거의 구분.
- [공식 MapLifeCycleCallback 선언](https://apis.map.kakao.com/android_v2/reference/com/kakao/vectormap/MapLifeCycleCallback.html): 정상 종료 `onMapDestroy`와 비정상 종료 `onMapError`.
- [공식 MapView 선언](https://apis.map.kakao.com/android_v2/reference/com/kakao/vectormap/MapView.html): 지도를 완전히 종료하는 `finish()`와 pause/resume의 구분.

프로젝트는 SDK `2.15.2`를 유지한다. 고정 버전의 호출 시그니처는 kakao debug·simulation 컴파일로 확인한다. 문서에 없는 append/transaction API나 애니메이션으로 삭제를 숨기는 처리는 사용하지 않는다.

## 검증

순수 테스트는 SDK 명령을 기록하는 대역으로 객체 identity, 생성/갱신/삭제 횟수와 전달한 모든 선분을 검사한다. 실제 카카오 렌더러 검증과 구분한다.

- 동일 내용의 새 목록 100회 입력: SDK 명령 0회.
- 예정 코스·완료 구간·진행 구간에 GPS 점 100회 추가: 진행 구간 update 100회, 기존 코스/완료 구간 생성·삭제·갱신 0회.
- 10,000점 예정 코스와 4,000점 기록에서 50점 추가: 변경 조각만 처리하고 매 전달은 최대 256점이다. 모든 기존 선분과 완료 조각 사본을 유지한다.
- 256/257/511/512점 경계, 중간 공유 좌표 수정, 반복 좌표, 가변 입력의 좌표 사본, 구간 시작의 0/1/2점, 코스 교체, 운동 교체/초기화, 목표 범위 축소·전체 복귀를 검사한다.
- 정상 객체 해제·중복 해제·SDK 파괴 후 참조 해제·복귀, 실패한 생성/갱신 재시도를 검사한다.
- 실제 RunEngine에 합성 GPS를 넣어 수동 일시정지/재개·긴 GPS 공백·INTERRUPTED 복구 후 재개를 경로 관리와 연결한다. 구간 간 선분을 만들지 않고 엔진의 누적 거리도 유지한다.

자동 검사 설정·범위와 공개 CI 상태는 [검증 기록](VALIDATION.md)에 정리한다. GitHub Actions는 JDK 25 / JVM 21 / JUnit 5에서 core·simulation 단위 테스트와 preview/kakao debug·simulation 네 variant 빌드·lint를 수행하도록 설정되어 있다.

실기기 시각 확인, 지도 인증/실제 GPS, 터치·TalkBack 실낭독, 조각 경계의 표시와 장시간 native 성능은 미실행이다.
