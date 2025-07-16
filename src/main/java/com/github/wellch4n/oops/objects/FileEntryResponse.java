package com.github.wellch4n.oops.objects;

import lombok.Data;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/15
 */

@Data
public class FileEntryResponse {

    private String pwd;
    private List<FileEntry> items;

    @Data
    public static class FileEntry {
        private String absolutePath;
        private String permissions;
        private int links;
        private String owner;
        private String group;
        private String size;
        private String date;
        private String name;
        private boolean directory;

        public void setAbsolutePath(String path, String name) {
            if ("/".equals(path)) {
                this.absolutePath = path + name;
            } else {
                this.absolutePath = path + "/" + name;
            }
        }

        public void setPermissions(String permissions) {
            this.directory = permissions.startsWith("d");
            this.permissions = permissions.substring(1);
        }
    }
}
