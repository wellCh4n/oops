package k8s

import "bytes"

// ScanLogLines is a bufio.SplitFunc matching Java's BufferedReader.readLine:
// a line ends at "\n", "\r\n" or a bare "\r". The default bufio.ScanLines
// only splits on "\n", which glues together progress output (git, curl) that
// redraws its line with carriage returns.
func ScanLogLines(data []byte, atEOF bool) (advance int, token []byte, err error) {
	if atEOF && len(data) == 0 {
		return 0, nil, nil
	}
	if index := bytes.IndexAny(data, "\r\n"); index >= 0 {
		if data[index] == '\n' {
			return index + 1, data[:index], nil
		}
		// "\r": also consume a directly following "\n" ("\r\n").
		if index+1 < len(data) {
			if data[index+1] == '\n' {
				return index + 2, data[:index], nil
			}
			return index + 1, data[:index], nil
		}
		// "\r" at the buffer edge: without more data we cannot tell "\r"
		// from "\r\n", so ask for more unless the stream is done.
		if atEOF {
			return index + 1, data[:index], nil
		}
		return 0, nil, nil
	}
	if atEOF {
		return len(data), data, nil
	}
	return 0, nil, nil
}
