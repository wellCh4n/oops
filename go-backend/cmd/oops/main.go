package main

import (
	"flag"
	"log"
	"net/http"

	"github.com/wellch4n/oops/go-backend/internal/config"
	"github.com/wellch4n/oops/go-backend/internal/httpapi"
	"github.com/wellch4n/oops/go-backend/internal/store"
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

	server := httpapi.NewServer(cfg, st)
	log.Printf("oops go-backend listening on %s", *listen)
	if err := http.ListenAndServe(*listen, server.Handler()); err != nil {
		log.Fatal(err)
	}
}
