# 빌드·실행 안내

## 필요한 환경

- Android Studio
- JDK 25: `gradle/gradle-daemon-jvm.properties`의 Gradle Daemon 기준과 프로젝트 toolchain에 사용합니다.
- Android SDK Platform 36, Build Tools 36.0.0, Platform Tools
- 실행할 Android 10(API 29) 이상 휴대폰
- 카카오 지도를 사용할 경우 카카오 네이티브앱 키와 서명 키 해시

저장소의 Gradle Wrapper를 사용합니다. 현재 빌드 설정은 Gradle 9.8.1, Android Gradle Plugin 9.4.1, Kotlin 2.4.21, KSP 2.3.12입니다. 앱과 core의 Java/JVM 타깃은 21이며 개발·테스트 JDK와 구분합니다.

## 저장소 열기

```sh
git clone https://github.com/delvering17/runkeep-public.git
cd runkeep-public
```

Android Studio에서 저장소 폴더를 **Open** 합니다. SDK Manager에서 필요한 SDK 구성 요소를 설치하고 Gradle Daemon JVM 기준 또는 Gradle JDK를 JDK 25로 설정합니다.

Android Studio가 생성한 `local.properties`의 `sdk.dir`를 유지하고 지도 키를 추가합니다. 설정 항목은 루트의 [local.properties.example](../local.properties.example)을 참고하세요.

```properties
KAKAO_NATIVE_APP_KEY=카카오_네이티브앱_키
```

실제 키에 따옴표를 붙이지 않습니다. REST API 키나 JavaScript 키가 아닌 네이티브앱 키를 사용합니다. `local.properties`는 Git에서 제외되므로 키와 개인 SDK 경로를 커밋하지 않습니다.

## 카카오 지도 설정

1. [카카오 개발자 콘솔](https://developers.kakao.com/)에서 카카오맵 사용을 활성화하고 Android 플랫폼을 등록합니다.
2. 패키지 이름을 **`com.runkeep.app`**으로 등록합니다.
3. 실행할 빌드에 사용하는 서명 키의 해시를 등록합니다. 다른 컴퓨터의 debug 서명 키나 release 서명 키는 각각 별도로 등록해야 합니다.

debug keystore가 생성된 환경에서는 다음과 같이 해시를 확인할 수 있습니다. 아래는 macOS·Linux에서 `keytool`과 `openssl`을 사용하는 예시입니다. 서명 키 위치를 변경했다면 해당 경로를 사용하세요.

```sh
keytool -exportcert -alias androiddebugkey \
  -keystore "$HOME/.android/debug.keystore" \
  -storepass android -keypass android \
  | openssl sha1 -binary | openssl base64
```

`keytool`을 찾을 수 없다면 설치한 JDK의 `bin/keytool`을 사용합니다. 기본 debug keystore가 아직 없다면 Android Studio에서 debug 빌드를 진행한 뒤 확인합니다.

공식 설정 절차는 [카카오 Android 지도 시작하기](https://apis.map.kakao.com/android_v2/docs/getting-started/quickstart/)와 [키 종류 안내](https://developers.kakao.com/docs/ko/kakaomap/common)를 참고하세요.

## 휴대폰에서 실행

1. Android Studio의 **Build Variants**에서 `app`의 **kakaoDebug**를 선택합니다.
2. 휴대폰의 개발자 옵션·USB 디버깅을 켜거나 [무선 디버깅](https://developer.android.com/studio/run/device#wireless)으로 연결합니다.
3. 상단 기기 목록에서 휴대폰을 선택하고 **Run**을 누릅니다.
4. 운동 시작 시 정확한 위치·알림·신체 활동 권한을 허용합니다. 위치 기능을 켜고 야외에서 GPS 수신 상태를 확인합니다.

신체 활동 권한이나 걸음 센서가 없으면 케이던스가 표시되지 않을 수 있습니다. 위치 수신과 음성 출력은 앱 **설정**에서 확인할 수 있습니다. 현재 위치 표시와 운동에 사용할 만큼 정확한 GPS 수신은 서로 다른 상태일 수 있습니다.

## 빌드 종류

| 종류 | 용도 | 앱 ID |
| --- | --- | --- |
| `kakaoDebug` | 카카오 지도를 사용하는 앱 | `com.runkeep.app` |
| `previewDebug` | 지도 SDK 없이 공통 UI·핵심 로직을 확인하는 앱 | `com.runkeep.app.preview` |
| `kakaoSimulation` / `previewSimulation` | GPS 시나리오 재생을 포함한 검증용 앱 | 각 flavor 앱 ID에 `.gpstest` 추가 |

preview는 지도 대신 경로 도식을 표시하며 지도 클릭으로 코스를 만들지 않습니다. 앱 ID가 다른 빌드는 데이터베이스도 별도입니다. simulation 재생 기능은 일반 debug·release에 포함되지 않습니다. 자세한 사용법은 [GPS 시뮬레이터](GPS_SIMULATOR.md)에 있습니다.

## 자동 검사

핵심 로직 테스트:

```sh
./gradlew :core:test
```

카카오 앱 빌드·lint:

```sh
./gradlew :app:assembleKakaoDebug :app:lintKakaoDebug
```

지도 키나 카카오 Maven 저장소 연결 없이 preview와 core 검사:

```sh
./gradlew :app:assemblePreviewDebug :app:lintPreviewDebug :core:test
```

Windows에서는 `./gradlew` 대신 `gradlew.bat`를 사용합니다. 필요한 JDK·Android SDK·의존성 저장소 연결은 검사 환경에 준비되어 있어야 합니다.

[Android build workflow](../.github/workflows/android.yml)는 두 지도 flavor의 debug·simulation 빌드와 lint, core 및 simulation 단위 테스트를 실행합니다. [GitHub Actions](https://github.com/delvering17/runkeep-public/actions)에서 해당 커밋의 결과를 확인하세요. 컴파일·lint·합성 테스트 결과와 실제 기기의 지도 인증, 화면 꺼짐 중 GPS, 오디오 출력, Health Connect 동기화 확인은 구분합니다.
