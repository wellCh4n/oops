// devtool is a scratch utility for local verification (create/delete a
// labelled test pod). Not part of the shipped server.
package main

import (
	"context"
	"fmt"
	"log"
	"os"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/wellch4n/oops/server/internal/k8s"
)

func main() {
	cluster, err := k8s.NewCluster("", "")
	if err != nil {
		log.Fatal(err)
	}
	pods := cluster.Clientset.CoreV1().Pods("default")
	if len(os.Args) > 1 && os.Args[1] == "cleanup" {
		cleanupVerification(os.Args[2:])
		return
	}
	if len(os.Args) > 1 && os.Args[1] == "jobs" {
		checkJobs()
		return
	}
	if len(os.Args) > 1 && os.Args[1] == "secrets" {
		listSecrets()
		return
	}
	if len(os.Args) > 1 && os.Args[1] == "events" {
		jobEvents()
		return
	}
	if len(os.Args) > 2 && os.Args[1] == "ws" {
		frames := 4
		send := ""
		if len(os.Args) > 3 {
			send = os.Args[3]
		}
		wsProbe(os.Args[2], send, frames)
		return
	}
	if len(os.Args) > 2 && os.Args[1] == "seed" {
		seedSucceededPipeline(os.Args[2])
		return
	}
	if len(os.Args) > 1 && os.Args[1] == "delete" {
		_ = pods.Delete(context.Background(), "test-0", metav1.DeleteOptions{})
		fmt.Println("deleted")
		return
	}
	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name: "test-0", Namespace: "default",
			Labels: map[string]string{"oops.type": "APPLICATION", "oops.app.name": "test"},
		},
		Spec: corev1.PodSpec{Containers: []corev1.Container{{
			Name: "test", Image: "busybox:1.36",
			Command: []string{"sh", "-c", "i=0; while true; do echo \"log line $i\"; i=$((i+1)); sleep 2; done"},
		}}},
	}
	if _, err := pods.Create(context.Background(), pod, metav1.CreateOptions{}); err != nil {
		log.Fatal(err)
	}
	fmt.Println("created")
}
