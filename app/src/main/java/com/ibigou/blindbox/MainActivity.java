package com.ibigou.blindbox;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import android.speech.tts.TextToSpeech;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "IbigouMain";
    private static final String LOGIN_URL = "https://ybgtc.com/h5/merchant/login.html";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private WebView webView;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private BluetoothSocket btSocket;
    private java.io.OutputStream btOut;
    private String btDeviceName;
    private boolean btScanning = false;
    private final Map<String, BluetoothDevice> discoveredDevices = new HashMap<>();
    private BroadcastReceiver discoveryReceiver;
    private BluetoothAdapter btAdapter;
    private NativeTTS nativeTTS;
    private final ActivityResultLauncher<String[]> btPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {});

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestBtPermissions();
        nativeTTS = new NativeTTS(this);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) btAdapter = bm.getAdapter();
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
        webView.loadUrl(LOGIN_URL);
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            String[] perms = {
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            };
            boolean need = false;
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    need = true; break;
                }
            }
            if (need) btPermissionLauncher.launch(perms);
        } else {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                btPermissionLauncher.launch(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION});
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopScan();
        disconnectBt();
        if (nativeTTS != null) nativeTTS.shutdown();
        super.onDestroy();
    }

    private void startScan() {
        if (btAdapter == null || !btAdapter.isEnabled()) return;
        discoveredDevices.clear();
        btScanning = true;
        discoveryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (dev != null) {
                        String name = dev.getName();
                        if (name == null) name = "unknown";
                        discoveredDevices.put(dev.getAddress(), dev);
                        Log.d(TAG, "Found: " + name + " " + dev.getAddress());
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    btScanning = false;
                    Log.d(TAG, "Discovery finished, found " + discoveredDevices.size());
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        btAdapter.startDiscovery();
    }

    private void stopScan() {
        if (btScanning && btAdapter != null) {
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            btAdapter.cancelDiscovery();
        }
        btScanning = false;
        if (discoveryReceiver != null) { try { unregisterReceiver(discoveryReceiver); } catch (Exception ignored) {} discoveryReceiver = null; }
    }

    private void showDevicePicker() {
        if (Build.VERSION.SDK_INT >= 31) {
            String[] needed = new String[]{
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            };
            boolean needRequest = false;
            for (String p : needed) {
                if (ContextCompat.checkSelfPermission(this, p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    needRequest = true; break;
                }
            }
            if (needRequest) {
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                        .setTitle("需要蓝牙权限")
                        .setMessage("连接蓝牙设备需要权限，请点允许。")
                        .setPositiveButton("允许", (d, w) -> btPermissionLauncher.launch(needed))
                        .setCancelable(false)
                        .show();
                });
                return;
            }
        }
        if (btAdapter == null) {
            runOnUiThread(() -> Toast.makeText(this, "此设备不支持蓝牙", Toast.LENGTH_LONG).show());
            return;
        }
        if (!btAdapter.isEnabled()) {
            runOnUiThread(() -> {
                new AlertDialog.Builder(this)
                    .setTitle("蓝牙未开启")
                    .setMessage("请先打开手机蓝牙，然后点重新连接。")
                    .setPositiveButton("打开蓝牙设置", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    })
                    .setNegativeButton("取消", null)
                    .show();
            });
            return;
        }
        startScan();
        List<BluetoothDevice> deviceList = new ArrayList<>();
        Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
        deviceList.addAll(paired);
        for (BluetoothDevice d : discoveredDevices.values()) {
            if (!deviceList.contains(d)) deviceList.add(d);
        }
        String[] items = new String[deviceList.size()];
        for (int i = 0; i < deviceList.size(); i++) {
            BluetoothDevice d = deviceList.get(i);
            String name = d.getName();
            if (name == null) name = "未知设备";
            String status = d.getBondState() == BluetoothDevice.BOND_BONDED ? "✅ " : "🔍 ";
            items[i] = status + name + "\n" + d.getAddress();
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择蓝牙设备");
        builder.setItems(items, (dialog, which) -> {
            stopScan();
            connectToDevice(deviceList.get(which));
        });
        builder.setNeutralButton("重新搜索", (dialog, which) -> { dialog.dismiss(); showDevicePicker(); });
        builder.setNegativeButton("取消", (dialog, which) -> { stopScan(); dialog.dismiss(); });
        builder.setOnDismissListener(dialog -> stopScan());
        builder.show();
    }

    private void connectToDevice(BluetoothDevice device) {
        new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    mainHandler.post(() -> Toast.makeText(this, "需要蓝牙权限", Toast.LENGTH_SHORT).show());
                    return;
                }
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    String dn = device.getName() != null ? device.getName() : "设备";
                    mainHandler.post(() -> Toast.makeText(this, "正在配对 " + dn + "...", Toast.LENGTH_SHORT).show());
                    device.createBond();
                    Thread.sleep(3000);
                    if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                        mainHandler.post(() -> Toast.makeText(this, "配对失败，请重试", Toast.LENGTH_SHORT).show());
                        return;
                    }
                }
                mainHandler.post(() -> Toast.makeText(this, "正在连接...", Toast.LENGTH_SHORT).show());
                btAdapter.cancelDiscovery();
                btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                btSocket.connect();
                btOut = btSocket.getOutputStream();
                btDeviceName = device.getName();
                mainHandler.post(() -> {
                    Toast.makeText(this, "✅ 已连接: " + btDeviceName, Toast.LENGTH_LONG).show();
                    webView.post(() -> webView.evaluateJavascript("if(window._onBtConnected)window._onBtConnected('" + btDeviceName + "');", null));
                });
            } catch (Exception e) {
                Log.e(TAG, "connect failed", e);
                mainHandler.post(() -> Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                disconnectBt();
            }
        }).start();
    }

    private void disconnectBt() {
        try { if (btOut != null) { btOut.flush(); btOut.close(); } } catch (Exception ignored) {}
        try { if (btSocket != null) btSocket.close(); } catch (Exception ignored) {}
        btOut = null; btSocket = null; btDeviceName = null;
    }

    private byte[] buildEscPosQR(String url, String shopName) {
        byte[] urlBytes = url.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] nameBytes = (shopName != null && !shopName.isEmpty())
                ? shopName.getBytes(java.nio.charset.StandardCharsets.UTF_8) : null;
        byte[] footer = "扫码开盲盒 · 宜必购".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.List<byte[]> parts = new ArrayList<>();
        parts.add(new byte[]{0x1B, 0x40});
        parts.add(new byte[]{0x1B, 0x61, 0x01});
        parts.add(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x31, 0x06});
        parts.add(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x32, 0x31});
        int dl = urlBytes.length + 3;
        parts.add(new byte[]{0x1D, 0x28, 0x6B, (byte)(dl & 0xFF), (byte)((dl >> 8) & 0xFF), 0x31, 0x31, 0x30});
        parts.add(urlBytes);
        parts.add(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x32, 0x00});
        parts.add(new byte[]{0x1B, 0x64, 0x02});
        if (nameBytes != null) { parts.add(nameBytes); parts.add(new byte[]{0x0A}); }
        parts.add(footer); parts.add(new byte[]{0x0A});
        parts.add(new byte[]{0x1B, 0x64, 0x03});
        parts.add(new byte[]{0x1D, 0x56, 0x00});
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, result, pos, p.length); pos += p.length; }
        return result;
    }

    public class AndroidBridge {
        @JavascriptInterface public boolean isAvailable() { return true; }

        @JavascriptInterface public boolean hasPermission() {
            if (Build.VERSION.SDK_INT >= 31)
                return ContextCompat.checkSelfPermission(MainActivity.this, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            return true;
        }
        @JavascriptInterface public void requestPermission() { mainHandler.post(() -> requestBtPermissions()); }
        @JavascriptInterface public boolean isBluetoothEnabled() { return btAdapter != null && btAdapter.isEnabled(); }
        @JavascriptInterface public void openBluetoothSettings() {
            mainHandler.post(() -> { Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS); intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); });
        }
        @JavascriptInterface public void showDevicePicker() { mainHandler.post(() -> MainActivity.this.showDevicePicker()); }
        @JavascriptInterface public String getPairedDevices() {
            try {
                if (btAdapter == null) return err("no bluetooth");
                if (!btAdapter.isEnabled()) return err("bluetooth off");
                Set<BluetoothDevice> devs = btAdapter.getBondedDevices();
                JSONArray arr = new JSONArray();
                for (BluetoothDevice d : devs) {
                    JSONObject o = new JSONObject();
                    String name = d.getName(); if (name == null) name = "unknown";
                    o.put("name", name); o.put("address", d.getAddress());
                    arr.put(o);
                }
                JSONObject res = new JSONObject(); res.put("code", 0); res.put("data", arr);
                return res.toString();
            } catch (Exception e) { return err(e.getMessage()); }
        }
        @JavascriptInterface public String connect(String address) {
            try {
                if (btAdapter == null) return err("no bluetooth");
                connectToDevice(btAdapter.getRemoteDevice(address));
                JSONObject res = new JSONObject(); res.put("code", 0); res.put("msg", "connecting");
                return res.toString();
            } catch (Exception e) { return err(e.getMessage()); }
        }
        @JavascriptInterface public String printQR(String url, String shopName) {
            try {
                if (btOut == null) return err("not connected");
                btOut.write(buildEscPosQR(url, shopName)); btOut.flush();
                JSONObject res = new JSONObject(); res.put("code", 0); res.put("msg", "ok");
                return res.toString();
            } catch (Exception e) { return err(e.getMessage()); }
        }
        @JavascriptInterface public String printText(String text) {
            try {
                if (btOut == null) return err("not connected");
                btOut.write(text.getBytes("GBK")); btOut.write(0x0A); btOut.flush();
                JSONObject res = new JSONObject(); res.put("code", 0); res.put("msg", "ok");
                return res.toString();
            } catch (Exception e) { return err(e.getMessage()); }
        }
        @JavascriptInterface public boolean isConnected() { return btSocket != null && btSocket.isConnected() && btOut != null; }
        @JavascriptInterface public void disconnect() { disconnectBt(); }
        @JavascriptInterface public String getConnectedDeviceName() { return btDeviceName; }
        @JavascriptInterface public void speak(String text) {
            if (nativeTTS != null) nativeTTS.speak(text);
        }
        @JavascriptInterface public void speakUrl(String url) {
            if (nativeTTS != null) nativeTTS.speakUrl(url);
        }
        @JavascriptInterface public boolean isTtsReady() { return nativeTTS != null && nativeTTS.isReady(); }
    }

    private String err(String msg) {
        try { JSONObject o = new JSONObject(); o.put("code", -1); o.put("msg", msg != null ? msg : "error"); return o.toString(); }
        catch (Exception e) { return "{\"code\":-1,\"msg\":\"error\"}"; }
    }
}
