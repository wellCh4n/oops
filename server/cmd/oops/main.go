package main

import (
	"context"
	"flag"
	"log"
	"net/http"

	"github.com/wellch4n/oops/server/internal/config"
	"github.com/wellch4n/oops/server/internal/crypto"
	"github.com/wellch4n/oops/server/internal/httpapi"
	"github.com/wellch4n/oops/server/internal/store"
)

func main() {
	configPath := flag.String("config", "config/application.yml", "path to the shared application.yml")
	listen := flag.String("listen", ":8081", "listen address (Java runs on :8080 during migration)")
	flag.Parse()

	cfg, err := config.Load(*configPath)
	if err != nil {
		log.Fatalf("load config: %v", err)
	}
	dsn, err := cfg.MySQLDSN()
	if err != nil {
		log.Fatalf("datasource: %v", err)
	}
	st, err := store.Open(dsn)
	if err != nil {
		log.Fatalf("open mysql: %v", err)
	}
	defer st.Close()
	st.SetCodec(crypto.NewCodec(cfg.Oops.Crypto.SecretKey))

	server := httpapi.NewServer(cfg, st)
	server.Engine().Run(context.Background())
	server.Engine().RunResourceAlerts(context.Background(), server.AlertConfig())
	log.Printf("oops server listening on %s", *listen)
	if err := http.ListenAndServe(*listen, server.Handler()); err != nil {
		log.Fatal(err)
	}
}
