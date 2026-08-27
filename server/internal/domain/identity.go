package domain

import (
	"crypto/rand"
	"math/big"
)

// NewID matches shared/util/NanoIdUtils: 24 chars over the lowercase
// alphanumeric alphabet, from a CSPRNG. Every entity identity in OOPS uses it.
const idAlphabet = "abcdefghijklmnopqrstuvwxyz0123456789"

func NewID() string {
	id := make([]byte, 24)
	alphabetSize := big.NewInt(int64(len(idAlphabet)))
	for i := range id {
		index, err := rand.Int(rand.Reader, alphabetSize)
		if err != nil {
			panic(err) // crypto/rand failure is unrecoverable
		}
		id[i] = idAlphabet[index.Int64()]
	}
	return string(id)
}
