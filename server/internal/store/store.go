// Package store is the MySQL persistence adapter, built on GORM. It reads the
// same schema the Java backend manages via Flyway; this process never runs
// migrations itself. The exported views keep the exact JSON shapes the Java
// DTOs serialize to.
package store

import (
	"errors"
	"fmt"
	"time"

	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// notFound translates GORM's sentinel to the store's own.
func notFound(err error) error {
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return ErrNotFound
	}
	return err
}

type Store struct {
	orm   *gorm.DB
	codec Codec
}

func Open(dsn string) (*Store, error) {
	orm, err := gorm.Open(mysql.Open(dsn), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		return nil, err
	}
	sqlDB, err := orm.DB()
	if err != nil {
		return nil, err
	}
	sqlDB.SetMaxOpenConns(10)
	sqlDB.SetMaxIdleConns(5)
	sqlDB.SetConnMaxLifetime(30 * time.Minute)
	if err := sqlDB.Ping(); err != nil {
		return nil, fmt.Errorf("ping mysql: %w", err)
	}
	return &Store{orm: orm}, nil
}

func (s *Store) Close() error {
	sqlDB, err := s.orm.DB()
	if err != nil {
		return err
	}
	return sqlDB.Close()
}
