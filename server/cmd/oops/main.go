package main

import (
	"context"
	"flag"
	"log/slog"
	"net/http"
	"os"

	"github.com/wellch4n/oops/server/internal/config"
	"github.com/wellch4n/oops/server/internal/crypto"
	"github.com/wellch4n/oops/server/internal/feishu"
	"github.com/wellch4n/oops/server/internal/httpapi"
	"github.com/wellch4n/oops/server/internal/store"
)

func main() {
	configPath := flag.String("config", "config/application.yml", "path to the shared application.yml")
	listen := flag.String("listen", ":8081", "listen address (Java runs on :8080 during migration)")
	flag.Parse()

	cfg, err := config.Load(*configPath)
	if err != nil {
		slog.Error("load config", "error", err)
		os.Exit(1)
	}
	dsn, err := cfg.MySQLDSN()
	if err != nil {
		slog.Error("datasource", "error", err)
		os.Exit(1)
	}
	st, err := store.Open(dsn)
	if err != nil {
		slog.Error("open mysql", "error", err)
		os.Exit(1)
	}
	defer st.Close()
	st.SetCodec(crypto.NewCodec(cfg.Oops.Crypto.SecretKey))

	server := httpapi.NewServer(cfg, st)
	server.Engine().Run(context.Background())
	server.Engine().RunResourceAlerts(context.Background(), server.AlertConfig())
	// Inbound Feishu events are double-gated, like the Java bean's
	// @ConditionalOnProperty on both names: resignations are the only
	// subscription, so with the switch off there is no socket worth opening.
	if cfg.Oops.Feishu.Enabled && cfg.Oops.Feishu.SyncUserDeactivation {
		feishu.RunEventClient(context.Background(), cfg.Oops.Feishu.AppID, cfg.Oops.Feishu.AppSecret, st)
	}
	slog.Info("oops server listening", "address", *listen)
	if err := http.ListenAndServe(*listen, server.Handler()); err != nil {
		slog.Error("http server stopped", "error", err)
		os.Exit(1)
	}
}
