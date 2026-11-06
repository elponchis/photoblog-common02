package com.example.photoviewer;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "photoviewer_prefs";
    private static final String KEY_URL = "site_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_AUTHOR = "author_name";

    private static final String LOCAL_URL = "http://10.0.2.2:8000";
    private static final String CLOUD_URL = "https://elponchis.pythonanywhere.com";

    private TextView textView;
    private TextView textServer;
    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private EditText editSearch;
    private LinearLayout layoutAuthorFilter;
    private TextView textAuthorFilter;

    // allPosts: 서버에서 받은 전체 목록, postList: 검색·작성자 조건을 거쳐 화면에 보이는 목록
    private final List<PostItem> allPosts = new ArrayList<>();
    private final List<PostItem> postList = new ArrayList<>();
    private String authorFilter = null;
    private ImageAdapter adapter;

    private String siteUrl;
    private String token;
    private String authorName;

    private Uri pickedImageUri;
    private ImageView dialogPreview;
    private TextView dialogFileName;
    private ActivityResultLauncher<String> imagePicker;

    private CloadImage taskDownload;
    private PutPost taskUpload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // targetSdk 35 이상은 edge-to-edge가 강제되어 상태바·내비게이션바와 겹치지 않게 패딩을 준다
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        textView = findViewById(R.id.textView);
        textServer = findViewById(R.id.textServer);
        progressBar = findViewById(R.id.progressBar);
        recyclerView = findViewById(R.id.recyclerView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        editSearch = findViewById(R.id.editSearch);
        layoutAuthorFilter = findViewById(R.id.layoutAuthorFilter);
        textAuthorFilter = findViewById(R.id.textAuthorFilter);

        adapter = new ImageAdapter(postList, this::showDetail,
                item -> setAuthorFilter(item.getAuthor()));
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(() -> onClickDownload(null));

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });
        editSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });
        findViewById(R.id.btnClearAuthor).setOnClickListener(v -> setAuthorFilter(null));

        imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) {
                        return;
                    }
                    pickedImageUri = uri;
                    if (dialogPreview != null) {
                        dialogPreview.setImageURI(uri);
                    }
                    if (dialogFileName != null) {
                        dialogFileName.setText(displayName(uri));
                    }
                });

        loadSettings();
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        siteUrl = prefs.getString(KEY_URL, LOCAL_URL);
        token = prefs.getString(KEY_TOKEN, "");
        authorName = prefs.getString(KEY_AUTHOR, "");
        textServer.setText(siteUrl);
    }

    private void saveSettings(String url, String newToken) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, url)
                .putString(KEY_TOKEN, newToken)
                .apply();
        loadSettings();
    }

    private void saveAuthor(String author) {
        authorName = author;
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_AUTHOR, author)
                .apply();
    }

    // 동기화 버튼
    public void onClickDownload(View v) {
        if (taskDownload != null && taskDownload.getStatus() == AsyncTask.Status.RUNNING) {
            taskDownload.cancel(true);
        }

        taskDownload = new CloadImage();
        taskDownload.execute(siteUrl + "/api_root/Post/");
        Toast.makeText(getApplicationContext(), "Download", Toast.LENGTH_LONG).show();
    }

    // 새로운 이미지 게시 버튼
    public void onClickUpload(View v) {
        pickedImageUri = null;

        View form = getLayoutInflater().inflate(R.layout.dialog_upload, null);
        EditText editAuthor = form.findViewById(R.id.editAuthor);
        EditText editTitle = form.findViewById(R.id.editTitle);
        EditText editText = form.findViewById(R.id.editText);
        Button btnPick = form.findViewById(R.id.btnPickImage);
        dialogPreview = form.findViewById(R.id.imagePreview);
        dialogFileName = form.findViewById(R.id.textFileName);

        editAuthor.setText(authorName);
        btnPick.setOnClickListener(b -> imagePicker.launch("image/*"));

        new AlertDialog.Builder(this)
                .setTitle("새로운 이미지 게시")
                .setView(form)
                .setPositiveButton("게시", (d, w) -> {
                    String author = editAuthor.getText().toString().trim();
                    String title = editTitle.getText().toString().trim();
                    String body = editText.getText().toString().trim();

                    if (author.isEmpty()) {
                        toast("작성자를 입력하세요");
                        return;
                    }
                    if (title.isEmpty()) {
                        toast("제목을 입력하세요");
                        return;
                    }
                    if (pickedImageUri == null) {
                        toast("이미지를 선택하세요");
                        return;
                    }
                    if (token.isEmpty()) {
                        toast("설정에서 API 토큰을 입력하세요");
                        return;
                    }

                    saveAuthor(author);
                    taskUpload = new PutPost(pickedImageUri);
                    taskUpload.execute(title, body, author);
                })
                .setNegativeButton("취소", null)
                .setOnDismissListener(d -> {
                    dialogPreview = null;
                    dialogFileName = null;
                })
                .show();
    }

    // 설정 버튼
    public void onClickSettings(View v) {
        View form = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        EditText editUrl = form.findViewById(R.id.editUrl);
        EditText editToken = form.findViewById(R.id.editToken);

        editUrl.setText(siteUrl);
        editToken.setText(token);

        form.findViewById(R.id.btnPresetLocal)
                .setOnClickListener(b -> editUrl.setText(LOCAL_URL));
        form.findViewById(R.id.btnPresetCloud)
                .setOnClickListener(b -> editUrl.setText(CLOUD_URL));

        new AlertDialog.Builder(this)
                .setTitle("서버 설정")
                .setView(form)
                .setPositiveButton("저장", (d, w) -> {
                    String url = editUrl.getText().toString().trim();
                    if (url.endsWith("/")) {
                        url = url.substring(0, url.length() - 1);
                    }
                    saveSettings(url, editToken.getText().toString().trim());
                    toast("저장되었습니다");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // 웹의 post_detail 페이지에 해당하는 전체 화면 상세 보기
    private void showDetail(PostItem item) {
        Dialog dialog = new Dialog(this, R.style.Theme_PhotoViewer);
        dialog.setContentView(R.layout.dialog_detail);

        View root = dialog.findViewById(R.id.detailRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        ((TextView) dialog.findViewById(R.id.textDetailTitle)).setText(item.getTitle());
        ((TextView) dialog.findViewById(R.id.textDetailDate)).setText(item.getPublishedDate());
        ((TextView) dialog.findViewById(R.id.textDetailBody)).setText(item.getText());
        ((ImageView) dialog.findViewById(R.id.imageDetail)).setImageBitmap(item.getImage());

        TextView author = dialog.findViewById(R.id.textDetailAuthor);
        author.setText(item.getAuthor());
        author.setOnClickListener(v -> {
            dialog.dismiss();
            setAuthorFilter(item.getAuthor());
        });

        dialog.findViewById(R.id.btnDetailBack).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void setAuthorFilter(String author) {
        authorFilter = author;
        applyFilter();
        recyclerView.scrollToPosition(0);
    }

    // 웹 목록의 제목 검색(q)·작성자별 보기와 같은 기준으로 받은 목록을 거른다
    private void applyFilter() {
        String query = editSearch.getText().toString().trim().toLowerCase(Locale.ROOT);

        postList.clear();
        for (PostItem item : allPosts) {
            if (authorFilter != null && !authorFilter.equals(item.getAuthor())) {
                continue;
            }
            if (!query.isEmpty() && !item.getTitle().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            postList.add(item);
        }
        adapter.notifyDataSetChanged();

        if (authorFilter != null) {
            layoutAuthorFilter.setVisibility(View.VISIBLE);
            textAuthorFilter.setText(authorFilter + "님의 글 (" + postList.size() + "개)");
        } else {
            layoutAuthorFilter.setVisibility(View.GONE);
        }

        if (!query.isEmpty() || authorFilter != null) {
            textView.setText(postList.isEmpty()
                    ? "조건에 맞는 글이 없습니다."
                    : "전체 " + allPosts.size() + "개 중 " + postList.size() + "개 표시");
        } else if (!allPosts.isEmpty()) {
            textView.setText("이미지 로드 성공! (" + allPosts.size() + "개)");
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(editSearch.getWindowToken(), 0);
        editSearch.clearFocus();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        }
        return uri.getLastPathSegment();
    }

    private void toast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }

    private class CloadImage extends AsyncTask<String, Integer, List<PostItem>> {

        private String error = null;

        @Override
        protected void onPreExecute() {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);
            textView.setText("동기화 중...");
        }

        @Override
        protected List<PostItem> doInBackground(String... urls) {
            List<PostItem> result = new ArrayList<>();

            try {
                String apiUrl = urls[0];
                URL urlAPI = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) urlAPI.openConnection();
                if (!token.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Token " + token);
                }
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    error = "서버 응답 " + responseCode;
                    return result;
                }

                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                is.close();

                JSONArray aryJson = new JSONArray(sb.toString());

                // 최신순: 서버가 같은 형식의 ISO 8601 문자열을 주므로 문자열 비교로 정렬된다
                List<JSONObject> posts = new ArrayList<>();
                for (int i = 0; i < aryJson.length(); i++) {
                    posts.add(aryJson.getJSONObject(i));
                }
                Collections.sort(posts, (a, b) -> b.optString("published_date", "")
                        .compareTo(a.optString("published_date", "")));

                for (int i = 0; i < posts.size(); i++) {
                    if (isCancelled()) {
                        break;
                    }

                    JSONObject postJson = posts.get(i);
                    String imageUrl = postJson.optString("image", "");

                    if (imageUrl.isEmpty() || "null".equals(imageUrl)) {
                        continue;
                    }

                    HttpURLConnection imgConn =
                            (HttpURLConnection) new URL(imageUrl).openConnection();
                    imgConn.setConnectTimeout(5000);
                    imgConn.setReadTimeout(5000);
                    InputStream imgStream = imgConn.getInputStream();
                    Bitmap bitmap = BitmapFactory.decodeStream(imgStream);
                    imgStream.close();

                    if (bitmap != null) {
                        result.add(new PostItem(
                                postJson.optString("author_name", ""),
                                postJson.optString("title", ""),
                                postJson.optString("text", ""),
                                postJson.optString("published_date", ""),
                                bitmap));
                    }

                    publishProgress((i + 1) * 100 / posts.size());
                }

            } catch (IOException | JSONException e) {
                error = e.getMessage();
                e.printStackTrace();
            }

            return result;
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            progressBar.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(List<PostItem> images) {
            progressBar.setVisibility(View.GONE);
            swipeRefresh.setRefreshing(false);

            if (error != null) {
                textView.setText("동기화 실패: " + error);
                return;
            }

            allPosts.clear();
            allPosts.addAll(images);

            if (images.isEmpty()) {
                textView.setText("불러올 이미지가 없습니다.");
            }
            applyFilter();
        }
    }

    private class PutPost extends AsyncTask<String, Void, Boolean> {

        private final Uri imageUri;
        private String message = "";

        PutPost(Uri imageUri) {
            this.imageUri = imageUri;
        }

        @Override
        protected void onPreExecute() {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
            textView.setText("업로드 중...");
        }

        @Override
        protected Boolean doInBackground(String... params) {
            String title = params[0];
            String text = params[1];
            String author = params[2];
            String boundary = "----PhotoViewer" + System.currentTimeMillis();
            String lineEnd = "\r\n";

            HttpURLConnection conn = null;

            try {
                URL url = new URL(siteUrl + "/api_root/Post/");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Token " + token);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + boundary);

                String now = new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ssZ", Locale.KOREA).format(new Date());

                DataOutputStream out = new DataOutputStream(conn.getOutputStream());

                writeField(out, boundary, "author_name", author);
                writeField(out, boundary, "title", title);
                writeField(out, boundary, "text", text);
                writeField(out, boundary, "created_date", now);
                writeField(out, boundary, "published_date", now);

                out.writeBytes("--" + boundary + lineEnd);
                out.writeBytes("Content-Disposition: form-data; name=\"image\"; "
                        + "filename=\"upload.jpg\"" + lineEnd);
                out.writeBytes("Content-Type: image/jpeg" + lineEnd);
                out.writeBytes(lineEnd);

                InputStream in = getContentResolver().openInputStream(imageUri);
                byte[] buffer = new byte[8192];
                int read;
                while (in != null && (read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                if (in != null) {
                    in.close();
                }

                out.writeBytes(lineEnd);
                out.writeBytes("--" + boundary + "--" + lineEnd);
                out.flush();
                out.close();

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_CREATED || code == HttpURLConnection.HTTP_OK) {
                    message = "게시 완료";
                    return true;
                }

                message = "게시 실패 (" + code + ") " + readError(conn);
                return false;

            } catch (IOException e) {
                message = "게시 실패: " + e.getMessage();
                e.printStackTrace();
                return false;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        private void writeField(DataOutputStream out, String boundary, String name, String value)
                throws IOException {
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n");
            out.writeBytes("Content-Type: text/plain; charset=UTF-8\r\n");
            out.writeBytes("\r\n");
            out.write(value.getBytes("UTF-8"));
            out.writeBytes("\r\n");
        }

        private String readError(HttpURLConnection conn) {
            try (InputStream es = conn.getErrorStream()) {
                if (es == null) {
                    return "";
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(es, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            } catch (IOException e) {
                return "";
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            progressBar.setIndeterminate(false);
            progressBar.setVisibility(View.GONE);
            textView.setText(message);
            toast(message);

            if (success) {
                onClickDownload(null);
            }
        }
    }
}
