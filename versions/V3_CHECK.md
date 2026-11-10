# MainActivity v3 검증 및 적용

검사일: 2026-11-10. 사용자 제공 v3를 ALS-Experiment의 실제 MainActivity.kt에 적용했다.
versions/MainActivity_v2.kt는 제거하고 versions/MainActivity_v3.kt에 제공 코드를 보관했다.

- 기존 1번 프로젝트의 Gradle 설정, LightCsv.kt, RawSensorCapture.kt, Manifest로 검증.
- compileDebugKotlin, testDebugUnitTest, lintDebug: BUILD SUCCESSFUL (1m 21s).
- 기존 단위 테스트 7개: 실패 0. CSV 2개, CaptureWindow 2개, PlaybackPreparation 3개.
- 준비/종료 유틸리티 테스트 통과는 해당 유틸리티를 사용하지 않는 v3의 실제 동작 검증을 뜻하지 않는다.
- Windows 한글 경로에서 테스트 실행기 실패 후 임시 V: 드라이브 경로로 재검증 성공.
- 전체 화면 systemUiVisibility API의 deprecation 컴파일 경고가 있다.
- APK 생성, 설치 및 실기기 재생/센서/ZIP 내보내기는 이번 검사에 포함하지 않았다.
- versions/v3_build_check.log에 성공한 실행 로그를 보관했다.

## 기존 1번과 달라진 동작

- 영상 원본 비율 유지 대신 화면 전체로 늘림. 화면/영상 비율이 다르면 도형 비율이 달라진다.
- 실제 영상 길이가 선택 시간표와 한 프레임 이내로 맞을 때만 패턴 라벨 활성화 가능.
- 영상/모드 변경 시 라벨 확인 초기화. 실험 시작 때 라벨 설정과 영상 길이/뷰 크기를 보관.
- 100ms 초과 위치 폴링 공백 또는 역행 구간은 위치 보간에서 제외.
- 패턴 경계를 정수 프레임에서 계산하고 비유한 위치 값을 거부.
- 라벨 비활성화 시 SCHEDULE_DISABLED 기록. 패턴 번호 의미 및 표시 설정을 run_info에 추가.
- 시작 시 키보드 숨김, 실험 중 현재 방향 잠금. 기존 Manifest의 가로 방향 지정은 유지된다.
- 기존 첫 프레임 사전 디코딩/되감기 확인 및 준비 15초 시간 초과 처리가 빠진다.
- 기존 메인 조도 기록 종료 시 스냅샷 재필터링은 빠진다. RawSensorCapture는 기존 1번 구현을 유지한다.
- 기존 사전 준비/화면 공개/첫 Texture 갱신 상세 로그 일부가 빠진다.

영상 길이 일치는 내용/제시 순서 검증을 대신하지 않는다. 실제 화면 발광 동기화는 검증하지 않았다.

Lint 결과: {'Warning': 3}.

## v3 APK 빌드 (2026-11-10)

- `python tools/check_android.py --apk`: BUILD SUCCESSFUL in 54s.
- 설치 파일: `ALS-Experiment-v3-debug.apk` (8,772,619 bytes).
- APK v2 서명 검증 성공. minSdk 25, targetSdk 35.
- ABI: armeabi-v7a, arm64-v8a, x86, x86_64.
- 앱 버전 설정은 0.4 / versionCode 2 유지.
- SHA-256: `26ab5a2d9a8e55ee59ff28ec6c3b29c880610a6c7a0cb7ff97b21dc599b67767`.
- 실제 태블릿 설치·재생은 미검증.

## 0.5 최종 개선판 (2026-11-10)

- 센서 FASTEST 요청, 수집 스레드 우선순위 소폭 상향, 버퍼 사전 확보, 측정 중 조도 UI 업데이트 생략.
- 최대 속도 요청은 정확한 10ms 주기나 실제 97Hz 이상을 보장하지 않는다. 이벤트를 반복/보간하지 않는다.
- 기록 종료 전 구간 닫기 및 스냅샷 재필터링 복원. Tablet_03의 종료 후 두 샘플 회귀 테스트 추가.
- 첫 프레임 사전 디코딩, 일시정지, 0ms 되감기 및 프레임 갱신 확인 복원. 준비 제한 15초.
- 초기 timestamp=0 Texture 갱신은 준비 근거에서 제외. 앱 중단/복귀 시 플레이어 정리/재준비.
- 재개 후 첫 프레임 정보 콜백이 오지 않을 수 있다. Texture 갱신 및 공개 요청은 실제 발광 시각이 아니다.
- `python tools/check_android.py --apk`: 최종 BUILD SUCCESSFUL in 59s.
- 테스트 8개 통과(CSV 2, 종료 경계 3, 준비 상태 3), lint 오류 0/경고 7.
- 최종 APK v2 서명 검증 성공, versionName 0.5, versionCode 3, minSdk 25.
- 설치 파일: `ALS-Experiment-0.5-debug.apk`, 8,843,487 bytes.
- SHA-256: `b8ce88e07088c266d5c4cc21f894475f9e8a92056dae4cc293d18a408f2bdd20`.
- 97Hz 달성 여부 및 약 0.412초 시작 지연 감소량은 실제 태블릿 재측정 필요.

## 0.6 자동 보정판 (2026-11-10)

- 앱에서 원본 저장 후 RecordingAlignment.generate로 영상 위치 로그와 메타데이터를 읽어 자동 시간 정렬.
- alignment/als_lux_aligned.csv, vendor_raw_aligned.csv, alignment_metadata.json 생성.
- 원본 열·샘플 값 보존. 구간 밖 샘플 제거, 위치 로그 외삽 없음, 100ms 초과 공백·역행·정지 구간 제외.
- 보정 오류는 원본 저장 성공과 분리하여 상태 및 가능한 경우 alignment_error.txt에 표시.
- ZIP 내보내기는 하위 alignment/ 폴더를 포함. 카운트다운 취소는 데이터 생성 안 함.
- 단위 테스트 13개 통과: 기존 8 + 자동 보정·CSV 보존·위치 예외·빈 로그·ZIP 테스트 5개.
- python tools/check_android.py --apk: BUILD SUCCESSFUL in 1m 9s.
- lint 오류 0개, 경고 7개. 전체 화면 API deprecation 컴파일 경고 유지.
- APK versionName 0.6 / versionCode 4 / minSdk 25. APK v2 서명 검증 성공.
- 파일: ALS-Experiment-0.6-debug.apk, 8,789,003 bytes.
- SHA-256: 24ab790d47773b564f191986b33f78040e60701a45b6c32cfb6a2e625dd6f1df.
- 실제 갤럭시탭에서 자동 보정 생성·ZIP 내보내기는 미검증. 영상 위치 기준 보정이며 광학 발광 교정이 아니다.
