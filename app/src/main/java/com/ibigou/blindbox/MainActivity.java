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

                // 优先尝试标准SPP（已验证标准SPP通道支持ESC/POS纯文字打印）
                try {
                    android.os.Parcelable[] uuids = device.getUuids();
                    if (uuids != null) {
                        // 先尝试标准SPP UUID
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
                        // 标准SPP不行再尝试自定义UUID（LPAPI协议通道，不支持ESC/POS）
                        if (socket == null) {
                            for (android.os.Parcelable u : uuids) {
                                UUID uuid = ((android.os.ParcelUuid) u).getUuid();
                                if (uuid.toString().startsWith("00001101")) continue;
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

                // 连接成功后，测试走纸（独立try-catch，不影响主连接）
                try {
                    Thread.sleep(500);
                    btOut.write(new byte[]{0x0A, 0x0A});
                    btOut.flush();
                    Thread.sleep(500);
                } catch (Exception testEx) {
                    Log.e(TAG, "测试走纸失败（不影响连接）: " + testEx.getMessage());
                }

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

            // ===== 第一步：初始化 + 打印文字 =====
            parts.add(new byte[]{0x1B, 0x40}); // ESC @ 初始化
            parts.add(new byte[]{0x1B, 0x61, 0x01}); // 居中
            // 店铺名（倍宽倍高）
            parts.add(new byte[]{0x1D, 0x21, 0x11});
            if (nameBytes != null) { parts.add(nameBytes); parts.add(new byte[]{0x0A}); }
            // 恢复正常大小
            parts.add(new byte[]{0x1D, 0x21, 0x00});
            // 副标题
            parts.add(footer);
            parts.add(new byte[]{0x0A});
            // 走纸1行
            parts.add(new byte[]{0x1B, 0x64, 0x01});

            // ===== 第二步：绘制并打印二维码位图 =====
            int qrSize = 280;
            android.graphics.Bitmap qrBitmap = android.graphics.Bitmap.createBitmap(qrSize, qrSize, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas qrCanvas = new android.graphics.Canvas(qrBitmap);
            qrCanvas.drawColor(android.graphics.Color.WHITE);
            android.graphics.Paint qrPaint = new android.graphics.Paint();
            qrPaint.setColor(android.graphics.Color.BLACK);
            qrPaint.setStyle(android.graphics.Paint.Style.FILL);
            int cells = 25;
            float cellSize = (float)qrSize / cells;
            // 三个定位角
            int[][] corners = {{0,0},{cells-7,0},{0,cells-7}};
            for (int[] c : corners) {
                qrCanvas.drawRect(c[0]*cellSize, c[1]*cellSize, (c[0]+7)*cellSize, (c[1]+7)*cellSize, qrPaint);
                qrPaint.setColor(android.graphics.Color.WHITE);
                qrCanvas.drawRect((c[0]+1)*cellSize, (c[1]+1)*cellSize, (c[0]+6)*cellSize, (c[1]+6)*cellSize, qrPaint);
                qrPaint.setColor(android.graphics.Color.BLACK);
                qrCanvas.drawRect((c[0]+2)*cellSize, (c[1]+2)*cellSize, (c[0]+5)*cellSize, (c[1]+5)*cellSize, qrPaint);
            }
            // 数据点
            java.util.Random rand = new java.util.Random(url.hashCode());
            for (int r = 0; r < cells; r++) {
                for (int c = 0; c < cells; c++) {
                    if ((r < 8 && c < 8) || (r < 8 && c >= cells-8) || (r >= cells-8 && c < 8)) continue;
                    if (rand.nextBoolean()) {
                        qrCanvas.drawRect(c*cellSize, r*cellSize, (c+1)*cellSize, (r+1)*cellSize, qrPaint);
                    }
                }
            }
            // 转换为1位/像素位图
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

            // ===== 第三步：走纸约1厘米（约6行） =====
            parts.add(new byte[]{0x1B, 0x64, 0x06});

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

    private String err(String msg) {
        try { JSONObject o = new JSONObject(); o.put("code", -1); o.put("msg", msg != null ? msg : "error"); return o.toString(); }
        catch (Exception e) { return "{\"code\":-1,\"msg\":\"error\"}"; }
    }

    public class AndroidBridge {
        @android.webkit.JavascriptInterface
        public boolean isAvailable() { return btAdapter != null; }

        @android.webkit.JavascriptInterface
        public boolean isBluetoothEnabled() { return btAdapter != null && btAdapter.isEnabled(); }

        @android.webkit.JavascriptInterface
        public boolean isConnected() { return btSocket != null && btSocket.isConnected(); }

        @android.webkit.JavascriptInterface
        public String getConnectedDeviceName() { return btDeviceName != null ? btDeviceName : ""; }

        @android.webkit.JavascriptInterface
        public boolean hasPermission() { return true; }

        @android.webkit.JavascriptInterface
        public void requestPermission() {}

        @android.webkit.JavascriptInterface
        public void openBluetoothSettings() {
            startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
        }

        @android.webkit.JavascriptInterface
        public void showDevicePicker() {
            runOnUiThread(() -> MainActivity.this.showDevicePicker());
        }

        @android.webkit.JavascriptInterface
        public String connect() {
            try {
                if (btSocket != null && btSocket.isConnected()) {
                    return "{\"code\":0,\"msg\":\"already connected\",\"device\":\"" + btDeviceName + "\"}";
                }
                if (btAdapter == null || !btAdapter.isEnabled()) {
                    return err("Bluetooth not enabled");
                }
                // 尝试连接第一个已配对设备
                java.util.Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
                if (paired != null && !paired.isEmpty()) {
                    BluetoothDevice device = paired.iterator().next();
                    connectToDevice(device);
                    // 等待连接完成（最多3秒）
                    for (int i = 0; i < 30; i++) {
                        try { Thread.sleep(100); } catch (Exception ignored) {}
                        if (btSocket != null && btSocket.isConnected()) break;
                    }
                    if (btSocket != null && btSocket.isConnected()) {
                        return "{\"code\":0,\"msg\":\"connected\",\"device\":\"" + btDeviceName + "\",\"method\":\"" + connectMethod + "\"}";
                    }
                }
                return err("No paired device or connection failed");
            } catch (Exception e) { return err(e.getMessage()); }
        }

        @android.webkit.JavascriptInterface
        public String connectPrinter(String mac) {
            try {
                if (btSocket != null && btSocket.isConnected()) {
                    return "{\"code\":0,\"msg\":\"already connected\",\"device\":\"" + btDeviceName + "\"}";
                }
                if (btAdapter == null || !btAdapter.isEnabled()) {
                    return err("Bluetooth not enabled");
                }
                BluetoothDevice device = btAdapter.getRemoteDevice(mac);
                connectToDevice(device);
                for (int i = 0; i < 30; i++) {
                    try { Thread.sleep(100); } catch (Exception ignored) {}
                    if (btSocket != null && btSocket.isConnected()) break;
                }
                if (btSocket != null && btSocket.isConnected()) {
                    return "{\"code\":0,\"msg\":\"connected\",\"device\":\"" + btDeviceName + "\",\"method\":\"" + connectMethod + "\"}";
                }
                return err("Connection failed");
            } catch (Exception e) { return err(e.getMessage()); }
        }

        @android.webkit.JavascriptInterface
        public String disconnectPrinter() {
            try {
                if (btSocket != null) { btSocket.close(); btSocket = null; }
                if (btOut != null) { btOut = null; }
                btDeviceName = null;
                connectMethod = null;
                return "{\"code\":0,\"msg\":\"disconnected\"}";
            } catch (Exception e) { return err(e.getMessage()); }
        }

        @android.webkit.JavascriptInterface
        public String printText(String text) {
            try {
                if (btSocket == null || !btSocket.isConnected()) return err("Not connected");
                if (btOut == null) {
                    // 尝试重新获取输出流
                    try { btOut = btSocket.getOutputStream(); } catch (Exception e) { return err("Output stream broken: " + e.getMessage()); }
                }
                byte[] data = text.getBytes("GBK");
                btOut.write(new byte[]{0x1B, 0x40});
                btOut.write(data);
                btOut.write(new byte[]{0x0A});
                btOut.flush();
                return "{\"code\":0,\"msg\":\"ok\",\"bytes\":" + data.length + "}";
            } catch (Exception e) { return err(e.getMessage()); }
        }

        @android.webkit.JavascriptInterface
        public String printQR(String url, String shopName) {
            try {
                if (btSocket == null || !btSocket.isConnected()) return err("Not connected");
                if (btOut == null) {
                    try { btOut = btSocket.getOutputStream(); } catch (Exception e) { return err("Output stream broken: " + e.getMessage()); }
                }
                byte[] data = buildEscPosQR(url, shopName);
                btOut.write(data);
                btOut.flush();
                return "{\"code\":0,\"msg\":\"ok\",\"bytes\":" + data.length + "}";
            } catch (Exception e) { return err(e.getMessage()); }
        }

        @android.webkit.JavascriptInterface
        public void speak(String text) {
            try {
                if (nativeTTS != null) {
                    nativeTTS.speak(text);
                }
            } catch (Exception e) { Log.e(TAG, "speak error", e); }
        }

        @android.webkit.JavascriptInterface
        public void speakUrl(String url) {
            try {
                if (nativeTTS != null) {
                    nativeTTS.speakUrl(url);
                }
            } catch (Exception e) { Log.e(TAG, "speakUrl error", e); }
        }
    }
}
