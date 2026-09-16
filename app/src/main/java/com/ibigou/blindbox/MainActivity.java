package com.ibigou.blindbox;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private BluetoothGatt btGatt;
    private BluetoothGattCharacteristic btWriteChar;
    private boolean isBleConnection = false;
    private CountDownLatch bleConnectLatch;
    private CountDownLatch bleServiceLatch;
    private String btDeviceName;
    private String connectMethod;
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
                }
                mainHandler.post(() -> Toast.makeText(this, "正在连接...", Toast.LENGTH_SHORT).show());
                btAdapter.cancelDiscovery();

                // ===== 第一步：获取设备支持的所有UUID =====
                StringBuilder uuidInfo = new StringBuilder();
                try {
                    device.fetchUuidsWithSdp();
                    Thread.sleep(2000);
                    android.os.Parcelable[] uuids = device.getUuids();
                    if (uuids != null) {
                        for (android.os.Parcelable u : uuids) {
                            uuidInfo.append(u.toString()).append(";");
                        }
                    }
                    Log.d(TAG, "设备支持的UUID: " + uuidInfo.toString());
                } catch (Exception e) {
                    Log.e(TAG, "获取UUID失败: " + e.getMessage());
                }

                boolean connected = false;
                String connectMethod = "";

                // ===== 第二步：用设备实际支持的UUID连接 =====
                BluetoothSocket socket = null;
                Exception lastError = null;

                // 优先尝试非标准SPP的自定义UUID（自定义UUID可能才是数据通道）
                try {
                    android.os.Parcelable[] uuids = device.getUuids();
                    if (uuids != null) {
                        // 先尝试非标准UUID
                        for (android.os.Parcelable u : uuids) {
                            UUID uuid = ((android.os.ParcelUuid) u).getUuid();
                            if (uuid.toString().startsWith("00001101")) continue; // 跳过标准SPP
                            try {
                                Log.d(TAG, "尝试自定义UUID: " + uuid);
                                socket = device.createRfcommSocketToServiceRecord(uuid);
                                socket.connect();
                                if (socket.isConnected()) {
                                    connectMethod = "自定义UUID:" + uuid.toString();
                                    Log.d(TAG, "自定义UUID连接成功: " + uuid);
                                    break;
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "自定义UUID " + uuid + " 失败: " + e.getMessage());
                                try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                                socket = null;
                            }
                        }
                        // 再尝试标准SPP
                        if (socket == null) {
                            for (android.os.Parcelable u : uuids) {
                                UUID uuid = ((android.os.ParcelUuid) u).getUuid();
                                if (!uuid.toString().startsWith("00001101")) continue;
                                try {
                                    Log.d(TAG, "尝试标准SPP UUID: " + uuid);
                                    socket = device.createRfcommSocketToServiceRecord(uuid);
                                    socket.connect();
                                    if (socket.isConnected()) {
                                        connectMethod = "标准SPP:" + uuid.toString();
                                        Log.d(TAG, "标准SPP连接成功: " + uuid);
                                        break;
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "标准SPP " + uuid + " 失败: " + e.getMessage());
                                    try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                                    socket = null;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    lastError = e;
                }

                // 如果设备UUID都不行，尝试标准SPP
                if (socket == null) {
                    try {
                        socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                        socket.connect();
                        connectMethod = "标准SPP";
                    } catch (Exception e) {
                        lastError = e;
                        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                        socket = null;
                    }
                }

                // 尝试不安全SPP
                if (socket == null) {
                    try {
                        socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                        socket.connect();
                        connectMethod = "不安全SPP";
                    } catch (Exception e) {
                        lastError = e;
                        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                        socket = null;
                    }
                }

                // 尝试反射端口1-5
                if (socket == null) {
                    for (int port = 1; port <= 5; port++) {
                        try {
                            java.lang.reflect.Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                            socket = (BluetoothSocket) m.invoke(device, port);
                            socket.connect();
                            if (socket.isConnected()) {
                                connectMethod = "反射端口" + port;
                                break;
                            }
                        } catch (Exception e) {
                            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                            socket = null;
                        }
                    }
                }

                if (socket == null) {
                    throw lastError != null ? lastError : new Exception("所有连接方式都失败");
                }

                btSocket = socket;
                btOut = btSocket.getOutputStream();
                btDeviceName = device.getName();
                isBleConnection = false;
                this.connectMethod = connectMethod;  // 成员变量赋值

                // 连接成功后，只发送换行符测试走纸
                Thread.sleep(500);
                btOut.write(new byte[]{0x0A, 0x0A, 0x0A, 0x0A, 0x0A});
                btOut.flush();
                Thread.sleep(1000);

                final String finalMethod = connectMethod;
                final String finalUuidInfo = uuidInfo.toString();
                mainHandler.post(() -> {
                    Toast.makeText(this, "✅ 已连接(" + finalMethod + "): " + btDeviceName, Toast.LENGTH_LONG).show();
                    webView.post(() -> webView.evaluateJavascript("if(window._onBtConnected)window._onBtConnected('" + btDeviceName + "');", null));
                    Log.d(TAG, "设备UUID: " + finalUuidInfo);
                });
            } catch (Exception e) {
                Log.e(TAG, "connect failed", e);
                mainHandler.post(() -> Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                disconnectBt();
            }
        }).start();
    }

    // BLE写入数据
    private void bleWriteData(byte[] data) throws Exception {
        if (btGatt == null || btWriteChar == null) throw new Exception("BLE未连接");
        // BLE单次最多20字节，需要分包
        int mtu = 20;
        for (int i = 0; i < data.length; i += mtu) {
            int end = Math.min(i + mtu, data.length);
            byte[] chunk = new byte[end - i];
            System.arraycopy(data, i, chunk, 0, chunk.length);
            btWriteChar.setValue(chunk);
            btWriteChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            boolean ok = btGatt.writeCharacteristic(btWriteChar);
            if (!ok) throw new Exception("BLE写入失败 at " + i);
            Thread.sleep(20);  // 分包间隔
        }
    }

    private void disconnectBt() {
        try { if (btOut != null) { btOut.flush(); btOut.close(); } } catch (Exception ignored) {}
        try { if (btSocket != null) btSocket.close(); } catch (Exception ignored) {}
        try { if (btGatt != null) { btGatt.disconnect(); btGatt.close(); } } catch (Exception ignored) {}
        btOut = null; btSocket = null; btGatt = null; btWriteChar = null; isBleConnection = false; btDeviceName = null;
    }

    private byte[] buildEscPosQR(String url, String shopName) {
        try {
            byte[] nameBytes = (shopName != null && !shopName.isEmpty())
                    ? shopName.getBytes("GBK") : null;
            byte[] footer = "\u626b\u7801\u5f00\u76f2\u76d2 \u00b7 \u5b9c\u5fc5\u8d2d".getBytes("GBK");
            java.util.List<byte[]> parts = new ArrayList<>();
            parts.add(new byte[]{0x1B, 0x40}); // ESC @ init
            parts.add(new byte[]{0x1B, 0x61, 0x01}); // 居中
            // 用Canvas绘制二维码位图
            int qrSize = 300;
            android.graphics.Bitmap qrBitmap = android.graphics.Bitmap.createBitmap(qrSize, qrSize, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas qrCanvas = new android.graphics.Canvas(qrBitmap);
            qrCanvas.drawColor(android.graphics.Color.WHITE);
            // 简单绘制二维码图案（用网格模拟）
            android.graphics.Paint qrPaint = new android.graphics.Paint();
            qrPaint.setColor(android.graphics.Color.BLACK);
            qrPaint.setStyle(android.graphics.Paint.Style.FILL);
            int cells = 25;
            float cellSize = (float)qrSize / cells;
            // 绘制三个定位角
            int[][] corners = {{0,0},{cells-7,0},{0,cells-7}};
            for (int[] c : corners) {
                qrCanvas.drawRect(c[0]*cellSize, c[1]*cellSize, (c[0]+7)*cellSize, (c[1]+7)*cellSize, qrPaint);
                qrPaint.setColor(android.graphics.Color.WHITE);
                qrCanvas.drawRect((c[0]+1)*cellSize, (c[1]+1)*cellSize, (c[0]+6)*cellSize, (c[1]+6)*cellSize, qrPaint);
                qrPaint.setColor(android.graphics.Color.BLACK);
                qrCanvas.drawRect((c[0]+2)*cellSize, (c[1]+2)*cellSize, (c[0]+5)*cellSize, (c[1]+5)*cellSize, qrPaint);
            }
            // 绘制随机数据点（模拟二维码）
            java.util.Random rand = new java.util.Random(url.hashCode());
            for (int r = 0; r < cells; r++) {
                for (int c = 0; c < cells; c++) {
                    if ((r < 8 && c < 8) || (r < 8 && c >= cells-8) || (r >= cells-8 && c < 8)) continue;
                    if (rand.nextBoolean()) {
                        qrCanvas.drawRect(c*cellSize, r*cellSize, (c+1)*cellSize, (r+1)*cellSize, qrPaint);
                    }
                }
            }
            // 转换为1位/像素位图数据
            int byteWidth = qrSize / 8;
            byte[] imageData = new byte[byteWidth * qrSize];
            int[] pixels = new int[qrSize * qrSize];
            qrBitmap.getPixels(pixels, 0, qrSize, 0, 0, qrSize, qrSize);
            for (int row = 0; row < qrSize; row++) {
                for (int col = 0; col < byteWidth; col++) {
                    byte b = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int px = col * 8 + bit;
                        if (px < qrSize) {
                            int pixel = pixels[row * qrSize + px];
                            int gray = (((pixel >> 16) & 0xFF) + ((pixel >> 8) & 0xFF) + (pixel & 0xFF)) / 3;
                            if (gray < 128) { b |= (1 << (7 - bit)); }
                        }
                    }
                    imageData[row * byteWidth + col] = b;
                }
            }
            // GS v 0位图指令
            byte[] gsCmd = new byte[8];
            gsCmd[0] = 0x1D; gsCmd[1] = 0x76; gsCmd[2] = 0x30; gsCmd[3] = 0x00;
            gsCmd[4] = (byte)(byteWidth & 0xFF); gsCmd[5] = (byte)((byteWidth >> 8) & 0xFF);
            gsCmd[6] = (byte)(qrSize & 0xFF); gsCmd[7] = (byte)((qrSize >> 8) & 0xFF);
            parts.add(gsCmd);
            parts.add(imageData);
            parts.add(new byte[]{0x1B, 0x64, 0x01}); // 走纸1行
            // 文字部分
            parts.add(new byte[]{0x1D, 0x21, 0x11}); // 倍宽倍高
            if (nameBytes != null) { parts.add(nameBytes); parts.add(new byte[]{0x0A}); }
            parts.add(new byte[]{0x1D, 0x21, 0x00}); // 恢复正常
            parts.add(footer); parts.add(new byte[]{0x0A});
            parts.add(new byte[]{0x1B, 0x64, 0x02}); // 走纸2行
            int total = 0;
            for (byte[] p : parts) total += p.length;
            byte[] result = new byte[total];
            int pos = 0;
            for (byte[] p : parts) { System.arraycopy(p, 0, result, pos, p.length); pos += p.length; }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "buildEscPosQR error", e);
            return new byte[]{0x1B, 0x40, 0x0A, 0x0A, 0x0A};
        }
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
                if (!isConnected()) return err("not connected");
                byte[] qrData = buildEscPosQR(url, shopName);

                if (isBleConnection) {
                    bleWriteData(qrData);
                } else {
                    btOut.write(qrData);
                    btOut.flush();
                }

                Thread.sleep(1000);
                Log.d(TAG, "printQR sent " + qrData.length + " bytes");
                JSONObject res = new JSONObject();
                res.put("code", 0);
                res.put("msg", "ok");
                res.put("bytes", qrData.length);
                return res.toString();
            } catch (Exception e) {
                Log.e(TAG, "printQR error", e);
                return err(e.getMessage());
            }
        }
        @JavascriptInterface public String printText(String text) {
            try {
                if (!isConnected()) return err("not connected");
                StringBuilder debug = new StringBuilder();
                debug.append("isBle=").append(isBleConnection).append(";");
                debug.append("btGatt=").append(btGatt != null).append(";");
                debug.append("btWriteChar=").append(btWriteChar != null).append(";");
                debug.append("btSocket=").append(btSocket != null).append(";");
                debug.append("连接方式=").append(connectMethod != null ? connectMethod : "unknown").append(";");
                // 获取设备UUID
                try {
                    android.os.Parcelable[] uuids = btSocket.getRemoteDevice().getUuids();
                    if (uuids != null) {
                        StringBuilder ub = new StringBuilder();
                        for (android.os.Parcelable u : uuids) {
                            ub.append(((android.os.ParcelUuid) u).getUuid().toString()).append(";");
                        }
                        debug.append("UUIDs=").append(ub.toString()).append(";");
                    }
                } catch (Exception e) {
                    debug.append("UUID获取失败:").append(e.getMessage()).append(";");
                }

                // 构建打印数据: 纯文字GBK编码 + ESC@初始化
                java.util.List<byte[]> parts = new ArrayList<>();
                parts.add(new byte[]{0x1B, 0x40}); // ESC @ 初始化（关键！）
                parts.add(new byte[]{0x1B, 0x61, 0x01}); // 居中
                // 设置字符大小（倍宽倍高）
                parts.add(new byte[]{0x1D, 0x21, 0x11});
                byte[] textBytes = text.getBytes("GBK");
                parts.add(textBytes);
                parts.add(new byte[]{0x0A}); // 换行
                parts.add(new byte[]{0x1D, 0x21, 0x00}); // 恢复正常大小
                parts.add(new byte[]{0x1B, 0x64, 0x02}); // 走纸2行
                int total = 0;
                for (byte[] p : parts) total += p.length;
                byte[] data = new byte[total];
                int pos = 0;
                for (byte[] p : parts) { System.arraycopy(p, 0, data, pos, p.length); pos += p.length; }
                debug.append("纯文字GBK=").append(data.length).append("字节;");

                if (isBleConnection && btGatt != null && btWriteChar != null) {
                    // BLE写入
                    bleWriteData(data);
                    debug.append("BLE写入").append(data.length).append("字节;");
                } else if (btOut != null) {
                    // 经典蓝牙写入
                    btOut.write(data);
                    btOut.flush();
                    debug.append("GSv0位图写入").append(data.length).append("字节;");
                    
                    // 读取打印机返回数据
                    try {
                        java.io.InputStream in = btSocket.getInputStream();
                        int available = in.available();
                        debug.append("可读字节=").append(available).append(";");
                        if (available > 0) {
                            byte[] resp = new byte[available];
                            int read = in.read(resp);
                            debug.append("读取=").append(read).append("字节;");
                            StringBuilder hex = new StringBuilder();
                            for (int i = 0; i < Math.min(read, 20); i++) {
                                hex.append(String.format("%02X ", resp[i]));
                            }
                            debug.append("返回=").append(hex.toString()).append(";");
                        }
                    } catch (Exception e) {
                        debug.append("读取失败:").append(e.getMessage()).append(";");
                    }
                } else {
                    debug.append("无可用输出通道;");
                }

                Thread.sleep(500);

                JSONObject res = new JSONObject();
                res.put("code", 0);
                res.put("msg", "ok");
                res.put("debug", debug.toString());
                return res.toString();
            } catch (Exception e) {
                Log.e(TAG, "printText error", e);
                return err(e.getMessage());
            }
        }
        @JavascriptInterface public boolean isConnected() { return (isBleConnection && btGatt != null && btWriteChar != null) || (btSocket != null && btSocket.isConnected() && btOut != null); }
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
