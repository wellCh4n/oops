package com.github.wellch4n.oops.app.pipline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2023/1/30
 */

@Service
public class ApplicationPipeServiceImpl extends ServiceImpl<ApplicationPipeRepository, ApplicationPipe> implements ApplicationPipeService {
    @Override
    public List<ApplicationPipe> listByApplicationId(Long appId) {
        LambdaQueryWrapper<ApplicationPipe> query = new LambdaQueryWrapper<>();
        query.eq(ApplicationPipe::getAppId, appId);
        return this.baseMapper.selectList(query);
    }
}
