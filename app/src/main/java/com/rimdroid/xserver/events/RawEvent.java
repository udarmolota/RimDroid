package com.rimdroid.xserver.events;

import com.rimdroid.xconnector.XOutputStream;
import com.rimdroid.xconnector.XStreamLock;

import java.io.IOException;

public class RawEvent extends Event {
    private final byte[] data;

    public RawEvent(byte[] data) {
        super(data[0]);
        this.data = data;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.write(data);
        }
    }
}
