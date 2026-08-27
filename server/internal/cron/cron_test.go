package cron

import (
	"testing"
	"time"
)

func at(value string) time.Time {
	t, _ := time.ParseInLocation("2006-01-02 15:04", value, time.Local)
	return t
}

func TestDailyAtNine(t *testing.T) {
	runs, err := NextRuns("0 9 * * *", 2, at("2026-08-27 10:00"))
	if err != nil {
		t.Fatal(err)
	}
	if runs[0] != at("2026-08-28 09:00") || runs[1] != at("2026-08-29 09:00") {
		t.Fatalf("got %v", runs)
	}
}

func TestWeekdayAndStep(t *testing.T) {
	runs, _ := NextRuns("*/15 8 * * 1-5", 1, at("2026-08-28 08:20")) // Friday
	if runs[0] != at("2026-08-28 08:30") {
		t.Fatalf("got %v", runs)
	}
	runs, _ = NextRuns("0 8 * * 1-5", 1, at("2026-08-28 09:00")) // next weekday = Monday
	if runs[0] != at("2026-08-31 08:00") {
		t.Fatalf("got %v", runs)
	}
}

func TestInvalid(t *testing.T) {
	for _, expression := range []string{"", "* * * *", "60 * * * *", "* 24 * * *", "a b c d e"} {
		if IsValid(expression) {
			t.Fatalf("%q should be invalid", expression)
		}
	}
	if !IsValid("30 3 1,15 * 0") {
		t.Fatal("expected valid")
	}
}
