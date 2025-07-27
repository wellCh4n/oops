package com.github.wellch4n.oops.objects;

import com.github.wellch4n.oops.data.Application;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.beans.BeanUtils;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/23
 */

@Data
@EqualsAndHashCode(callSuper = true)
public class ApplicationDetailResponse extends Application {

    public ApplicationDetailResponse(Application application) {
        BeanUtils.copyProperties(application, this);
    }

    private List<BuildStorage> buildStorages;
}
