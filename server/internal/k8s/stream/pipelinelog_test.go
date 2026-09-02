package stream

import (
	"encoding/json"
	"testing"
)

func TestParseTimestampedLine(t *testing.T) {
	cases := []struct {
		name      string
		line      string
		wantStamp string
		wantText  string
	}{
		{"stamped line", "2026-09-01T02:23:45.123456789Z Cloning into '/workspace'...", "2026-09-01T02:23:45.123456789Z", "Cloning into '/workspace'..."},
		{"stamp without fraction", "2026-09-01T02:23:45Z hello", "2026-09-01T02:23:45Z", "hello"},
		{"stamp with offset", "2026-09-01T02:23:45+08:00 hello", "2026-09-01T02:23:45+08:00", "hello"},
		{"bare stamp", "2026-09-01T02:23:45Z", "2026-09-01T02:23:45Z", ""},
		{"bare stamp with trailing space", "2026-09-01T02:23:45Z ", "2026-09-01T02:23:45Z", ""},
		{"empty", "", "", ""},
		{"no stamp", "Receiving objects: 20%", "", "Receiving objects: 20%"},
		{"single word", "STEP", "", "STEP"},
		{"leading space", " 2026-09-01T02:23:45Z text", "", " 2026-09-01T02:23:45Z text"},
		{"date only lookalike", "2026-09-01 02:23:45 text", "", "2026-09-01 02:23:45 text"},
		{"stamp with suffix lookalike", "2026-09-01T02:23:45Zfoo text", "", "2026-09-01T02:23:45Zfoo text"},
		{"stamp missing zone", "2026-09-01T02:23:45 text", "", "2026-09-01T02:23:45 text"},
		{"impossible month", "2026-13-01T02:23:45Z text", "", "2026-13-01T02:23:45Z text"},
		{"multiple spaces keep the rest intact", "2026-09-01T02:23:45Z  two  spaces", "2026-09-01T02:23:45Z", " two  spaces"},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			stamp, text := ParseTimestampedLine(testCase.line)
			if stamp != testCase.wantStamp || text != testCase.wantText {
				t.Fatalf("ParseTimestampedLine(%q) = (%q, %q), want (%q, %q)", testCase.line, stamp, text, testCase.wantStamp, testCase.wantText)
			}
		})
	}
}

func TestStepMessageKeyOrderAndOmittedTime(t *testing.T) {
	withTime, _ := json.Marshal(stepMessage{Type: "step", Data: "[fetch] hi", Container: "fetch", Time: "2026-09-01T00:00:00Z"})
	if string(withTime) != `{"type":"step","data":"[fetch] hi","container":"fetch","time":"2026-09-01T00:00:00Z"}` {
		t.Fatalf("unexpected step JSON: %s", withTime)
	}
	withoutTime, _ := json.Marshal(stepMessage{Type: "step", Data: "[fetch] hi", Container: "fetch"})
	if string(withoutTime) != `{"type":"step","data":"[fetch] hi","container":"fetch"}` {
		t.Fatalf("unexpected step JSON without time: %s", withoutTime)
	}
	steps, _ := json.Marshal(stepsMessage{Type: "steps", Data: []string{}})
	if string(steps) != `{"type":"steps","data":[]}` {
		t.Fatalf("unexpected steps JSON: %s", steps)
	}
}
