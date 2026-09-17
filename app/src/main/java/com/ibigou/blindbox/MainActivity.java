package com.ibigou.blindbox;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
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
import androidx.core.content.ContextCompat;

import com.dothantech.lpapi.LPAPI;
import com.dothantech.printer.IDzPrinter;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "IbigouMain";
    private static final String LOGIN_URL = "https://ybgtc.com/h5/merchant/login.html";

    private WebView webView;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private NativeTTS nativeTTS;

    // ===== LPAPI 德佟官方SDK =====
    private LPAPI api;
    private final List<IDzPrinter.PrinterAddress> discoveredPrinters = new ArrayList<>();
    private final Object printerLock = new Object();
    private AlertDialog deviceDialog;
    private volatile boolean connecting = false;
    private String connectedPrinterName = "";

    private final ActivityResultLauncher<String[]> btPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {});

    // ===== LPAPI 回调 =====
    private final LPAPI.Callback mCallback = new LPAPI.Callback() {
        @Override
        public void onStateChange(IDzPrinter.PrinterAddress printer, IDzPrinter.PrinterState state) {
            Log.d(TAG, "state = " + state + ", printer = " + (printer == null ? "null" : printer.shownName));
            if (state == IDzPrinter.PrinterState.Connected || state == IDzPrinter.PrinterState.Connected2) {
                connectedPrinterName = (printer != null && printer.shownName != null) ? printer.shownName : "";
                connecting = false;
                mainHandler.post(() -> {
                    Toast.makeText(MainActivity.this, "✅ 已连接: " + connectedPrinterName, Toast.LENGTH_LONG).show();
                    if (webView != null) {
                        webView.post(() -> webView.evaluateJavascript("if(window._onBtConnected)window._onBtConnected('" + connectedPrinterName + "');", null));
                    }
                    if (deviceDialog != null) {
                        try { deviceDialog.dismiss(); } catch (Exception ignored) {}
                        deviceDialog = null;
                    }
                });
            } else if (state == IDzPrinter.PrinterState.Disconnected) {
                connecting = false;
                connectedPrinterName = "";
                mainHandler.post(() -> {
                    if (deviceDialog != null) {
                        try { deviceDialog.dismiss(); } catch (Exception ignored) {}
                        deviceDialog = null;
                    }
                });
            }
        }

        @Override
        public void onProgressInfo(IDzPrinter.ProgressInfo progressInfo, Object o) {
        }

        @Override
        public void onPrinterDiscovery(IDzPrinter.PrinterAddress printerAddress, Object o) {
            if (printerAddress == null) return;
            Log.d(TAG, "discovered: " + printerAddress.shownName);
            synchronized (printerLock) {
                for (IDzPrinter.PrinterAddress p : discoveredPrinters) {
                    if (p.shownName != null && p.shownName.equals(printerAddress.shownName)) return;
                }
                discoveredPrinters.add(printerAddress);
            }
            mainHandler.post(() -> MainActivity.this.refreshDeviceDialog());
        }

        @Override
        public void onPrintProgress(IDzPrinter.PrinterAddress printerAddress, IDzPrinter.PrintData printData, IDzPrinter.PrintProgress progress, Object o) {
            Log.d(TAG, "print progress: " + progress);
            mainHandler.post(() -> {
                if (progress == IDzPrinter.PrintProgress.Success) {
                    Toast.makeText(MainActivity.this, "打印成功", Toast.LENGTH_SHORT).show();
                } else if (progress == IDzPrinter.PrintProgress.Failed) {
                    Toast.makeText(MainActivity.this, "打印失败", Toast.LENGTH_SHORT).show();
                }
            });
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestBtPermissions();
        nativeTTS = new NativeTTS(this);
        // 初始化LPAPI
        try {
            api = LPAPI.Factory.createInstance(mCallback);
            Log.d(TAG, "LPAPI initialized");
        } catch (Throwable t) {
            Log.e(TAG, "LPAPI init failed", t);
            api = null;
        }
        webView = findViewById(R.id.webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " IbigouApp/1.0");
        webView.clearCache(true);
        webView.clearHistory();
        android.webkit.WebStorage.getInstance().deleteAllData();
        android.webkit.CookieManager.getInstance().removeAllCookies(null);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidPrinter");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (!url.contains("ybgtc.com")) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl()));
                        } catch (Exception ignored) {}
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
        webView.loadUrl(LOGIN_URL);
    }

    private void requestBtPermissions() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= 31) {
            perms = new String[]{
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            };
        } else {
            perms = new String[]{
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }
        boolean need = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                need = true;
                break;
            }
        }
        if (need) btPermissionLauncher.launch(perms);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try {
            if (api != null) api.quit();
        } catch (Exception ignored) {}
        if (nativeTTS != null) nativeTTS.shutdown();
        super.onDestroy();
    }

    // ===== 打印机搜索/连接 =====
    private boolean isPrinterConnected() {
        if (api == null) return false;
        try {
            IDzPrinter.PrinterState st = api.getPrinterState();
            return st != null && (st.equals(IDzPrinter.PrinterState.Connected) || st.equals(IDzPrinter.PrinterState.Connected2));
        } catch (Throwable t) {
            return false;
        }
    }

    private void startDiscovery() {
        if (api == null) return;
        synchronized (printerLock) { discoveredPrinters.clear(); }
        try {
            api.discovery();
        } catch (Throwable t) {
            Log.e(TAG, "discovery failed", t);
        }
    }

    private void stopDiscovery() {
        try {
            if (api != null) api.stopDiscovery();
        } catch (Throwable ignored) {}
    }

    private void showDevicePicker() {
        if (Build.VERSION.SDK_INT >= 31) {
            String[] needed = new String[]{
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            };
            boolean needRequest = false;
            for (String p : needed) {
                if (ContextCompat.checkSelfPermission(this, p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    needRequest = true;
                    break;
                }
            }
            if (needRequest) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("需要蓝牙权限")
                    .setMessage("连接蓝牙设备需要权限，请点允许。")
                    .setPositiveButton("允许", (d, w) -> btPermissionLauncher.launch(needed))
                    .setCancelable(false)
                    .show());
                return;
            }
        }
        if (api == null) {
            runOnUiThread(() -> Toast.makeText(this, "打印SDK初始化失败", Toast.LENGTH_LONG).show());
            return;
        }
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null || !btAdapter.isEnabled()) {
            runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("蓝牙未开启")
                .setMessage("请先打开手机蓝牙，然后点重新连接。")
                .setPositiveButton("打开蓝牙设置", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("取消", null)
                .show());
            return;
        }
        runOnUiThread(() -> {
            showDeviceDialog();
            startDiscovery();
        });
    }

    private void showDeviceDialog() {
        if (deviceDialog != null) {
            try { deviceDialog.dismiss(); } catch (Exception ignored) {}
            deviceDialog = null;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("正在搜索打印机...");
        builder.setItems(new String[]{"搜索中..."}, (dialog, which) -> {});
        builder.setNeutralButton("重新搜索", (dialog, which) -> {
            startDiscovery();
        });
        builder.setNegativeButton("取消", (dialog, which) -> {
            stopDiscovery();
            dialog.dismiss();
        });
        builder.setOnDismissListener(dialog -> {
            stopDiscovery();
            deviceDialog = null;
        });
        deviceDialog = builder.show();
        // listView需要窗口创建后才可用，延迟刷新
        mainHandler.postDelayed(() -> refreshDeviceDialog(), 300);
    }

    private void refreshDeviceDialog() {
        if (deviceDialog == null) return;
        android.widget.ListView lv = deviceDialog.getListView();
        if (lv == null) return;
        synchronized (printerLock) {
            if (discoveredPrinters.isEmpty()) {
                deviceDialog.setTitle("正在搜索打印机...");
                lv.setAdapter(new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_list_item_1, new String[]{"搜索中..."}));
                return;
            }
            deviceDialog.setTitle("选择蓝牙打印机（" + discoveredPrinters.size() + "台）");
            String[] items = new String[discoveredPrinters.size()];
            for (int i = 0; i < discoveredPrinters.size(); i++) {
                IDzPrinter.PrinterAddress p = discoveredPrinters.get(i);
                items[i] = p.shownName;
            }
            lv.setAdapter(new android.widget.ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_1, items));
            final int size = discoveredPrinters.size();
            lv.setOnItemClickListener((parent, view, position, id) -> {
                if (position < 0 || position >= size) return;
                IDzPrinter.PrinterAddress target;
                synchronized (printerLock) {
                    if (position >= discoveredPrinters.size()) return;
                    target = discoveredPrinters.get(position);
                }
                stopDiscovery();
                connecting = true;
                try {
                    if (deviceDialog != null) deviceDialog.dismiss();
                    deviceDialog = null;
                } catch (Exception ignored) {}
                Toast.makeText(MainActivity.this, "正在连接 " + target.shownName + "...", Toast.LENGTH_SHORT).show();
                boolean ok = api.openPrinterByAddress(target);
                if (!ok) {
                    connecting = false;
                    Toast.makeText(MainActivity.this, "连接请求失败", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    // ===== LPAPI 打印 =====
    private void applyPaperSettings() {
        try {
            if (api != null) {
                // 间隙标签纸：类型=2(间隙纸/不干胶)，间隙长度=2mm（80×60标签纸最常见间隙值，匹配打印机"间隙1"设置）
                api.setPrintPageGapType(2);
                api.setPrintPageGapLength(2);
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyPaperSettings failed", t);
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

    private String ok(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("code", 0);
            o.put("msg", msg != null ? msg : "ok");
            return o.toString();
        } catch (Exception e) {
            return "{\"code\":0,\"msg\":\"ok\"}";
        }
    }

    // ===== AndroidBridge (H5接口，保持兼容) =====
    public class AndroidBridge {
        @JavascriptInterface
        public boolean isAvailable() { return api != null; }

        @JavascriptInterface
        public boolean isBluetoothEnabled() {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a != null && a.isEnabled();
        }

        @JavascriptInterface
        public boolean isConnected() { return isPrinterConnected(); }

        @JavascriptInterface
        public String getConnectedDeviceName() { return connectedPrinterName; }

        @JavascriptInterface
        public boolean hasPermission() { return true; }

        @JavascriptInterface
        public void requestPermission() {}

        @JavascriptInterface
        public void openBluetoothSettings() {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void showDevicePicker() {
            runOnUiThread(() -> MainActivity.this.showDevicePicker());
        }

        @JavascriptInterface
        public String connect() {
            try {
                if (isPrinterConnected()) {
                    return ok("already connected:" + connectedPrinterName);
                }
                if (api == null) return err("LPAPI not initialized");
                startDiscovery();
                // 等待发现打印机（最多5秒）
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    synchronized (printerLock) {
                        if (!discoveredPrinters.isEmpty()) break;
                    }
                    try { Thread.sleep(100); } catch (Exception ignored) {}
                }
                stopDiscovery();
                IDzPrinter.PrinterAddress target = null;
                synchronized (printerLock) {
                    if (!discoveredPrinters.isEmpty()) target = discoveredPrinters.get(0);
                }
                if (target == null) return err("未发现打印机");
                // 同步连接（内部等待连接完成，最多约10秒）
                boolean linked = api.openPrinterByAddressSync(target);
                if (linked || isPrinterConnected()) {
                    String name = api.getPrinterName();
                    if (name != null && !name.isEmpty()) connectedPrinterName = name;
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "✅ 已连接: " + connectedPrinterName, Toast.LENGTH_LONG).show();
                        if (webView != null) {
                            webView.post(() -> webView.evaluateJavascript("if(window._onBtConnected)window._onBtConnected('" + connectedPrinterName + "');", null));
                        }
                    });
                    return ok("connected:" + connectedPrinterName);
                }
                return err("连接失败");
            } catch (Exception e) { return err(e.getMessage()); }
        }

        @JavascriptInterface
        public String connectPrinter(String mac) {
            try {
                if (isPrinterConnected()) {
                    return ok("already connected:" + connectedPrinterName);
                }
                if (api == null) return err("LPAPI not initialized");
                startDiscovery();
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    synchronized (printerLock) {
                        if (!discoveredPrinters.isEmpty()) break;
                    }
                    try { Thread.sleep(100); } catch (Exception ignored) {}
                }
                stopDiscovery();
                IDzPrinter.PrinterAddress target = null;
                synchronized (printerLock) {
                    if (mac != null && !mac.isEmpty()) {
                        for (IDzPrinter.PrinterAddress p : discoveredPrinters) {
                            if (mac.equalsIgnoreCase(String.valueOf(p.macAddress))) {
                                target = p;
                                break;
                            }
                        }
                    }
                    if (target == null && !discoveredPrinters.isEmpty()) target = discoveredPrinters.get(0);
                }
                if (target == null) return err("未发现打印机");
                boolean linked = api.openPrinterByAddressSync(target);
                if (linked || isPrinterConnected()) {
                    String name = api.getPrinterName();
                    if (name != null && !name.isEmpty()) connectedPrinterName = name;
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "✅ 已连接: " + connectedPrinterName, Toast.LENGTH_LONG).show();
                        if (webView != null) {
                            webView.post(() -> webView.evaluateJavascript("if(window._onBtConnected)window._onBtConnected('" + connectedPrinterName + "');", null));
                        }
                    });
                    return ok("connected:" + connectedPrinterName);
                }
                return err("连接失败");
            } catch (Exception e) { return err(e.getMessage()); }
        }

        @JavascriptInterface
        public String disconnectPrinter() {
            try {
                if (api != null) {
                    api.quit();
                    api = LPAPI.Factory.createInstance(mCallback);
                }
                connectedPrinterName = "";
                return ok("disconnected");
            } catch (Exception e) { return err(e.getMessage()); }
        }

        @JavascriptInterface
        public String printText(String text) {
            try {
                if (!isPrinterConnected()) return err("Not connected");
                applyPaperSettings();
                // 页面宽72mm（80mm纸可用宽度） 高40mm（测试文字用，减少走纸） 不旋转
                api.startJob(72, 40, 0);
                // 绘制文字：text, x=4, y=4, 宽64, 高32, 字号8mm
                api.drawText(text == null ? "" : text, 4, 4, 64, 32, 8);
                api.commitJob();
                return ok("ok");
            } catch (Exception e) { return err(e.getMessage()); }
        }

        @JavascriptInterface
        public String printQR(String url, String shopName) {
            try {
                if (!isPrinterConnected()) return err("Not connected");
                applyPaperSettings();
                // 页面宽72mm 高60mm（80×60标签纸） 不旋转
                api.startJob(72, 60, 0);
                // 店铺名（字号5mm，防止长店名超宽）
                if (shopName != null && !shopName.isEmpty()) {
                    api.drawText(shopName, 4, 3, 64, 10, 5);
                }
                // 副标题
                api.drawText("扫码开盲盒 · 宜必购", 4, 15, 64, 7, 4);
                // 二维码（36mm宽，居中，y=24开始到60mm）
                api.draw2DQRCode(url == null ? "" : url, 18, 24, 36);
                api.commitJob();
                return ok("ok");
            } catch (Exception e) { return err(e.getMessage()); }
        }

        @JavascriptInterface
        public void speak(String text) {
            try {
                if (nativeTTS != null) nativeTTS.speak(text);
            } catch (Exception e) { Log.e(TAG, "speak error", e); }
        }

        @JavascriptInterface
        public void speakUrl(String url) {
            try {
                if (nativeTTS != null) nativeTTS.speakUrl(url);
            } catch (Exception e) { Log.e(TAG, "speakUrl error", e); }
        }
    }
}
