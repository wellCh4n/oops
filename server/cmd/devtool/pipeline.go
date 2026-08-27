package main

import (
	"context"
	"fmt"
	"log"

	"github.com/wellch4n/oops/server/internal/config"
	"github.com/wellch4n/oops/server/internal/crypto"
	"github.com/wellch4n/oops/server/internal/store"
)

// seedSucceededPipeline inserts a SUCCEEDED pipeline with a public artifact so
// the rollback path can be exercised without a registry.
func seedSucceededPipeline(artifact string) {
	cfg, err := config.Load("../config/application.yml")
	if err != nil {
		log.Fatal(err)
	}
	dsn, err := cfg.MySQLDSN()
	if err != nil {
		log.Fatal(err)
	}
	st, err := store.Open(dsn)
	if err != nil {
		log.Fatal(err)
	}
	defer st.Close()
	st.SetCodec(crypto.NewCodec(cfg.Oops.Crypto.SecretKey))
	ctx := context.Background()
	id, err := st.CreatePipeline(ctx, "default", "test", "dev", "GIT",
		store.GitPublishConfig{Type: "GIT", Repository: "https://example.com/repo.git", Branch: "main"},
		"IMMEDIATE", "", "RELEASE", "")
	if err != nil {
		log.Fatal(err)
	}
	if err := st.UpdatePipelineArtifact(ctx, id, artifact); err != nil {
		log.Fatal(err)
	}
	if _, err := st.UpdatePipelineStatusIfMatch(ctx, id, store.StatusInitialized, store.StatusRunning); err != nil {
		log.Fatal(err)
	}
	if _, err := st.UpdatePipelineStatusIfMatch(ctx, id, store.StatusRunning, store.StatusDeploying); err != nil {
		log.Fatal(err)
	}
	if _, err := st.UpdatePipelineStatusIfMatch(ctx, id, store.StatusDeploying, store.StatusRollingOut); err != nil {
		log.Fatal(err)
	}
	if _, err := st.UpdatePipelineStatusIfMatch(ctx, id, store.StatusRollingOut, store.StatusSucceeded); err != nil {
		log.Fatal(err)
	}
	fmt.Println(id)
}
