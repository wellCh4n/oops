package com.github.wellch4n.oops.app.deploy;

/**
 * @author wellCh4n
 * @date 2023/1/30
 */
public interface DeployService {
    void publish(Long appId) throws Exception;
}
