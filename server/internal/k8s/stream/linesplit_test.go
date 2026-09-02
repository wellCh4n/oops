package stream

import (
	"reflect"
	"strings"
	"testing"
)

func scanAll(t *testing.T, input string) []string {
	t.Helper()
	scanner := NewLineScanner(strings.NewReader(input))
	var lines []string
	for scanner.Scan() {
		lines = append(lines, scanner.Text())
	}
	if err := scanner.Err(); err != nil {
		t.Fatalf("scanner error: %v", err)
	}
	return lines
}

func TestScanLinesSplitsOnEveryTerminator(t *testing.T) {
	cases := []struct {
		name  string
		input string
		want  []string
	}{
		{"empty", "", nil},
		{"newline only", "a\nb\n", []string{"a", "b"}},
		{"no trailing terminator", "a\nb", []string{"a", "b"}},
		{"carriage return only", "a\rb\rc", []string{"a", "b", "c"}},
		{"crlf", "a\r\nb\r\n", []string{"a", "b"}},
		{"crlf is one terminator", "a\r\nb", []string{"a", "b"}},
		{"mixed", "a\rb\nc\r\nd", []string{"a", "b", "c", "d"}},
		{"empty lines preserved", "a\n\nb", []string{"a", "", "b"}},
		{"trailing bare cr", "a\r", []string{"a"}},
		{"progress redraw", "2026-09-01T00:00:00Z Receiving 10%\rReceiving 20%\rReceiving 100%\n", []string{"2026-09-01T00:00:00Z Receiving 10%", "Receiving 20%", "Receiving 100%"}},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			got := scanAll(t, testCase.input)
			if !reflect.DeepEqual(got, testCase.want) {
				t.Fatalf("got %q, want %q", got, testCase.want)
			}
		})
	}
}

// A "\r" that lands exactly on a read boundary must still be merged with a
// following "\n"; drive the split function by hand to cover the buffering path.
func TestScanLinesAsksForMoreDataOnTrailingCarriageReturn(t *testing.T) {
	advance, token, err := ScanLines([]byte("abc\r"), false)
	if err != nil || advance != 0 || token != nil {
		t.Fatalf("expected request for more data, got advance=%d token=%q err=%v", advance, token, err)
	}
	advance, token, err = ScanLines([]byte("abc\r\nrest"), false)
	if err != nil || advance != 5 || string(token) != "abc" {
		t.Fatalf("expected crlf consumed as one terminator, got advance=%d token=%q err=%v", advance, token, err)
	}
}
