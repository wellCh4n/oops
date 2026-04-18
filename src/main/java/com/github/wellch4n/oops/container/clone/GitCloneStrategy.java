package com.github.wellch4n.oops.container.clone;

import com.github.wellch4n.oops.data.Application;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

public class GitCloneStrategy implements CloneStrategy<GitCloneParam> {

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
        return String.join(" ", args);
    }
}
