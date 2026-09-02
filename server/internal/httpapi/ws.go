package httpapi

import (
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	CheckOrigin:     func(*http.Request) bool { return true }, // setAllowedOrigins("*")
}

// wsSink adapts a gorilla connection to the stream.Sink contract.
type wsSink struct {
	mu     sync.Mutex
	conn   *websocket.Conn
	closed bool
}

func newWSSink(conn *websocket.Conn) *wsSink { return &wsSink{conn: conn} }

func (s *wsSink) IsOpen() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return !s.closed
}

func (s *wsSink) SendText(text string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil
	}
	return s.conn.WriteMessage(websocket.TextMessage, []byte(text))
}

func (s *wsSink) SendBinary(data []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil
	}
	return s.conn.WriteMessage(websocket.BinaryMessage, data)
}

func (s *wsSink) SendPing() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil
	}
	return s.conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second))
}

func (s *wsSink) closeWith(code int, reason string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil
	}
	s.closed = true
	_ = s.conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(code, reason), time.Now().Add(5*time.Second))
	return s.conn.Close()
}

// Close is a normal close (1000).
func (s *wsSink) Close() error { return s.closeWith(websocket.CloseNormalClosure, "") }

// CloseWithError is 1011.
func (s *wsSink) CloseWithError() error { return s.closeWith(websocket.CloseInternalServerErr, "") }

// ClosePolicy is 1008 with a reason (bad path, missing pipeline...).
func (s *wsSink) ClosePolicy(reason string) error {
	return s.closeWith(websocket.ClosePolicyViolation, reason)
}

// heartbeat sends a native ping every 10s while the sink is open.
func (s *wsSink) heartbeat(stop <-chan struct{}) {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			if !s.IsOpen() || s.SendPing() != nil {
				return
			}
		}
	}
}
