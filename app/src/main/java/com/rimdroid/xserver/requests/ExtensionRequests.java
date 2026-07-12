package com.rimdroid.xserver.requests;

import static com.rimdroid.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.rimdroid.xconnector.XInputStream;
import com.rimdroid.xconnector.XOutputStream;
import com.rimdroid.xconnector.XStreamLock;
import com.rimdroid.xserver.XClient;
import com.rimdroid.xserver.errors.XRequestError;
import com.rimdroid.xserver.extensions.Extension;

import java.io.IOException;

public abstract class ExtensionRequests {
    public static void queryExtension(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        short length = inputStream.readShort();
        inputStream.skip(2);
        String name = inputStream.readString8(length);
        Extension extension = client.xServer.getExtensionByName(name);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);

            if (extension != null) {
                outputStream.writeByte((byte)1);
                outputStream.writeByte(extension.getMajorOpcode());
                outputStream.writeByte(extension.getFirstEventId());
                outputStream.writeByte(extension.getFirstErrorId());
                outputStream.writePad(20);
            }
            else {
                outputStream.writeByte((byte)0);
                outputStream.writePad(23);
            }
        }
    }

    /** RimDroid addition: core ListExtensions (opcode 99) — xdpyinfo and toolkits call it;
     *  without a reply the client blocks forever. Returns the names of our extensions. */
    public static void listExtensions(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        Extension[] extensions = client.xServer.getExtensions();

        int namesLength = 0;
        for (Extension e : extensions) namesLength += 1 + e.getName().length();   // length byte + chars
        int pad = (4 - (namesLength % 4)) % 4;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)extensions.length);          // number of STRs
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((namesLength + pad) / 4);           // extra length in 4-byte units
            outputStream.writePad(24);
            for (Extension e : extensions) {
                String name = e.getName();
                outputStream.writeByte((byte)name.length());
                for (int i = 0; i < name.length(); i++) outputStream.writeByte((byte)name.charAt(i));
            }
            outputStream.writePad(pad);
        }
    }
}
