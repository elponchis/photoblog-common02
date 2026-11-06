from django.urls import path, include
from rest_framework import routers
from . import views

router = routers.DefaultRouter()
router.register('Post', views.IntruderImage)

urlpatterns = [
    path('', views.post_list, name='post_list'),
    path('author/<path:username>/', views.author_posts, name='author_posts'),
    path('post/<int:pk>/', views.post_detail, name='post_detail'),
    path('post/new/', views.post_new, name='post_new'),
    path('post/<int:pk>/edit/', views.post_edit, name='post_edit'),
    path('stats/', views.stats, name='stats'),
    path('stats/data/', views.stats_data, name='stats_data'),
    path('js_test/', views.js_test, name='js_test'),
    path('api_root/', include(router.urls)),
]