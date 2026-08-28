package k8s

import (
	"bufio"
	"strings"
	"testing"
)

func scanAll(t *testing.T, input string) []string {
	t.Helper()
	scanner := bufio.NewScanner(strings.NewReader(input))
	scanner.Split(ScanLogLines)
	lines := []string{}
	for scanner.Scan() {
		lines = append(lines, scanner.Text())
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	return lines
}

func TestScanLogLines(t *testing.T) {
	cases := []struct {
		input string
		want  []string
	}{
		{"a\nb\n", []string{"a", "b"}},
		{"a\r\nb\r\n", []string{"a", "b"}},
		// git-style progress: same line redrawn with bare \r
		{"Receiving objects: 1% (1/88)\rReceiving objects: 2% (2/88)\rdone\n", []string{"Receiving objects: 1% (1/88)", "Receiving objects: 2% (2/88)", "done"}},
		{"no trailing newline", []string{"no trailing newline"}},
		{"mixed\rline\nendings\r\nhere", []string{"mixed", "line", "endings", "here"}},
		{"trailing cr\r", []string{"trailing cr"}},
		{"", nil},
	}
	for _, c := range cases {
		got := scanAll(t, c.input)
		if strings.Join(got, "|") != strings.Join(c.want, "|") {
			t.Errorf("input %q: got %v, want %v", c.input, got, c.want)
		}
	}
}
