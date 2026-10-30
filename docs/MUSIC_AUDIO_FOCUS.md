# 다른 음악 오디오 포커스 정책

## 문제와 선택 범위

사용자가 안내·경보 때 다른 앱 음악의 음량이 줄어든다고 보고했다. 이전 `RunAudioFocus`는 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`만 요청했다. 이번 변경은 그 요청 종류를 명시적으로 선택하도록 한다. 실기기에서 해당 보고의 원인을 재현하거나 특정 음악 앱의 반응을 확인한 결과는 아니다.

| 설정의 표시 | 저장값 | 실제 Android 요청 |
| --- | --- | --- |
| 음량 낮춤 요청 | `MusicFocusPolicy.DUCK` | `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` |
| 일시정지 요청 | `MusicFocusPolicy.PAUSE` | `AUDIOFOCUS_GAIN_TRANSIENT` |

기본값은 `DUCK`이다. 이전 설정 JSON에 필드가 없으면 `Preferences.musicFocusPolicy`의 기본값으로 읽으며 DB 마이그레이션은 필요 없다. 두 값은 새 설정 JSON에 보존된다. 음성의 자체 음량과 경보·메트로놈의 자체 음량은 기존 별도 값이다. 다른 음악 앱의 음량, 시스템 음악 스트림 음량, 통화 음량을 직접 변경하지 않는다.

전체 음성·페이스/거리/회전 안내·경보·메트로놈과 사용자 미리듣기에 같은 선택이 적용된다. 출력 켜기·끄기나 엔진의 안내 주기·GPS·박자 계산을 바꾸지 않는다. 지속 메트로놈이 포커스를 소유하면 안내가 끝난 뒤에도 음악에 대한 요청은 유지된다. 메트로놈 사이의 매 박자마다 요청/반환하지 않는다. 두 플레이어 중 마지막 소유자가 반환해야 OS 포커스가 해제된다.

## 세션과 변경 수명

`RunAudioFocus(context, initialPolicy = DUCK)`는 메인 스레드에서 사용한다. `client { Int focusChange }`, 사용권의 `acquire/release/close`, 세션의 `close` 계약을 유지하고 `setPolicy(policy)`를 추가한다. Android 어댑터는 순수 `AudioFocusSession`과 요청별 backend handle을 사용한다.

- 같은 정책은 요청·해제·플레이어 재시작을 하지 않는다. 소유자가 없는 동안 변경하면 값만 보관하고 다음 acquire에 적용한다.
- 정상 재생 중 변경하면 이전 요청을 한 번 반환하고 새 정책을 한 번 요청한다. 공유 음성·박자 소유권은 유지하며 새 요청이 허용되면 재생을 유지한다. 실패/예외는 `LOSS`로 모든 현재 소유자에게 전달하여 TTS를 중지하고 경보를 영구 차단한다. 포커스가 허용되기 전에는 출력하지 않는다.
- 일시 손실 중에는 기존 요청을 유지해 실제 GAIN을 기다린다. 정책 변경은 원하는 값만 보관하므로 다른 앱·통화의 포커스를 빼앗아 차단을 우회하지 않는다. 전화/통신/벨 등 `MODE_NORMAL`이 아닌 상태에는 새 acquire/정책 교체를 막는다. 정상 모드의 실제 GAIN이나 다음 허용된 acquire에서 보관한 정책을 적용한다.
- 일시 손실에서는 TTS가 중지·반환하고 경보는 소유권을 유지한 채 정지한다. GAIN 후 보관한 새 정책이 있다면 교체 성공 뒤 경보에 GAIN을 전달한다. 실패하면 영구 LOSS를 전달하여 비트를 다시 시작하지 않는다. 기존 `BeatOutputPolicy`가 최신 만료/박자 위상을 처리한다.
- 영구 손실과 이어폰 해제는 기존 경보의 `requiresResume`를 유지한다. 설정 변경이나 오래된 GAIN으로 해제하지 않는다. 명시적 START/RESUME 또는 출력 껐다 켜기의 `allowResume` 계약을 유지한다.
- 각 요청의 generation을 해제 **전에** 무효화한다. 정책 교체·마지막 반환·실패·close 이후 이전 요청의 LOSS/GAIN이 새 소유권·출력에 적용되지 않는다. 콜백에서 stop→release/close해도 다른 소유자의 알림 순회는 스냅샷을 사용한다.
- `ACTION_AUDIO_BECOMING_NOISY` 수신기는 공유 세션이 하나만 소유한다. 포커스를 가진 TTS뿐 아니라 재요청을 기다리는 경보에도 LOSS를 전달한다. 이어폰 해제 뒤 대기 경보가 스피커에서 자동 재시작하지 않도록 기존 영구 차단을 보존한다. 마지막 플레이어 close 뒤 세션 close로 수신기를 해제한다.
- `PaceAlarmPlayer.interruptions`는 focus LOSS/TRANSIENT/CAN_DUCK 및 noisy LOSS에서만 증가하는 StateFlow 카운터다. 명시적 stop·샘플 완료·GAIN은 증가시키지 않는다. 미리듣기 화면은 경보 샘플의 중단을 관찰하면 active/armed를 함께 해제하고 alarm.stop한다. 이전 샘플의 지연 완료도 request token으로 무효화하며, 이후 음량/박자 설정 변경이 `allowResume`를 거쳐 중단한 샘플을 자동 재생하지 않게 한다.

운동 서비스는 생성 때 현재 설정을 넣고 설정 이벤트에서 두 플레이어를 갱신하기 전에 같은 세션의 `setPolicy`를 한 번 호출한다. 미리듣기는 하나의 세션을 두 플레이어에 전달하며, 정책 갱신만으로 완료된 샘플을 다시 재생하지 않는다. 화면 일시정지·이탈에서는 두 플레이어 stop/close 후 세션을 닫는다. 이 연결은 `RunService`·설정·미리듣기에서 함께 사용한다.

## Android가 보장하지 않는 것

현재 앱은 min SDK 29, target SDK 36이다. 아래는 Android 공식 API의 요청 계약이다.

- [AudioFocusRequest](https://developer.android.com/reference/android/media/AudioFocusRequest)의 공개 gain 종류에는 원래 음량으로 다른 앱 음악을 계속 섞으면서 포커스 손실 콜백을 보장받는 선택이 없다. `MAY_DUCK`은 다른 음악이 낮은 음량으로 계속할 수 있다는 요청이며 `TRANSIENT`는 잠깐 일시정지 후 재개를 기대하는 요청이다. 동시 원래 음량 재생을 보장하는 옵션이나 무포커스 우회는 구현하지 않는다.
- [오디오 포커스 관리](https://developer.android.com/media/optimize/audio-focus)는 재생 전 `AUDIOFOCUS_REQUEST_GRANTED` 확인과 손실 시 중지/일시정지를 요구한다. 자동 ducking은 다른 앱의 콘텐츠 종류·포커스 설정에 따라 달라진다. 다른 앱이 스스로 pause하거나 요청을 다르게 처리하면 런킵에서 강제할 수 없다. `setWillPauseWhenDucked(true)`는 **런킵이 다른 앱의 duck 요청을 받았을 때**의 대응이며, 다른 앱의 ducking을 끄는 옵션이 아니다.
- [Android 15의 target 35 이상 제한](https://developer.android.com/about/versions/15/behavior-changes-15#request-audio-focus)에 따라 최상위 앱 또는 실행 중인 foreground service가 아니면 포커스 요청이 실패할 수 있다. 운동은 기존 foreground location service, 미리듣기는 표시된 화면 수명을 사용하고 실제 허용 결과를 계속 검사한다.
- 시스템의 전화 중 자동 mute에는 MEDIA/GAME 등의 조건이 있다. GUIDANCE인 런킵이 항상 OS에서 자동 mute된다고 설명하지 않는다. 기존 `AudioManager.mode` 새 출력 차단과 외부 손실 시 stop을 유지한다. [AudioManager](https://developer.android.com/reference/android/media/AudioManager)의 noisy 알림은 유선·Bluetooth 출력이 스피커로 바뀔 수 있다는 신호이며 모든 장치의 라우팅을 보장하는 이벤트는 아니다.

Android 버전·TTS 엔진·다른 음악 플레이어·Bluetooth 장치의 실제 음량/정지/복귀와 전화/화면 꺼짐에서의 실제 청취는 순수 backend 테스트로 입증하지 않는다.

## 검증

새 순수 fake backend 회귀는 두 정책과 idle/동일/active 변경, 음성·박자 시작/반환 순서, 지속 박자 중 100회 음성 수명, 일시/영구 손실, 정책 교체 실패/예외, 늦은 이벤트, 해제 중 재진입 콜백, 전화 차단/복귀, 포커스 대기 중 이어폰 해제, close/해제 중복을 검사한다. 기존 `BeatOutputPolicy`와 연결해 영구 차단 보존을 확인하고 미리듣기 중단·늦은 완료·정책 단독 변경의 자동 재생 방지를 `AudioPreviewState`와 연결해 검사한다. 설정 회귀는 이전 JSON 기본값과 두 값의 저장·자체 음량 보존을 검사하고 앱 단위 회귀는 실제 Android gain 상수의 매핑을 검사한다. Android StateFlow 관찰·실제 플레이어 출력은 순수 연결 회귀의 검증 범위가 아니다.

자동 검사는 [Android 워크플로](../.github/workflows/android.yml)의 JDK 25/JVM 21/JUnit 5 환경에서 두 지도 flavor의 debug·simulation 빌드·lint와 단위 테스트를 수행한다. 커밋별 실행 결과는 [GitHub Actions](https://github.com/delvering17/runkeep/actions)에서 확인한다. 이 문서는 회귀의 검사 범위와 기기 검증 한계를 설명한다.

실제 TTS·PCM·다른 앱 음악·이어폰·전화 청취와 에뮬레이터 검증은 미실행이다.
