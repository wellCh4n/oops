package domain

import (
	"math"
	"regexp"
	"strconv"
	"strings"
)

var quantityPattern = regexp.MustCompile(`^(\d+(?:\.\d+)?(?:[eE][-+]?\d+)?)\s*([a-zA-Z]*)$`)

var quantityMultipliers = map[string]float64{
	"": 1, "m": 1e-3, "k": 1e3, "K": 1e3, "M": 1e6, "G": 1e9, "T": 1e12, "P": 1e15, "E": 1e18,
	"Ki": 1024, "Mi": math.Pow(1024, 2), "Gi": math.Pow(1024, 3), "Ti": math.Pow(1024, 4), "Pi": math.Pow(1024, 5), "Ei": math.Pow(1024, 6),
}

func parseQuantity(q *string) (float64, bool) {
	if q == nil {
		return 0, false
	}
	m := quantityPattern.FindStringSubmatch(strings.TrimSpace(*q))
	if m == nil {
		return 0, false
	}
	value, err := strconv.ParseFloat(m[1], 64)
	if err != nil {
		return 0, false
	}
	mult, ok := quantityMultipliers[m[2]]
	if !ok {
		return 0, false
	}
	return value * mult, true
}

// QuantityToMillicores mirrors ResourceQuantities.toMillicores (nil on failure).
func QuantityToMillicores(q *string) *int64 {
	v, ok := parseQuantity(q)
	if !ok {
		return nil
	}
	r := int64(math.Round(v * 1000))
	return &r
}

// QuantityToBytes mirrors ResourceQuantities.toBytes (nil on failure).
func QuantityToBytes(q *string) *int64 {
	v, ok := parseQuantity(q)
	if !ok {
		return nil
	}
	r := int64(math.Round(v))
	return &r
}
