package main

import (
	"context"
	"fmt"
	"log"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/wellch4n/oops/server/internal/config"
	"github.com/wellch4n/oops/server/internal/crypto"
	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

// cleanupVerification removes what the deploy verification created: the test
// workload, the work namespace, and the seeded pipeline rows.
func cleanupVerification(pipelineIDs []string) {
	cluster, err := k8s.NewCluster("", "")
	if err != nil {
		log.Fatal(err)
	}
	ctx := context.Background()
	_ = cluster.Clientset.AppsV1().StatefulSets("default").Delete(ctx, "test", metav1.DeleteOptions{})
	_ = cluster.Clientset.CoreV1().Services("default").Delete(ctx, "test", metav1.DeleteOptions{})
	_ = cluster.Clientset.CoreV1().Namespaces().Delete(ctx, "oops-work", metav1.DeleteOptions{})

	cfg, err := config.Load("../config/application.yml")
	if err != nil {
		log.Fatal(err)
	}
	dsn, _ := cfg.MySQLDSN()
	st, err := store.Open(dsn)
	if err != nil {
		log.Fatal(err)
	}
	defer st.Close()
	st.SetCodec(crypto.NewCodec(cfg.Oops.Crypto.SecretKey))
	for _, id := range pipelineIDs {
		if err := st.DeletePipelineByID(ctx, id); err != nil {
			fmt.Println("delete pipeline", id, err)
		}
	}
	fmt.Println("cleaned")
}
