import re
from collections import Counter, defaultdict
from datetime import timedelta

from django.utils import timezone

from .models import Post

# YOLO 엣지가 올리는 본문 형식: "person 0.83, cell phone 0.61"
# 신뢰도가 없는 예전 형식("person, bus,")도 이름만 센다.
ITEM_RE = re.compile(r'^\s*([a-z][a-z ]*?)\s*(?:\s(0?\.\d+|1(?:\.0+)?))?\s*$')


def parse_detections(post):
    found = []
    for chunk in post.text.split(','):
        m = ITEM_RE.match(chunk)
        if m:
            found.append((m.group(1), float(m.group(2)) if m.group(2) else None))
    if not found and post.title:
        found.append((post.title.strip().lower(), None))
    return found


def build_stats(hours=24):
    now = timezone.localtime()
    posts = list(Post.objects.filter(published_date__lte=now).order_by('published_date'))

    counts = Counter()
    conf_sum = defaultdict(float)
    conf_n = Counter()
    last_seen = {}
    for post in posts:
        for name, conf in parse_detections(post):
            counts[name] += 1
            last_seen[name] = post.published_date
            if conf is not None:
                conf_sum[name] += conf
                conf_n[name] += 1

    objects = [{
        'name': name,
        'count': n,
        'avg_conf': round(conf_sum[name] / conf_n[name], 3) if conf_n[name] else None,
        'last_seen': timezone.localtime(last_seen[name]).isoformat(),
        'last_seen_label': timezone.localtime(last_seen[name]).strftime('%m-%d %H:%M'),
    } for name, n in counts.most_common()]

    start = now.replace(minute=0, second=0, microsecond=0) - timedelta(hours=hours - 1)
    buckets = [start + timedelta(hours=h) for h in range(hours)]
    per_hour = Counter()
    for post in posts:
        t = timezone.localtime(post.published_date)
        if t >= start:
            per_hour[t.replace(minute=0, second=0, microsecond=0)] += 1

    return {
        'total_posts': len(posts),
        'total_detections': sum(counts.values()),
        'distinct_objects': len(counts),
        'last_post': timezone.localtime(posts[-1].published_date).isoformat() if posts else None,
        'objects': objects,
        'hourly': {
            'labels': [b.strftime('%m-%d %H시') for b in buckets],
            'counts': [per_hour[b] for b in buckets],
        },
    }
