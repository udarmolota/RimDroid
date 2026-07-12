package com.rimdroid.xserver;

public interface XLock extends AutoCloseable {
    @Override
    void close();
}
