package cron

import (
	"testing"
	"time"
)

func TestNormalize(t *testing.T) {
	cases := map[string]string{
		"0 3 * * *":     "0 0 3 * * *",
		"0 0 3 * * *":   "0 0 3 * * *",
		"  0 9 * * *  ": "0 0 9 * * *",
		"@daily":        "@daily",
	}
	for input, want := range cases {
		got, err := Normalize(input)
		if err != nil {
			t.Fatalf("%q: %v", input, err)
		}
		if got != want {
			t.Fatalf("%q: got %q want %q", input, got, want)
		}
	}
	if _, err := Normalize("   "); err == nil || err.Error() != "Cron expression must not be blank" {
		t.Fatalf("blank: %v", err)
	}
	if _, err := Normalize("* * *"); err == nil || err.Error() != "Cron expression must have 5 fields: * * *" {
		t.Fatalf("3 fields: %v", err)
	}
}

func TestIsValid(t *testing.T) {
	for _, valid := range []string{"0 9 * * *", "0 0 3 * * *", "@daily", "@hourly", "@weekly", "@monthly", "@yearly", "@annually", "@midnight", "0 0 12 * * 7", "0 0 12 * * 5-7", "*/15 * * * *", "0 0 12 ? * MON-FRI"} {
		if !IsValid(valid) {
			t.Fatalf("%q should be valid", valid)
		}
	}
	for _, invalid := range []string{"not a cron", "* * *", "60 * * * *", "", "0 0 25 * * *"} {
		if IsValid(invalid) {
			t.Fatalf("%q should be invalid", invalid)
		}
	}
}

func TestNextRunsAtNineDaily(t *testing.T) {
	from := time.Date(2026, 9, 2, 10, 30, 0, 0, time.Local)
	runs, err := NextRuns("0 9 * * *", 3, from)
	if err != nil {
		t.Fatal(err)
	}
	if len(runs) != 3 {
		t.Fatalf("want 3 runs got %d", len(runs))
	}
	for index, run := range runs {
		if run.Hour() != 9 || run.Minute() != 0 || run.Second() != 0 {
			t.Fatalf("run %d not at 09:00: %v", index, run)
		}
		if index > 0 && !run.After(runs[index-1]) {
			t.Fatalf("runs not sorted: %v", runs)
		}
	}
	if runs[0].Day() != 3 {
		t.Fatalf("first run should be tomorrow, got %v", runs[0])
	}
}

func TestSundaySeven(t *testing.T) {
	// 2026-09-06 is a Sunday.
	saturday := time.Date(2026, 9, 5, 12, 0, 0, 0, time.Local)
	runs, err := NextRuns("0 0 8 * * 7", 1, saturday)
	if err != nil {
		t.Fatal(err)
	}
	if runs[0].Weekday() != time.Sunday {
		t.Fatalf("dow 7 should be Sunday, got %v", runs[0])
	}
}

func TestMatchesMinute(t *testing.T) {
	at := time.Date(2026, 9, 2, 3, 0, 27, 500, time.Local)
	if !MatchesMinute("0 3 * * *", at) {
		t.Fatal("03:00 should match '0 3 * * *'")
	}
	if MatchesMinute("0 3 * * *", at.Add(time.Minute)) {
		t.Fatal("03:01 should not match")
	}
	if !MatchesMinute("@daily", time.Date(2026, 9, 2, 0, 0, 59, 0, time.Local)) {
		t.Fatal("@daily should match midnight")
	}
	if MatchesMinute("not a cron", at) {
		t.Fatal("invalid expression never matches")
	}
}
