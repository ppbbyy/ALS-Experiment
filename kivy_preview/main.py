"""Windows desktop UI preview. Does not collect or simulate Android sensor data."""
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parent
os.environ.setdefault("KIVY_HOME", str(ROOT / ".kivy"))
os.environ.setdefault("KIVY_VIDEO", "ffpyplayer")
os.environ.setdefault("KIVY_NO_ARGS", "1")

from kivy.config import Config
Config.set("graphics", "width", "1120")
Config.set("graphics", "height", "760")
from kivy.app import App
from kivy.clock import Clock
from kivy.core.text import LabelBase
from kivy.core.window import Window
from kivy.graphics import Color, RoundedRectangle
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.uix.video import Video

FONT = Path("C:/Windows/Fonts/malgun.ttf")
if FONT.exists():
    LabelBase.register("Korean", str(FONT))
    FONT_NAME = "Korean"
else:
    FONT_NAME = "Roboto"


def label(text, size=15, height=34, color=(.78, .83, .9, 1), **kw):
    widget = Label(text=text, font_name=FONT_NAME, font_size=size,
                   size_hint_y=None, height=height, color=color,
                   halign="left", valign="middle", **kw)
    widget.bind(size=lambda obj, val: setattr(obj, "text_size", val))
    return widget


class Panel(BoxLayout):
    def __init__(self, **kw):
        super().__init__(padding=18, spacing=12, **kw)
        with self.canvas.before:
            Color(.075, .105, .16, 1)
            self.bg = RoundedRectangle(pos=self.pos, size=self.size, radius=[14])
        self.bind(pos=self.update_bg, size=self.update_bg)

    def update_bg(self, *_):
        self.bg.pos, self.bg.size = self.pos, self.size


class VideoPreview(Video):
    def texture_update(self, *args):
        # Image's default behavior tries to decode an MP4 as a still image.
        if self.preview:
            self.set_texture_from_resource(self.preview)


class ExperimentApp(App):
    title = "ALS Experiment · Windows 미리보기"

    def build(self):
        Window.clearcolor = (.035, .055, .09, 1)
        Window.minimum_width, Window.minimum_height = 920, 690
        self.phase = "idle"
        self.path = None
        self.deadline = 0
        self.had_frame = False
        self.timer = None
        root = BoxLayout(orientation="vertical", padding=24, spacing=14)
        root.add_widget(label("ALS EXPERIMENT", size=27, height=44, color=(.95, .97, 1, 1)))
        root.add_widget(label("Galaxy Tab A · SM-T550   /   Windows에서 영상과 실험 흐름 확인", height=28))
        body = BoxLayout(spacing=16)
        root.add_widget(body)
        settings = Panel(orientation="vertical", size_hint_x=.38)
        body.add_widget(settings)
        settings.add_widget(label("01   실험 설정", 20, 40, color=(.4, .7, 1, 1)))
        settings.add_widget(label("실험 번호"))
        self.name_input = TextInput(text="Tablet_01", multiline=False, font_name=FONT_NAME,
                                    size_hint_y=None, height=44, padding=[12, 10])
        settings.add_widget(self.name_input)
        settings.add_widget(label("02   영상 파일", 20, 44, color=(.4, .7, 1, 1)))
        self.file_label = label("선택한 영상이 없습니다.", height=72)
        settings.add_widget(self.file_label)
        self.pick = self.button("영상 파일 선택", self.choose_video)
        settings.add_widget(self.pick)
        settings.add_widget(label("MP4 · MOV · MKV · AVI · WEBM\nPC의 파일을 직접 선택합니다.", 13, 52))
        settings.add_widget(label("03   센서 상태", 20, 42, color=(.4, .7, 1, 1)))
        settings.add_widget(label("현재 조도: -- lux", 23, 40))
        settings.add_widget(label("Windows 미리보기에는 태블릿 센서가 없습니다.\n영상 재생만 확인하며 센서 CSV는 만들지 않습니다.", 13, 70))
        settings.add_widget(Label())
        right = Panel(orientation="vertical", size_hint_x=.62)
        body.add_widget(right)
        right.add_widget(label("VIDEO PREVIEW", 16, 32, color=(.4, .7, 1, 1)))
        self.video = VideoPreview(state="stop", options={"eos": "stop"}, fit_mode="contain")
        self.video.bind(texture=self.on_frame, eos=self.on_eos)
        right.add_widget(self.video)
        self.progress = label("00:00 / 00:00", 14, 28)
        right.add_widget(self.progress)
        self.countdown = label("영상을 선택해 주세요.", 24, 62, color=(.95, .97, 1, 1))
        right.add_widget(self.countdown)
        controls = BoxLayout(size_hint_y=None, height=48, spacing=10)
        self.preview_btn = self.button("미리보기 재생", self.preview)
        self.start_btn = self.button("실험 시작 · 5초 후", self.arm, primary=True)
        self.stop_btn = self.button("중지 / 취소", self.stop_run)
        for b in (self.preview_btn, self.start_btn, self.stop_btn):
            controls.add_widget(b)
        right.add_widget(controls)
        self.status = label("준비 · 영상 파일을 선택하면 시작할 수 있습니다.", 14, 50)
        root.add_widget(self.status)
        Clock.schedule_interval(self.refresh, .1)
        self.refresh(0)
        if "--smoke-test" in sys.argv:
            Clock.schedule_once(self.smoke_start, 1)
        return root

    def button(self, text, action, primary=False):
        b = Button(text=text, font_name=FONT_NAME, font_size=14,
                   size_hint_y=None, height=46, background_normal="",
                   background_color=(.16, .4, .85, 1) if primary else (.16, .21, .3, 1))
        b.bind(on_release=action)
        return b

    def choose_video(self, *_):
        # Native Windows picker. Android port must use Storage Access Framework.
        import tkinter as tk
        from tkinter import filedialog
        dialog = tk.Tk()
        dialog.withdraw()
        try:
            path = filedialog.askopenfilename(parent=dialog, title="실험 영상 선택",
                filetypes=[("영상 파일", "*.mp4 *.mov *.mkv *.avi *.webm *.m4v"), ("모든 파일", "*.*")])
        finally:
            dialog.destroy()
        if path:
            self.select_video(path)

    def select_video(self, path):
        if not Path(path).is_file():
            self.status.text = "파일을 찾을 수 없습니다."
            return
        self.stop_run()
        self.path = str(Path(path).resolve())
        self.video.unload()
        self.video.source = ""
        self.video.source = self.path
        self.file_label.text = Path(path).name
        self.countdown.text = "선택 완료 · 재생을 확인해 주세요."
        self.status.text = "영상 선택 완료 · 미리보기 또는 5초 후 시작을 누르세요."

    def reset_video(self):
        self.video.state = "stop"
        self.had_frame = False

    def preview(self, *_):
        if not self.path:
            return
        self.reset_video()
        self.phase = "preview"
        self.video.state = "play"
        self.play_started = time.monotonic()
        self.countdown.text = "미리보기 재생 중"
        self.status.text = "영상 미리보기 · 센서 측정 없음"

    def arm(self, *_):
        if not self.path or self.phase == "countdown":
            return
        self.reset_video()
        self.phase = "countdown"
        self.deadline = time.monotonic() + 5
        self.status.text = "5초 후 선택한 영상을 재생합니다. · Windows에서는 센서 측정 없음"
        self.timer = Clock.schedule_interval(self.tick, .025)
        self.tick(0)

    def tick(self, _dt):
        import math
        remaining = self.deadline - time.monotonic()
        if remaining > 0:
            self.countdown.text = f"실험 시작까지   {math.ceil(remaining)}"
            return True
        self.phase = "running"
        self.play_started = time.monotonic()
        self.video.state = "play"
        self.countdown.text = "영상 재생 중"
        self.status.text = "실험 영상 재생 · 센서 데이터 저장은 Android 연결 구현 후 사용 가능"
        self.timer = None
        return False

    def stop_run(self, *_):
        if self.timer:
            self.timer.cancel()
            self.timer = None
        self.video.state = "stop"
        self.phase = "idle"
        self.countdown.text = "중지됨 · 다시 시작할 수 있습니다."
        self.status.text = "중지 완료 · 미리보기에서는 측정 파일을 생성하지 않습니다."

    def on_frame(self, _video, texture):
        if texture is not None and self.phase in ("preview", "running"):
            self.had_frame = True

    def on_eos(self, _video, eos):
        if eos and self.phase in ("preview", "running"):
            self.phase = "idle"
            self.countdown.text = "영상 재생 완료"
            self.status.text = "완료 · Windows 영상 미리보기이며 센서 데이터는 없습니다."

    def refresh(self, _dt):
        busy = self.phase in ("countdown", "running")
        self.pick.disabled = busy
        self.name_input.disabled = busy
        self.preview_btn.disabled = not self.path or busy
        self.start_btn.disabled = not self.path or busy
        self.stop_btn.disabled = self.phase == "idle"
        def fmt(seconds):
            seconds = max(0, int(seconds or 0))
            return f"{seconds // 60:02d}:{seconds % 60:02d}"
        self.progress.text = f"{fmt(self.video.position)} / {fmt(self.video.duration)}"
        if self.phase in ("running", "preview") and not self.had_frame and time.monotonic() - self.play_started > 10:
            self.stop_run()
            self.status.text = "영상 프레임이 나오지 않습니다. 다른 MP4 파일이나 코덱을 확인하세요."

    def smoke_start(self, _dt):
        assert self.start_btn.disabled
        self.select_video(ROOT / ".test-video.mp4")
        self.refresh(0)
        assert not self.start_btn.disabled
        self.start_btn.dispatch("on_release")
        assert self.phase == "countdown"
        self.stop_btn.dispatch("on_release")
        assert self.phase == "idle"
        self.start_btn.dispatch("on_release")
        Clock.schedule_once(self.smoke_finish, 7)

    def smoke_finish(self, _dt):
        assert self.had_frame, "Video frames were not rendered"
        assert self.phase == "running", self.phase
        assert time.monotonic() - self.play_started >= 1
        Window.screenshot(name=str(ROOT / ".smoke.png"))
        print("SMOKE_OK: file selection, start, cancel, 5s countdown and video frames", flush=True)
        self.stop_btn.dispatch("on_release")
        self.stop_run()
        super().stop()

    def on_stop(self):
        if self.timer:
            self.timer.cancel()
        self.video.state = "stop"
        self.video.unload()


if __name__ == "__main__":
    ExperimentApp().run()
