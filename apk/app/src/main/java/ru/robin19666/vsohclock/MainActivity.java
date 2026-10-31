package ru.robin19666.vsohclock;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Обёртка вокруг HTML-файла с часами.
 *
 * Зачем: на части интерактивных досок нет приложения, связанного с .html,
 * поэтому файл просто нечем открыть. Здесь страница лежит внутри APK
 * (assets/clock.html) и показывается в WebView — выбор файла не нужен.
 *
 * Особенности:
 *  - полный экран, без статус-бара и кнопок навигации;
 *  - экран не гаснет (FLAG_KEEP_SCREEN_ON);
 *  - включён localStorage (настройки и идущий таймер);
 *  - звук разрешён без жеста (предупреждения и финальная трель);
 *  - системный размер шрифта не влияет на вёрстку (setTextZoom(100)).
 */
public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

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

        setContentView(web);
        web.loadUrl("file:///android_asset/clock.html");
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
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
