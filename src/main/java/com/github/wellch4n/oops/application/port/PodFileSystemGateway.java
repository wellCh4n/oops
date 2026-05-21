package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public interface PodFileSystemGateway {
    List<PodFileEntry> listDirectory(Environment environment, String namespace, String podName, String container, String path);

    long getFileSize(Environment environment, String namespace, String podName, String container, String path);

    void streamFile(Environment environment, String namespace, String podName, String container, String path, OutputStream outputStream);

    void uploadFile(Environment environment, String namespace, String podName, String container, String path, InputStream inputStream);

    void deletePath(Environment environment, String namespace, String podName, String container, String path);

    void renamePath(Environment environment, String namespace, String podName, String container, String fromPath, String toPath);

    record PodFileEntry(String name, String path, FileType type, Long size) {
        public enum FileType {
            DIRECTORY,
            FILE,
            SYMLINK,
            OTHER
        }
    }
}
