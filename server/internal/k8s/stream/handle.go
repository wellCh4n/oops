package stream

import (
	"context"
	"io"
	"sync"
	"time"
)

// handle mirrors KubernetesStreamHandle: a thread-safe bag of closers with an
// idempotent Close that also cancels the context every stream runs under.
type handle struct {
	ctx    context.Context
	cancel context.CancelFunc

	mu        sync.Mutex
	closed    bool
	resources []io.Closer
}

func newHandle(parent context.Context) *handle {
	if parent == nil {
		parent = context.Background()
	}
	ctx, cancel := context.WithCancel(parent)
	return &handle{ctx: ctx, cancel: cancel}
}

// add registers a closer, closing it immediately if the handle is already closed.
func (h *handle) add(resource io.Closer) {
	if resource == nil {
		return
	}
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		_ = resource.Close()
		return
	}
	h.resources = append(h.resources, resource)
	h.mu.Unlock()
}

// remove forgets a closer without closing it.
func (h *handle) remove(resource io.Closer) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for index, candidate := range h.resources {
		if candidate == resource {
			h.resources = append(h.resources[:index], h.resources[index+1:]...)
			return
		}
	}
}

// isOpen reports whether both the handle and the sink are still open.
func (h *handle) isOpen(sink Sink) bool {
	h.mu.Lock()
	closed := h.closed
	h.mu.Unlock()
	return !closed && sink.IsOpen()
}

// Close cancels the context and closes every registered resource. Idempotent.
func (h *handle) Close() error {
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return nil
	}
	h.closed = true
	resources := h.resources
	h.resources = nil
	h.mu.Unlock()

	h.cancel()
	for _, resource := range resources {
		_ = resource.Close()
	}
	return nil
}

// sleep waits for the duration or until the context is done; it reports false
// when interrupted.
func sleep(ctx context.Context, duration time.Duration) bool {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
