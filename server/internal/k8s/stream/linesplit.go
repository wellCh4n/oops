package stream

import (
	"bufio"
	"bytes"
	"io"
)

// maxLineBytes bounds a single log line; Java's BufferedReader had no cap, but
// a scanner needs one and 16 MiB is far beyond any real container output line.
const maxLineBytes = 16 * 1024 * 1024

// NewLineScanner returns a scanner that yields lines terminated by "\n", "\r"
// or "\r\n", exactly like java.io.BufferedReader.readLine. The terminators are
// stripped; a trailing fragment without a terminator is yielded at EOF.
func NewLineScanner(reader io.Reader) *bufio.Scanner {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64*1024), maxLineBytes)
	scanner.Split(ScanLines)
	return scanner
}

// ScanLines is the bufio.SplitFunc behind NewLineScanner.
func ScanLines(data []byte, atEOF bool) (advance int, token []byte, err error) {
	if atEOF && len(data) == 0 {
		return 0, nil, nil
	}
	index := bytes.IndexAny(data, "\r\n")
	if index < 0 {
		if atEOF {
			return len(data), data, nil
		}
		return 0, nil, nil
	}
	if data[index] == '\n' {
		return index + 1, data[:index], nil
	}
	// A bare "\r": look one byte further to see whether "\n" follows.
	if index+1 < len(data) {
		if data[index+1] == '\n' {
			return index + 2, data[:index], nil
		}
		return index + 1, data[:index], nil
	}
	if atEOF {
		return index + 1, data[:index], nil
	}
	// Need more data to tell "\r" from "\r\n".
	return 0, nil, nil
}
