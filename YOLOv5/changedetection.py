import os
import pathlib
import time
from datetime import datetime

import cv2
import requests


class ChangeDetection:
    """객체가 새로 나타난 순간에만 서버로 게시물을 전송한다.

    프레임 단위 검출은 깜빡임이 심해서 강의자료 방식(직전 프레임 0 -> 현재 1)으로는
    오검출 하나에도 게시물이 올라간다. 클래스마다 상태를 두고
      - min_conf 이상으로 appear_frames 프레임 연속 보여야 '출현'
      - vanish_frames 프레임 연속 안 보여야 '퇴장'
      - 같은 클래스는 cooldown 초 안에 다시 전송하지 않음
    으로 판정한다.
    """

    HOST = 'http://127.0.0.1:8000'
    USERNAME = 'elponchis'
    PASSWORD = 'Blog2026pw'
    AUTHOR_ID = 1

    MIN_CONF = 0.5
    APPEAR_FRAMES = 3
    VANISH_FRAMES = 15
    COOLDOWN_SEC = 10.0
    REPORT_EVERY = 100

    def __init__(self, names):
        n = len(names)
        self.result_prev = [0] * n
        self.present = [False] * n
        self.on_streak = [0] * n
        self.off_streak = [0] * n
        self.last_sent = [0.0] * n
        self.title = ''
        self.text = ''
        self.token = ''

        self.frames = 0
        self.raw_events = 0
        self.sent_events = 0

        res = requests.post(self.HOST + '/api-token-auth/', {
            'username': self.USERNAME,
            'password': self.PASSWORD,
        })
        res.raise_for_status()
        self.token = res.json()['token']
        print('[ChangeDetection] token =', self.token)
        print('[ChangeDetection] min_conf={} appear={}f vanish={}f cooldown={}s'.format(
            self.MIN_CONF, self.APPEAR_FRAMES, self.VANISH_FRAMES, self.COOLDOWN_SEC))

    def add(self, names, detected_current, save_dir, image, confs=None):
        if confs is None:
            confs = [1.0 if d else 0.0 for d in detected_current]
        now = time.monotonic()
        self.frames += 1

        appeared = []
        for i in range(len(names)):
            # 강의자료 방식이었다면 전송됐을 횟수 (비교용)
            if self.result_prev[i] == 0 and detected_current[i] == 1:
                self.raw_events += 1

            if detected_current[i] and confs[i] >= self.MIN_CONF:
                self.on_streak[i] += 1
                self.off_streak[i] = 0
            else:
                self.off_streak[i] += 1
                self.on_streak[i] = 0

            if not self.present[i] and self.on_streak[i] >= self.APPEAR_FRAMES:
                self.present[i] = True
                wait = self.COOLDOWN_SEC - (now - self.last_sent[i])
                if self.last_sent[i] and wait > 0:
                    print('[ChangeDetection] skip {} (cooldown {:.1f}s left)'.format(names[i], wait))
                else:
                    appeared.append(i)
            elif self.present[i] and self.off_streak[i] >= self.VANISH_FRAMES:
                self.present[i] = False
                print('[ChangeDetection] vanish', names[i])

        self.result_prev = list(detected_current)

        if appeared:
            appeared.sort(key=lambda i: confs[i], reverse=True)
            self.title = names[appeared[0]]
            self.text = ', '.join('{} {:.2f}'.format(names[i], confs[i]) for i in appeared)
            for i in appeared:
                self.last_sent[i] = now
            self.sent_events += len(appeared)
            self.send(save_dir, image)

        if self.frames % self.REPORT_EVERY == 0:
            self.report()

    def report(self):
        suppressed = 1 - self.sent_events / self.raw_events if self.raw_events else 0
        print('[ChangeDetection] frames={} raw={} sent={} suppressed={:.1%}'.format(
            self.frames, self.raw_events, self.sent_events, suppressed))

    def send(self, save_dir, image):
        now = datetime.now().isoformat()
        today = datetime.now()

        save_path = (pathlib.Path(os.getcwd()) / str(save_dir) / 'detected'
                     / str(today.year) / str(today.month) / str(today.day))
        save_path.mkdir(parents=True, exist_ok=True)

        full_path = save_path / '{0}-{1}-{2}-{3}.jpg'.format(
            today.hour, today.minute, today.second, today.microsecond)

        dst = cv2.resize(image, dsize=(320, 240), interpolation=cv2.INTER_AREA)
        cv2.imwrite(str(full_path), dst)

        headers = {
            'Authorization': 'Token ' + self.token,
            'Accept': 'application/json',
        }

        data = {
            'author': self.AUTHOR_ID,
            'title': self.title,
            'text': self.text,
            'created_date': now,
            'published_date': now,
        }

        with open(full_path, 'rb') as f:
            files = {'image': f}
            res = requests.post(self.HOST + '/api_root/Post/',
                                data=data, files=files, headers=headers)

        print('[ChangeDetection]', res.status_code, self.title, '|', self.text)
        if res.status_code >= 400:
            print(res.text)
