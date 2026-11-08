# Android 코드 검사 결과

검사일: 2026-11-08. 대상 설정: SM-T550, minSdk 25 / compileSdk·targetSdk 35.

## 완료한 검사

Microsoft OpenJDK 17, Gradle 8.9, Android SDK Platform 35와 Build Tools 34.0.0으로 실행했다.

```text
:app:compileDebugKotlin
:app:testDebugUnitTest
:app:lintDebug
BUILD SUCCESSFUL in 1m 28s
32 actionable tasks: 32 executed
```

- 실제 의존성 해석, AAR 메타데이터 검사 및 Manifest 통합 성공.
- 앱 Kotlin 코드와 테스트 코드 컴파일 성공.
- CSV 단위 테스트 2개 성공, 실패·오류 0개.
  - Unix 나노초 정수 하위 자릿수 보존과 측정 시작 기준 경과 시간 확인.
  - 소수점 구분자가 쉼표인 시스템 언어에서도 CSV 숫자 형식 유지 확인.
- Android lint 오류 0개, 경고 5개.
- APK 생성 작업(assemble/package)은 실행하지 않았고 `app/build`의 APK 파일도 0개다.

## 남은 경고

- GradleDependency 3개: Compose BOM, Activity, Lifecycle의 새 버전 안내.
  현재 검증한 고정 버전을 유지했다.
- DataExtractionRules 1개: Android 12 이상에서 사용할 백업·기기 이전 정책 추가 안내.
- MissingApplicationIcon 1개: 앱 전용 아이콘 추가 필요.
- 별도로 Kotlin 컴파일러는 구형 전체 화면 API의 deprecation 경고를 출력한다.
  컴파일 오류는 아니며 실제 기기에서 전체 화면 전환을 확인해야 한다.

## 검사 환경에서 해결한 문제

Gradle Wrapper 생성 시 기본 배포 URL 접속 검사에 실패했다. Gradle 8.9 배포본을
공식 GitHub 배포 API로 받고 공식 SHA-256과 일치하는지 확인한 뒤 직접 실행했다.
이 검사에는 Wrapper 생성 작업을 포함하지 않았다.

Windows 한글 경로에서 Gradle 테스트 프로세스가 GradleWorkerMain을 찾지 못했다.
원본 폴더를 이동하지 않고 검사 동안만 `subst`로 영문 드라이브 경로를 연결하여
컴파일·테스트·lint를 완료했다. 별칭은 종료 후 해제한다.

## 아직 확인하지 않은 범위

APK 패키징·서명·설치, SM-T550에서의 영상 디코딩, 실제 센서 이벤트 빈도,
CSV·ZIP 내보내기, 앱 중단·재시작 및 반복 측정은 실기기 확인이 필요하다.
호스트 단위 테스트 성공은 센서 100Hz 또는 실제 발광 시각 동기화의 검증을 의미하지 않는다.

## 로컬 보고서

- 단위 테스트: `app/build/reports/tests/testDebugUnitTest/index.html`
- Android lint: `app/build/reports/lint-results-debug.html`

빌드 산출물은 Git에서 제외한다. 재검사는 `python tools/check_android.py`로 실행한다.

## CSV 저장 위치

Windows Kivy 미리보기에서는 센서 CSV를 생성하지 않는다.
Kotlin Android 앱의 기본 저장 경로는 보통 다음과 같다.

```text
/storage/emulated/0/Android/data/com.example.alsexperiment/files/ALSRecords/<실험번호_시간>/Light.csv
```

외부 앱 전용 저장 공간을 사용할 수 없으면 앱 내부 저장 공간으로 대체한다.
실제 경로는 앱 화면에 표시된다. 결과 ZIP은 내보내기 버튼을 누르고 사용자가
위치를 선택했을 때 해당 위치에 저장된다. APK 설치 후에는 PC 없이 실행할 수 있다.
