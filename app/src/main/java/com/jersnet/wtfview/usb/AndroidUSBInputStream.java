package com.jersnet.wtfview.usb;

import java.io.IOException;
import java.io.InputStream;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

import com.jersnet.wtfview.FileLogger;

public class AndroidUSBInputStream extends InputStream {
    private static final String TAG = "USBInputStream";
    private static final int READ_TIMEOUT = 50;

    private final UsbDeviceConnection usbConnection;
    private final UsbEndpoint receiveEndPoint;
    private final UsbEndpoint sendEndPoint;

    private volatile boolean closed = false;
    private long totalBytesRead = 0;
    private long emptyCount = 0;
    private long lastLogTime = System.currentTimeMillis();
    private final byte[] singleByteBuffer = new byte[1];

    public AndroidUSBInputStream(UsbEndpoint readEndpoint, UsbEndpoint sendEndpoint, UsbDeviceConnection connection) {
        this.usbConnection = connection;
        this.receiveEndPoint = readEndpoint;
        this.sendEndPoint = sendEndpoint;
    }

    @Override
    public int read() throws IOException {
        int r = read(singleByteBuffer, 0, 1);
        if (r <= 0) return -1;
        return singleByteBuffer[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (closed || usbConnection == null || receiveEndPoint == null) return -1;
        if (buffer == null) throw new NullPointerException("buffer is null");
        if (offset < 0 || length < 0 || offset + length > buffer.length) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) return 0;

        int receivedBytes = 0;

        while (!closed && receivedBytes <= 0) {

            receivedBytes = usbConnection.bulkTransfer(receiveEndPoint, buffer, offset, length, READ_TIMEOUT);
            if (receivedBytes > 0) {
                break;
            }

            emptyCount++;

            if (closed) return -1;

            try {

                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        if (receivedBytes > 0) {
            totalBytesRead += receivedBytes;
        }

        long now = System.currentTimeMillis();
        if (now - lastLogTime > 10000) {
            FileLogger.log(TAG, String.format("Video I/O: totalBytes=%.2f MB, emptyEvents=%d, lastRead=%d bytes",
                    totalBytesRead / (1024.0 * 1024.0), emptyCount, receivedBytes));
            lastLogTime = now;
        }

        return receivedBytes;
    }

    @Override
    public void close() throws IOException {
        closed = true;
    }
}
