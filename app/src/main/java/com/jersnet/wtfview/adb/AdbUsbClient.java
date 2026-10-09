package com.jersnet.wtfview.adb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import com.jersnet.wtfview.FileLogger;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

public class AdbUsbClient {
    private static final String TAG = "AdbUsbClient";

    public static final int A_CNXN = 0x4e584e43;
    public static final int A_OPEN = 0x4e45504f;
    public static final int A_OKAY = 0x59414b4f;
    public static final int A_CLSE = 0x45534c43;
    public static final int A_WRTE = 0x45545257;

    public static final int A_VERSION = 0x01000000;
    public static final int MAX_DATA = 4096;

    private final UsbDeviceConnection connection;
    private final UsbInterface adbInterface;
    private final UsbEndpoint inEndpoint;
    private final UsbEndpoint outEndpoint;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean streamRunning = new AtomicBoolean(false);
    private int activeLocalId = 0;
    private int activeRemoteId = 0;
    private int nextLocalId = 1;
    private Thread streamThread;
    private final byte[] readHeaderBuffer = new byte[24];
    private final byte[] sendHeaderBuffer = new byte[24];

    public interface StreamCallback {
        void onData(byte[] data, int offset, int length);
        void onClose();
    }

    public static AdbUsbClient findAndCreate(UsbDeviceConnection conn, UsbDevice device) {
        if (conn == null || device == null) return null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == 0xFF &&
                iface.getInterfaceSubclass() == 0x42 &&
                iface.getInterfaceProtocol() == 0x01) {

                boolean claimed = conn.claimInterface(iface, true);
                FileLogger.log(TAG, "Claimed ADB interface " + i + ": " + claimed);
                if (!claimed) {
                    return null;
                }

                UsbEndpoint in = null, out = null;
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        in = ep;
                    } else {
                        out = ep;
                    }
                }
                if (in != null && out != null) {
                    FileLogger.log(TAG, String.format("Found ADB endpoints: IN=0x%02X, OUT=0x%02X",
                            in.getAddress(), out.getAddress()));
                    return new AdbUsbClient(conn, iface, in, out);
                }
            }
        }
        FileLogger.log(TAG, "No ADB interface matching 0xFF/0x42/0x01 found");
        return null;
    }

    public AdbUsbClient(UsbDeviceConnection conn, UsbInterface iface, UsbEndpoint in, UsbEndpoint out) {
        this.connection = conn;
        this.adbInterface = iface;
        this.inEndpoint = in;
        this.outEndpoint = out;
    }

    public boolean connect() {
        FileLogger.log(TAG, "Flushing stale ADB packets and sending CNXN...");
        byte[] drain = new byte[4096];
        int drainedCount = 0;
        while (connection.bulkTransfer(inEndpoint, drain, drain.length, 30) > 0) {
            drainedCount++;
            if (drainedCount > 50) break;
        }
        if (drainedCount > 0) {
            FileLogger.log(TAG, "Drained " + drainedCount + " stale packets from USB endpoint");
        }

        byte[] hostPayload = "host::wtfview\0".getBytes();
        sendPacket(A_CNXN, A_VERSION, MAX_DATA, hostPayload);

        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            AdbMessage resp = readPacket(1000);
            if (resp == null) continue;

            if (resp.command == A_CNXN) {
                String devInfo = (resp.data != null) ? new String(resp.data) : "";
                FileLogger.log(TAG, "ADB handshake successful: " + devInfo);
                running.set(true);
                return true;
            } else if (resp.command == A_WRTE) {
                FileLogger.log(TAG, "Handling stale WRTE during handshake (remoteId=" + resp.arg0 + ")");
                sendPacket(A_OKAY, resp.arg1, resp.arg0, null);
                sendPacket(A_CLSE, resp.arg1, resp.arg0, null);
            } else {
                FileLogger.log(TAG, String.format("Skipping packet during handshake: cmd=0x%08X", resp.command));
            }
        }
        FileLogger.log(TAG, "ADB handshake timed out");
        return false;
    }

    public synchronized void startCommandStream(String command, StreamCallback callback) {
        stopStream();

        streamRunning.set(true);
        streamThread = new Thread(() -> {
            int localId = nextLocalId++;
            if (nextLocalId > 0x70000000) nextLocalId = 1;
            String serviceCmd = (command.startsWith("shell:") || command.startsWith("exec:")) ? command : "exec:" + command;
            FileLogger.log(TAG, "Opening ADB stream: " + serviceCmd + " (localId=" + localId + ")");
            byte[] cmdBytes = (serviceCmd + "\0").getBytes();
            sendPacket(A_OPEN, localId, 0, cmdBytes);

            AdbMessage resp = null;
            long deadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < deadline && streamRunning.get()) {
                AdbMessage msg = readPacket(1000);
                if (msg == null) continue;
                if (msg.command == A_OKAY && msg.arg1 == localId) {
                    resp = msg;
                    break;
                } else if (msg.command == A_WRTE) {
                    sendPacket(A_OKAY, msg.arg1, msg.arg0, null);
                    sendPacket(A_CLSE, msg.arg1, msg.arg0, null);
                } else if (msg.command == A_CLSE) {
                    FileLogger.log(TAG, "Drained stale CLSE while opening stream");
                } else {
                    FileLogger.log(TAG, String.format("Skipping packet waiting for OPEN ACK: cmd=0x%08X", msg.command));
                }
            }

            if (resp == null) {
                FileLogger.log(TAG, "Failed to open stream for command (timeout or no ACK): " + serviceCmd);
                if (callback != null) callback.onClose();
                return;
            }

            int remoteId = resp.arg0;
            activeLocalId = localId;
            activeRemoteId = remoteId;
            FileLogger.log(TAG, "Opened ADB stream remoteId=" + remoteId + ", localId=" + localId);

            long totalBytes = 0;
            long lastLogTime = System.currentTimeMillis();

            while (running.get() && streamRunning.get()) {
                AdbMessage msg = readPacket(500);
                if (msg == null) continue;

                if (msg.command == A_WRTE && msg.arg0 == remoteId) {

                    sendPacket(A_OKAY, localId, remoteId, null);

                    if (callback != null && msg.data != null && msg.data.length > 0) {
                        callback.onData(msg.data, 0, msg.data.length);
                        if (totalBytes == 0) {
                            FileLogger.log(TAG, "First ADB packet received from goggles! (" + msg.data.length + " bytes)");
                        }
                        totalBytes += msg.data.length;
                    }

                    if (System.currentTimeMillis() - lastLogTime > 5000) {
                        FileLogger.log(TAG, "ADB stream active, received total bytes: " + totalBytes);
                        lastLogTime = System.currentTimeMillis();
                    }
                } else if (msg.command == A_CLSE && msg.arg0 == remoteId) {
                    FileLogger.log(TAG, "Remote closed ADB stream");
                    break;
                }
            }

            if (callback != null) callback.onClose();
        }, "AdbStreamThread");
        streamThread.start();
    }

    public synchronized boolean pushFile(String remotePath, byte[] content, int timeoutMs) {
        if (!running.get() || content == null) return false;
        int localId = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        String serviceCmd = "exec:cat > " + remotePath;
        FileLogger.log(TAG, "pushFile: " + serviceCmd + " (" + content.length + " bytes)");
        byte[] cmdBytes = (serviceCmd + "\0").getBytes();
        sendPacket(A_OPEN, localId, 0, cmdBytes);

        AdbMessage resp = readPacket(timeoutMs);
        if (resp == null || resp.command != A_OKAY) {
            FileLogger.log(TAG, "pushFile failed to open remote stream");
            return false;
        }

        int remoteId = resp.arg0;
        int offset = 0;
        while (offset < content.length) {
            int chunkLen = Math.min(content.length - offset, 4096);
            byte[] chunk = new byte[chunkLen];
            System.arraycopy(content, offset, chunk, 0, chunkLen);
            sendPacket(A_WRTE, localId, remoteId, chunk);
            offset += chunkLen;

            AdbMessage ack = readPacket(timeoutMs);
            if (ack == null || ack.command != A_OKAY) {
                FileLogger.log(TAG, "pushFile write ACK failed at offset " + offset);
                break;
            }
        }

        sendPacket(A_CLSE, localId, remoteId, null);
        readPacket(1000);
        return true;
    }

    public synchronized byte[] executeCommandGetOutput(String command, int timeoutMs) {
        if (!running.get()) return null;
        int localId = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        String serviceCmd = (command.startsWith("shell:") || command.startsWith("exec:")) ? command : "shell:" + command;
        FileLogger.log(TAG, "executeCommandGetOutput: " + serviceCmd);
        byte[] cmdBytes = (serviceCmd + "\0").getBytes();
        sendPacket(A_OPEN, localId, 0, cmdBytes);

        AdbMessage resp = null;
        long openDeadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < openDeadline) {
            AdbMessage msg = readPacket(500);
            if (msg == null) continue;
            if (msg.command == A_OKAY && msg.arg1 == localId) {
                resp = msg;
                break;
            } else if (msg.command == A_WRTE) {
                sendPacket(A_OKAY, msg.arg1, msg.arg0, null);
                sendPacket(A_CLSE, msg.arg1, msg.arg0, null);
            } else if (msg.command == A_CLSE) {
                FileLogger.log(TAG, "Drained stale CLSE in executeCommandGetOutput");
            }
        }

        if (resp == null) {
            FileLogger.log(TAG, "executeCommandGetOutput failed to open: " + serviceCmd);
            return null;
        }

        int remoteId = resp.arg0;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            AdbMessage msg = readPacket(1000);
            if (msg == null) continue;
            if (msg.command == A_WRTE && msg.arg0 == remoteId) {
                if (msg.data != null && msg.data.length > 0) {
                    baos.write(msg.data, 0, msg.data.length);
                }
                sendPacket(A_OKAY, localId, remoteId, null);
                deadline = System.currentTimeMillis() + 3000;
            } else if (msg.command == A_CLSE && msg.arg0 == remoteId) {
                break;
            }
        }
        sendPacket(A_CLSE, localId, remoteId, null);
        return baos.toByteArray();
    }

    private synchronized void sendPacket(int cmd, int arg0, int arg1, byte[] payload) {
        int len = (payload != null) ? payload.length : 0;
        int crc = 0;
        if (payload != null) {
            for (byte b : payload) crc += (b & 0xFF);
        }

        putInt32LE(sendHeaderBuffer, 0, cmd);
        putInt32LE(sendHeaderBuffer, 4, arg0);
        putInt32LE(sendHeaderBuffer, 8, arg1);
        putInt32LE(sendHeaderBuffer, 12, len);
        putInt32LE(sendHeaderBuffer, 16, crc);
        putInt32LE(sendHeaderBuffer, 20, cmd ^ 0xFFFFFFFF);

        connection.bulkTransfer(outEndpoint, sendHeaderBuffer, 24, 2000);
        if (len > 0) {
            connection.bulkTransfer(outEndpoint, payload, len, 2000);
        }
    }

    private synchronized AdbMessage readPacket(int timeoutMs) {
        int read = connection.bulkTransfer(inEndpoint, readHeaderBuffer, 24, timeoutMs);
        if (read < 24) return null;

        int cmd = readInt32LE(readHeaderBuffer, 0);
        int arg0 = readInt32LE(readHeaderBuffer, 4);
        int arg1 = readInt32LE(readHeaderBuffer, 8);
        int len = readInt32LE(readHeaderBuffer, 12);
        int crc = readInt32LE(readHeaderBuffer, 16);
        int magic = readInt32LE(readHeaderBuffer, 20);

        if (magic != (cmd ^ 0xFFFFFFFF)) {
            FileLogger.log(TAG, "ADB packet magic mismatch: cmd=" + cmd + ", magic=" + magic);
            return null;
        }

        byte[] payload = null;
        if (len > 0) {
            payload = new byte[len];
            int totalRead = 0;
            while (totalRead < len) {
                int r = connection.bulkTransfer(inEndpoint, payload, totalRead, len - totalRead, 2000);
                if (r <= 0) break;
                totalRead += r;
            }
        }
        return new AdbMessage(cmd, arg0, arg1, payload);
    }

    private static void putInt32LE(byte[] b, int offset, int val) {
        b[offset]     = (byte) (val & 0xFF);
        b[offset + 1] = (byte) ((val >> 8) & 0xFF);
        b[offset + 2] = (byte) ((val >> 16) & 0xFF);
        b[offset + 3] = (byte) ((val >> 24) & 0xFF);
    }

    private static int readInt32LE(byte[] b, int offset) {
        return (b[offset] & 0xFF)
                | ((b[offset + 1] & 0xFF) << 8)
                | ((b[offset + 2] & 0xFF) << 16)
                | ((b[offset + 3] & 0xFF) << 24);
    }

    public synchronized void stopStream() {
        FileLogger.log(TAG, "Stopping active ADB command stream");
        streamRunning.set(false);
        if (activeLocalId != 0 && activeRemoteId != 0) {
            try {
                sendPacket(A_CLSE, activeLocalId, activeRemoteId, null);
            } catch (Exception ignored) {}
            activeLocalId = 0;
            activeRemoteId = 0;
        }
        if (streamThread != null && streamThread.isAlive()) {
            try {
                streamThread.join(800);
            } catch (InterruptedException ignored) {}
            streamThread = null;
        }
    }

    public void stop() {
        stopStream();
        running.set(false);
        try {
            if (connection != null && adbInterface != null) {
                connection.releaseInterface(adbInterface);
            }
        } catch (Exception ignored) {}
    }

    public static class AdbMessage {
        public final int command;
        public final int arg0;
        public final int arg1;
        public final byte[] data;

        public AdbMessage(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }
}
