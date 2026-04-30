package com.github.wellch4n.oops.application.port;

import java.io.IOException;

public interface StreamSink {
    boolean isOpen();

    void sendText(String text) throws IOException;

    void sendBinary(byte[] bytes, int offset, int length) throws IOException;

    void close() throws IOException;

    void closeWithError() throws IOException;
}
