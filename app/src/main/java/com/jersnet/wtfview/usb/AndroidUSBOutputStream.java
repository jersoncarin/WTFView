package com.jersnet.wtfview.usb;

import java.io.IOException;
import java.io.OutputStream;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

public class AndroidUSBOutputStream extends OutputStream {

    private static final int TIMEOUT = 2000;
    private final UsbDeviceConnection connection;
    private final UsbEndpoint sendEndpoint;

    public AndroidUSBOutputStream(UsbEndpoint sendEndpoint, UsbDeviceConnection connection) {
        this.connection = connection;
        this.sendEndpoint = sendEndpoint;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b});
    }

    @Override
    public void write(byte[] b) throws IOException {
        if (connection != null && sendEndpoint != null) {
            connection.bulkTransfer(sendEndpoint, b, b.length, TIMEOUT);
        }
    }
}
