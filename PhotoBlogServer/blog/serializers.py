from django.contrib.auth.models import User
from blog.models import Post
from rest_framework import serializers

class PostSerializer(serializers.ModelSerializer):
    author = serializers.PrimaryKeyRelatedField(
        queryset=User.objects.all(), required=False, allow_null=True)
    # 이름으로 보내면 해당 사용자가 없을 때 새로 만든다
    author_name = serializers.CharField(required=False, max_length=150)

    class Meta:
        model = Post
        fields = ('author', 'author_name', 'title', 'text', 'created_date', 'published_date', 'image')

    def validate(self, attrs):
        name = attrs.pop('author_name', '').strip()
        if name:
            attrs['author'], _ = User.objects.get_or_create(username=name)
        elif attrs.get('author') is None and (self.instance is None or 'author' in attrs):
            raise serializers.ValidationError({'author_name': '작성자를 입력하세요.'})
        return attrs
