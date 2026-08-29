package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.shared.util.CronSchedule;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Evaluates a 5-field cron expression and returns its upcoming fire times, used by the expert-config
 * scheduled-restart UI to preview the next run. Times are formatted in the server's default timezone.
 */
@RestController
@RequestMapping("/api/cron")
public class CronController {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @GetMapping("/next")
    public Result<List<String>> next(@RequestParam String expression,
                                     @RequestParam(defaultValue = "1") int count) {
        if (!CronSchedule.isValid(expression)) {
            return Result.failure("Invalid cron expression");
        }
        List<String> runs = CronSchedule.nextRuns(expression, Math.clamp(count, 1, 5)).stream()
                .map(run -> run.format(FORMATTER))
                .toList();
        return Result.success(runs);
    }
}
