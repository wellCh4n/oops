package store

import "context"

type Namespace struct {
	ID          string         `json:"id"`
	CreatedTime *LocalDateTime `json:"createdTime"`
	Name        string         `json:"name"`
	Description *string        `json:"description"`
}

func (s *Store) ListNamespaces(ctx context.Context) ([]Namespace, error) {
	rows, err := s.db.QueryContext(ctx,
		"SELECT id, created_time, name, description FROM namespace ORDER BY created_time")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	namespaces := []Namespace{}
	for rows.Next() {
		var namespace Namespace
		if err := rows.Scan(&namespace.ID, &namespace.CreatedTime, &namespace.Name, &namespace.Description); err != nil {
			return nil, err
		}
		namespaces = append(namespaces, namespace)
	}
	return namespaces, rows.Err()
}

func (s *Store) CreateNamespace(ctx context.Context, name, description string) error {
	_, err := s.db.ExecContext(ctx,
		"INSERT INTO namespace (id, created_time, name, description) VALUES (?, ?, ?, ?)",
		NewNanoID(), Now(), name, description)
	return err
}

func (s *Store) UpdateNamespaceDescription(ctx context.Context, name, description string) error {
	_, err := s.db.ExecContext(ctx,
		"UPDATE namespace SET description = ? WHERE name = ?", description, name)
	return err
}
