package com.github.wellch4n.oops.infrastructure.kubernetes.container.clone;

import com.github.wellch4n.oops.domain.application.Application;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

public class GitCloneStrategy implements CloneStrategy<GitCloneParam> {

    private static final String SHOW_HEAD_COMMIT =
            "echo && git -C /workspace --no-pager log -1 --date=iso --format='commit %H%nAuthor: %an <%ae>%nDate:   %ad%n%n    %s'";

    @Override
    public boolean supports(CloneStrategyParam param) {
        return param instanceof GitCloneParam;
    }

    @Override
    public String buildCommand(Application application, GitCloneParam param) {
        if (StringUtils.isBlank(param.repository())) {
            throw new IllegalArgumentException("Repository URL must not be empty for application: " + application.getName());
        }

        List<String> args = new ArrayList<>();
        args.add("git");
        args.add("clone");
        args.add("--progress");

        if (param.shallow()) {
            args.add("--depth");
            args.add("1");
        }

        if (StringUtils.isNotBlank(param.branch())) {
            args.add("-b");
            args.add(param.branch());
        }

        args.add(param.repository());
        args.add("/workspace");
        // The branch is all a publish names, so the log says which commit it resolved to. `&&` keeps a
        // failed clone failing the step; --no-pager because a pager would wait on a terminal nobody has.
        return String.join(" ", args) + " && " + SHOW_HEAD_COMMIT;
    }
}
