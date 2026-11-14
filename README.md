# 잡소리 리포트 (BSR Report)

재현이 어려운 차량 잡소리를 운전 중 음성 한 마디로 기록하고, 소리 전후의 차량 상태 변화를 담은 리포트로 정비소에 증명하는 Pleos Connect 앱입니다.

- 팀: BEQUIET
- 패키지: `com.bsr.noisereport`
- Pleos Playground 프로젝트: `bsr_recorder` (카테고리: 모빌리티 서비스)

## APK

- 위치: [`apk/BEQUIET_BSR_Report_release.apk`](apk/BEQUIET_BSR_Report_release.apk) (release 서명)
- 설치: 에뮬레이터를 켠 뒤 `adb install -g apk/BEQUIET_BSR_Report_release.apk`
- 이전에 debug 빌드를 설치했다면 서명이 달라 먼저 `adb uninstall com.bsr.noisereport`가 필요합니다.

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (Android 14 / API 34, Automotive, x86_64) |
| 화면 규격 | 2560 × 1440, 160 dpi (앱 영역 약 1680 × 1210) |
| AVD 설정 | RAM 4GB, Cold boot |
| 테스트 PC | Windows 10, Intel Iris Xe |

## 실행 방법

1. 에뮬레이터를 Cold boot로 켜고 APK를 설치합니다(아래 1장 참고).
2. 앱 목록에서 **잡소리 리포트**를 엽니다.
3. **증상 등록**에서 소리 위치와 종류를 고릅니다. 바로 리포트를 보려면 홈의 **샘플 기록 5건 넣기**를 누릅니다.
4. **기록 시작하고 출발** → 소리가 나면 **방금 소리 났어** → **주차하고 기록 끝내기**
5. **리포트 쓰기**에서 보낼 기록, 소리 위치, 받는 곳을 고르고 **리포트 만들기**를 누르면 정비사용 리포트와 QR이 나옵니다.

음성 명령(Gleo)은 승인 전이라 같은 동작을 adb로 보낼 수 있습니다.

```bash
adb shell am start --user current -a MARK_NOISE -n com.bsr.noisereport/.MainActivity
```

`MARK_NOISE` 대신 `START_TRACKING`(기록 시작), `TRACKING_STATUS`(기록 횟수)도 됩니다.

## 필요한 설정과 제약

- **속도·기어**: 에뮬레이터와 Vehicle SDK에 값이 없어 앱 내 주행 시뮬레이션 값을 씁니다.
- **브레이크·조향각**: 기록 화면의 **Virtual Console 값 사용**을 켜면 Vehicle SDK 값을 씁니다(4장 Virtual Console).
- **마이크**: PC 마이크 입력을 켜야 실제 소리가 녹음됩니다(5장). 입력이 무음이면 시연용 합성음으로 대신 저장합니다.
- **서버**: 아직 없습니다. 리포트는 차 화면 안에서만 열리고, 링크·QR은 형식만 맞춘 것입니다.
- 프로토타입 구조와 코드 위치는 [`PROTOTYPE.md`](PROTOTYPE.md)에 있습니다.
- 리포트 디자인 목업: [`mockups/mechanic-report.html`](mockups/mechanic-report.html)

## 개발 환경

| 항목 | 버전 | 비고 |
|---|---|---|
| Android Studio | 2026.2.2 이상 | |
| JDK | 17 | Gradle이 필요하면 다른 JDK를 자동으로 내려받습니다 |
| Android SDK Platform | 34, 35 | 에뮬레이터는 API 34 |
| Pleos Connect Emulator | v2.1.0 (API 34, x86_64) | 시스템 이미지 약 4.1GB |
| Vehicle SDK | `ai.pleos.playground:Vehicle:2.3.2` | |

PC 사양: 공식 최소 GPU는 RTX 3060 Laptop 6GB입니다. GTX 1660 SUPER에서도 실행은 확인했습니다.

## 1. Pleos 에뮬레이터 설치

1. Android Studio에서 **Tools > SDK Manager > SDK Update Sites**로 가서 **+**를 누르고 다음을 입력합니다.
   - Name: `Pleos Connect System Image`
   - URL: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
2. **SDK Platforms** 탭에서 **Show Package Details**를 켜고, API 34 아래의 **Pleos Connect v2.1.0 APIs Intel x86_64 Atom System Image**를 설치합니다. 이때 42dot SDK 라이선스에 동의하게 됩니다.
3. **Device Manager > Create device**에서 다음과 같이 만듭니다.
   - Form Factor: **Automotive**, 기기: **Pleos Connect v2.1.0**
   - 시스템 이미지: **API 34, Pleos Connect v2.1.0 APIs**
   - RAM 4GB, 부팅 방식 **Cold boot**. Quick boot를 쓰면 차량 HAL(VHAL)이 동작하지 않습니다.

> **주의:** AVD를 처음부터 Pleos 이미지로 만들어야 합니다. Google 자동차 이미지로 한 번이라도 부팅한 뒤 이미지를 바꾸면 이전 데이터가 남아 다음 증상이 생깁니다. 이 경우 Device Manager에서 **Wipe Data**를 하고 다시 부팅하세요.
> - 메인 화면이 검게 나옴
> - 차량 서비스가 반복 종료됨
> - Virtual Console이 `emulator_inactive` 오류를 냄

4. 처음 실행하면 에뮬레이터 화면에 **USB 디버깅 허용** 창이 뜹니다. "이 컴퓨터에서 항상 허용"을 체크하고 허용합니다. 이 이미지는 보안 설정이 켜져 있어 허용하기 전에는 `adb`가 `unauthorized`로 나옵니다.

## 2. 프로젝트 설정

```bash
git clone https://github.com/jijihuny/bsr-recorder.git
cp pleos.properties.example pleos.properties
```

`pleos.properties`에는 Pleos Playground > My Project > 프로젝트 정보의 프로젝트 ID, 클라이언트 ID, 클라이언트 시크릿을 넣습니다. 이 파일은 Git에서 제외되어 있으니 **절대 커밋하지 마세요.**

Vehicle SDK는 `settings.gradle.kts`에 등록된 Pleos Maven 저장소에서 받습니다. 공식 시작 가이드에는 `2.3.1`로 적혀 있지만 이 버전은 저장소에 없어서 `2.3.2`를 씁니다.

### Dropbox 폴더에서 작업할 때

Dropbox 안에서 빌드하면 빌드 중간 파일이 클라우드 자리 표시자로 바뀌어 `Cannot snapshot ... not a regular file` 오류로 실패합니다. 빌드 폴더를 Dropbox 동기화에서 빼 주세요. 폴더가 없으면 먼저 만든 뒤 실행합니다(PowerShell).

```powershell
foreach ($d in '.gradle','build','app\build','.kotlin','.git') {
  if (-not (Test-Path $d)) { New-Item -ItemType Directory $d | Out-Null }
  Set-Content -Path $d -Stream com.dropbox.ignored -Value 1
}
```

되돌리려면 `Clear-Content -Path <폴더> -Stream com.dropbox.ignored`를 실행합니다.

## 3. 빌드와 실행

에뮬레이터가 부팅된 상태에서 Android Studio의 **Run**을 누르거나 다음을 실행합니다.

```bash
./gradlew installDebug
```

release APK는 `keystore.properties`(Git 제외)가 있을 때 서명됩니다. 형식은 다음과 같습니다.

```properties
storeFile=C:/path/to/bsr-release.jks
storePassword=...
keyAlias=bsr
keyPassword=...
```

```bash
./gradlew assembleRelease
```

GitHub Actions의 **Release APK** 워크플로는 저장소 Secrets(`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`)로 같은 서명의 APK를 만듭니다.

## 4. Pleos Connect Virtual Console

차량 상태(기어, 브레이크, 조향각, 속도, 도어, 공조 등)를 바꿔 앱 반응을 시험하는 웹 콘솔입니다. 에뮬레이터 v2.1.0 이상이 필요합니다.

```bash
adb forward tcp:8088 tcp:8088
```

브라우저에서 `http://127.0.0.1:8088/`을 엽니다.

- 에뮬레이터를 다시 켤 때마다 `adb forward`를 다시 실행해야 합니다.
- 콘솔은 브라우저 탭이 열려 있는 동안에만 차량 상태를 바꿉니다. 탭이 1초마다 세션 신호를 보내는 구조입니다.
- 에뮬레이터를 재시작한 뒤 fetch 오류가 남아 있으면 탭을 새로고침합니다.

## 5. 마이크 입력

에뮬레이터 앱이 PC 마이크 소리를 받으려면 마이크 입력을 켜야 합니다.

```bash
adb emu avd hostmicon
```

또는 에뮬레이터 **Extended Controls > Microphone**에서 PC 오디오 입력을 켭니다. 일반 앱의 `AudioRecord` 녹음이 에뮬레이터에서 동작하는 것을 확인했습니다. 실차 마이크와는 다르므로 실차 동작은 별도로 확인이 필요합니다.

## 문제 해결

| 증상 | 원인 | 해결 |
|---|---|---|
| `adb devices`가 `unauthorized` | 디버깅 허용 전 | 에뮬레이터 화면에서 USB 디버깅 허용 |
| 메인 화면이 검게 나옴, 차량 서비스 반복 종료 | 다른 이미지로 부팅한 데이터가 남음 | Wipe Data 후 Cold boot |
| 콘솔이 `emulator_inactive` | 에뮬레이터 내부 통신(DDS)이 쓰는 `eth0`의 `10.0.2.15` 주소가 없음 | Wipe Data 후 Cold boot, 브라우저 탭을 연 상태 유지 |
| 빌드가 `not a regular file`로 실패 | Dropbox가 빌드 파일을 자리 표시자로 바꿈 | 위 Dropbox 설정 적용 |
| Vehicle SDK 의존성을 못 찾음 | 가이드의 `2.3.1`은 존재하지 않음 | `2.3.2` 사용 |

## 참고

- [Pleos Connect 앱 개발 시작하기](https://document.pleos.ai/en/docs/pleos-only/getting-started)
- [에뮬레이터 설치](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [Virtual Console 사용 가이드](https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide)
- [Vehicle SDK 샘플 앱](https://github.com/Pleos-Playground/pleos-sample-vehicle-app)
