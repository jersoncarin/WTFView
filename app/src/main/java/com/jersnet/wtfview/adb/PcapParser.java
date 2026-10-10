package com.jersnet.wtfview.adb;

import com.jersnet.wtfview.FileLogger;

public class PcapParser {
    private static final String TAG = "PcapParser";

    public interface PacketListener {
        void onTelemetryPacket(int temp, float voltage, String fcVariant);
        void onCompressedOsdPacket(byte[] payload, int offset, int length);
        void onGogglesTelemetryPacket(int temp, float voltage);
    }

    private static final int MODE_DETECT = 0;
    private static final int MODE_WTFV   = 1;
    private static final int MODE_PCAP   = 2;

    private static final int INITIAL_BUFFER_CAPACITY = 65536;

    private final PacketListener listener;
    private byte[] buffer = new byte[INITIAL_BUFFER_CAPACITY];
    private int bufferLength = 0;

    private int streamMode = MODE_DETECT;
    private boolean headerParsed = false;
    private long totalPktCount = 0;
    private long lastLogTime = System.currentTimeMillis();
    private String lastFc = "BTFL";

    public PcapParser(PacketListener listener) {
        this.listener = listener;
    }

    public synchronized void reset() {
        bufferLength = 0;
        streamMode = MODE_DETECT;
        headerParsed = false;
        totalPktCount = 0;
        lastFc = "BTFL";
        FileLogger.log(TAG, "PcapParser reset");
    }

    public synchronized void feedData(byte[] data, int offset, int length) {
        if (data == null || length <= 0) return;

        if (bufferLength + length > buffer.length) {
            int newCap = Math.max(buffer.length * 2, bufferLength + length);
            byte[] newBuf = new byte[newCap];
            System.arraycopy(buffer, 0, newBuf, 0, bufferLength);
            buffer = newBuf;
        }

        System.arraycopy(data, offset, buffer, bufferLength, length);
        bufferLength += length;

        int pos = 0;

        if (streamMode == MODE_DETECT) {
            if (bufferLength < 4) return;

            int scanLimit = Math.min(bufferLength - 3, 2048);
            for (int i = 0; i < scanLimit; i++) {
                if (buffer[i] == 'W' && buffer[i + 1] == 'T' && buffer[i + 2] == 'F' && buffer[i + 3] == 'V') {
                    streamMode = MODE_WTFV;
                    pos = i;
                    FileLogger.log(TAG, "Stream detected: WTFV native micro-daemon! Offset: " + i);
                    break;
                }
                int magicLe = readInt32LE(buffer, i);
                if (magicLe == 0xA1B2C3D4 || magicLe == 0xD4C3B2A1 || magicLe == 0xA1B23C4D) {
                    streamMode = MODE_PCAP;
                    pos = i;
                    FileLogger.log(TAG, String.format("Stream detected: PCAP (magic: 0x%08X)! Offset: %d", magicLe, i));
                    break;
                }
            }

            if (streamMode == MODE_DETECT) {
                if (bufferLength > 4096) {

                    System.arraycopy(buffer, bufferLength - 4, buffer, 0, 4);
                    bufferLength = 4;
                }
                return;
            }
        }

        if (streamMode == MODE_WTFV) {

            while (pos + 8 <= bufferLength) {
                if (buffer[pos] != 'W' || buffer[pos + 1] != 'T' || buffer[pos + 2] != 'F' || buffer[pos + 3] != 'V') {
                    pos++;
                    continue;
                }

                int port = readUInt16LE(buffer, pos + 4);
                int payloadLen = readUInt16LE(buffer, pos + 6);

                if (payloadLen < 0 || payloadLen > 65535) {
                    pos += 4;
                    continue;
                }

                if (pos + 8 + payloadLen > bufferLength) {

                    break;
                }

                int payloadOffset = pos + 8;
                totalPktCount++;
                dispatchPayload(port, buffer, payloadOffset, payloadLen);
                pos = payloadOffset + payloadLen;

                logProgress("WTFV");
            }
        } else if (streamMode == MODE_PCAP) {
            if (!headerParsed) {
                if (pos + 24 > bufferLength) return;
                int magic = readInt32LE(buffer, pos);
                headerParsed = true;
                pos += 24;
                FileLogger.log(TAG, String.format("PCAP global header parsed! Magic: 0x%08X", magic));
            }

            while (pos + 16 <= bufferLength) {
                int caplen = readInt32LE(buffer, pos + 8);
                if (caplen < 0 || caplen > 65535) {
                    FileLogger.log(TAG, "PCAP frame out of sync, caplen=" + caplen + ", resetting buffer");
                    headerParsed = false;
                    streamMode = MODE_DETECT;
                    bufferLength = 0;
                    return;
                }

                if (pos + 16 + caplen > bufferLength) {
                    break;
                }

                int pktStart = pos + 16;
                totalPktCount++;
                parsePcapPacket(buffer, pktStart, caplen);
                pos = pktStart + caplen;

                logProgress("PCAP");
            }
        }

        int remaining = bufferLength - pos;
        if (remaining > 0 && pos > 0) {
            System.arraycopy(buffer, pos, buffer, 0, remaining);
        }
        bufferLength = remaining;
    }

    private void logProgress(String type) {
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 10000) {
            FileLogger.log(TAG, "Parsed " + type + " packets total: " + totalPktCount);
            lastLogTime = now;
        }
    }

    private void parsePcapPacket(byte[] data, int offset, int length) {
        if (length < 16 + 20 + 8) return;

        int ipOffset = offset + 16;
        int ipHeaderLen = (data[ipOffset] & 0x0F) * 4;
        int udpOffset = ipOffset + ipHeaderLen;

        if (udpOffset + 8 > offset + length) return;

        int dstPort = readUInt16BE(data, udpOffset + 2);
        int udpLen = readUInt16BE(data, udpOffset + 4);

        int payloadOffset = udpOffset + 8;
        int payloadLen = udpLen - 8;
        if (payloadOffset + payloadLen > offset + length || payloadLen <= 0) return;

        dispatchPayload(dstPort, data, payloadOffset, payloadLen);
    }

    private void dispatchPayload(int port, byte[] data, int payloadOffset, int payloadLen) {
        if (port == 7655) {

            if (payloadLen >= 6) {
                int temp = readUInt16LE(data, payloadOffset);
                int verSpec = readUInt16LE(data, payloadOffset + 2);
                int voltRaw = readUInt16LE(data, payloadOffset + 4);
                float voltage = voltRaw / 64.0f;

                String fc = lastFc;
                if (payloadLen >= 10) {
                    char c0 = (char) data[payloadOffset + 6];
                    char c1 = (char) data[payloadOffset + 7];
                    char c2 = (char) data[payloadOffset + 8];
                    char c3 = (char) data[payloadOffset + 9];
                    if (c0 > ' ' || c1 > ' ' || c2 > ' ' || c3 > ' ') {
                        fc = ("" + c0 + c1 + c2 + c3).trim();
                        lastFc = fc;
                    }
                }

                if (listener != null) {
                    listener.onTelemetryPacket(temp, voltage, fc);
                }
            }
        } else if (port == 7656) {
            if (payloadLen > 4 && listener != null) {
                listener.onCompressedOsdPacket(data, payloadOffset, payloadLen);
            }
        } else if (port == 7650) {
            if (payloadLen >= 4 && listener != null) {
                int mv = readInt32LE(data, payloadOffset);
                int temp = 0;
                if (payloadLen >= 8) {
                    temp = readInt32LE(data, payloadOffset + 4);
                }
                if (mv > 0 || temp > 0) {
                    listener.onGogglesTelemetryPacket(temp, mv / 1000.0f);
                }
            }
        }
    }

    private static int readUInt16LE(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
    }

    private static int readUInt16BE(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
    }

    private static int readInt32LE(byte[] b, int offset) {
        return (b[offset] & 0xFF)
                | ((b[offset + 1] & 0xFF) << 8)
                | ((b[offset + 2] & 0xFF) << 16)
                | ((b[offset + 3] & 0xFF) << 24);
    }
}
