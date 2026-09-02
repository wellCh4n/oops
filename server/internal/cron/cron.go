// Package cron mirrors the Java CronSchedule helper built on Spring's
// CronExpression: six-field expressions (seconds first), five-field
// expressions promoted with a leading "0", and the @daily-style macros.
package cron

import (
	"errors"
	"strings"
	"time"

	robfig "github.com/robfig/cron/v3"
)

// ErrBlank is returned by Normalize for a blank expression.
var ErrBlank = errors.New("Cron expression must not be blank")

// FieldCountError is returned by Normalize for an expression that has neither
// five nor six fields.
type FieldCountError struct{ Expression string }

func (err *FieldCountError) Error() string {
	return "Cron expression must have 5 fields: " + err.Expression
}

var parser = robfig.NewParser(
	robfig.Second | robfig.Minute | robfig.Hour | robfig.Dom | robfig.Month | robfig.Dow | robfig.Descriptor,
)

// Normalize trims the expression and promotes a five-field expression to six
// fields by prefixing the seconds field with "0". Macros (starting with "@")
// are returned unchanged.
func Normalize(expression string) (string, error) {
	trimmed := strings.TrimSpace(expression)
	if trimmed == "" {
		return "", ErrBlank
	}
	if strings.HasPrefix(trimmed, "@") {
		return trimmed, nil
	}
	fields := strings.Fields(trimmed)
	switch len(fields) {
	case 5:
		return "0 " + trimmed, nil
	case 6:
		return trimmed, nil
	default:
		return "", &FieldCountError{Expression: expression}
	}
}

// parse normalizes and compiles the expression.
func parse(expression string) (robfig.Schedule, error) {
	normalized, err := Normalize(expression)
	if err != nil {
		return nil, err
	}
	return parser.Parse(rewriteSundaySeven(normalized))
}

// rewriteSundaySeven maps day-of-week 7 (Sunday in Spring) to 0 in the last
// field, since robfig only accepts 0–6.
func rewriteSundaySeven(normalized string) string {
	if strings.HasPrefix(normalized, "@") {
		return normalized
	}
	fields := strings.Fields(normalized)
	if len(fields) != 6 {
		return normalized
	}
	parts := strings.Split(fields[5], ",")
	for index, part := range parts {
		parts[index] = rewriteDowPart(part)
	}
	fields[5] = strings.Join(parts, ",")
	return strings.Join(fields, " ")
}

func rewriteDowPart(part string) string {
	if part == "7" {
		return "0"
	}
	if strings.HasPrefix(part, "7/") {
		return "0/" + strings.TrimPrefix(part, "7/")
	}
	if strings.HasSuffix(part, "-7") {
		// "5-7" means Friday through Sunday: keep the range to Saturday and add Sunday.
		return strings.TrimSuffix(part, "-7") + "-6,0"
	}
	return part
}

// IsValid reports whether the expression normalizes and compiles.
func IsValid(expression string) bool {
	_, err := parse(expression)
	return err == nil
}

// NextRuns returns up to count upcoming fire times strictly after from,
// in from's location.
func NextRuns(expression string, count int, from time.Time) ([]time.Time, error) {
	schedule, err := parse(expression)
	if err != nil {
		return nil, err
	}
	runs := make([]time.Time, 0, max(count, 0))
	cursor := from
	for len(runs) < count {
		next := schedule.Next(cursor)
		if next.IsZero() {
			break
		}
		runs = append(runs, next)
		cursor = next
	}
	return runs, nil
}

// MatchesMinute reports whether the expression fires in the minute containing
// at: the minute is truncated, and the schedule's next fire time after
// (minute - 1ns) must be exactly that minute.
func MatchesMinute(expression string, at time.Time) bool {
	schedule, err := parse(expression)
	if err != nil {
		return false
	}
	minute := at.Truncate(time.Minute)
	return schedule.Next(minute.Add(-time.Nanosecond)).Equal(minute)
}
