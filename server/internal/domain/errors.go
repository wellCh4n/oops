package domain

import (
	"errors"
	"fmt"
)

// BizError is a business rule violation. Its message is returned to the client
// verbatim inside the Result envelope with HTTP 200 and success=false. Every
// other error becomes "Internal server error".
type BizError struct {
	Message string
	Cause   error
}

func (e *BizError) Error() string { return e.Message }
func (e *BizError) Unwrap() error { return e.Cause }

// Biz creates a BizError with a literal message.
func Biz(message string) error { return &BizError{Message: message} }

// Bizf creates a BizError with a formatted message.
func Bizf(format string, args ...any) error {
	return &BizError{Message: fmt.Sprintf(format, args...)}
}

// BizWrap creates a BizError that keeps the cause for logging.
func BizWrap(message string, cause error) error {
	return &BizError{Message: message, Cause: cause}
}

// IsBiz reports whether err is (or wraps) a BizError.
func IsBiz(err error) bool {
	var target *BizError
	return errors.As(err, &target)
}

// BizMessage returns the business message of err, or "" if err is not a BizError.
func BizMessage(err error) string {
	var target *BizError
	if errors.As(err, &target) {
		return target.Message
	}
	return ""
}

// InternalServerErrorMessage is the message the Java backend returned for every
// unhandled exception. Kept as a constant because the integration suite pins it.
const InternalServerErrorMessage = "Internal server error"
