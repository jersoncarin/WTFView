package com.jersnet.wtfview.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import java.io.IOException;

public class UsbMaskConnection {
    private static final String TAG = "UsbMaskConnection";
    private final byte[] magicPacket = "RMVT".getBytes();
    private UsbDeviceConnection usbConnection;
    private UsbDevice device;
    private UsbInterface videoInterface;
    public AndroidUSBInputStream mInputStream;
    public AndroidUSBOutputStream mOutputStream;
    private boolean ready = false;

    public UsbMaskConnection() {}

    public boolean setUsbDevice(UsbDeviceConnection conn, UsbDevice dev) {
        this.usbConnection = conn;
        this.device = dev;

        if (dev.getInterfaceCount() <= 3) {
            Log.e(TAG, "Device does not have interface 3");
            return false;
        }

        videoInterface = dev.getInterface(3);
        usbConnection.claimInterface(videoInterface, true);

        UsbEndpoint inEp = null;
        UsbEndpoint outEp = null;
        for (int i = 0; i < videoInterface.getEndpointCount(); i++) {
            UsbEndpoint ep = videoInterface.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                inEp = ep;
            } else {
                outEp = ep;
            }
        }

        if (inEp == null || outEp == null) {
            Log.e(TAG, "Failed to find IN/OUT endpoints on interface 3");
            return false;
        }

        mOutputStream = new AndroidUSBOutputStream(outEp, usbConnection);
        mInputStream = new AndroidUSBInputStream(inEp, outEp, usbConnection);
        ready = true;
        Log.i(TAG, "Interface 3 claimed successfully for video!");
        return true;
    }

    public void start() {
        if (mOutputStream != null) {
            try {
                mOutputStream.write(magicPacket);
            } catch (IOException e) {
                Log.e(TAG, "Failed to send RMVT", e);
            }
        }
    }

    public void stop() {
        ready = false;
        try {
            if (mInputStream != null) mInputStream.close();
            if (mOutputStream != null) mOutputStream.close();
        } catch (IOException ignored) {}

        if (usbConnection != null && videoInterface != null) {
            usbConnection.releaseInterface(videoInterface);
        }
    }

    public boolean isReady() {
        return ready;
    }
}
