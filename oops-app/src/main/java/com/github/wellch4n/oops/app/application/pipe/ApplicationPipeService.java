package com.github.wellch4n.oops.app.application.pipe;

/**
 * @author wellCh4n
 * @date 2023/2/7
 */
public interface ApplicationPipeService {
    ApplicationPipeRelation line(Long appid);

    boolean put(ApplicationPipeRelation relation);

}
