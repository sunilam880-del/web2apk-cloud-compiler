package com.app.web2apkstudio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TARGET_URL = "https://web2apk-studio.ai.studio";
    private static final String TARGET_HOST = "web2apk-studio.ai.studio";
    private static final int TOP_HEADER_TRIM_DP = 0;

    private WebView webView;
    private ValueCallback<Uri[]> fileUploadCallback;
    private PermissionRequest pendingWebPermissionRequest;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (pendingWebPermissionRequest != null) {
                    grantSupportedWebPermissions(pendingWebPermissionRequest);
                    pendingWebPermissionRequest = null;
                }
            });

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (fileUploadCallback != null) {
                    Uri[] uris = WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), result.getData());
                    fileUploadCallback.onReceiveValue(uris);
                    fileUploadCallback = null;
                }
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        configureWindowDisplay();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(Color.parseColor("#090D16"));
        rootContainer.setClipChildren(true);
        rootContainer.setClipToPadding(true);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#090D16"));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        int trimPx = (int) (TOP_HEADER_TRIM_DP * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        if (trimPx > 0) {
            webParams.topMargin = -trimPx;
        }
        rootContainer.addView(webView, webParams);
        setContentView(rootContainer);

        configureWebSettings(webView.getSettings());
        configureWebClients();
        configureNativeDownloadSupport();
        requestRequiredRuntimePermissions();
        setupBackNavigation();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(TARGET_URL);
        }
    }

    private void configureWindowDisplay() {
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebSettings(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
    }

    private void configureWebClients() {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) return false;
                String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "";
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    // Keep all navigation inside standalone WebView
                    return false;
                }
                openExternalUri(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (view != null) {
                    view.evaluateJavascript(
                            "(function(){document.documentElement.style.overscrollBehavior='none';document.documentElement.style.overscrollBehaviorY='none';document.body&&(document.body.style.overscrollBehavior='none',document.body.style.overscrollBehaviorY='none');})();",
                            null
                    );
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    String offlineHtml = "<html><body style='margin:0;background:#090D16;color:#E2E8F0;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;text-align:center;padding:24px;'>"
                            + "<h2 style='margin:0 0 8px 0;font-size:20px;'>Connection Unavailable</h2>"
                            + "<p style='margin:0 0 20px 0;color:#94A3B8;font-size:14px;'>Unable to reach web2apk-studio.ai.studio.</p>"
                            + "<button onclick=\"window.location.href='https://web2apk-studio.ai.studio'\" style='background:#10B981;color:#06281E;border:none;padding:12px 24px;border-radius:8px;font-weight:600;font-size:14px;'>Retry</button>"
                            + "</body></html>";
                    view.loadDataWithBaseURL(null, offlineHtml, "text/html", "UTF-8", null);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    List<String> resources = Arrays.asList(request.getResources());
                    List<String> needed = new ArrayList<>();
                    if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                            ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        needed.add(Manifest.permission.RECORD_AUDIO);
                    }
                    if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                            ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        needed.add(Manifest.permission.CAMERA);
                    }
                    if (!needed.isEmpty()) {
                        pendingWebPermissionRequest = request;
                        permissionLauncher.launch(needed.toArray(new String[0]));
                    } else {
                        grantSupportedWebPermissions(request);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    fileChooserLauncher.launch(intent);
                    return true;
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
            }
        });
    }

    @SuppressLint("JavascriptInterface")
    private void configureNativeDownloadSupport() {
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimeType != null ? mimeType : "application/vnd.android.package-archive");
                    request.addRequestHeader("User-Agent", userAgent);
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null && !cookies.isEmpty()) {
                        request.addRequestHeader("Cookie", cookies);
                    }
                    request.setTitle(fileName);
                    request.setDescription("Downloading file...");
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        Toast.makeText(this, "Downloading " + fileName + "...", Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Exception ignored) {
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void saveBase64File(String base64Data, String filename, String mimeType) {
            runOnUiThread(() -> {
                try {
                    String cleanB64 = base64Data.contains(",")
                            ? base64Data.substring(base64Data.indexOf(",") + 1)
                            : base64Data;
                    byte[] bytes = Base64.decode(cleanB64, Base64.DEFAULT);
                    String safeName = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
                    File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (downloadsDir == null) {
                        downloadsDir = getCacheDir();
                    }
                    File outFile = new File(downloadsDir, safeName);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(bytes);
                    }
                    if (safeName.toLowerCase().endsWith(".apk")
                            || (mimeType != null && mimeType.contains("android.package-archive"))) {
                        Uri apkUri = FileProvider.getUriForFile(
                                MainActivity.this,
                                getPackageName() + ".fileprovider",
                                outFile
                        );
                        Intent installIntent = new Intent(Intent.ACTION_VIEW);
                        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(installIntent);
                    } else {
                        Toast.makeText(MainActivity.this, "Saved " + safeName, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Unable to save " + filename, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void grantSupportedWebPermissions(PermissionRequest request) {
        List<String> resources = Arrays.asList(request.getResources());
        List<String> granted = new ArrayList<>();
        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            granted.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE);
        }
        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            granted.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        }
        if (!granted.isEmpty()) {
            request.grant(granted.toArray(new String[0]));
        } else {
            request.deny();
        }
    }

    private void openExternalUri(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            // No handler installed on device
        }
    }

    private void requestRequiredRuntimePermissions() {
        List<String> toRequest = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toRequest.add(Manifest.permission.RECORD_AUDIO);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toRequest.add(Manifest.permission.CAMERA);
        }
        if (!toRequest.isEmpty()) {
            permissionLauncher.launch(toRequest.toArray(new String[0]));
        }
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            configureWindowDisplay();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}
