package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
)

// sseWriter writes "event:<name>\ndata:<payload>\n\n" frames.
type sseWriter struct {
	mu      sync.Mutex
	w       http.ResponseWriter
	flusher http.Flusher
}

func newSSE(w http.ResponseWriter) (*sseWriter, bool) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		return nil, false
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	flusher.Flush()
	return &sseWriter{w: w, flusher: flusher}, true
}

func (s *sseWriter) Event(name, data string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := fmt.Fprintf(s.w, "event:%s\ndata:%s\n\n", name, data); err != nil {
		return err
	}
	s.flusher.Flush()
	return nil
}

// EventJSON writes a frame whose payload is the JSON encoding of value.
func (s *sseWriter) EventJSON(name string, value any) error {
	encoded, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return s.Event(name, string(encoded))
}
