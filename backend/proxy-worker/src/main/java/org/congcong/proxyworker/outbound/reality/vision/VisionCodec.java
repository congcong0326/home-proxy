package org.congcong.proxyworker.outbound.reality.vision;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class VisionCodec {
    public static final int BUFFER_SIZE = 8192;
    public static final int UUID_LENGTH = 16;
    public static final int FRAME_HEADER_LENGTH = 5;
    public static final int MAX_RESHAPED_CONTENT_LENGTH = BUFFER_SIZE - UUID_LENGTH - FRAME_HEADER_LENGTH;
    public static final int LONG_CONTENT_LIMIT = 900;
    public static final int LONG_RANDOM_BOUND = 500;
    public static final int LONG_BASE_PADDING = 900;
    public static final int SHORT_RANDOM_BOUND = 256;
    private static final byte[] TLS_APPLICATION_DATA_START = new byte[] {0x17, 0x03, 0x03};

    private final byte[] uuidBytes;
    private final VisionPaddingSource paddingSource;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private boolean writeUuid = true;
    private boolean uuidRead;
    private boolean paddingEnded;
    private boolean directRead;

    public VisionCodec(UUID uuid, VisionPaddingSource paddingSource) {
        this.uuidBytes = uuidBytes(uuid);
        this.paddingSource = paddingSource;
    }

    public byte[] encode(byte[] payload, VisionCommand command, boolean longPadding) {
        int maxContentLength = maxContentLength(writeUuid);
        if (payload.length > maxContentLength) {
            throw new IllegalArgumentException("Vision payload length " + payload.length
                    + " exceeds maximum " + maxContentLength);
        }
        int paddingLength = paddingLength(payload.length, longPadding, writeUuid);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (writeUuid) {
            out.write(uuidBytes, 0, uuidBytes.length);
            writeUuid = false;
        }
        out.write(command.code());
        out.write((payload.length >>> 8) & 0xff);
        out.write(payload.length & 0xff);
        out.write((paddingLength >>> 8) & 0xff);
        out.write(paddingLength & 0xff);
        out.write(payload, 0, payload.length);
        for (int i = 0; i < paddingLength; i++) {
            out.write(0);
        }
        return out.toByteArray();
    }

    public static List<byte[]> reshapeForPadding(byte[] payload) {
        if (payload.length <= MAX_RESHAPED_CONTENT_LENGTH) {
            return Collections.singletonList(copyOf(payload, 0, payload.length));
        }
        List<byte[]> frames = new ArrayList<byte[]>();
        int offset = 0;
        while (payload.length - offset > MAX_RESHAPED_CONTENT_LENGTH) {
            int split = splitLength(payload, offset, payload.length - offset);
            frames.add(copyOf(payload, offset, offset + split));
            offset += split;
        }
        frames.add(copyOf(payload, offset, payload.length));
        return frames;
    }

    public VisionDecodeResult decode(byte[] bytes) {
        if (directRead) {
            return new VisionDecodeResult(bytes, true);
        }
        if (paddingEnded) {
            return new VisionDecodeResult(bytes, false);
        }
        pending.write(bytes, 0, bytes.length);
        byte[] all = pending.toByteArray();
        int index = 0;
        int strippedUuidLength = 0;
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        if (!uuidRead) {
            if (all.length >= UUID_LENGTH && startsWithUuid(all)) {
                index = UUID_LENGTH;
                strippedUuidLength = UUID_LENGTH;
                uuidRead = true;
            } else if (all.length < UUID_LENGTH && matchesUuidPrefix(all)) {
                return new VisionDecodeResult(new byte[0], false);
            } else {
                paddingEnded = true;
                pending.reset();
                return new VisionDecodeResult(all, false);
            }
        }
        while (all.length - index > 0) {
            if (!isFrameCommand(all[index] & 0xff)) {
                throw invalidFrameHeader();
            }
            if (all.length - index < FRAME_HEADER_LENGTH) {
                break;
            }
            VisionCommand command = VisionCommand.fromCode(all[index] & 0xff);
            int contentLength = ((all[index + 1] & 0xff) << 8) | (all[index + 2] & 0xff);
            int paddingLength = ((all[index + 3] & 0xff) << 8) | (all[index + 4] & 0xff);
            int frameLength = FRAME_HEADER_LENGTH + contentLength + paddingLength;
            int bufferedFrameLength = strippedUuidLength + frameLength;
            strippedUuidLength = 0;
            if (bufferedFrameLength > BUFFER_SIZE) {
                throw new IllegalArgumentException("Vision frame length " + bufferedFrameLength
                        + " exceeds maximum " + BUFFER_SIZE);
            }
            int frameEnd = index + frameLength;
            if (all.length < frameEnd) {
                break;
            }
            payload.write(all, index + FRAME_HEADER_LENGTH, contentLength);
            index = frameEnd;
            if (command == VisionCommand.PADDING_DIRECT) {
                payload.write(all, index, all.length - index);
                paddingEnded = true;
                directRead = true;
                pending.reset();
                return new VisionDecodeResult(payload.toByteArray(), true);
            }
            if (command == VisionCommand.PADDING_END) {
                payload.write(all, index, all.length - index);
                paddingEnded = true;
                pending.reset();
                return new VisionDecodeResult(payload.toByteArray(), false);
            }
        }
        compact(all, index);
        return new VisionDecodeResult(payload.toByteArray(), false);
    }

    private int paddingLength(int contentLength, boolean longPadding, boolean includeUuid) {
        int padding;
        if (contentLength < LONG_CONTENT_LIMIT && longPadding) {
            padding = paddingSource.nextInt(LONG_RANDOM_BOUND) + LONG_BASE_PADDING - contentLength;
        } else {
            padding = paddingSource.nextInt(SHORT_RANDOM_BOUND);
        }
        int max = maxContentLength(includeUuid) - contentLength;
        return Math.min(padding, Math.max(max, 0));
    }

    private void compact(byte[] all, int consumed) {
        pending.reset();
        if (consumed < all.length) {
            pending.write(all, consumed, all.length - consumed);
        }
    }

    private int maxContentLength(boolean includeUuid) {
        int uuidLength = includeUuid ? UUID_LENGTH : 0;
        return BUFFER_SIZE - uuidLength - FRAME_HEADER_LENGTH;
    }

    private static int splitLength(byte[] payload, int offset, int remaining) {
        int searchEnd = offset + Math.min(remaining, MAX_RESHAPED_CONTENT_LENGTH);
        int recordStart = lastIndexOf(payload, offset, searchEnd, TLS_APPLICATION_DATA_START);
        int split = recordStart - offset;
        if (split < UUID_LENGTH + FRAME_HEADER_LENGTH || split > MAX_RESHAPED_CONTENT_LENGTH) {
            return MAX_RESHAPED_CONTENT_LENGTH / 2;
        }
        return split;
    }

    private static int lastIndexOf(byte[] bytes, int fromInclusive, int toExclusive, byte[] needle) {
        for (int i = toExclusive - needle.length; i >= fromInclusive; i--) {
            boolean matches = true;
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] copyOf(byte[] bytes, int fromInclusive, int toExclusive) {
        byte[] copy = new byte[toExclusive - fromInclusive];
        System.arraycopy(bytes, fromInclusive, copy, 0, copy.length);
        return copy;
    }

    private boolean startsWithUuid(byte[] bytes) {
        for (int i = 0; i < UUID_LENGTH; i++) {
            if (bytes[i] != uuidBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesUuidPrefix(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != uuidBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean hasFrameHeaderAt(byte[] bytes, int index) {
        if (bytes.length - index < FRAME_HEADER_LENGTH) {
            return false;
        }
        return isFrameCommand(bytes[index] & 0xff);
    }

    private boolean isPartialFrameHeaderAt(byte[] bytes, int index) {
        int available = bytes.length - index;
        return available > 0 && available < FRAME_HEADER_LENGTH && isFrameCommand(bytes[index] & 0xff);
    }

    private boolean isFrameCommand(int commandCode) {
        return commandCode == VisionCommand.PADDING_CONTINUE.code()
                || commandCode == VisionCommand.PADDING_END.code()
                || commandCode == VisionCommand.PADDING_DIRECT.code();
    }

    private IllegalArgumentException invalidFrameHeader() {
        pending.reset();
        return new IllegalArgumentException("Invalid Vision frame header");
    }

    private static byte[] uuidBytes(UUID uuid) {
        byte[] bytes = new byte[UUID_LENGTH];
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (most >>> (56 - (i * 8)));
            bytes[i + 8] = (byte) (least >>> (56 - (i * 8)));
        }
        return bytes;
    }
}
