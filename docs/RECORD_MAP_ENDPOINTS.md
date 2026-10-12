# 기록 지도 끝점

기록 화면만 `showRecordEndpoints=true`를 전달한다. 다른 공개 RunMap 인자는 그대로이며 새 인자 기본false로 코스 편집/운동 중 지도 동작을 유지한다. caller의 목표/전체 범위로 잘린 `tracks`에서 실제 첫·끝 좌표를 사용하고 비어 있는 segment는 건너뛴다. 계획 코스에서 가짜 끝점을 생성하지 않는다.

출발은 원+삼각형, 종료는 사각 깃발로 색만으로 구분하지 않는다. 같은 좌표/한 점/왕복 복귀는 두 모양을 함께 표시한다. 별개 좌표의 표식은 현재 화면 좌표와 아이콘 크기로 겹침을 판단하고 실제 화면상 왼쪽 표식은 왼쪽, 오른쪽 표식은 오른쪽으로 배치한다. 같은 x에서는 출발/종료를 좌우로 나눈다. 지리 거리 5m만으로 판단하지 않아 줌/회전/긴 경로의 축소에서도 겹침을 보정하며 실제 좌표는 바꾸지 않는다. 접근성 이름은 기록 경로/출발 삼각형/종료 깃발로 제공한다. preview는 실제 지도 인증을 대신하지 않는 경로 도식이다.

카카오 공식 [LabelManager](https://apis.map.kakao.com/android_v2/reference/com/kakao/vectormap/label/LabelManager.html) `addLayer`, [LabelLayerOptions](https://apis.map.kakao.com/android_v2/reference/com/kakao/vectormap/label/LabelLayerOptions.html) `from/setCompetitionType/setClickable/setZOrder`, [CompetitionType.None](https://apis.map.kakao.com/android_v2/reference/com/kakao/vectormap/label/CompetitionType.html)를 확인했다(2026-10-12). 기존 기본 코스 라벨과 독립된 기록 끝점 layer를 사용하고 실제 끝점/지도 객체가 바뀔 때만 최대2개 라벨을 생성한다. 기록 끝점이 없는 지도에는 끝점 bitmap/layer/리스너를 생성하지 않는다. 카메라/viewport가 바뀔 때 기존 라벨의 스타일만 갱신하며 경로선은 다시 만들지 않는다. 경로선 `MapRouteUpdates`와 현재 위치·카메라·전체보기·native 지도 제스처는 변경하지 않는다. 지도 종료 시 SDK 소유 객체를 사용하지 않고 effect 해제 경로로 정리한다. 정상 effect 해제에서는 기록 전용 layer/리스너도 해제한다.

순수 회귀8개: 빈 경로, 분리/빈 segment의 실제 끝, 한 점/같은점/왕복, 가까운 원좌표 보존, 목표/전체 범위·원본 보존, 역방향 화면 근접, 줌/회전 시 재배치, 모든 겹침 사분면의 아이콘 경계 분리. 지도 경로 객체 수명/갱신 회귀 유지. 자동 검사 설정·범위와 공개 CI 상태는 [검증 기록](VALIDATION.md)에 정리한다.

실기기 렌더링·카카오 인증·근접 표식 시각·TalkBack은 미실행이다.

## 근접 표식 보완과 SDK 관찰 계약

이전 고정 출발-left/종료-right anchor는 출발이 종료의 오른쪽에 있으면 오히려 표식 중심을 겹치게 했다. 위도0의 경도 `.00004 → .0005 → 0` 경로를 300dp preview에 표시하면 실제 출발x24dp/종료x0dp인데 두 아이콘 중심이 모두12dp가 되는 소스 수식 재현을 확인했다. 개선한 공통 `recordEndpointAnchors`는 centred 아이콘 경계가 이미 분리돼 있으면 중앙 anchor를 유지하고, 겹치면 현재 화면 x 순서의 바깥 방향으로 나눈다. preview는 Canvas 투영 직후 이 정책을 적용하므로 확대 비율·창 크기에 맞게 계산한다.

카카오 공식 [KakaoMap](https://apis.map.kakao.com/android_v2/reference/com/kakao/vectormap/KakaoMap.html)의 nullable `toScreenPoint(LatLng)`로 viewport pixel 위치를 읽는다. [OnCameraMoveEndListener](https://apis.map.kakao.com/android_v2/reference/com/kakao/vectormap/KakaoMap.OnCameraMoveEndListener.html)의 `(KakaoMap, CameraPosition, GestureType)`와 [OnViewportChangeListener](https://apis.map.kakao.com/android_v2/reference/com/kakao/vectormap/KakaoMap.OnViewportChangeListener.html)의 `(KakaoMap, Rect)` 시그니처 및 setter의 null 해제 계약을 공식 문서에서 직접 확인했다(2026-10-12). 두 관찰 리스너는 기록 끝점 effect가 소유하며 카메라/viewport를 움직이지 않는다. 카메라 이동 종료와 창 크기 변경 뒤 화면 x/y 및 36dp 아이콘 경계를 재평가한다. 현재 anchor와 같으면 갱신하지 않는다. projection이 null이면 SDK 좌표를 만들어내지 않고 다음 유효 관찰까지 현재 스타일을 유지한다.

[Label.changeStyles(LabelStyles, boolean)](https://apis.map.kakao.com/android_v2/reference/com/kakao/vectormap/label/Label.html)로 기존 라벨의 anchor 스타일을 전환한다. native callback 작업은 view thread로 보내고 effect 해제 토큰과 지도 identity로 늦은 작업을 막는다. 실제 지도 파괴 뒤에는 layer/리스너 API를 호출하지 않는다. callback 기반 보정은 카메라 이동 종료 후의 표시를 보장하며 움직이는 중간 프레임의 실제 렌더링·시각 평가는 미실행이다.

수식 교차검증에서 24/36dp 크기와 방향/거리/위치 조합 2,178개의 아이콘 경계가 서로 침범하지 않음을 확인한 기록이다. 이는 Kotlin/JUnit 실행이나 실제 렌더링 검사가 아니다. 소스 수식 및 순수 회귀를 실제 카카오 렌더링 성공으로 간주하지 않는다. 자동 검사 설정·범위와 공개 CI 상태는 [검증 기록](VALIDATION.md)에 정리한다.

현재 [카카오 RunMap](../app/src/kakao/java/com/runkeep/app/ui/RunMap.kt)에서 사용자 조작은 `MapGestureFrame.onPan`의 `cameraPolicy.userMoved(); follow=false; centerRequested=false` 경로로 처리한다. 끝점 observer와 현재 위치 버튼의 follow 복귀, 최초 전체보기, 위치/camera 효과를 별도로 유지한다.
