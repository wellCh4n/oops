// Package sqltypes holds the scanner/valuer helpers the row structs need for
// column types database/sql cannot map on its own.
package sqltypes

import (
	"database/sql/driver"
	"fmt"
)

// BitBool maps a MySQL bit(1) column. The driver hands a bit(1) back as a
// one-byte slice (0x00 / 0x01), which database/sql refuses to convert into a
// bool, so the conversion is done here.
type BitBool bool

// Scan implements sql.Scanner.
func (b *BitBool) Scan(src any) error {
	switch value := src.(type) {
	case nil:
		*b = false
	case bool:
		*b = BitBool(value)
	case int64:
		*b = value != 0
	case []byte:
		*b = len(value) > 0 && value[len(value)-1] != 0 && value[len(value)-1] != '0'
	case string:
		*b = value != "" && value != "0" && value != "\x00"
	default:
		return fmt.Errorf("cannot scan %T into BitBool", src)
	}
	return nil
}

// Value implements driver.Valuer; MySQL accepts an integer for a bit(1) column.
func (b BitBool) Value() (driver.Value, error) {
	if b {
		return int64(1), nil
	}
	return int64(0), nil
}
