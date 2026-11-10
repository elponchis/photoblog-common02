package com.example.photoviewer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 앱이 백그라운드에 있어도 서버를 주기적으로 조회해서
 * 엣지(YOLOv5)가 새로 올린 검출 게시물을 알림으로 띄운다.
 * 게시물 id가 API에 없으므로 published_date 최댓값을 기준점으로 삼는다.
 */
public class DetectionWatchService extends Service {

    static final String PREFS = "photoviewer_prefs";
    static final String KEY_URL = "site_url";
    static final String KEY_TOKEN = "token";
    static final String KEY_WATCH = "watch_enabled";
    static final String EXTRA_POST_DATE = "open_post_date";

    private static final String TAG = "DetectionWatch";
    private static final String CH_WATCH = "watch";
    private static final String CH_DETECT = "detections";
    private static final int ID_ONGOING = 1;
    private static final long POLL_SEC = 3;

    private ScheduledExecutorService poller;
    private String siteUrl;
    private String token;
    private String watermark;
    private int newCount = 0;
    private String lastSummary = "";

    public static void start(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, DetectionWatchService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, DetectionWatchService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        siteUrl = prefs.getString(KEY_URL, "");
        token = prefs.getString(KEY_TOKEN, "");

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;
        ServiceCompat.startForeground(this, ID_ONGOING, buildOngoing(), type);

        if (poller == null) {
            watermark = null;
            poller = Executors.newSingleThreadScheduledExecutor();
            poller.scheduleWithFixedDelay(this::poll, 0, POLL_SEC, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15부터 dataSync 서비스는 하루 6시간 제한이 있다
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WATCH, false).apply();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void poll() {
        try {
            JSONArray posts = new JSONArray(get(siteUrl + "/api_root/Post/"));

            List<JSONObject> fresh = new ArrayList<>();
            String newest = watermark;
            for (int i = 0; i < posts.length(); i++) {
                JSONObject p = posts.getJSONObject(i);
                String date = p.optString("published_date", "");
                if (watermark != null && date.compareTo(watermark) > 0) {
                    fresh.add(p);
                }
                if (newest == null || date.compareTo(newest) > 0) {
                    newest = date;
                }
            }

            // 첫 조회는 기준점만 잡는다. 기존 글로 알림이 쏟아지지 않게.
            boolean first = watermark == null;
            watermark = newest == null ? "" : newest;
            if (first) {
                Log.i(TAG, "watching " + siteUrl + " from " + watermark);
                return;
            }

            Collections.sort(fresh, (a, b) -> a.optString("published_date", "")
                    .compareTo(b.optString("published_date", "")));
            for (JSONObject p : fresh) {
                notifyDetection(p);
            }
        } catch (Exception e) {
            Log.w(TAG, "poll failed: " + e.getMessage());
        }
    }

    private void notifyDetection(JSONObject post) {
        String title = post.optString("title", "");
        String text = post.optString("text", "");
        String date = post.optString("published_date", "");
        Bitmap image = loadImage(post.optString("image", ""));

        newCount++;
        lastSummary = title + " " + timeOf(date);
        Log.i(TAG, "new detection: " + title + " | " + text);

        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_POST_DATE, date);
        PendingIntent pi = PendingIntent.getActivity(this, date.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_DETECT)
                .setSmallIcon(R.drawable.ic_notify_detect)
                .setContentTitle(title + " 검출")
                .setContentText(text.isEmpty() ? title : text)
                .setSubText(timeOf(date))
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (image != null) {
            b.setLargeIcon(image)
                    .setStyle(new NotificationCompat.BigPictureStyle()
                            .bigPicture(image)
                            .bigLargeIcon((Bitmap) null)
                            .setSummaryText(text));
        }

        if (canNotify()) {
            NotificationManagerCompat nm = NotificationManagerCompat.from(this);
            nm.notify(date.hashCode(), b.build());
            nm.notify(ID_ONGOING, buildOngoing());
        }
    }

    private Notification buildOngoing() {
        String content = newCount == 0
                ? "새 검출을 기다리는 중"
                : "새 검출 " + newCount + "건 · 마지막 " + lastSummary;
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_WATCH)
                .setSmallIcon(R.drawable.ic_notify_detect)
                .setContentTitle("실시간 검출 감시 중")
                .setContentText(content)
                .setSubText(siteUrl)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CH_WATCH, "검출 감시 상태", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel detect = new NotificationChannel(
                CH_DETECT, "객체 검출 알림", NotificationManager.IMPORTANCE_HIGH);
        detect.setDescription("엣지에서 새 객체가 검출되면 알린다");
        nm.createNotificationChannel(detect);
    }

    private boolean canNotify() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (!token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Token " + token);
        }
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try (InputStream is = conn.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private Bitmap loadImage(String url) {
        if (url.isEmpty() || "null".equals(url)) {
            return null;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            try (InputStream is = conn.getInputStream()) {
                return BitmapFactory.decodeStream(is);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // "2026-11-10T14:02:31.123456+09:00" -> "14:02:31"
    private static String timeOf(String iso) {
        int t = iso.indexOf('T');
        return t >= 0 && iso.length() >= t + 9 ? iso.substring(t + 1, t + 9) : iso;
    }
}
