package store

import (
	"database/sql"
	"testing"

	"github.com/wellch4n/oops/server/internal/domain"
)

func column(text string) sql.NullString { return sql.NullString{String: text, Valid: true} }

func TestEncodeSlice(t *testing.T) {
	tests := []struct {
		name  string
		items []domain.BuildEnvironmentConfig
		want  sql.NullString
	}{
		{name: "nil slice is unset", items: nil, want: sql.NullString{}},
		{name: "empty slice is []", items: []domain.BuildEnvironmentConfig{}, want: column("[]")},
		{
			name:  "nulls are included",
			items: []domain.BuildEnvironmentConfig{{Environment: domain.Ptr("prod")}},
			want:  column(`[{"environment":"prod","buildCommand":null}]`),
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, err := encodeSlice(test.items)
			if err != nil {
				t.Fatal(err)
			}
			if got != test.want {
				t.Fatalf("got %#v want %#v", got, test.want)
			}
		})
	}
}

func TestDecodeSlice(t *testing.T) {
	tests := []struct {
		name      string
		column    sql.NullString
		wantNil   bool
		wantCount int
	}{
		{name: "unset column is nil", column: sql.NullString{}, wantNil: true},
		{name: "blank column is nil", column: column("   "), wantNil: true},
		{name: "[] is an empty slice", column: column("[]"), wantNil: false, wantCount: 0},
		{name: "legacy environmentName key is ignored", column: column(`[{"environmentName":"prod","buildCommand":"x"}]`), wantCount: 1},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, err := decodeSlice[domain.BuildEnvironmentConfig](test.column)
			if err != nil {
				t.Fatal(err)
			}
			if test.wantNil {
				if got != nil {
					t.Fatalf("expected nil, got %#v", got)
				}
				return
			}
			if got == nil || len(got) != test.wantCount {
				t.Fatalf("expected %d items, got %#v", test.wantCount, got)
			}
			if test.wantCount == 1 && got[0].Environment != nil {
				t.Fatalf("legacy key must not populate environment, got %q", *got[0].Environment)
			}
		})
	}
	if _, err := decodeSlice[domain.BuildEnvironmentConfig](column("{not json")); err == nil {
		t.Fatal("malformed JSON must fail the read")
	}
}

func TestServiceEnvironmentConfigs(t *testing.T) {
	t.Run("basic auth fields omitted when nil", func(t *testing.T) {
		encoded, err := encodeSlice([]domain.ServiceEnvironmentConfig{{
			Environment: domain.Ptr("prod"), Host: domain.Ptr("app.example.com"), HTTPS: domain.Ptr(true),
		}})
		if err != nil {
			t.Fatal(err)
		}
		if encoded.String != `[{"environment":"prod","host":"app.example.com","https":true}]` {
			t.Fatalf("unexpected JSON %s", encoded.String)
		}
	})
	t.Run("basic auth fields written when set", func(t *testing.T) {
		encoded, err := encodeSlice([]domain.ServiceEnvironmentConfig{{
			Environment: domain.Ptr("prod"), Host: domain.Ptr("app.example.com"), HTTPS: domain.Ptr(true),
			BasicAuthEnabled: domain.Ptr(true), BasicAuthUsername: domain.Ptr("visitor"), BasicAuthPasswordHash: domain.Ptr("$2a$10$x"),
		}})
		if err != nil {
			t.Fatal(err)
		}
		want := `[{"environment":"prod","host":"app.example.com","https":true,"basicAuthEnabled":true,"basicAuthUsername":"visitor","basicAuthPasswordHash":"$2a$10$x"}]`
		if encoded.String != want {
			t.Fatalf("unexpected JSON %s", encoded.String)
		}
	})
	t.Run("https null is kept", func(t *testing.T) {
		encoded, err := encodeSlice([]domain.ServiceEnvironmentConfig{{Environment: domain.Ptr("prod")}})
		if err != nil {
			t.Fatal(err)
		}
		if encoded.String != `[{"environment":"prod","host":null,"https":null}]` {
			t.Fatalf("unexpected JSON %s", encoded.String)
		}
	})
	t.Run("legacy keys ignored and https defaults to true when missing", func(t *testing.T) {
		decoded, err := decodeServiceEnvironmentConfigs(column(`[{"environmentName":"prod","host":"a.example.com","basicAuthPassword":"plain"}]`))
		if err != nil {
			t.Fatal(err)
		}
		if len(decoded) != 1 {
			t.Fatalf("expected one item, got %d", len(decoded))
		}
		item := decoded[0]
		if item.Environment != nil {
			t.Fatalf("environmentName must be ignored, got %q", *item.Environment)
		}
		if item.HTTPS == nil || !*item.HTTPS {
			t.Fatalf("missing https must default to true, got %v", item.HTTPS)
		}
		if item.BasicAuthEnabled != nil || item.BasicAuthUsername != nil || item.BasicAuthPasswordHash != nil {
			t.Fatal("basic auth must stay unset")
		}
	})
	t.Run("explicit https null stays null", func(t *testing.T) {
		decoded, err := decodeServiceEnvironmentConfigs(column(`[{"environment":"prod","https":null}]`))
		if err != nil {
			t.Fatal(err)
		}
		if decoded[0].HTTPS != nil {
			t.Fatalf("explicit null must stay nil, got %v", *decoded[0].HTTPS)
		}
	})
	t.Run("unset column is nil", func(t *testing.T) {
		decoded, err := decodeServiceEnvironmentConfigs(column(""))
		if err != nil || decoded != nil {
			t.Fatalf("got %#v, %v", decoded, err)
		}
	})
}

func TestHealthCheck(t *testing.T) {
	t.Run("unset column is nil", func(t *testing.T) {
		decoded, err := decodeHealthCheck(column(""))
		if err != nil || decoded != nil {
			t.Fatalf("got %#v, %v", decoded, err)
		}
	})
	t.Run("missing readiness yields default probe", func(t *testing.T) {
		decoded, err := decodeHealthCheck(column(`{"liveness":{"enabled":true,"path":"/healthz","initialDelaySeconds":5,"periodSeconds":10,"timeoutSeconds":3,"failureThreshold":3}}`))
		if err != nil {
			t.Fatal(err)
		}
		if decoded.Liveness == nil || !*decoded.Liveness.Enabled || *decoded.Liveness.Path != "/healthz" || *decoded.Liveness.InitialDelaySeconds != 5 {
			t.Fatalf("liveness mis-read: %#v", decoded.Liveness)
		}
		readiness := decoded.Readiness
		if readiness == nil || *readiness.Enabled || *readiness.Path != "/" || *readiness.InitialDelaySeconds != 30 || *readiness.PeriodSeconds != 10 || *readiness.TimeoutSeconds != 3 || *readiness.FailureThreshold != 3 {
			t.Fatalf("readiness must be the default probe: %#v", readiness)
		}
	})
	t.Run("missing probe field keeps java default, explicit null stays null", func(t *testing.T) {
		decoded, err := decodeHealthCheck(column(`{"liveness":{"enabled":true,"path":null},"readiness":null}`))
		if err != nil {
			t.Fatal(err)
		}
		if decoded.Liveness.Path != nil {
			t.Fatal("explicit null path must stay nil")
		}
		if *decoded.Liveness.PeriodSeconds != 10 {
			t.Fatal("missing periodSeconds must keep the default 10")
		}
		if decoded.Readiness != nil {
			t.Fatal("explicit null readiness must stay nil")
		}
	})
	t.Run("round trip shape", func(t *testing.T) {
		encoded, err := encodeObject(domain.DefaultHealthCheck())
		if err != nil {
			t.Fatal(err)
		}
		want := `{"liveness":{"enabled":false,"path":"/","initialDelaySeconds":30,"periodSeconds":10,"timeoutSeconds":3,"failureThreshold":3},"readiness":{"enabled":false,"path":"/","initialDelaySeconds":30,"periodSeconds":10,"timeoutSeconds":3,"failureThreshold":3}}`
		if encoded.String != want {
			t.Fatalf("unexpected JSON %s", encoded.String)
		}
	})
}

func TestPolymorphicBlobs(t *testing.T) {
	tests := []struct {
		name string
		in   string
		want string
	}{
		{name: "zip source", in: `{"type":"ZIP"}`, want: `{"type":"ZIP"}`},
		{name: "git source", in: `{"type":"GIT","repository":"https://x/y.git"}`, want: `{"type":"GIT","repository":"https://x/y.git"}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			decoded, err := decodeObject[domain.SourceConfig](column(test.in))
			if err != nil {
				t.Fatal(err)
			}
			encoded, err := encodeObject(decoded)
			if err != nil {
				t.Fatal(err)
			}
			if encoded.String != test.want {
				t.Fatalf("got %s want %s", encoded.String, test.want)
			}
		})
	}
	t.Run("publish config legacy shapes", func(t *testing.T) {
		decoded, err := decodeObject[domain.PublishConfig](column(`{"type":"ZIP","url":"https://host/a.zip"}`))
		if err != nil {
			t.Fatal(err)
		}
		if decoded.Type != domain.SourceZip || decoded.URL == nil || decoded.ObjectKey != nil {
			t.Fatalf("mis-read %#v", decoded)
		}
		encoded, err := encodeObject(decoded)
		if err != nil {
			t.Fatal(err)
		}
		if encoded.String != `{"type":"ZIP","objectKey":null,"url":"https://host/a.zip"}` {
			t.Fatalf("unexpected JSON %s", encoded.String)
		}
	})
	t.Run("docker file config without content key", func(t *testing.T) {
		decoded, err := decodeObject[domain.DockerFileConfig](column(`{"type":"BUILTIN","path":"Dockerfile"}`))
		if err != nil {
			t.Fatal(err)
		}
		if decoded.Type != domain.DockerFileBuiltin || *decoded.Path != "Dockerfile" || decoded.Content != nil {
			t.Fatalf("mis-read %#v", decoded)
		}
		encoded, _ := encodeObject(decoded)
		if encoded.String != `{"type":"BUILTIN","path":"Dockerfile","content":null}` {
			t.Fatalf("unexpected JSON %s", encoded.String)
		}
	})
	t.Run("internal ports", func(t *testing.T) {
		encoded, _ := encodeSlice([]int{9090, 50051})
		if encoded.String != `[9090,50051]` {
			t.Fatalf("unexpected JSON %s", encoded.String)
		}
		decoded, err := decodeSlice[int](sql.NullString{})
		if err != nil || decoded != nil {
			t.Fatalf("NULL must decode to nil: %#v %v", decoded, err)
		}
	})
	t.Run("expert config shape", func(t *testing.T) {
		encoded, _ := encodeSlice([]domain.ExpertEnvironmentConfig{{Environment: domain.Ptr("prod")}})
		if encoded.String != `[{"environment":"prod","serviceAccountName":null,"priority":null,"scheduledRestartEnabled":false,"scheduledRestartCron":null,"nodeNames":null}]` {
			t.Fatalf("unexpected JSON %s", encoded.String)
		}
	})
}
