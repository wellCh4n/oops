package com.github.wellch4n.oops.app.release;

/**
 * @author wellCh4n
 * @date 2023/2/13
 */
public interface ReleaseService {

    Boolean publish(Long appId) throws Exception;
}
