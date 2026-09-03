// Command oops is the OOPS backend: a Kubernetes-based PaaS.
//
// It reads one strict YAML configuration file, migrates the schema, and serves
// the REST, WebSocket and SSE surface while a handful of background jobs keep
// pipelines, restarts and alerts moving.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/wellch4n/oops/server/internal/auth"
	"github.com/wellch4n/oops/server/internal/config"
	"github.com/wellch4n/oops/server/internal/crypto"
	"github.com/wellch4n/oops/server/internal/feishu"
	"github.com/wellch4n/oops/server/internal/httpapi"
	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/objectstorage"
	"github.com/wellch4n/oops/server/internal/scheduler"
	"github.com/wellch4n/oops/server/internal/service"
	"github.com/wellch4n/oops/server/internal/store"
)

// shutdownGrace is how long in-flight requests get once a signal arrives. Log
// and terminal streams are long-lived and will simply be cut; a deploy already
// handed to Kubernetes carries on without us.
const shutdownGrace = 15 * time.Second

func main() {
	if err := run(); err != nil {
		slog.Error("startup failed", "error", err)
		os.Exit(1)
	}
}

func run() error {
	configPath := flag.String("config", "config/oops.yaml", "path to the configuration file")
	flag.Parse()

	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})))

	cfg, err := config.Load(*configPath)
	if err != nil {
		return err
	}

	db, err := store.Open(cfg.Database.DSN(), cfg.Database.MaxOpenConns)
	if err != nil {
		return err
	}
	defer db.Close()

	// The schema is created before anything reads it, so a fresh database needs
	// no separate provisioning step.
	migrateCtx, cancelMigrate := context.WithTimeout(context.Background(), time.Minute)
	defer cancelMigrate()
	if err := store.Migrate(migrateCtx, db); err != nil {
		return err
	}

	codec := crypto.NewCodec(cfg.Crypto.SecretKey)
	if !codec.Enabled() {
		slog.Warn("crypto.secret_key is blank: environment tokens and registry passwords are stored in plaintext")
	}
	repositories := store.New(db, codec)

	storage, err := objectstorage.New(objectStorageOptions(cfg))
	if err != nil {
		return err
	}

	pool := k8s.NewPool()
	// One Feishu client serves both jobs it is used for — the login flow and the
	// notifications — so the app-level tokens are fetched and cached once.
	var feishuClient *feishu.Client
	var notifier service.Notifier
	if cfg.Feishu.Enabled {
		feishuClient = feishu.NewClient(cfg.Feishu.AppID, cfg.Feishu.AppSecret)
		notifier = feishu.NewNotifier(feishuClient, repositories)
	}
	services := service.New(cfg, repositories, pool, storage, notifier)
	if feishuClient != nil {
		services.RegisterExternalProvider(feishu.NewAuthProvider(feishuClient, cfg.Feishu.RedirectURI))
	}

	startupCtx, cancelStartup := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancelStartup()
	if err := services.Users.EnsureDefaultAdmin(startupCtx, cfg.Admin.Password); err != nil {
		return fmt.Errorf("seed the default admin: %w", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	jobs := []scheduler.Job{
		scheduler.NewPipelineScan(services),
		scheduler.NewScheduledRestart(services),
	}
	// The alert scan messages real people and queries a backend not every
	// cluster runs, so it is opt-in rather than merely idle when unconfigured.
	if cfg.Metrics.Alert.Enabled {
		jobs = append(jobs, scheduler.NewResourceAlert(services))
	}
	scheduler.Start(ctx, jobs...)

	server := &http.Server{
		Addr:    fmt.Sprintf(":%d", cfg.Server.Port),
		Handler: httpapi.NewServer(services, auth.NewJWT(cfg.JWT.Secret, cfg.JWT.Expiration)).Handler(),
		// No write timeout: log tails, terminals and the status stream are all
		// long-lived, and a deadline here would cut them mid-stream.
		ReadHeaderTimeout: 10 * time.Second,
	}

	listenErrors := make(chan error, 1)
	go func() {
		slog.Info("oops is listening", "port", cfg.Server.Port)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			listenErrors <- err
		}
	}()

	select {
	case err := <-listenErrors:
		return err
	case <-ctx.Done():
		slog.Info("shutting down")
	}
	shutdownCtx, cancelShutdown := context.WithTimeout(context.Background(), shutdownGrace)
	defer cancelShutdown()
	return server.Shutdown(shutdownCtx)
}

func objectStorageOptions(cfg *config.Config) objectstorage.Options {
	source := cfg.ObjectStorage
	return objectstorage.Options{
		Enabled:                      source.Enabled,
		Endpoint:                     source.Endpoint,
		Region:                       source.Region,
		Bucket:                       source.Bucket,
		AccessKey:                    source.AccessKey,
		SecretKey:                    source.SecretKey,
		PathStyleAccess:              source.PathStyleAccess,
		KeyPrefix:                    source.KeyPrefix,
		AssetKeyPrefix:               source.AssetKeyPrefix,
		AssetBaseURL:                 source.AssetBaseURL,
		UploadURLExpirationSeconds:   source.UploadURLExpirationSeconds,
		DownloadURLExpirationSeconds: source.DownloadURLExpirationSeconds,
		MaxFileSizeBytes:             source.MaxFileSizeBytes,
	}
}
