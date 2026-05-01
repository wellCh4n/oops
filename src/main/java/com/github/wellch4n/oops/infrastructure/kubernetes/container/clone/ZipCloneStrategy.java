package com.github.wellch4n.oops.infrastructure.kubernetes.container.clone;

import com.github.wellch4n.oops.domain.application.Application;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

public class ZipCloneStrategy implements CloneStrategy<ZipCloneParam> {

    private static final List<String> UNZIP_EXCLUDES = List.of(
            "node_modules/*",
            "*/node_modules/*"
    );

    @Override
    public boolean supports(CloneStrategyParam param) {
        return param instanceof ZipCloneParam;
    }

    @Override
    public String buildCommand(Application application, ZipCloneParam param) {
        if (StringUtils.isBlank(param.sourceDownloadUrl())) {
            throw new IllegalArgumentException("Zip source must have a download URL for application: " + application.getName());
        }

        return """
                set -e
                rm -rf /workspace/* /tmp/source-download /tmp/source.zip
                mkdir -p /workspace /tmp/source-download

                curl -fL --connect-timeout 30 --max-time 120 '%s' -o /tmp/source.zip

                if [ ! -s /tmp/source.zip ]; then
                    echo "Downloaded file is empty" >&2
                    exit 1
                fi

                magic=$(od -A n -t x1 -N 2 /tmp/source.zip 2>/dev/null | sed 's/ //g')
                if [ "$magic" != "504b" ]; then
                    echo "Downloaded file is not a valid ZIP archive" >&2
                    exit 1
                fi

                unzip -o /tmp/source.zip -d /tmp/source-download -x %s

                find /tmp/source-download -mindepth 1 -maxdepth 1 \\
                  ! -name '__MACOSX' \\
                  ! -name '.DS_Store' \\
                  > /tmp/source-entries
                first_entry="$(head -n 1 /tmp/source-entries)"
                entry_count="$(wc -l < /tmp/source-entries | tr -d ' ')"

                if [ "$entry_count" = "1" ] && [ -d "$first_entry" ]; then
                  cp -a "$first_entry"/. /workspace/
                else
                  cp -a /tmp/source-download/. /workspace/
                  rm -rf /workspace/__MACOSX /workspace/.DS_Store
                fi
                """.formatted(param.sourceDownloadUrl(),
                        UNZIP_EXCLUDES.stream().map(e -> "'" + e + "'").collect(Collectors.joining(" ")));
    }
}
