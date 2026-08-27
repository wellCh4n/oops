package main

import (
	"context"
	"fmt"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/wellch4n/oops/server/internal/k8s"
)

// checkJobs prints build job/pod progress in the work namespace.
func checkJobs() {
	cluster, err := k8s.NewCluster("", "")
	if err != nil {
		fmt.Println(err)
		return
	}
	ctx := context.Background()
	jobs, _ := cluster.Clientset.BatchV1().Jobs("oops-work").List(ctx, metav1.ListOptions{})
	for _, job := range jobs.Items {
		fmt.Printf("job %s active=%d succeeded=%d failed=%d\n", job.Name, job.Status.Active, job.Status.Succeeded, job.Status.Failed)
	}
	pods, _ := cluster.Clientset.CoreV1().Pods("oops-work").List(ctx, metav1.ListOptions{})
	for _, pod := range pods.Items {
		fmt.Println("pod", pod.Name, pod.Status.Phase)
		for _, initStatus := range pod.Status.InitContainerStatuses {
			state := ""
			switch {
			case initStatus.State.Waiting != nil:
				state = "waiting:" + initStatus.State.Waiting.Reason
			case initStatus.State.Running != nil:
				state = "running"
			case initStatus.State.Terminated != nil:
				state = fmt.Sprintf("terminated:%d %s", initStatus.State.Terminated.ExitCode, initStatus.State.Terminated.Reason)
			}
			fmt.Println("  init", initStatus.Name, state)
		}
	}
}

// jobEvents prints recent events in the work namespace.
func jobEvents() {
	cluster, err := k8s.NewCluster("", "")
	if err != nil {
		fmt.Println(err)
		return
	}
	events, _ := cluster.Clientset.CoreV1().Events("oops-work").List(context.Background(), metav1.ListOptions{})
	for _, event := range events.Items {
		fmt.Printf("%s %s %s: %s\n", event.LastTimestamp.Format("15:04:05"), event.Type, event.Reason, event.Message)
	}
}

// listSecrets prints secrets in the work namespace.
func listSecrets() {
	cluster, err := k8s.NewCluster("", "")
	if err != nil {
		fmt.Println(err)
		return
	}
	secrets, _ := cluster.Clientset.CoreV1().Secrets("oops-work").List(context.Background(), metav1.ListOptions{})
	for _, secret := range secrets.Items {
		fmt.Println(secret.Name, secret.Type)
	}
}
