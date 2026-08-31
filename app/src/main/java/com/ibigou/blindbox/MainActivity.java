package com.ibigou.blindbox;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "IbigouMain";
    private static final String BASE_URL = "https://ybgtc.com/h5/merchant/index.html";

    private WebView webView;
    private BluetoothPrintService btService;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btService = new BluetoothPrintService(this);

        webView = findViewById(R.id.webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " IbigouApp/1.0");

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidPrinter");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (!url.contains("ybgtc.com")) {
                        startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl()));
                        return true;
                    }
                }
                return false;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        webView.loadUrl(BASE_URL);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        btService.disconnect();
        super.onDestroy();
    }

    // === JavaScript Bridge ===
    public class AndroidBridge {

        @JavascriptInterface
        public boolean isAvailable() { return true; }

        @JavascriptInterface
        public String getPairedDevices() {
            try {
                BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                if (bm == null) return err("no bluetooth");
                BluetoothAdapter ba = bm.getAdapter();
                if (ba == null) return err("no bluetooth");
                if (!ba.isEnabled()) return err("bluetooth off");
                Set<BluetoothDevice> devs = ba.getBondedDevices();
                JSONArray arr = new JSONArray();
                for (BluetoothDevice d : devs) {
                    JSONObject o = new JSONObject();
                    o.put("name", d.getName());
                    o.put("address", d.getAddress());
                    arr.put(o);
                }
                JSONObject res = new JSONObject();
                res.put("code", 0);
                res.put("data", arr);
                return res.toString();
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public String connect(String address) {
            try {
                boolean ok = btService.connect(address);
                JSONObject res = new JSONObject();
                res.put("code", ok ? 0 : -1);
                res.put("msg", ok ? "ok" : "connect failed");
                return res.toString();
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public String printQR(String url, String shopName) {
            try {
                boolean ok = btService.printQR(url, shopName);
                JSONObject res = new JSONObject();
                res.put("code", ok ? 0 : -1);
                res.put("msg", ok ? "ok" : "print failed");
                return res.toString();
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public String printText(String text) {
            try {
                boolean ok = btService.printText(text);
                JSONObject res = new JSONObject();
                res.put("code", ok ? 0 : -1);
                res.put("msg", ok ? "ok" : "print failed");
                return res.toString();
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public boolean isConnected() {
            return btService.isConnected();
        }

        @JavascriptInterface
        public void disconnect() {
            btService.disconnect();
        }

        @JavascriptInterface
        public String getConnectedDeviceName() {
            return btService.getConnectedDeviceName();
        }
    }

    private String err(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("code", -1);
            o.put("msg", msg != null ? msg : "error");
            return o.toString();
        } catch (Exception e) {
            return "{\"code\":-1,\"msg\":\"error\"}";
        }
    }
}
