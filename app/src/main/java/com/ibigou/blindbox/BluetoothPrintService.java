package com.ibigou.blindbox;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Classic Bluetooth SPP connection for ESC/POS thermal printers.
 */
public class BluetoothPrintService {
    private static final String TAG = "BtPrintService";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context ctx;
    private BluetoothSocket socket;
    private OutputStream out;
    private String connectedName;

    public BluetoothPrintService(Context ctx) {
        this.ctx = ctx;
    }

    public boolean isConnected() {
        return socket != null && out != null;
    }

    public String getConnectedDeviceName() {
        return connectedName;
    }

    public boolean connect(String address) {
        disconnect();
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null) return false;
            BluetoothAdapter ba = bm.getAdapter();
            if (ba == null || !ba.isEnabled()) return false;
            BluetoothDevice dev = ba.getRemoteDevice(address);
            socket = dev.createRfcommSocketToServiceRecord(SPP_UUID);
            ba.cancelDiscovery();
            socket.connect();
            out = socket.getOutputStream();
            connectedName = dev.getName();
            Log.i(TAG, "Connected to " + connectedName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "connect failed", e);
            disconnect();
            return false;
        }
    }

    public void disconnect() {
        try { if (out != null) { out.flush(); out.close(); } } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        out = null;
        socket = null;
        connectedName = null;
    }

    public boolean printQR(String url, String shopName) {
        if (!isConnected()) return false;
        try {
            out.write(buildEscPosQR(url, shopName));
            out.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "printQR failed", e);
            return false;
        }
    }

    public boolean printText(String text) {
        if (!isConnected()) return false;
        try {
            out.write(text.getBytes("GBK"));
            out.write(0x0A);
            out.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "printText failed", e);
            return false;
        }
    }

    // === ESC/POS QR command builder ===
    private byte[] buildEscPosQR(String url, String shopName) {
        byte[] urlBytes = url.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] nameBytes = (shopName != null && !shopName.isEmpty())
                ? shopName.getBytes(java.nio.charset.StandardCharsets.UTF_8) : null;
        byte[] footer = "\u626b\u7801\u5f00\u76f2\u76d2 \u00b7 \u5b9c\u5fc5\u8d2d"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        java.util.List<byte[]> parts = new java.util.ArrayList<>();
        parts.add(new byte[]{0x1B, 0x40});                                    // ESC @ init
        parts.add(new byte[]{0x1B, 0x61, 0x01});                               // ESC a center
        parts.add(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x31, 0x06}); // QR module size 6
        parts.add(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x32, 0x31}); // QR error level M
        int dl = urlBytes.length + 3;
        parts.add(new byte[]{0x1D, 0x28, 0x6B, (byte)(dl & 0xFF), (byte)((dl >> 8) & 0xFF), 0x31, 0x31, 0x30});
        parts.add(urlBytes);                                                   // QR data
        parts.add(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x32, 0x00});  // print QR
        parts.add(new byte[]{0x1B, 0x64, 0x02});                               // feed 2

        if (nameBytes != null) {
            parts.add(nameBytes);
            parts.add(new byte[]{0x0A});
        }
        parts.add(footer);
        parts.add(new byte[]{0x0A});
        parts.add(new byte[]{0x1B, 0x64, 0x03});                               // feed 3
        parts.add(new byte[]{0x1D, 0x56, 0x00});                               // cut paper

        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, result, pos, p.length);
            pos += p.length;
        }
        return result;
    }
}
