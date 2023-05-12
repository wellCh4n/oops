package com.github.wellch4n.oops.app.release;

import com.github.wellch4n.oops.common.objects.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author wellCh4n
 * @date 2023/2/13
 */

@RestController
@RequestMapping(value = "/oops/api/release")
public class ReleaseServer {

    private final ReleaseService releaseService;

    public ReleaseServer(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @GetMapping(value = "/publish")
    public Result<Boolean> publish(@RequestParam("appId") Long id) {
        try {
            return Result.success(releaseService.publish(id));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
