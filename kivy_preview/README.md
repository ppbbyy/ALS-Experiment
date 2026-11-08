# Windows Kivy 미리보기

저장소 루트의 `미리보기 실행.cmd`를 더블클릭한다.
가상환경이 없는 PC에서는 Python 3.12를 설치한 뒤 루트에서 다음을 실행한다.

```powershell
python -m venv .venv-kivy
.\.venv-kivy\Scripts\python.exe -m pip install -r kivy_preview/requirements.txt
.\.venv-kivy\Scripts\python.exe kivy_preview/main.py
```

영상 파일 선택 → 미리보기 재생 또는 실험 시작 → 5초 카운트다운 → 영상 재생.
중지/취소 버튼으로 카운트다운과 재생을 중단할 수 있다.
파일 선택은 Windows 기본 대화상자를 사용한다. 서버 전송이나 파일 복사는 하지 않는다.
재생 가능 여부는 ffpyplayer가 해당 영상 코덱을 지원하는지에 따라 다르다.

이 폴더는 Windows용 UI 프로토타입이다. Kotlin Android 앱을 대체하거나
태블릿 센서를 PC에 연결하지 않는다. 센서값을 생성하거나 측정 CSV를 저장하지 않는다.
Android 이식 시 파일 선택은 Storage Access Framework로, 센서 수집은
Pyjnius를 통한 SensorManager 및 별도 스레드로 구현해야 한다.
Kivy UI 프레임 주기를 센서의 100Hz 측정 주기로 사용하면 안 된다.

Kivy + Buildozer로 APK 제작은 가능하다. Windows에서는 WSL/Linux를 사용하며,
추후 python-for-android/SDK/NDK 및 API 25 호환성을 확인해야 한다.
현재 Buildozer 설치, WSL 설정, APK 빌드는 수행하지 않았다.

공식 문서:
- https://kivy.org/doc/stable/guide/packaging-android.html
- https://buildozer.readthedocs.io/en/1.6.0/installation/
