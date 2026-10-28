package com.bgeforce.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.webkit.*;
import android.widget.*;

/** Official store authorization inside the app; no JavaScript bridge or account token injection. */
public final class AccountWebActivity extends Activity {
  private WebView web;
  private TextView address;
  private L l;

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    Ui.fullscreen(this);
    l = new L(new Settings(this).locale());
    String url = getIntent().getStringExtra("url");
    if (!safe(url)) {
      finish();
      return;
    }
    LinearLayout root = Ui.column(this);
    root.setBackgroundColor(Ui.BG);
    LinearLayout toolbar = Ui.row(this);
    toolbar.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
    toolbar.addView(Ui.button(this, "←  " + l.t("back"), () -> finish()));
    address = Ui.text(this, "", 12, Ui.MUTED);
    address.setSingleLine(true);
    address.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
    toolbar.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
    toolbar.addView(Ui.button(this, l.t("refresh"), () -> web.reload()));
    root.addView(toolbar);
    web = new WebView(this);
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            Uri uri = request.getUrl();
            if ("http".equals(uri.getScheme())
                && "localhost".equals(uri.getHost())
                && uri.getPort() == 2259) {
              setResult(RESULT_OK);
              finish();
              return true;
            }
            if (!safe(uri.toString())) {
              Toast.makeText(AccountWebActivity.this, l.t("unavailable"), Toast.LENGTH_LONG).show();
              return true;
            }
            return false;
          }

          @Override
          public void onPageStarted(WebView view, String url, Bitmap icon) {
            address.setText(Uri.parse(url).getHost());
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            CookieManager.getInstance().flush();
          }

          @Override
          public void onReceivedHttpError(
              WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame())
              address.setText(
                  "HTTP " + response.getStatusCode() + " · " + request.getUrl().getHost());
          }
        });
    web.setWebChromeClient(new WebChromeClient());
    root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    web.loadUrl(url);
  }

  private static boolean safe(String url) {
    if (url == null) return false;
    okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
    return parsed != null
        && parsed.isHttps()
        && parsed.username().isEmpty()
        && parsed.password().isEmpty();
  }

  @Override
  public void onBackPressed() {
    if (web != null && web.canGoBack()) web.goBack();
    else super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    if (web != null) {
      web.stopLoading();
      web.destroy();
    }
    super.onDestroy();
  }
}
