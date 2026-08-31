package com.ibigou.blindbox;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "IbigouMain";
    private static final String BASE_URL = "https://ybgtc.com/h5/merchant/index.html";

    private WebView webView;
    private BluetoothPrintService btService;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> btPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean connect = result.get(Manifest.permission.BLUETOOTH_CONNECT);
                Boolean scan = result.get(Manifest.permission.BLUETOOTH_SCAN);
                Log.d(TAG, "BT permissions: connect=" + connect + " scan=" + scan);
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btService = new BluetoothPrintService(this);

        // Request Bluetooth permissions on startup (Android 12+)
        requestBtPermissions();

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

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            String[] perms = {
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
            boolean need = false;
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    need = true;
                    break;
                }
            }
            if (need) {
                btPermissionLauncher.launch(perms);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 100);
            }
        }
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
        public boolean hasPermission() {
            if (Build.VERSION.SDK_INT >= 31) {
                return ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        @JavascriptInterface
        public void requestPermission() {
            mainHandler.post(() -> requestBtPermissions());
        }

        @JavascriptInterface
        public boolean isBluetoothEnabled() {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null) return false;
            BluetoothAdapter ba = bm.getAdapter();
            return ba != null && ba.isEnabled();
        }

        @JavascriptInterface
        public void openBluetoothSettings() {
            mainHandler.post(() -> {
                Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            });
        }

        @JavascriptInterface
        public String getPairedDevices() {
            try {
                if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return err("no permission");
                }
                BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                if (bm == null) return err("no bluetooth");
                BluetoothAdapter ba = bm.getAdapter();
                if (ba == null) return err("no bluetooth");
                if (!ba.isEnabled()) return err("bluetooth off");
                Set<BluetoothDevice> devs = ba.getBondedDevices();
                JSONArray arr = new JSONArray();
                for (BluetoothDevice d : devs) {
                    JSONObject o = new JSONObject();
                    String name = d.getName();
                    if (name == null) name = "unknown";
                    o.put("name", name);
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
                if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return err("no permission");
                }
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
