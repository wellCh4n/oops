// Package cron evaluates 5-field cron expressions (minute hour day-of-month
// month day-of-week), the Go counterpart of shared/util/CronSchedule. Standard
// semantics: when both day-of-month and day-of-week are restricted, a time
// matches if either does.
package cron

import (
	"fmt"
	"strconv"
	"strings"
	"time"
)

type field struct {
	values     map[int]struct{}
	restricted bool // false when the field was "*"
}

type Schedule struct {
	minute, hour, dayOfMonth, month, dayOfWeek field
}

func parseField(spec string, min, max int) (field, error) {
	parsed := field{values: map[int]struct{}{}}
	if spec == "*" {
		for value := min; value <= max; value++ {
			parsed.values[value] = struct{}{}
		}
		return parsed, nil
	}
	parsed.restricted = true
	for _, part := range strings.Split(spec, ",") {
		step := 1
		if base, stepSpec, hasStep := strings.Cut(part, "/"); hasStep {
			parsedStep, err := strconv.Atoi(stepSpec)
			if err != nil || parsedStep <= 0 {
				return parsed, fmt.Errorf("invalid step %q", stepSpec)
			}
			step = parsedStep
			part = base
		}
		low, high := min, max
		switch {
		case part == "*":
			// keep full range
		case strings.Contains(part, "-"):
			lowSpec, highSpec, _ := strings.Cut(part, "-")
			var err1, err2 error
			low, err1 = strconv.Atoi(lowSpec)
			high, err2 = strconv.Atoi(highSpec)
			if err1 != nil || err2 != nil {
				return parsed, fmt.Errorf("invalid range %q", part)
			}
		default:
			value, err := strconv.Atoi(part)
			if err != nil {
				return parsed, fmt.Errorf("invalid value %q", part)
			}
			low, high = value, value
		}
		if low < min || high > max || low > high {
			return parsed, fmt.Errorf("value out of range in %q", part)
		}
		for value := low; value <= high; value += step {
			parsed.values[value] = struct{}{}
		}
	}
	return parsed, nil
}

func Parse(expression string) (*Schedule, error) {
	fields := strings.Fields(strings.TrimSpace(expression))
	if len(fields) != 5 {
		return nil, fmt.Errorf("expected 5 fields, got %d", len(fields))
	}
	var schedule Schedule
	var err error
	if schedule.minute, err = parseField(fields[0], 0, 59); err != nil {
		return nil, err
	}
	if schedule.hour, err = parseField(fields[1], 0, 23); err != nil {
		return nil, err
	}
	if schedule.dayOfMonth, err = parseField(fields[2], 1, 31); err != nil {
		return nil, err
	}
	if schedule.month, err = parseField(fields[3], 1, 12); err != nil {
		return nil, err
	}
	if schedule.dayOfWeek, err = parseField(fields[4], 0, 7); err != nil {
		return nil, err
	}
	// Cron allows both 0 and 7 for Sunday.
	if _, has7 := schedule.dayOfWeek.values[7]; has7 {
		schedule.dayOfWeek.values[0] = struct{}{}
	}
	return &schedule, nil
}

func IsValid(expression string) bool {
	_, err := Parse(expression)
	return err == nil
}

func (f field) matches(value int) bool {
	_, matched := f.values[value]
	return matched
}

func (s *Schedule) dayMatches(t time.Time) bool {
	domMatch := s.dayOfMonth.matches(t.Day())
	dowMatch := s.dayOfWeek.matches(int(t.Weekday()))
	if s.dayOfMonth.restricted && s.dayOfWeek.restricted {
		return domMatch || dowMatch
	}
	return domMatch && dowMatch
}

// Next returns the first fire time strictly after the given instant.
func (s *Schedule) Next(after time.Time) time.Time {
	candidate := after.Truncate(time.Minute).Add(time.Minute)
	limit := after.AddDate(4, 0, 0) // guard against impossible expressions
	for candidate.Before(limit) {
		switch {
		case !s.month.matches(int(candidate.Month())):
			candidate = time.Date(candidate.Year(), candidate.Month(), 1, 0, 0, 0, 0, candidate.Location()).AddDate(0, 1, 0)
		case !s.dayMatches(candidate):
			candidate = time.Date(candidate.Year(), candidate.Month(), candidate.Day(), 0, 0, 0, 0, candidate.Location()).AddDate(0, 0, 1)
		case !s.hour.matches(candidate.Hour()):
			candidate = candidate.Truncate(time.Hour).Add(time.Hour)
		case !s.minute.matches(candidate.Minute()):
			candidate = candidate.Add(time.Minute)
		default:
			return candidate
		}
	}
	return time.Time{}
}

func NextRuns(expression string, count int, from time.Time) ([]time.Time, error) {
	schedule, err := Parse(expression)
	if err != nil {
		return nil, err
	}
	runs := make([]time.Time, 0, count)
	current := from
	for range count {
		current = schedule.Next(current)
		if current.IsZero() {
			break
		}
		runs = append(runs, current)
	}
	return runs, nil
}
