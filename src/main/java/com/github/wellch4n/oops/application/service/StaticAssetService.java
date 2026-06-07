package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.dto.AssetEntry;
import com.github.wellch4n.oops.application.dto.ObjectStorageUploadResult;
import com.github.wellch4n.oops.application.port.ObjectStorage;
import com.github.wellch4n.oops.application.port.ObjectStorage.DirectoryListing;
import com.github.wellch4n.oops.application.port.ObjectStorage.ObjectSummary;
import com.github.wellch4n.oops.infrastructure.config.ObjectStorageProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class StaticAssetService {

    private final ObjectStorage objectStorage;
    private final ObjectStorageProperties config;

    public StaticAssetService(ObjectStorage objectStorage, ObjectStorageProperties config) {
        this.objectStorage = objectStorage;
        this.config = config;
    }

    public List<AssetEntry> list(String path) {
        String dirPrefix = dirPrefix(path);
        DirectoryListing listing = objectStorage.listDirectory(dirPrefix);

        List<AssetEntry> folders = new ArrayList<>();
        for (String folder : listing.folders()) {
            folders.add(AssetEntry.folder(lastSegment(folder), folder));
        }

        List<AssetEntry> files = new ArrayList<>();
        for (ObjectSummary summary : listing.files()) {
            if (summary.key().equals(dirPrefix) || summary.key().endsWith("/")) {
                continue;
            }
            String name = lastSegment(summary.key());
            files.add(AssetEntry.file(
                    name,
                    summary.key(),
                    summary.size(),
                    summary.lastModified(),
                    guessContentType(name),
                    objectStorage.buildPublicUrl(summary.key()),
                    objectStorage.createDownloadUrl(summary.key())
            ));
        }

        List<AssetEntry> entries = new ArrayList<>(folders.size() + files.size());
        entries.addAll(folders);
        entries.addAll(files);
        return entries;
    }

    public ObjectStorageUploadResult createUploadUrl(String path, String fileName, String contentType, Long fileSize) {
        if (StringUtils.isBlank(fileName)) {
            throw new BizException("File name is required");
        }
        if (fileSize == null || fileSize <= 0) {
            throw new BizException("File size must be greater than 0");
        }
        if (fileSize > config.getMaxFileSizeBytes()) {
            throw new BizException("File size exceeds the configured limit");
        }
        String objectKey = dirPrefix(path) + cleanFileName(fileName);
        return objectStorage.presignPut(objectKey, resolveUploadContentType(fileName, contentType));
    }

    /**
     * Decide the Content-Type the object is stored with. The browser-reported
     * {@code file.type} is unreliable (often blank), which leaves objects as
     * {@code application/octet-stream} and makes browsers download files such as
     * HTML instead of rendering them. Prefer the extension-based guess so the
     * stored type matches what we display in the listing, and only fall back to
     * the client-provided type when the name has no recognizable extension.
     */
    private String resolveUploadContentType(String fileName, String clientContentType) {
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        if (StringUtils.isNotBlank(guessed)) {
            return guessed;
        }
        return StringUtils.defaultIfBlank(clientContentType, "application/octet-stream");
    }

    public void delete(String key) {
        validateAssetKey(key);
        if (key.endsWith("/")) {
            List<String> keys = objectStorage.listObjects(key).stream().map(ObjectSummary::key).toList();
            if (keys.isEmpty()) {
                objectStorage.deleteObject(key);
            } else {
                objectStorage.deleteObjects(keys);
            }
        } else {
            objectStorage.deleteObject(key);
        }
    }

    private String dirPrefix(String path) {
        String normalized = normalizePath(path);
        return normalized.isEmpty() ? assetPrefix() + "/" : assetPrefix() + "/" + normalized + "/";
    }

    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        if (normalized.contains("..")) {
            throw new BizException("Invalid path");
        }
        return normalized;
    }

    private String cleanFileName(String fileName) {
        String name = StringUtils.stripStart(fileName.trim(), "/");
        if (name.isEmpty() || name.endsWith("/") || name.contains("..")) {
            throw new BizException("Invalid file name");
        }
        return name;
    }

    private void validateAssetKey(String key) {
        if (StringUtils.isBlank(key) || !key.startsWith(assetPrefix() + "/") || key.contains("..")) {
            throw new BizException("Invalid asset key");
        }
    }

    private String lastSegment(String key) {
        String stripped = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
        return stripped.substring(stripped.lastIndexOf('/') + 1);
    }

    private String guessContentType(String fileName) {
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        return StringUtils.defaultIfBlank(guessed, "application/octet-stream");
    }

    private String assetPrefix() {
        return StringUtils.defaultIfBlank(config.getAssetKeyPrefix(), "oops-assets")
                .replaceAll("^/+", "")
                .replaceAll("/+$", "");
    }
}
