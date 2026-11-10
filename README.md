# ALS-Experiment

Galaxy Tab A SM-T550의 조도 측정 앱. Android 7.1(API 25) 이상을 대상으로 한다.
현재 실제 앱 소스는 v3 기반 자동 보정판 0.6이다(2026-11-10).
센서 최대 속도 요청, 첫 프레임 사전 준비, 종료 경계 필터링을 적용했다.
이전 v3 APK는 앱 버전 0.4/versionCode 2이며, 자동 보정판은 0.6/versionCode 4이다.
설치용 파일: [ALS-Experiment-0.6-debug.apk](ALS-Experiment-0.6-debug.apk) (약 8.38 MiB, Android 7.1/API 25 이상).
0.6 검증: Kotlin 컴파일·단위 테스트 13개·lint·APK 빌드 성공(1분 9초), APK v2 서명 검증 성공.
실제 갤럭시탭에서 자동 생성·ZIP 내보내기는 아직 재검증하지 않았다.
이전 0.5 검사: Kotlin 컴파일·단위 테스트 8개·lint·APK 빌드 성공(59초), APK v2 서명 검증 성공.
Lint 오류 0개·경고 7개. 전체 화면 API deprecation 컴파일 경고는 남아 있다.
실제 기기에서 97Hz 달성 여부와 영상 발광 지연 감소량은 재측정해야 한다.
검사 결과는 [versions/V3_CHECK.md](versions/V3_CHECK.md), 이전 빌드 기록은
[BUILD_CHECK.md](BUILD_CHECK.md)에 있다.

Windows에서 직접 클릭할 수 있는 별도 Kivy UI는 [kivy_preview](kivy_preview/README.md)에 있다.
루트의 `미리보기 실행.cmd`로 실행하며, 영상 선택·미리보기·5초 카운트다운·중지를 지원한다.
Kivy 미리보기는 센서를 측정하지 않으며 기존 Kotlin Android 소스와 별도다.

## 사용 흐름

1. 태블릿에 영상을 복사하고 앱의 **기기에서 영상 선택**으로 선택한다.
2. 분석용 시간표 모드를 선택한다. 영상이 0초부터 P01→P30 순서로 진행하고
   각 패턴 뒤에 검정 구간이 있는 경우에만 라벨 활성화를 체크한다.
   영상 길이가 선택 시간표와 한 프레임 이내로 맞아야 체크할 수 있다.
3. 검은 화면 뒤에서 첫 프레임 디코딩·일시정지·0ms 되감기 후, 되감기 완료와 0ms 프레임 갱신을 모두 확인한다. 15초 내 준비되지 않으면 다시 영상을 선택한다.
4. 센서와 영상이 준비되면 실험 번호를 입력하고 시작한다.
5. 키보드를 숨기고 5초 카운트다운 후 영상 재생 명령과 센서 기록을 시작한다.
6. 영상 완료 또는 뒤로 가기로 종료하면 앱 전용 `ALSRecords/실험번호_시간/`에 원본을 저장한 뒤 `alignment/` 보정 폴더를 자동 생성한다.
7. **ZIP으로 내보내기**로 원본과 `alignment/` 폴더를 함께 사용자가 지정한 위치에 저장한다.

카운트다운 중에는 센서를 미리 구동하지만 데이터를 저장하지 않는다.
영상은 파일 선택기로 읽으므로 APK에 영상 리소스를 포함할 필요가 없다.
실험 중 앱 창 밝기를 최대로 설정하고 시스템 바를 숨기며 현재 화면 방향을 잠근다.
종료 시 이전 밝기·시스템 바·방향 설정을 복원한다. Manifest의 가로 방향 지정은 유지된다.
영상 전체를 화면 끝까지 늘려 표시하므로 화면과 영상 비율이 다르면 도형 비율이 달라진다.

## 0.5에서 추가한 개선

| 항목 | 동작 및 검증 범위 |
|---|---|
| 측정 빈도 | `SENSOR_DELAY_FASTEST` 요청, 센서 전용 스레드 우선순위를 소폭 높이고 버퍼를 미리 확보. 실험 중 조도 UI 갱신 생략 |
| 97Hz 목표 확인 | 실제 센서 timestamp로 Hz 계산. `minimum_desired_rate_met`에 평균 97Hz 이상 여부 기록. 정확한 10ms 주기와 실제 97Hz 이상은 기기에서 검증 필요 |
| 원본 보존 | 모든 실제 이벤트를 저장하며 10ms 타이머로 값 반복·보간·행 추가를 하지 않음 |
| 준비 중 화면 정리 | 초기 timestamp=0 Texture 갱신을 준비 완료 근거에서 제외. 앱을 벗어나면 플레이어를 정리하고 복귀 시 다시 준비 |
| 영상 시작 지연 완화 | 검은 화면 뒤에서 첫 프레임 디코딩 후 0ms로 되감아 대기. 0.412초 지연 감소량은 실제 기기에서 재측정 필요 |
| 종료 경계 | 다른 종료 작업 전에 기록 구간을 닫고 `시작 <= sensor timestamp < 종료` 조건으로 스냅샷 재필터링. 조도·제조사 원시 데이터 모두 적용 |
| 추가 로그 | 준비 시작·완료, 화면 공개 요청, 재개 후 첫 Texture 갱신, 구간 밖 제외 샘플 수 기록 |

사전 준비 중 첫 프레임 정보 콜백이 발생할 수 있어 재개 후 해당 값은 `UNAVAILABLE`일 수 있다.
새 Texture 갱신은 준비된 첫 프레임 공개 시각이나 실제 LCD 발광 시각과 같다고 보장할 수 없다.
이전 Tablet_03에서 센서 기록 시작→첫 프레임 정보 콜백은 약 0.412초,
MediaPlayer 위치 기준 오프셋은 약 0.381초였다. 개선판의 감소량은 아직 실측하지 않았다.

## 최초 v3에서 추가·변경된 기능

아래 표는 최초 v3와 기존 1번 프로젝트의 MainActivity(0.4)를 비교한 기록이다. 사전 준비·종료 경계·상세 로그는 0.5에서 복원했다.

| 항목 | v3 동작 |
|---|---|
| 영상 표시 | 원본 비율 유지와 검정 여백 대신 화면 전체로 늘림. 크롭 없이 표시하되 도형 비율이 달라질 수 있음 |
| 시간표 길이 검증 | 영상 길이와 선택 시간표의 차이가 한 프레임 이내일 때만 패턴 라벨 활성화 가능 |
| 라벨 확인 초기화 | 영상이나 모드를 변경하면 이전 라벨 확인을 해제 |
| 실험 설정 보관 | 시작 시 라벨 설정·영상 길이를 보관하고, 재생 시작 시 표시 영역 크기를 기록 |
| 위치 보간 제한 | 위치 조회 사이 간격이 100ms를 넘거나 재생 위치가 역행하면 해당 구간의 보간 제외 |
| 패턴 경계 계산 | 정수 프레임 기준으로 경계를 계산하고 NaN·무한대 위치 값을 제외 |
| 라벨 상태 기록 | 라벨을 끈 경우 `SCHEDULE_DISABLED`, 추정 불가 시 `NO_POSITION_ESTIMATE` 기록 |
| 추가 메타데이터 | 패턴 번호 의미, 실제 영상 길이, 확대 방식, 표시 영역 크기를 `run_info.txt`에 기록 |
| 실험 시작 화면 | 키보드 숨김, 카운트다운·재생 중 현재 화면 방향 잠금 및 전체 화면 처리 |

| 기존 1번에서 빠진 기능 | v3에서의 차이 |
|---|---|
| 첫 프레임 사전 준비 | 사전 디코딩·일시정지·0ms 되감기 확인과 15초 준비 시간 제한 제거. MediaPlayer 준비 완료를 사용 |
| 메인 조도 기록 종료 필터 | 종료 직전 버퍼를 기록 구간으로 다시 필터링하는 처리 제거. 종료 경계 설정 전 들어온 종료 이후 샘플이 남을 가능성 있음 |
| 상세 준비 로그 | 사전 준비 시작·완료, 화면 공개 요청, 재개 후 첫 TextureView 갱신 및 제외 샘플 수 등 일부 로그 제거 |

`RawSensorCapture.kt`는 기존 1번 구현을 유지하므로 제조사 원시 센서 데이터의
종료 구간 재필터링은 유지된다.

### 패턴 라벨 의미와 시간표

| 모드 | FPS | 패턴 / 검정 프레임 | 패턴 / 검정 시간 | 총 길이 |
|---|---|---|---|---|
| PAPER | 12 | 4 / 2 | 약 0.333초 / 0.167초 | 15초 |
| LONG | 10 | 30 / 30 | 3초 / 3초 | 180초 |

`pattern_id`는 도형 종류의 고유 번호가 아닌 제시 순서 1~30이다.
검정 구간은 직전 패턴 번호에 속한다. 영상·모드 변경 시 라벨 확인은 초기화된다.
길이 일치는 영상 내용이나 순서의 자동 검증을 뜻하지 않는다.
패턴 라벨은 MediaPlayer 위치를 보간한 추정치이며, 전환 경계 50ms 이내에는
`NEAR_TRANSITION`을 기록한다. 이는 보정된 오차 범위를 뜻하지 않는다.

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

센서 전용 HandlerThread에서 `registerListener(..., SENSOR_DELAY_FASTEST, 0, handler)`로
기기 최대 속도와 배치 지연 0을 요청한다. 실제 평균 이벤트 Hz는 센서 timestamp로 계산하여
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
설치할 수 있다. 검증한 설치용 파일과 결과는 문서 상단 및 검사 기록을 참고한다.

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
실제 설치용 APK는 문서 상단을 참고한다. API 25에서 설치·영상 재생·CSV 내보내기 및 실제 센서 Hz는 실기기 검증이 필요하다.

`versions/MainActivity_v3.kt`는 현재 적용한 v3 코드의 보관본이며, 실제 빌드 소스는 `app/src/main/java/com/example/alsexperiment/MainActivity.kt`다.

기존 테스트는 CSV 2개, CaptureWindow 3개, PlaybackPreparation 3개다.
0.6에서 시간축 보정·원본 보존·위치 공백·ZIP 내보내기 테스트 5개를 추가했다.
CaptureWindow에는 Tablet_03의 종료 이후 샘플 2개를 제외하는 회귀 테스트를 추가했다.
테스트 통과가 실제 기기의 샘플링 속도나 화면 발광 시각을 검증하는 것은 아니다.
전체 화면 API의 deprecation 컴파일 경고가 있으며, 실제 태블릿에서 별도 확인이 필요하다.

센서 주기는 시스템에 대한 요청값이며 정확한 10ms 간격을 보장하지 않는다.
[Android SensorManager 공식 문서](https://developer.android.com/reference/android/hardware/SensorManager)를 참고한다.

## 측정 후 시간축 보정

0.6 APK는 실험 종료 후 원본 CSV와 로그를 저장한 뒤, 백그라운드 저장 작업에서 이를 읽어
`alignment/` 보정 폴더를 자동 생성한다. 정상 종료와 수동 종료 모두 적용되며,
취소한 카운트다운은 기록을 생성하지 않는다. 보정이 완료되면 상태에 유효 보정 샘플 수를 표시한다.
ZIP 내보내기는 하위 폴더를 포함하므로 원본과 보정 파일을 함께 받을 수 있다.

보정 실패 시 원본 파일을 유지하고 결과 상태에 실패를 표시한다. 가능한 경우
`alignment_error.txt`에 오류를 남긴다. 이 경우 부분 생성된 보정 파일을 완성본으로 사용하면 안 된다.
성공 여부는 `alignment/alignment_metadata.json`과 앱 결과 상태로 확인한다.
보정 가능한 로그가 없으면 CSV는 생성하되 보정 시간은 빈칸이고 유효 샘플 수는 0이다.

기존 실험 데이터에도 적용할 수 있도록 PC용 `tools/align_recording.py`를 유지한다.
앱과 PC 도구 모두 원본 파일을 수정하지 않고 각 측정 폴더의 `alignment/`에
영상 위치 기준 보정 파일을 만든다.

프로젝트 루트(`ALS-Experiment`)에서 실행:

```powershell
python tools/align_recording.py ../Data/Tablet_03_20261110_205155_042 ../Data/Tablet_04_20261110_212455_875
```

다음 실험에서도 측정 폴더 경로를 인자로 지정하면 된다. 메타데이터뿐 아니라
`video_timeline.csv`의 `VIDEO_POSITION` 로그가 필요하다. 한 번 구한 고정 보정량을
다른 실험에 그대로 적용하지 않는다.

| 출력 파일 | 내용 |
|---|---|
| `alignment/als_lux_aligned.csv` | 원본 센서값·타임스탬프·기존 열을 유지하고 영상 기준 시간과 보정량 추가 |
| `alignment/vendor_raw_aligned.csv` | 제조사 원시 데이터에도 같은 영상 시간축 적용. 미선택 시 헤더만 생성 |
| `alignment/alignment_metadata.json` | 방법, 원본 SHA-256, 행 수, 제외 행 수, 유효 상태별 개수, 보정량 요약과 한계 |

| 추가 열 | 의미 |
|---|---|
| `aligned_video_time_ms` | 센서 이벤트 시각에서 두 영상 위치 로그 사이를 선형 보간한 영상 기준 시간 |
| `time_correction_ms` | `영상 기준 시간 − 센서 기록 시작 기준 경과 시간`. 양수는 더하고 음수는 뺌 |
| `alignment_status` | 유효한 추정은 `ESTIMATED_PLAYER_TIME`. 그 외 상태의 보정 시간은 빈칸 |
| `position_bracket_span_ms` | 보간에 사용한 두 위치 로그 사이 간격. 보정 오차 한계가 아님 |
| `aligned_pattern_id`, `aligned_segment_type` | 사용자 확인 시간표 기준의 패턴 번호·PATTERN/BLACK 구간 |
| `aligned_ms_to_transition`, `aligned_transition_flag` | 전환 경계까지 거리와 50ms 주의 플래그. 보정된 신뢰 구간이 아님 |

보정식: `aligned_video_time_ms = (sensor_timestamp_ns - actual_sensor_log_start_ns) / 1e6 + time_correction_ms`.
서로 다른 실험을 비교할 때는 기기별 절대 센서 timestamp 대신 `aligned_video_time_ms`를 사용한다.
lux 등 측정값을 바꾸거나 같은 간격으로 보간·복제하지 않으므로 100Hz 데이터로 변환하는 기능은 아니다.

시작 전·종료 시각 이상 샘플은 보정본에서 제외한다. 위치 로그 범위 밖, 100ms 초과 공백,
위치 역행·정지 구간에는 보정을 생성하지 않는다. 첫 프레임 정보가 있으면 그 이전도 제외한다.
사전 준비로 해당 정보가 없으면 표시 전 구간을 그 콜백으로 판별할 수 없다.

Tablet03·04에 적용한 결과(2026-11-10):

| 실험 | 유효 시간 보정량 중앙값 | 보정본의 기록 구간 밖 제외 행 | 유효 영상 시간 행 |
|---|---|---|---|
| Tablet03 | −380.907482ms | 2 | 16,539 |
| Tablet04 | +90.475266ms | 0 | 16,994 |

이 결과는 MediaPlayer 위치 기준 추정이다. 기존 앱의 위치 보간을 후처리로 재현하는 것이며,
실제 화면 발광·센서 내부 응답 지연을 독립적으로 검증하거나 없애지 않는다.
특히 Tablet04의 재생 직후 위치 점프도 그 로그에 포함되어 있으므로 물리적 싱크가
해결됐다는 근거로 사용하면 안 된다. 외부 동기화 기준을 측정한 경우 별도의 교정이 필요하다.

검증: `python -m unittest discover -s tools -p test_align_recording.py` — 테스트 3개 통과.
두 실제 실험의 보정본에서 유지된 모든 원본 열이 원본과 일치하고 원본 SHA-256도 확인했다.
