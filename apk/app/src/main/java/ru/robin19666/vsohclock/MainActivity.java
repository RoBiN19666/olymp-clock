package ru.robin19666.vsohclock;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Обёртка вокруг HTML-файла с часами.
 *
 * Зачем: на части интерактивных досок нет приложения, связанного с .html,
 * поэтому файл нечем открыть. Здесь страница лежит внутри APK
 * (assets/clock.html) и показывается в WebView.
 *
 * Особенности:
 *  - полный экран, без статус-бара и кнопок навигации;
 *  - экран не гаснет (FLAG_KEEP_SCREEN_ON);
 *  - включён localStorage (настройки и идущий таймер);
 *  - звук разрешён без жеста;
 *  - системный размер шрифта не влияет на вёрстку (setTextZoom(100));
 *  - работает выбор аудиофайла (onShowFileChooser) — без него кнопка «Выбрать файл»
 *    в WebView просто ничего не делает;
 *  - в списке недавних задач принудительно показаны название и иконка приложения
 *    (иначе Android берёт заголовок страницы и фавикон, и там оказывается
 *    «Всероссийская олимпиада…» или пустой значок);
 *  - при запуске спрашивает про обновление с GitHub.
 */
public class MainActivity extends Activity {

    private static final String TAG = "vsoh";
    private static final int REQ_FILE = 1001;
    private static final String UPDATE_API =
            "https://api.github.com/repos/RoBiN19666/olymp-clock/releases/latest";
    private static final String PREFS = "vsoh-clock";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private long downloadId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();
        applyTaskDescription();

        web = new WebView(this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // localStorage
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false); // звук без клика
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);                           // игнорировать системный масштаб шрифта
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.setBackgroundColor(Color.parseColor("#1A1A2E"));
        web.setWebViewClient(new WebViewClient());

        /* Без этого колбэка <input type="file"> в WebView не открывает выбор файла */
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;

                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("audio/*");
                try {
                    startActivityForResult(Intent.createChooser(pick, "Выберите аудиозапись"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        setContentView(web);
        /* ?app=1 — страница прячет кнопку «Во весь экран»: в приложении она не нужна */
        web.loadUrl("file:///android_asset/clock.html?app=1");

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        registerReceiver(downloadReceiver, filter);

        checkForUpdates(false);
    }

    /* ---------- имя и иконка в списке недавних задач ---------- */

    private void applyTaskDescription() {
        if (Build.VERSION.SDK_INT < 21) return;
        try {
            Bitmap icon = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
            ActivityManager.TaskDescription desc = new ActivityManager.TaskDescription(
                    getString(R.string.app_name), icon, Color.parseColor("#16213E"));
            setTaskDescription(desc);
        } catch (Exception e) {
            Log.w(TAG, "task description failed", e);
        }
    }

    /* ---------- выбор аудиофайла ---------- */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /* ---------- проверка и установка обновлений ---------- */

    private void checkForUpdates(final boolean manual) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String json = httpGet(UPDATE_API);
                    JSONObject release = new JSONObject(json);
                    final String tag = release.optString("tag_name", "");
                    final String remote = tag.replaceFirst("^v", "");
                    final String local = appVersionName();

                    if (remote.length() == 0 || !isNewer(remote, local)) {
                        if (manual) {
                            runOnUiThread(new Runnable() {
                                public void run() { toast("Установлена последняя версия: " + local); }
                            });
                        }
                        return;
                    }

                    String apkUrl = null;
                    JSONArray assets = release.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name", "").endsWith(".apk")) {
                                apkUrl = a.optString("browser_download_url", "");
                                break;
                            }
                        }
                    }
                    if (apkUrl == null || apkUrl.length() == 0) return;
                    final String url = apkUrl;

                    SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                    String skipped = prefs.getString("skippedVersion", "");
                    if (!manual && remote.equals(skipped)) return;

                    runOnUiThread(new Runnable() {
                        public void run() { askUpdate(remote, url); }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "update check failed", e);
                    if (manual) {
                        runOnUiThread(new Runnable() {
                            public void run() { toast("Не удалось проверить обновления"); }
                        });
                    }
                }
            }
        }, "update-check").start();
    }

    private void askUpdate(final String version, final String url) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Доступно обновление")
                .setMessage("Версия " + version + ". Установить её поверх текущей? "
                        + "Настройки и выбранная запись сохранятся.")
                .setPositiveButton("Обновить", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { startDownload(url); }
                })
                .setNegativeButton("Позже", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString("skippedVersion", version).apply();
                    }
                })
                .show();
    }

    private void startDownload(String url) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType("application/vnd.android.package-archive");
            req.setTitle("Часы для олимпиады");
            req.setDescription("Загрузка обновления " + url.substring(url.lastIndexOf('/') + 1));
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS,
                    "olymp-clock-update.apk");
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            downloadId = dm.enqueue(req);
            toast("Загрузка обновления…");
        } catch (Exception e) {
            Log.w(TAG, "download failed", e);
            toast("Не удалось начать загрузку");
        }
    }

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != downloadId || downloadId == -1) return;
            downloadId = -1;

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            Uri uri = null;
            try {
                uri = dm.getUriForDownloadedFile(id);
            } catch (Exception ignored) {
            }
            if (uri == null) {
                toast("Обновление не скачалось");
                return;
            }
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(install);
            } catch (Exception e) {
                toast("Разрешите установку приложений из этого источника");
            }
        }
    };

    private String appVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "0" : info.versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    private static boolean isNewer(String remote, String local) {
        String[] a = remote.split("\\.");
        String[] b = local.split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? parseIntSafe(a[i]) : 0;
            int y = i < b.length ? parseIntSafe(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "vsoh-clock");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /* ---------- служебное ---------- */

    private void toast(final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                android.widget.Toast.makeText(MainActivity.this, text,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(downloadReceiver);
        } catch (Exception ignored) {
        }
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
