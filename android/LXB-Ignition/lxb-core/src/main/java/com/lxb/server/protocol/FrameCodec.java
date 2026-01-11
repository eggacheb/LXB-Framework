package com.lxb.server.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * LXB-Link 协议帧编解码器
 *
 * 帧格式 (大端序 / 网络字节序):
 * ┌─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┐
 * │  Magic  │ Version │   Seq   │   Cmd   │  Length │ Payload │  CRC32  │
 * │  2B     │  1B     │   4B    │   1B    │   2B    │  N B    │   4B    │
 * │ 0xAA55  │  0x01   │ uint32  │  uint8  │ uint16  │  bytes  │ uint32  │
 * └─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┘
 *
 * CRC32 计算范围: Header (10B) + Payload (N B)
 * CRC32 位置: 帧尾 (最后 4 字节)
 */
public class FrameCodec {

    // 协议常量
    public static final short MAGIC = (short) 0xAA55;
    public static final byte VERSION = 0x01;

    // 帧大小常量
    public static final int HEADER_SIZE = 10;       // Magic(2) + Version(1) + Seq(4) + Cmd(1) + Len(2)
    public static final int CRC_SIZE = 4;
    public static final int MIN_FRAME_SIZE = HEADER_SIZE + CRC_SIZE;  // 14 bytes
    public static final int MAX_PAYLOAD_SIZE = 65535;

    /**
     * 编码帧 (自动计算 CRC32)
     *
     * @param seq 序列号
     * @param cmd 命令号
     * @param payload 负载数据 (可为 null)
     * @return 完整帧字节数组
     */
    public static byte[] encode(int seq, byte cmd, byte[] payload) {
        if (payload == null) {
            payload = new byte[0];
        }

        int frameSize = HEADER_SIZE + payload.length + CRC_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(frameSize);
        buffer.order(ByteOrder.BIG_ENDIAN);  // 强制大端序

        // 写入帧头 (10 bytes)
        buffer.putShort(MAGIC);              // 0-1: Magic
        buffer.put(VERSION);                 // 2: Version
        buffer.putInt(seq);                  // 3-6: Sequence
        buffer.put(cmd);                     // 7: Command
        buffer.putShort((short) payload.length);  // 8-9: Payload Length

        // 写入 Payload
        buffer.put(payload);

        // 计算 CRC32 (覆盖 Header + Payload)
        CRC32 crc32 = new CRC32();
        crc32.update(buffer.array(), 0, HEADER_SIZE + payload.length);
        int crcValue = (int) crc32.getValue();

        // 写入 CRC32 (帧尾)
        buffer.putInt(crcValue);

        return buffer.array();
    }

    /**
     * 解码帧 (含 CRC32 验证)
     *
     * @param data 原始帧数据
     * @return 解码后的帧信息
     * @throws ProtocolException 协议错误
     * @throws CRCException CRC32 校验失败
     */
    public static DecodedFrame decode(byte[] data) throws ProtocolException, CRCException {
        // 1. 检查最小长度
        if (data.length < MIN_FRAME_SIZE) {
            throw new ProtocolException("Frame too short: " + data.length +
                    " bytes (minimum " + MIN_FRAME_SIZE + ")");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);  // 强制大端序

        // 2. 解析帧头
        short magic = buffer.getShort();
        if (magic != MAGIC) {
            throw new ProtocolException("Invalid magic: 0x" +
                    String.format("%04X", magic & 0xFFFF) +
                    " (expected 0xAA55)");
        }

        byte version = buffer.get();
        if (version != VERSION) {
            throw new ProtocolException("Invalid version: 0x" +
                    String.format("%02X", version) +
                    " (expected 0x01)");
        }

        int seq = buffer.getInt();
        byte cmd = buffer.get();
        int payloadLength = buffer.getShort() & 0xFFFF;

        // 3. 检查帧完整性
        int expectedSize = HEADER_SIZE + payloadLength + CRC_SIZE;
        if (data.length < expectedSize) {
            throw new ProtocolException("Frame truncated: expected " +
                    expectedSize + " bytes, got " + data.length);
        }

        // 4. 提取 Payload
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);

        // 5. 验证 CRC32
        int receivedCRC = buffer.getInt();

        CRC32 crc32 = new CRC32();
        crc32.update(data, 0, HEADER_SIZE + payloadLength);
        int calculatedCRC = (int) crc32.getValue();

        if (receivedCRC != calculatedCRC) {
            throw new CRCException(String.format(
                    "CRC mismatch: calculated=0x%08X, received=0x%08X",
                    calculatedCRC, receivedCRC));
        }

        return new DecodedFrame(version, seq, cmd, payload);
    }

    /**
     * 快速验证帧头魔数 (不做完整解码)
     *
     * @param data 帧数据
     * @return true 如果魔数有效
     */
    public static boolean validateMagic(byte[] data) {
        if (data.length < 2) return false;

        ByteBuffer buffer = ByteBuffer.wrap(data, 0, 2);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return buffer.getShort() == MAGIC;
    }

    /**
     * 获取帧信息 (不验证 CRC)
     *
     * @param data 帧数据
     * @return 帧信息
     * @throws ProtocolException 帧格式错误
     */
    public static FrameInfo getFrameInfo(byte[] data) throws ProtocolException {
        if (data.length < HEADER_SIZE) {
            throw new ProtocolException("Data too short for frame header");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);

        FrameInfo info = new FrameInfo();
        info.magic = buffer.getShort();
        info.version = buffer.get();
        info.seq = buffer.getInt();
        info.cmd = buffer.get();
        info.payloadLength = buffer.getShort() & 0xFFFF;

        return info;
    }

    /**
     * 编码 ACK 响应帧
     *
     * @param seq 确认的序列号
     * @param responsePayload 响应负载
     * @return ACK 帧
     */
    public static byte[] encodeAck(int seq, byte[] responsePayload) {
        return encode(seq, (byte) 0x02, responsePayload);
    }

    /**
     * 编码简单 ACK (仅状态)
     *
     * @param seq 序列号
     * @param success 是否成功
     * @return ACK 帧
     */
    public static byte[] encodeSimpleAck(int seq, boolean success) {
        return encode(seq, (byte) 0x02, new byte[]{(byte) (success ? 0x01 : 0x00)});
    }

    // =========================================================================
    // 内部类
    // =========================================================================

    /**
     * 解码后的帧
     */
    public static class DecodedFrame {
        public final byte version;
        public final int seq;
        public final byte cmd;
        public final byte[] payload;

        public DecodedFrame(byte version, int seq, byte cmd, byte[] payload) {
            this.version = version;
            this.seq = seq;
            this.cmd = cmd;
            this.payload = payload;
        }

        @Override
        public String toString() {
            return String.format("Frame[seq=%d, cmd=0x%02X, len=%d]",
                    seq, cmd & 0xFF, payload.length);
        }
    }

    /**
     * 帧信息 (快速解析用)
     */
    public static class FrameInfo {
        public short magic;
        public byte version;
        public int seq;
        public byte cmd;
        public int payloadLength;

        @Override
        public String toString() {
            return String.format("FrameInfo[magic=0x%04X, ver=0x%02X, seq=%d, cmd=0x%02X, len=%d]",
                    magic & 0xFFFF, version, seq, cmd & 0xFF, payloadLength);
        }
    }

    /**
     * 协议异常
     */
    public static class ProtocolException extends Exception {
        public ProtocolException(String message) {
            super(message);
        }
    }

    /**
     * CRC 校验异常
     */
    public static class CRCException extends Exception {
        public CRCException(String message) {
            super(message);
        }
    }
}
