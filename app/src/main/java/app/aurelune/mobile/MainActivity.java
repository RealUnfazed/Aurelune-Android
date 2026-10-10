package app.aurelune.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Aurelune for Android: one full-screen WebView that opens the site (BuildConfig.START_URL) and nothing else.
 * No Capacitor, no Cordova, no AndroidX: only the Android framework, so there is almost nothing that can break.
 *
 * What it adds on top of a bare WebView:
 *  - the page's file inputs work (artists uploading music, covers, ...);
 *  - the Back button goes back inside the site, and only then leaves the app;
 *  - links to other websites open in the browser, links to this site stay inside the app;
 *  - music keeps playing with the screen off or the app in the background, with a media notification (title, artist,
 *    cover, previous / play-pause / next, seek bar), lock-screen controls and headset buttons (see PlaybackService);
 *  - a friendly screen with a Retry button when the site can't be reached (the site's own service worker still
 *    opens the app and the offline downloads when you have been online before);
 *  - files the site offers for download are saved to the Downloads folder.
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_STORAGE = 2;
    private static final int REQ_NOTIFICATIONS = 3;
    private static final String OFFLINE_PAGE = "file:///android_asset/offline.html";

    private FrameLayout root;
    private WebView web;
    private String hostName;

    private ValueCallback<Uri[]> filePathCallback;
    private String[] pendingDownload; // url, userAgent, contentDisposition, mimeType: waiting for the storage permission (Android 9 and older)

    private boolean resumed;

    /* ------------------------------------------------------------------ lifecycle */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hostName = Uri.parse(BuildConfig.START_URL).getHost();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0A0B14"));
        setContentView(root);
        applyWindowInsets(root);

        CookieManager.getInstance().setAcceptCookie(true);
        MediaState.clear();
        MediaState.listener = this::onMediaAction;
        buildWebView();
        web.loadUrl(urlFor(getIntent()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // A tap on the media notification only brings the app forward; a link to the site opens that page.
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null && isOurHost(intent.getData())) {
            web.loadUrl(intent.getData().toString());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        web.onResume();
        web.resumeTimers();
    }

    @Override
    protected void onPause() {
        resumed = false;
        CookieManager.getInstance().flush(); // keeps you signed in even if the system kills the app
        // WebView.onPause() can silence audio, so it is only called when nothing is playing.
        if (!MediaState.playing) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        MediaState.listener = null;
        MediaState.clear();
        stopService(new Intent(this, PlaybackService.class));
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            if (OFFLINE_PAGE.equals(web.getUrl())) {
                // History here is [..., page that failed to load, offline screen]: skip the failed entry.
                if (web.canGoBackOrForward(-2)) web.goBackOrForward(-2);
                else moveTaskToBack(true);
            } else {
                web.goBack();
            }
        } else {
            moveTaskToBack(true); // like a music app: Back leaves the screen, the music keeps going
        }
    }

    /* ------------------------------------------------------------------ window insets (status bar, notch, keyboard) */

    /** Pads the app by the system bars / notch / keyboard, so the page never sits underneath them (Android 15 forces edge-to-edge). */
    private void applyWindowInsets(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            // Consumed: the page must not also add its own safe-area padding for the same bars.
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });
    }

    /* ------------------------------------------------------------------ the WebView */

    private void buildWebView() {
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0A0B14"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // localStorage + IndexedDB (offline downloads live there)
        s.setMediaPlaybackRequiresUserGesture(false); // the next song may start on its own
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);            // window.open / target=_blank are routed in onCreateWindow
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);                  // (the bundled offline.html is under android_asset, which is always readable)
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setUserAgentString(s.getUserAgentString() + " AureluneAndroid/" + BuildConfig.VERSION_NAME);

        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        web.addJavascriptInterface(new Bridge(this), "AurAndroid");
        web.setWebViewClient(new AppClient());
        web.setWebChromeClient(new AppChrome());
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                startDownload(url, userAgent, contentDisposition, mimeType));
    }

    private String urlFor(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null && isOurHost(intent.getData())) {
            return intent.getData().toString();
        }
        return BuildConfig.START_URL;
    }

    private boolean isOurHost(Uri u) {
        String scheme = u.getScheme();
        return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && u.getHost() != null && u.getHost().equalsIgnoreCase(hostName);
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // nothing on the phone can open it
        }
    }

    /** true = handled here (the WebView must not load it), false = let the WebView load it. */
    private boolean routeUrl(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        switch (scheme) {
            case "http":
            case "https":
                if (isOurHost(u)) return false;
                openExternal(u);
                return true;
            case "about":
            case "blob":
            case "data":
            case "javascript":
                return false;
            case "file":
                return !u.toString().startsWith(OFFLINE_PAGE); // only our own offline screen
            case "mailto":
            case "tel":
            case "sms":
            case "geo":
                openExternal(u);
                return true;
            default:
                return true; // intent:, market:, anything else: ignore
        }
    }

    private void showOfflinePage() {
        if (web != null) web.loadUrl(OFFLINE_PAGE);
    }

    private class AppClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return routeUrl(request.getUrl());
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            CookieManager.getInstance().flush();
            if (url != null && isOurHost(Uri.parse(url))) {
                String js = readAsset("bridge.js");
                if (js != null) view.evaluateJavascript(js, null);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            // Only when the page itself can't load (no network, server down). If the site's service worker has a
            // saved copy it answers instead, and this is never reached.
            if (request.isForMainFrame() && !request.getUrl().toString().startsWith(OFFLINE_PAGE)) showOfflinePage();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The page's renderer crashed or was killed for memory: rebuild the WebView instead of crashing the app.
            if (view != web) return true;
            root.removeView(web);
            web.destroy();
            buildWebView();
            web.loadUrl(BuildConfig.START_URL);
            MediaState.clear();
            afterStateChange(); // nothing is playing any more: removes the notification
            return true;
        }
    }

    private class AppChrome extends WebChromeClient {
        /** Opens the system file picker for <input type="file"> (uploads). */
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                callback.onReceiveValue(null);
                Toast.makeText(MainActivity.this, R.string.no_file_picker, Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        /** window.open() and target="_blank": same-site pages open in this window, everything else in the browser. */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            WebView probe = new WebView(MainActivity.this);
            probe.setWebViewClient(new WebViewClient() {
                private boolean routed;

                private void route(WebView v, Uri u) {
                    if (routed || u == null || "about".equals(u.getScheme())) return; // about:blank comes first, the real URL after it
                    routed = true;
                    if (isOurHost(u)) {
                        if (web != null) web.loadUrl(u.toString());
                    } else {
                        routeUrl(u);
                    }
                    v.post(v::destroy);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    route(v, r.getUrl());
                    return true;
                }

                @Override
                public void onPageStarted(WebView v, String url, Bitmap favicon) {
                    if (url != null) route(v, Uri.parse(url));
                }
            });
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(probe);
            resultMsg.sendToTarget();
            return true;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }

    /* ------------------------------------------------------------------ downloads */

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        Uri u = Uri.parse(url);
        if (!"http".equals(u.getScheme()) && !"https".equals(u.getScheme())) return; // blob:/data: downloads are not supported
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = new String[]{url, userAgent, contentDisposition, mimeType};
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request r = new DownloadManager.Request(u);
            if (mimeType != null) r.setMimeType(mimeType);
            String cookie = CookieManager.getInstance().getCookie(url); // so the download is you, signed in
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (userAgent != null) r.addRequestHeader("User-Agent", userAgent);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && pendingDownload != null) {
            String[] d = pendingDownload;
            pendingDownload = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startDownload(d[0], d[1], d[2], d[3]);
        }
    }

    /* ------------------------------------------------------------------ background playback + media notification */

    /** The page started or stopped playing audio (bridge.js, part 1). */
    private void onPlayingChanged(boolean isPlaying) {
        MediaState.playing = isPlaying;
        afterStateChange();
    }

    /** The page reports what is playing (bridge.js, part 2). */
    private void onMedia(String title, String artist, String album, String artUrl, long durationMs, long positionMs, boolean isPlaying) {
        if (!artUrl.equals(MediaState.artUrl)) {
            MediaState.artUrl = artUrl;
            MediaState.artwork = null; // the new cover arrives in onArtwork()
        }
        MediaState.title = title;
        MediaState.artist = artist;
        MediaState.album = album;
        MediaState.durationMs = durationMs;
        MediaState.positionMs = positionMs;
        MediaState.positionAt = SystemClock.elapsedRealtime();
        MediaState.playing = isPlaying;
        afterStateChange();
    }

    private void onMediaCleared() {
        MediaState.clear();
        afterStateChange();
    }

    /** The cover for artUrl: a small JPEG drawn by the page (base64), or empty if the page couldn't, then it is downloaded. */
    private void onArtwork(String forUrl, String base64) {
        if (forUrl.isEmpty() || !forUrl.equals(MediaState.artUrl)) return; // an old song's cover arriving late
        if (!base64.isEmpty()) {
            try {
                Bitmap bitmap = Artwork.decode(Base64.decode(base64, Base64.DEFAULT));
                if (bitmap != null) {
                    MediaState.artwork = bitmap;
                    PlaybackService.refresh();
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // fall through to the download
            }
        }
        if (forUrl.startsWith("https://")) {
            new Thread(() -> {
                Bitmap bitmap = Artwork.download(forUrl);
                if (bitmap != null) runOnUiThread(() -> {
                    if (forUrl.equals(MediaState.artUrl)) {
                        MediaState.artwork = bitmap;
                        PlaybackService.refresh();
                    }
                });
            }).start();
        }
    }

    /** Keeps the service and the sleeping/awake state of the page in step with MediaState. */
    private void afterStateChange() {
        if (!MediaState.playing && !resumed && web != null) web.onPause(); // in the background and silent: let the page sleep

        boolean running = PlaybackService.isRunning();
        if (MediaState.playing) {
            if (running) {
                PlaybackService.refresh();
            } else {
                askForNotificationsOnce();
                try {
                    Intent service = new Intent(this, PlaybackService.class);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                    else startService(service);
                } catch (RuntimeException e) {
                    // The system refused to start a foreground service right now; playback itself carries on regardless.
                }
            }
        } else if (running) {
            // Paused: keep the notification (so it can resume) as long as we know what the song is.
            if (MediaState.hasMedia()) PlaybackService.refresh();
            else stopService(new Intent(this, PlaybackService.class));
        }
    }

    /** A button press (notification, lock screen, headset): pass it to the page's player. Runs on the main thread. */
    private void onMediaAction(String action, double seconds) {
        if (web == null) return;
        if ("stop".equals(action)) { // the notification was swiped away
            runControl("pause", 0);
            MediaState.playing = false; // (the song's details stay: the next play brings the notification back)
            stopService(new Intent(this, PlaybackService.class));
            return;
        }
        if ("play".equals(action) || "pause".equals(action)) {
            MediaState.playing = "play".equals(action); // show it at once; the page's own report follows
            afterStateChange();
        }
        runControl(action, seconds);
    }

    private void runControl(String action, double seconds) {
        // The page may be asleep (app in the background, nothing playing): wake it for the command.
        web.onResume();
        web.resumeTimers();
        web.evaluateJavascript("window.__aurControl&&window.__aurControl('" + action + "'," + seconds + ")", null);
        // If the command didn't start any music, let the page go back to sleep.
        web.postDelayed(() -> {
            if (web != null && !resumed && !MediaState.playing) web.onPause();
        }, 8000);
    }

    /** Android 13+: the notification needs the user's OK. Asked once, the first time music plays. */
    private void askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || !resumed) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        if (prefs.getBoolean("asked_notifications", false)) return;
        prefs.edit().putBoolean("asked_notifications", true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    /** What bridge.js (injected into the page) talks to. Only these few small methods are exposed to the page. */
    public static final class Bridge {
        private final MainActivity activity;

        Bridge(MainActivity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public void setPlaying(boolean isPlaying) {
            activity.runOnUiThread(() -> activity.onPlayingChanged(isPlaying));
        }

        @JavascriptInterface
        public void setMedia(String title, String artist, String album, String artUrl, long durationMs, long positionMs, boolean isPlaying) {
            final String t = title == null ? "" : title;
            final String ar = artist == null ? "" : artist;
            final String al = album == null ? "" : album;
            final String u = artUrl == null ? "" : artUrl;
            activity.runOnUiThread(() -> activity.onMedia(t, ar, al, u, durationMs, positionMs, isPlaying));
        }

        @JavascriptInterface
        public void setArtwork(String forUrl, String base64) {
            final String u = forUrl == null ? "" : forUrl;
            final String b = base64 == null ? "" : base64;
            activity.runOnUiThread(() -> activity.onArtwork(u, b));
        }

        @JavascriptInterface
        public void clearMedia() {
            activity.runOnUiThread(activity::onMediaCleared);
        }
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
    }
}
