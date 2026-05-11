package org.congcong.proxyworker.outbound.reality.tls;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class TlsHandshakeMessageBuffer {
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    public List<TlsHandshakeMessage> append(byte[] bytes) {
        pending.write(bytes, 0, bytes.length);
        byte[] all = pending.toByteArray();
        List<TlsHandshakeMessage> messages = new ArrayList<TlsHandshakeMessage>();
        int index = 0;
        while (index + 4 <= all.length) {
            int type = all[index] & 0xff;
            int length = ((all[index + 1] & 0xff) << 16)
                    | ((all[index + 2] & 0xff) << 8)
                    | (all[index + 3] & 0xff);
            if (index + 4 + length > all.length) {
                break;
            }
            byte[] body = new byte[length];
            System.arraycopy(all, index + 4, body, 0, length);
            messages.add(new TlsHandshakeMessage(TlsHandshakeMessageType.fromCode(type), body));
            index += 4 + length;
        }
        pending.reset();
        if (index < all.length) {
            pending.write(all, index, all.length - index);
        }
        return messages;
    }

    public void reset() {
        pending.reset();
    }
}
