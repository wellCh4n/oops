package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;
import java.io.OutputStream;
import java.util.List;

public interface PodFileSystemGateway {
    List<PodFileEntry> listDirectory(Environment environment, String namespace, String podName, String container, String path);

    long getFileSize(Environment environment, String namespace, String podName, String container, String path);

    void streamFile(Environment environment, String namespace, String podName, String container, String path, OutputStream outputStream);

    record PodFileEntry(String name, String path, FileType type, Long size) {
        public enum FileType {
            DIRECTORY,
            FILE,
            SYMLINK,
            OTHER
        }
    }
}
