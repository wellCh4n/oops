package com.github.wellch4n.oops.objects;

import lombok.Data;

/**
 * @author wellCh4n
 * @date 2025/7/15
 */

@Data
public class FileEntryResponse {
    public String permissions;
    public int links;
    public String owner;
    public String group;
    public String size;
    public String date;
    public String name;
}
