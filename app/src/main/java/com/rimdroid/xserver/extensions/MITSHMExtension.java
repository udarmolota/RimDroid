package com.rimdroid.xserver.extensions;

import static com.rimdroid.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.rimdroid.xconnector.XInputStream;
import com.rimdroid.xconnector.XOutputStream;
import com.rimdroid.xconnector.XStreamLock;
import com.rimdroid.xserver.Drawable;
import com.rimdroid.xserver.GraphicsContext;
import com.rimdroid.xserver.XClient;
import com.rimdroid.xserver.XLock;
import com.rimdroid.xserver.XServer;
import com.rimdroid.xserver.errors.BadDrawable;
import com.rimdroid.xserver.errors.BadGraphicsContext;
import com.rimdroid.xserver.errors.BadImplementation;
import com.rimdroid.xserver.errors.BadSHMSegment;
import com.rimdroid.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public class MITSHMExtension extends Extension {
    public static final byte MAJOR_VERSION = 1;
    public static final byte MINOR_VERSION = 1;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte ATTACH = 1;
        private static final byte DETACH = 2;
        private static final byte PUT_IMAGE = 3;
        private static final byte CREATE_PIXMAP = 5;
    }

    public MITSHMExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "MIT-SHM";
    }

    @Override
    public byte getFirstErrorId() {
        return Byte.MIN_VALUE;
    }

    @Override
    public byte getFirstEventId() {
        return 64;
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)MAJOR_VERSION);
            outputStream.writeShort((short)MINOR_VERSION);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writeByte((byte)0);
        }
    }

    private void attach(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int xid = inputStream.readInt();
        int shmid = inputStream.readInt();
        inputStream.skip(4);
        xServer.getSHMSegmentManager().attach(xid, shmid);
    }

    private void detach(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        xServer.getSHMSegmentManager().detach(inputStream.readInt());
    }

    private void putImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short totalWidth = inputStream.readShort();
        short totalHeight = inputStream.readShort();
        short srcX = inputStream.readShort();
        short srcY = inputStream.readShort();
        short srcWidth = inputStream.readShort();
        short srcHeight = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        byte depth = inputStream.readByte();
        inputStream.skip(3);
        int shmseg = inputStream.readInt();
        inputStream.skip(4);

        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        GraphicsContext graphicsContext = xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        ByteBuffer data = xServer.getSHMSegmentManager().getData(shmseg);
        if (data == null) throw new BadSHMSegment(shmseg);

        if (graphicsContext.getFunction() != GraphicsContext.Function.COPY) {
            throw new UnsupportedOperationException("GC Function other than COPY is not supported.");
        }

        if (drawable.isUseSharedData()) {
            synchronized (drawable.renderLock) {
                if (drawable.getData() != data) drawable.setData(data);
                drawable.forceUpdate();
            }
        }
        else drawable.drawImage(srcX, srcY, dstX, dstY, srcWidth, srcHeight, depth, data, totalWidth, totalHeight);
    }

    private void createPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(4);
        int drawableId = inputStream.readInt();
        short width = inputStream.readShort();
        inputStream.skip(14);

        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        drawable.setUseSharedData(width == drawable.width);
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.ATTACH :
                try (XLock lock = xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER)) {
                    attach(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.DETACH :
                try (XLock lock = xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER)) {
                    detach(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PUT_IMAGE :
                try (XLock lock = xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                    putImage(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.CREATE_PIXMAP :
                try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                    createPixmap(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
