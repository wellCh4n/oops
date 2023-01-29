package com.github.wellch4n.oops.app.application;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * @author wellCh4n
 * @date 2023/1/29
 */

@Service
public class ApplicationServiceImpl extends ServiceImpl<ApplicationRepository, Application> implements ApplicationService {
}
