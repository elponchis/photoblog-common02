from django.contrib.auth.models import User
from django.core.paginator import Paginator
from django.db.models import Count, Q
from django.shortcuts import render, get_object_or_404, redirect
from django.urls import reverse
from django.utils import timezone
from django.utils.http import url_has_allowed_host_and_scheme
from .models import Post
from .forms import PostForm
from rest_framework import viewsets
from .serializers import PostSerializer
from django.http import JsonResponse
from .stats import build_stats

POSTS_PER_PAGE = 10
MAX_PAGES = 10
SIDEBAR_ITEMS = 5

def sidebar_context():
    now = timezone.now()
    authors = (User.objects
               .annotate(post_count=Count('post', filter=Q(post__published_date__lte=now)))
               .filter(post_count__gt=0)
               .order_by('username'))
    all_posts = Post.objects.filter(published_date__lte=now).order_by('-published_date')
    return {
        'top_authors': authors.order_by('-post_count', 'username')[:SIDEBAR_ITEMS],
        'all_authors': authors,
        'recent_posts': all_posts[:SIDEBAR_ITEMS],
        'all_posts': all_posts,
        'sidebar_limit': SIDEBAR_ITEMS,
    }

def paginated(request, posts, limit_pages=True):
    # 검색이 아닐 때는 최근 MAX_PAGES 페이지까지만 넘겨 볼 수 있다
    total = posts.count()
    if limit_pages:
        posts = posts[:POSTS_PER_PAGE * MAX_PAGES]
    page_obj = Paginator(posts, POSTS_PER_PAGE).get_page(request.GET.get('page'))
    return {'page_obj': page_obj, 'posts': page_obj.object_list, 'total': total}

def post_list(request):
    query = request.GET.get('q', '').strip()
    posts = Post.objects.filter(
        published_date__lte=timezone.now()
    ).order_by('-published_date')
    if query:
        posts = posts.filter(title__icontains=query)
    return render(request, 'blog/post_list.html', {
        'query': query,
        'list_url': request.get_full_path(),
        **paginated(request, posts, limit_pages=not query),
        **sidebar_context(),
    })

def author_posts(request, username):
    author = get_object_or_404(User, username=username)
    posts = Post.objects.filter(
        author=author, published_date__lte=timezone.now()
    ).order_by('-published_date')
    return render(request, 'blog/post_list.html', {
        'current_author': author,
        'list_url': request.get_full_path(),
        **paginated(request, posts),
        **sidebar_context(),
    })

def post_detail(request, pk):
    post = get_object_or_404(Post, pk=pk)
    # 목록에서 넘어올 때 붙인 ?next= 로 보던 페이지·검색 결과로 돌아간다
    list_url = request.GET.get('next', '')
    if not url_has_allowed_host_and_scheme(list_url, allowed_hosts={request.get_host()}):
        list_url = reverse('post_list')
    return render(request, 'blog/post_detail.html', {
        'post': post,
        'list_url': list_url,
        'current_author': post.author,
        **sidebar_context(),
    })

def post_new(request):
    if request.method == "POST":
        form = PostForm(request.POST)
        if form.is_valid():
            post = form.save(commit=False)
            post.author = request.user
            post.published_date = timezone.now()
            post.save()
            return redirect('post_detail', pk=post.pk)
    else:
        form = PostForm()
    return render(request, 'blog/post_edit.html', {'form': form})

def post_edit(request, pk):
    post = get_object_or_404(Post, pk=pk)
    if request.method == "POST":
        form = PostForm(request.POST, instance=post)
        if form.is_valid():
            post = form.save(commit=False)
            post.author = request.user
            post.published_date = timezone.now()
            post.save()
            return redirect('post_detail', pk=post.pk)
    else:
        form = PostForm(instance=post)
    return render(request, 'blog/post_edit.html', {'form': form})

def stats(request):
    return render(request, 'blog/stats.html', {
        'stats': build_stats(),
        'list_url': request.get_full_path(),
        **sidebar_context(),
    })

def stats_data(request):
    return JsonResponse(build_stats(), json_dumps_params={'ensure_ascii': False})

def js_test(request):
    return render(request, 'blog/js_test.html', {})

class IntruderImage(viewsets.ModelViewSet):
    queryset = Post.objects.all()
    serializer_class = PostSerializer