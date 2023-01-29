package com.github.wellch4n.oops.app.application;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author wellCh4n
 * @date 2023/1/29
 */

@Mapper
public interface ApplicationRepository extends BaseMapper<Application> {
}
