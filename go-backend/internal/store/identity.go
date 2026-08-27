package store

import (
	"crypto/rand"
	"math/big"
	"regexp"
	"time"
)

// NewNanoID matches shared/util/NanoIdUtils on the Java side:
// 24 chars over the lowercase alphanumeric alphabet, from a CSPRNG.
const nanoIDAlphabet = "abcdefghijklmnopqrstuvwxyz0123456789"

func NewNanoID() string {
	id := make([]byte, 24)
	alphabetSize := big.NewInt(int64(len(nanoIDAlphabet)))
	for i := range id {
		index, err := rand.Int(rand.Reader, alphabetSize)
		if err != nil {
			panic(err) // crypto/rand failure is unrecoverable
		}
		id[i] = nanoIDAlphabet[index.Int64()]
	}
	return string(id)
}

// Now returns the creation timestamp the way BaseDataObject.prePersist does.
func Now() *LocalDateTime {
	return &LocalDateTime{Time: time.Now().UTC()}
}

// validResourceName mirrors shared/util/ResourceNameChecker: lowercase RFC-1123
// label, max 24 chars.
var validResourceName = regexp.MustCompile(`^[a-z]([-a-z0-9]*[a-z0-9])?$`)

func IsValidResourceName(name string) bool {
	return name != "" && len(name) <= 24 && validResourceName.MatchString(name)
}
