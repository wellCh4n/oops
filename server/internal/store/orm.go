package store

import (
	"database/sql/driver"
	"encoding/json"
	"fmt"

	"gorm.io/gorm/schema"
)

// JSONField is the GORM counterpart of the Java @AttributeConverter JSON
// columns: a typed value serialized as a JSON TEXT column. Valid mirrors
// column NULL-ness so views can keep the null-vs-[] distinction Jackson has.
type JSONField[T any] struct {
	Data  T
	Valid bool
}

func jsonOf[T any](data T) JSONField[T] {
	return JSONField[T]{Data: data, Valid: true}
}

func (field *JSONField[T]) Scan(value any) error {
	field.Valid = false
	var raw []byte
	switch typed := value.(type) {
	case nil:
		return nil
	case []byte:
		raw = typed
	case string:
		raw = []byte(typed)
	default:
		return fmt.Errorf("cannot scan %T into JSONField", value)
	}
	if len(raw) == 0 {
		return nil
	}
	if err := json.Unmarshal(raw, &field.Data); err != nil {
		return fmt.Errorf("decode json column: %w", err)
	}
	field.Valid = true
	return nil
}

func (field JSONField[T]) Value() (driver.Value, error) {
	if !field.Valid {
		return nil, nil
	}
	encoded, err := json.Marshal(field.Data)
	if err != nil {
		return nil, err
	}
	return string(encoded), nil
}

// GormDataType keeps GORM from guessing a type for the wrapper.
func (JSONField[T]) GormDataType() string { return "text" }

var _ schema.GormDataTypeInterface = JSONField[any]{}
