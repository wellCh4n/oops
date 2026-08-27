package store

import (
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
)

// NewNanoID delegates to the shared identity scheme in domain.
func NewNanoID() string { return domain.NewID() }

// Now returns the creation timestamp the way BaseDataObject.prePersist does.
func Now() *LocalDateTime {
	return &LocalDateTime{Time: time.Now().UTC()}
}

// IsValidResourceName re-exports the domain rule for existing callers.
func IsValidResourceName(name string) bool { return domain.IsValidResourceName(name) }
