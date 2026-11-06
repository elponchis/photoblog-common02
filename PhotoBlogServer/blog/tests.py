import io
import json
import shutil
import tempfile
from datetime import timedelta

from django.contrib.auth.models import User
from django.test import TestCase, override_settings
from django.urls import reverse
from django.utils import timezone
from PIL import Image
from rest_framework.authtoken.models import Token
from rest_framework.test import APIClient

from .models import Post

API_URL = '/api_root/Post/'
TEST_MEDIA_ROOT = tempfile.mkdtemp()


def tearDownModule():
    shutil.rmtree(TEST_MEDIA_ROOT, ignore_errors=True)


def make_image_file(name='test.jpg', size=(100, 80), color=(200, 60, 60)):
    """테스트용 이미지 파일을 메모리에 생성한다."""
    buffer = io.BytesIO()
    Image.new('RGB', size, color).save(buffer, format='JPEG')
    buffer.seek(0)
    buffer.name = name
    return buffer


@override_settings(MEDIA_ROOT=TEST_MEDIA_ROOT)
class PostApiTest(TestCase):

    def setUp(self):
        self.user = User.objects.create_user(
            username='tester', email='tester@example.com', password='test1234'
        )
        self.token = Token.objects.create(user=self.user)

        self.client = APIClient()
        self.auth_client = APIClient()
        self.auth_client.credentials(HTTP_AUTHORIZATION='Token ' + self.token.key)

    def create_post(self, title='테스트 게시물'):
        return Post.objects.create(
            author=self.user,
            title=title,
            text='본문',
            created_date=timezone.now(),
            published_date=timezone.now(),
        )

    def test_get_returns_200(self):
        response = self.auth_client.get(API_URL)
        self.assertEqual(response.status_code, 200)

    def test_get_returns_json_array(self):
        self.create_post()
        response = self.auth_client.get(API_URL)
        data = json.loads(response.content)
        self.assertIsInstance(data, list)
        self.assertEqual(len(data), 1)

    def test_get_item_has_required_fields(self):
        self.create_post()
        response = self.auth_client.get(API_URL)
        item = json.loads(response.content)[0]

        for field in ('author', 'title', 'text',
                      'created_date', 'published_date', 'image'):
            self.assertIn(field, item)

    def test_allowed_methods(self):
        response = self.auth_client.options(API_URL)
        allow = response.headers.get('Allow', '')
        for method in ('GET', 'POST', 'HEAD', 'OPTIONS'):
            self.assertIn(method, allow)

    def test_post_creates_item(self):
        now = timezone.now().isoformat()
        payload = {
            'author': self.user.id,
            'title': 'curl 테스트',
            'text': 'API 자동 테스트로 작성된 게시물',
            'created_date': now,
            'published_date': now,
            'image': make_image_file(),
        }

        response = self.auth_client.post(API_URL, payload, format='multipart')

        self.assertIn(response.status_code, (200, 201))
        self.assertEqual(Post.objects.count(), 1)
        self.assertEqual(Post.objects.first().title, 'curl 테스트')

    def test_post_saves_image(self):
        now = timezone.now().isoformat()
        payload = {
            'author': self.user.id,
            'title': '이미지 포함',
            'text': '이미지 업로드 확인',
            'created_date': now,
            'published_date': now,
            'image': make_image_file('photo.jpg'),
        }

        self.auth_client.post(API_URL, payload, format='multipart')
        post = Post.objects.first()

        self.assertTrue(post.image)
        self.assertIn('photo', post.image.name)

    def test_image_url_in_response(self):
        now = timezone.now().isoformat()
        self.auth_client.post(API_URL, {
            'author': self.user.id,
            'title': 'URL 확인',
            'text': '본문',
            'created_date': now,
            'published_date': now,
            'image': make_image_file(),
        }, format='multipart')

        response = self.auth_client.get(API_URL)
        item = json.loads(response.content)[0]

        self.assertTrue(item['image'].startswith('http'))

    def test_post_with_new_author_name_creates_user(self):
        now = timezone.now().isoformat()
        response = self.auth_client.post(API_URL, {
            'author_name': '새작성자',
            'title': '이름으로 게시',
            'text': '본문',
            'created_date': now,
            'published_date': now,
            'image': make_image_file(),
        }, format='multipart')

        self.assertEqual(response.status_code, 201)
        self.assertTrue(User.objects.filter(username='새작성자').exists())
        self.assertEqual(Post.objects.first().author.username, '새작성자')
        self.assertEqual(json.loads(response.content)['author_name'], '새작성자')

    def test_post_with_existing_author_name_reuses_user(self):
        now = timezone.now().isoformat()
        self.auth_client.post(API_URL, {
            'author_name': 'tester',
            'title': '기존 작성자',
            'text': '본문',
            'created_date': now,
            'published_date': now,
            'image': make_image_file(),
        }, format='multipart')

        self.assertEqual(User.objects.count(), 1)
        self.assertEqual(Post.objects.first().author, self.user)

    def test_post_without_author_fails(self):
        now = timezone.now().isoformat()
        response = self.auth_client.post(API_URL, {
            'author': '',
            'author_name': '',
            'title': '작성자 없음',
            'text': '본문',
            'created_date': now,
            'published_date': now,
            'image': make_image_file(),
        }, format='multipart')

        self.assertEqual(response.status_code, 400)
        self.assertIn('author_name', json.loads(response.content))
        self.assertEqual(Post.objects.count(), 0)

    def test_post_without_title_fails(self):
        now = timezone.now().isoformat()
        response = self.auth_client.post(API_URL, {
            'author': self.user.id,
            'text': '제목 없음',
            'created_date': now,
            'published_date': now,
        }, format='multipart')

        self.assertEqual(response.status_code, 400)
        self.assertEqual(Post.objects.count(), 0)


@override_settings(MEDIA_ROOT=TEST_MEDIA_ROOT)
class PostListViewTest(TestCase):
    """웹 페이지(브라우저 화면) 동작 확인"""

    def setUp(self):
        self.user = User.objects.create_user(username='viewer', password='test1234')

    def test_post_list_page_opens(self):
        response = self.client.get('/')
        self.assertEqual(response.status_code, 200)

    def test_published_post_shown(self):
        Post.objects.create(
            author=self.user,
            title='게시된 글',
            text='본문',
            created_date=timezone.now(),
            published_date=timezone.now(),
        )

        response = self.client.get('/')
        self.assertContains(response, '게시된 글')

    def test_author_page_shows_only_that_author(self):
        other = User.objects.create_user(username='other', password='test1234')
        Post.objects.create(author=self.user, title='내 글', text='본문',
                            published_date=timezone.now())
        Post.objects.create(author=other, title='남의 글', text='본문',
                            published_date=timezone.now())

        response = self.client.get(reverse('author_posts', kwargs={'username': 'other'}))

        self.assertEqual(response.status_code, 200)
        titles = [post.title for post in response.context['posts']]
        self.assertEqual(titles, ['남의 글'])

    def test_unknown_author_page_404(self):
        response = self.client.get(reverse('author_posts', kwargs={'username': 'nobody'}))
        self.assertEqual(response.status_code, 404)

    def test_search_by_title(self):
        for title in ('Django 배포 기록', '안드로이드 업로드', 'django 테스트'):
            Post.objects.create(author=self.user, title=title, text='본문',
                                published_date=timezone.now())

        response = self.client.get('/', {'q': 'django'})

        titles = sorted(post.title for post in response.context['posts'])
        self.assertEqual(titles, ['Django 배포 기록', 'django 테스트'])

    def make_posts(self, count):
        now = timezone.now()
        Post.objects.bulk_create([
            Post(author=self.user, title=f'글 {i:03d}', text='본문',
                 published_date=now - timedelta(minutes=i))
            for i in range(count)
        ])

    def test_list_shows_ten_posts_per_page(self):
        self.make_posts(25)

        first = self.client.get('/')
        third = self.client.get('/', {'page': 3})

        self.assertEqual(len(first.context['posts']), 10)
        self.assertEqual(first.context['posts'][0].title, '글 000')
        self.assertEqual(len(third.context['posts']), 5)

    def test_list_stops_at_ten_pages_but_search_finds_older(self):
        self.make_posts(105)

        response = self.client.get('/', {'page': 11})
        self.assertEqual(response.context['page_obj'].paginator.num_pages, 10)
        self.assertEqual(response.context['posts'][9].title, '글 099')

        found = self.client.get('/', {'q': '글 104'})
        self.assertEqual([p.title for p in found.context['posts']], ['글 104'])

    def test_sidebar_limits_items_to_five(self):
        for i in range(7):
            user = User.objects.create_user(username=f'writer{i}', password='test1234')
            Post.objects.create(author=user, title=f'작성자 {i}', text='본문',
                                published_date=timezone.now())

        response = self.client.get('/')

        self.assertEqual(len(response.context['top_authors']), 5)
        self.assertEqual(len(response.context['recent_posts']), 5)
        self.assertEqual(len(response.context['all_authors']), 7)

    def test_detail_back_link_returns_to_previous_list(self):
        self.make_posts(15)
        post = Post.objects.get(title='글 012')

        listing = self.client.get('/', {'page': 2})
        self.assertContains(listing, f'/post/{post.pk}/?next=/%3Fpage%3D2')

        detail = self.client.get(f'/post/{post.pk}/', {'next': '/?page=2'})
        self.assertEqual(detail.context['list_url'], '/?page=2')

    def test_detail_back_link_ignores_external_url(self):
        self.make_posts(1)
        post = Post.objects.first()

        detail = self.client.get(f'/post/{post.pk}/', {'next': 'https://example.com/'})

        self.assertEqual(detail.context['list_url'], '/')

    def test_unpublished_post_hidden(self):
        Post.objects.create(
            author=self.user,
            title='임시 저장 글',
            text='본문',
            created_date=timezone.now(),
        )

        response = self.client.get('/')
        self.assertNotContains(response, '임시 저장 글')


@override_settings(MEDIA_ROOT=TEST_MEDIA_ROOT)
class PostEditViewTest(TestCase):
    """웹 작성·수정 폼과 모델 메소드 동작 확인"""

    def setUp(self):
        self.user = User.objects.create_user(username='editor', password='test1234')
        self.client.force_login(self.user)

    def test_post_new_get_shows_form(self):
        response = self.client.get(reverse('post_new'))

        self.assertEqual(response.status_code, 200)
        self.assertTemplateUsed(response, 'blog/post_edit.html')
        self.assertIn('form', response.context)

    def test_post_new_post_saves_and_redirects(self):
        response = self.client.post(reverse('post_new'),
                                    {'title': '웹에서 쓴 글', 'text': '본문'})

        post = Post.objects.get()
        self.assertRedirects(response, reverse('post_detail', kwargs={'pk': post.pk}))
        self.assertEqual(post.title, '웹에서 쓴 글')
        self.assertEqual(post.author, self.user)
        self.assertIsNotNone(post.published_date)

    def test_post_edit_get_shows_form_with_post(self):
        post = Post.objects.create(author=self.user, title='원래 제목', text='본문')

        response = self.client.get(reverse('post_edit', kwargs={'pk': post.pk}))

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.context['form'].instance, post)
        self.assertContains(response, '원래 제목')

    def test_post_edit_post_updates_and_redirects(self):
        post = Post.objects.create(author=self.user, title='원래 제목', text='본문')

        response = self.client.post(reverse('post_edit', kwargs={'pk': post.pk}),
                                    {'title': '수정한 제목', 'text': '수정한 본문'})

        post.refresh_from_db()
        self.assertRedirects(response, reverse('post_detail', kwargs={'pk': post.pk}))
        self.assertEqual(post.title, '수정한 제목')
        self.assertEqual(post.text, '수정한 본문')
        self.assertIsNotNone(post.published_date)

    def test_js_test_page_opens(self):
        response = self.client.get(reverse('js_test'))

        self.assertEqual(response.status_code, 200)
        self.assertTemplateUsed(response, 'blog/js_test.html')

    def test_publish_sets_published_date(self):
        post = Post.objects.create(author=self.user, title='임시 글', text='본문')
        self.assertIsNone(post.published_date)

        post.publish()

        post.refresh_from_db()
        self.assertIsNotNone(post.published_date)
        self.assertLessEqual(post.published_date, timezone.now())

    def test_str_returns_title(self):
        post = Post.objects.create(author=self.user, title='문자열 확인', text='본문')
        self.assertEqual(str(post), '문자열 확인')


class DetectionStatsTest(TestCase):

    def setUp(self):
        self.user = User.objects.create_user('elponchis', password='pw')
        now = timezone.now()

        def post(title, text, ago):
            Post.objects.create(author=self.user, title=title, text=text,
                                published_date=now - timedelta(minutes=ago))

        post('person', 'person 0.80, cell phone 0.60', 5)
        post('person', 'person 0.90', 3)
        post('bus', 'person, bus,', 2)
        post('cup', '', 1)
        Post.objects.create(author=self.user, title='future', text='dog 0.9',
                            published_date=now + timedelta(days=1))

    def test_parse_detections(self):
        from .stats import parse_detections
        p = Post(title='x', text='person 0.83, cell phone 0.61')
        self.assertEqual(parse_detections(p), [('person', 0.83), ('cell phone', 0.61)])
        p = Post(title='bus', text='person, bus,')
        self.assertEqual(parse_detections(p), [('person', None), ('bus', None)])
        p = Post(title='Cup', text='')
        self.assertEqual(parse_detections(p), [('cup', None)])

    def test_counts_and_avg_conf(self):
        data = self.client.get(reverse('stats_data')).json()
        self.assertEqual(data['total_posts'], 4)
        objects = {o['name']: o for o in data['objects']}
        self.assertEqual(objects['person']['count'], 3)
        self.assertAlmostEqual(objects['person']['avg_conf'], 0.85)
        self.assertEqual(objects['cell phone']['count'], 1)
        self.assertIsNone(objects['bus']['avg_conf'])
        self.assertNotIn('dog', objects)
        self.assertEqual(data['objects'][0]['name'], 'person')

    def test_hourly_has_24_buckets(self):
        data = self.client.get(reverse('stats_data')).json()
        self.assertEqual(len(data['hourly']['counts']), 24)
        self.assertEqual(sum(data['hourly']['counts']), 4)

    def test_stats_page_renders(self):
        res = self.client.get(reverse('stats'))
        self.assertEqual(res.status_code, 200)
        self.assertContains(res, '검출 통계')
        self.assertContains(res, 'cell phone')
        self.assertContains(res, 'id="stats-data"')

    def test_empty_stats(self):
        Post.objects.all().delete()
        res = self.client.get(reverse('stats'))
        self.assertContains(res, '아직 검출 기록이 없습니다')
