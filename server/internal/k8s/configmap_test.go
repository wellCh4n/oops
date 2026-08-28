package k8s

import (
	"testing"
)

// Mirrors ConfigMetaAnnotationTests + ConfigMapItem naming tests.

func TestResourceNameOf(t *testing.T) {
	cases := []struct{ key, want string }{
		{"DATABASE_URL", "database-url"},
		{"app.config.yaml", "app-config-yaml"},
		{"simple", "simple"},
		{"--weird--", "weird"},
		{"", "item"},
		{"###", "item"},
	}
	for _, c := range cases {
		if got := ResourceNameOf(c.key); got != c.want {
			t.Errorf("ResourceNameOf(%q) = %q, want %q", c.key, got, c.want)
		}
	}
	long := ResourceNameOf("a-very-long-key-name-that-exceeds-fifty-characters-for-sure")
	if len(long) > 50 {
		t.Errorf("names are capped at 50 chars, got %d", len(long))
	}
}

// The oops.mounts / oops.config-meta annotations round-trip through
// annotationValues + readAnnotationMap without losing keys.
func TestConfigAnnotationRoundTrip(t *testing.T) {
	group, comment := "database", "primary DSN"
	mounts := map[string]string{"app.yaml": "/etc/app/app.yaml"}
	metas := map[string]configMeta{"DB_URL": {Group: &group, Comment: &comment}}

	annotations := annotationValues(mounts, metas)
	if annotations[mountAnnotation] == "" || annotations[configMetaAnnotation] == "" {
		t.Fatalf("annotations missing: %v", annotations)
	}

	restoredMounts := readAnnotationMap[string](annotations, mountAnnotation)
	if restoredMounts["app.yaml"] != "/etc/app/app.yaml" {
		t.Errorf("mounts lost: %v", restoredMounts)
	}
	restoredMetas := readAnnotationMap[configMeta](annotations, configMetaAnnotation)
	meta := restoredMetas["DB_URL"]
	if meta.Group == nil || *meta.Group != "database" || meta.Comment == nil || *meta.Comment != "primary DSN" {
		t.Errorf("meta lost: %+v", meta)
	}
}

func TestConfigAnnotationsOmittedWhenEmpty(t *testing.T) {
	if annotations := annotationValues(nil, nil); len(annotations) != 0 {
		t.Errorf("empty inputs must produce no annotations: %v", annotations)
	}
}

func TestReadAnnotationMapTolerancesGarbage(t *testing.T) {
	annotations := map[string]string{mountAnnotation: "{not json"}
	if parsed := readAnnotationMap[string](annotations, mountAnnotation); len(parsed) != 0 {
		t.Errorf("garbage annotation must parse to empty, got %v", parsed)
	}
	if parsed := readAnnotationMap[string](nil, mountAnnotation); parsed == nil {
		t.Error("absent annotation must return an empty (non-nil) map")
	}
}

func TestBuildConfigItemCarriesMeta(t *testing.T) {
	group := "cache"
	item := buildConfigItem("REDIS_URL", "redis://x", true, nil, &configMeta{Group: &group})
	if !item.Secret || item.Name != "redis-url" || item.Group == nil || *item.Group != "cache" {
		t.Errorf("item = %+v", item)
	}
	plain := buildConfigItem("KEY", "v", false, nil, nil)
	if plain.Group != nil || plain.Comment != nil {
		t.Errorf("nil meta must leave meta fields nil: %+v", plain)
	}
}
