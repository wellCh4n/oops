// Package stream holds the Kubernetes streaming adapters behind the WebSocket
// handlers: the interactive pod terminal, the plain pod log follow and the
// pipeline build-log follow. Every adapter is transport-agnostic and talks to
// its client only through Sink, so the HTTP layer owns the socket.
package stream

// Sink is the outbound half of a client connection. Implementations must be
// safe for concurrent use and must turn every method into a no-op once the
// connection is closed.
type Sink interface {
	IsOpen() bool
	SendText(text string) error
	SendBinary(data []byte) error
	// Close closes the connection normally (WebSocket 1000).
	Close() error
	// CloseWithError closes the connection with a server error (WebSocket 1011).
	CloseWithError() error
}
