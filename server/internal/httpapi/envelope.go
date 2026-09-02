// Package httpapi is the HTTP/WebSocket/SSE surface of OOPS: the chi router,
// the auth middleware, and one handler file per resource.
package httpapi

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"github.com/wellch4n/oops/server/internal/domain"
)

// Result is the envelope every JSON endpoint answers with, always HTTP 200.
type Result struct {
	Success bool    `json:"success"`
	Message *string `json:"message"`
	Data    any     `json:"data"`
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	encoder := json.NewEncoder(w)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(body); err != nil {
		slog.Debug("write response failed", "error", err)
	}
}

// OK writes {"success":true,"message":null,"data":data}.
func OK(w http.ResponseWriter, data any) {
	writeJSON(w, http.StatusOK, Result{Success: true, Data: data})
}

// Fail writes {"success":false,"message":msg,"data":null}.
func Fail(w http.ResponseWriter, message string) {
	writeJSON(w, http.StatusOK, Result{Success: false, Message: &message})
}

// Error maps an error onto the envelope: a BizError carries its message, any
// other error becomes "Internal server error" (and is logged), exactly like
// GlobalExceptionHandler.
func Error(w http.ResponseWriter, r *http.Request, err error) {
	if message := domain.BizMessage(err); message != "" {
		Fail(w, message)
		return
	}
	slog.Error("Unhandled exception", "method", r.Method, "path", r.URL.Path, "error", err)
	Fail(w, domain.InternalServerErrorMessage)
}

// Respond writes data or the mapped error.
func Respond(w http.ResponseWriter, r *http.Request, data any, err error) {
	if err != nil {
		Error(w, r, err)
		return
	}
	OK(w, data)
}

// ErrBadRequest marks a malformed request body/params; Java surfaced these as
// "Internal server error" too, so it is mapped the same way but not logged as
// loudly.
var ErrBadRequest = errors.New("bad request")

// DecodeJSON reads a JSON body; unknown fields are ignored like Jackson.
func DecodeJSON(r *http.Request, target any) error {
	if r.Body == nil {
		return ErrBadRequest
	}
	decoder := json.NewDecoder(r.Body)
	if err := decoder.Decode(target); err != nil {
		return errors.Join(ErrBadRequest, err)
	}
	return nil
}

// Page mirrors the Java Page record.
type Page[T any] struct {
	Total      int64 `json:"total"`
	Data       []T   `json:"data"`
	Size       int   `json:"size"`
	TotalPages int   `json:"totalPages"`
}

// EmptyIfNil turns a nil slice into an empty one so it renders as [].
func EmptyIfNil[T any](items []T) []T {
	if items == nil {
		return []T{}
	}
	return items
}
