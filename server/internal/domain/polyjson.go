package domain

import "encoding/json"

type gitPublishJSON struct {
	Type       string  `json:"type"`
	Repository *string `json:"repository"`
	Branch     *string `json:"branch"`
}

type zipPublishJSON struct {
	Type      string  `json:"type"`
	ObjectKey *string `json:"objectKey"`
	URL       *string `json:"url"`
}

type anyPublishJSON struct {
	Type       string  `json:"type"`
	Repository *string `json:"repository"`
	Branch     *string `json:"branch"`
	ObjectKey  *string `json:"objectKey"`
	URL        *string `json:"url"`
}

// MarshalJSON renders the exact Jackson shape for each variant.
func (p PublishConfig) MarshalJSON() ([]byte, error) {
	if p.Type == SourceZip {
		return json.Marshal(zipPublishJSON{Type: "ZIP", ObjectKey: p.ObjectKey, URL: p.URL})
	}
	return json.Marshal(gitPublishJSON{Type: "GIT", Repository: p.Repository, Branch: p.Branch})
}

func (p *PublishConfig) UnmarshalJSON(data []byte) error {
	var raw anyPublishJSON
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	switch raw.Type {
	case "ZIP":
		*p = PublishConfig{Type: SourceZip, ObjectKey: raw.ObjectKey, URL: raw.URL}
	case "GIT":
		*p = PublishConfig{Type: SourceGit, Repository: raw.Repository, Branch: raw.Branch}
	default:
		return &json.UnmarshalTypeError{Value: "publish config type " + raw.Type}
	}
	return nil
}

type sourceConfigJSON struct {
	Type       string  `json:"type"`
	Repository *string `json:"repository,omitempty"`
}

// MarshalJSON: {"type":"GIT","repository":...} or {"type":"ZIP"}.
func (s SourceConfig) MarshalJSON() ([]byte, error) {
	if s.Type == SourceZip {
		return []byte(`{"type":"ZIP"}`), nil
	}
	return json.Marshal(struct {
		Type       string  `json:"type"`
		Repository *string `json:"repository"`
	}{"GIT", s.Repository})
}

func (s *SourceConfig) UnmarshalJSON(data []byte) error {
	var raw sourceConfigJSON
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	switch raw.Type {
	case "ZIP":
		*s = SourceConfig{Type: SourceZip}
	case "GIT":
		*s = SourceConfig{Type: SourceGit, Repository: raw.Repository}
	default:
		return &json.UnmarshalTypeError{Value: "source config type " + raw.Type}
	}
	return nil
}
