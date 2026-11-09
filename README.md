# ALS-Experiment

Galaxy Tab A SM-T550의 조도 측정 앱. Android 7.1(API 25) 이상을 대상으로 한다.
Kotlin 컴파일, CSV 단위 테스트 2개, Android lint 검사를 통과했다.
테스트용 APK 빌드와 서명 검증을 완료했다. 실제 기기에서의 실행은 아직 검증하지 않았다.
검사 범위와 남은 경고는 [BUILD_CHECK.md](BUILD_CHECK.md)에 기록했다.

Windows에서 직접 클릭할 수 있는 별도 Kivy UI는 [kivy_preview](kivy_preview/README.md)에 있다.
루트의 `미리보기 실행.cmd`로 실행하며, 영상 선택·미리보기·5초 카운트다운·중지를 지원한다.
Kivy 미리보기는 센서를 측정하지 않으며 기존 Kotlin Android 소스와 별도다.

## 사용 흐름

1. 태블릿에 영상을 복사하고 앱의 **기기에서 영상 선택**으로 선택한다.
2. 센서와 영상이 준비되면 실험 번호를 입력하고 시작한다.
3. 5초 카운트다운 후 영상 재생 명령과 센서 기록을 시작한다.
4. 영상 완료 또는 뒤로 가기로 종료하면 앱 전용 `ALSRecords/실험번호_시간/`에 저장한다.
5. **ZIP으로 내보내기**로 사용자가 지정한 위치에 결과를 저장한다.

카운트다운 중에는 센서를 미리 구동하지만 데이터를 저장하지 않는다.
영상은 파일 선택기로 읽으므로 APK에 영상 리소스를 포함할 필요가 없다.
실험 중 앱 창 밝기는 최대로 설정하고 종료 시 원래 값으로 복원한다.

## CSV 형식

`Light.csv`는 제공받은 Sensor Logger 파일과 동일한 열 순서를 사용한다.

```csv
time,seconds_elapsed,lux
1793616190000000123,0.000000123,56.0
```

- `time`: Unix epoch 기준 나노초 정수. 센서의 단조 시각을 기록 시작 전의 고정된
  wall-clock/monotonic 앵커로 변환한다. 나노초 단위 표기이며 절대시각 정확도가
  나노초라는 의미는 아니다. 변환 앵커의 wall clock은 밀리초 해상도다.
- `seconds_elapsed`: 실제 ALS 기록 시작 시각부터 센서 이벤트 시각까지의 초.
  버튼을 누른 시점이나 첫 발광 시점이 기준인 것은 아니다.
- `lux`: Android TYPE_LIGHT 이벤트의 실제 조도값. 보간·복제하여 행을 추가하지 않는다.

헤더만 있는 파일은 수집된 이벤트가 0개라는 뜻이다.
`Metadata.csv`는 앱 자체 메타데이터 형식이며 Sensor Logger 전체 형식이나
그 앱으로의 재가져오기 호환성을 보장하지 않는다.

ZIP에는 상세 시각을 보존한 `als_lux.csv`, `video_timeline.csv`, `run_info.txt`,
`sensor_inventory.txt`, `selected_video.txt`, `vendor_raw.csv`,
`vendor_registration.txt`도 포함된다. 시간표 일치 체크를 켠 경우에만
`pattern_schedule.csv`와 추정 패턴 라벨을 생성한다. 임의 영상의 내용을 자동 분석하지 않는다.

## 측정 속도와 동기화

센서 전용 HandlerThread에서 `registerListener(..., 10000, 0, handler)`로
100Hz와 배치 지연 0을 요청한다. 실제 평균 이벤트 Hz는 센서 timestamp로 계산하여
화면과 `run_info.txt`에 기록한다. 제공된 SM-T550의 Sensor Logger 데이터는
13,394행/134.968초, 평균 99.231Hz였으며 새 앱에서도 실제 기기 비교가 필요하다.

재생 명령 전후, 첫 프레임 정보 콜백, TextureView 프레임 갱신 콜백 수신 시각을
기록한다. Texture timestamp는 별도의 원점을 가질 수 있어 센서 시각과 직접 빼지 않는다.
5초는 예약 목표이며 실제 지연은 `actual_start_lateness_ms`로 확인한다.
콜백은 화면의 물리적인 발광 시각이 아니다. 실제 발광 동기화 검증에는 포토다이오드 등
외부 기준 측정과 ALS 응답 지연 보정이 필요하다.

TYPE_LIGHT는 lux를 제공한다. 제조사 센서(type >= 65536)가 공개된 경우 추가로
선택하여 원시 배열을 저장할 수 있지만, 이것을 RGB라고 자동 해석하지 않는다.
RGB 채널 의미와 접근 권한은 해당 기기의 제조사 문서 또는 실측으로 확인해야 한다.

## 빌드 준비

이 작업 환경에서는 JDK/Gradle/SDK를 저장소의 `.build-tools/`에 준비했다.
Windows에서 `python tools/check_android.py`를 실행하면 의존성 검사, Kotlin 컴파일,
단위 테스트, lint를 수행한다. APK는 생성하지 않는다. 도구가 없으면 공식 배포처에서
다운로드하며, 한글 경로 문제를 피하도록 검사 중에만 빈 드라이브 문자를 임시 연결하고
완료 후 해제한다. 시스템 전체 Java나 PATH 설정은 변경하지 않는다.

APK를 만드는 후속 단계의 준비 사항:

이 PC에서 테스트용 APK를 만들려면 저장소 루트에서 다음 명령을 실행한다.
`--apk`를 붙였을 때만 검사 후 `assembleDebug`로 디버그 APK를 생성한다.

```powershell
python tools/check_android.py --apk
```

완료 파일은 `app/build/outputs/apk/debug/app-debug.apk`다. 태블릿으로 복사해
설치할 수 있다. 이 명령으로 테스트용 APK 빌드와 서명 검증을 완료했다.

- JDK 17, Android SDK Platform 35, SDK Build Tools, Gradle 8.9가 필요하다.
- Android Studio에서 저장소 루트를 열고 SDK 경로를 설정한다.
- Gradle Wrapper는 아직 포함되어 있지 않다. 설치한 Gradle 8.9로 루트에서
  `gradle wrapper --gradle-version 8.9`를 실행하여 생성한다.
- `gradlew.bat testDebugUnitTest lintDebug assembleDebug`로 검사 및 디버그 APK 빌드.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- 기기에서 개발자 옵션/USB 디버깅을 켜고 연결하거나 APK를 직접 설치한다.

`minSdk=25`는 설치 가능한 최소 OS이며 `compileSdk/targetSdk=35`와는 다르다.
현재 고정한 Compose/Activity/Lifecycle 의존성을 사용하고 자동 최신화하지 않는다.
Java 17은 빌드 PC용이며 태블릿에 Java 17을 설치하는 것이 아니다.
현재 개발 환경에 도구를 준비하여 컴파일·단위 테스트·lint 검사를 완료했다.
APK 패키징과 서명 검증을 완료했다. API 25에서 설치·영상 재생·CSV 내보내기 및 실제 센서 Hz를 검증해야 한다.

`versions/MainActivity_v2.kt`는 보관용 이전 코드이며 빌드 소스에 포함되지 않는다.

## 이전 및 개선 코드 보관

`versions/` 폴더에는 이전 코드와 개선 실험 버전을 보관한다. 이 폴더의 Kotlin 파일은 기록 및 비교를 위한 보관용이며, 현재 Android 앱의 빌드 소스에는 포함되지 않는다.

- `versions/MainActivity_v2.kt`: 기존 앱 코드의 이전 버전.
- `versions/MainActivity_v3.kt`: 영상 재생 시작 신호 기록, 센서 데이터 종료 구간 필터링, 영상 위치 보간 조건 등을 개선한 실험용 코드.

### MainActivity v3 개선 사항

1. **영상 사전 준비(Prewarming)**
   - 카운트다운 중 MediaPlayer를 미리 준비하여 재생 시작 시 초기화 지연을 줄이도록 개선했다.
   - 사전 준비 완료 여부를 확인하고, 준비가 늦어지는 경우 대기하도록 구성했다.

2. **첫 프레임 신호 기록**
   - 재생 명령 시각과 첫 프레임 관련 콜백 수신 시각을 구분하여 기록한다.
   - TextureView 프레임 갱신 및 MediaPlayer 렌더링 시작 신호를 활용한다.
   - 첫 프레임 신호의 수신 시각과 종류를 `run_info.txt`에 기록한다.
   - 해당 콜백은 실제 디스플레이의 물리적인 발광 시각을 의미하지 않는다.

3. **ALS 데이터 종료 구간 필터링**
   - 실험 종료 시각을 기준으로 저장 직전 센서 데이터를 다시 필터링한다.
   - 실제 측정 시작 시각 이상, 종료 시각 미만인 데이터만 저장하도록 구성했다.

4. **영상 위치 보간 제한**
   - 영상 위치 기록 사이의 간격이 100ms를 초과하면 해당 구간의 영상 위치와 패턴 라벨을 보간하지 않도록 했다.

5. **시간 기록 항목 보완**
   - 실제 재생 명령 시각, 첫 프레임 신호 수신 시각, 센서 이벤트 시각을 구분하여 분석할 수 있도록 기록 항목을 보완했다.

### 적용 및 검증 상태

- V3는 기존 코드와 비교하고 통합하기 위한 개선 실험용 버전이다.
- 현재 빌드 및 테스트를 통과한 앱 소스와 별도로 보관한다.
- V3 자체의 Android Gradle 빌드 및 실제 태블릿 동작은 아직 검증되지 않았다.
- 실제 앱에 적용하려면 기존 `app/src/main/java/com/example/alsexperiment/MainActivity.kt`와 비교·통합해야 한다.
- V3 파일을 빌드 소스 경로에 별도로 추가하면 `MainActivity` 클래스 중복 문제가 발생할 수 있으므로 주의한다.
