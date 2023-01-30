package com.github.wellch4n.oops.app.pipline;

import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2023/1/30
 */
public interface ApplicationPipeService extends IService<ApplicationPipe> {

    List<ApplicationPipe> listByApplicationId(Long appId);
}
