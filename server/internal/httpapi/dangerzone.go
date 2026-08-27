package httpapi

import (
	"context"
	"log"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

// ensureOwnerOrAdmin mirrors the Java owner/admin check on the danger zone.
func (s *Server) ensureOwnerOrAdmin(ctx context.Context, application *store.Application, userID string) bool {
	if s.store.IsAdmin(ctx, userID) {
		return true
	}
	return application.Owner != nil && *application.Owner == userID
}

func (s *Server) clusterForEnvironment(environment *store.EnvironmentFull) (*k8s.Cluster, error) {
	url, token := "", ""
	if environment.KubernetesApiServer != nil {
		if environment.KubernetesApiServer.URL != nil {
			url = *environment.KubernetesApiServer.URL
		}
		if environment.KubernetesApiServer.Token != nil {
			token = *environment.KubernetesApiServer.Token
		}
	}
	return k8s.NewCluster(url, token)
}

func (s *Server) deleteApplication(c *gin.Context) {
	ctx := c.Request.Context()
	namespace, name := c.Param("namespace"), c.Param("name")
	application, err := s.store.FindApplication(ctx, namespace, name)
	if err != nil {
		c.JSON(http.StatusOK, fail("Application not found"))
		return
	}
	if !s.ensureOwnerOrAdmin(ctx, application, principalFrom(c).UserID) {
		c.JSON(http.StatusOK, fail("Permission denied"))
		return
	}
	bindings, err := s.store.ListEnvironmentBindings(ctx, namespace, name)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	for _, binding := range bindings {
		environment, err := s.store.FindEnvironmentFullByName(ctx, binding.EnvironmentName)
		if err != nil {
			continue
		}
		cluster, err := s.clusterForEnvironment(environment)
		if err == nil {
			err = deleteWorkload(ctx, cluster, namespace, name)
		}
		if err != nil {
			log.Printf("failed to delete K8s resources for %s/%s in env %s: %v", namespace, name, binding.EnvironmentName, err)
			c.JSON(http.StatusOK, fail("Application deletion failed"))
			return
		}
	}
	if err := s.store.DeleteApplicationAggregate(ctx, namespace, name); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

// deleteWorkload mirrors the gateway: only the StatefulSet is deleted; Service
// and IngressRoute cascade via ownerReference.
func deleteWorkload(ctx context.Context, cluster *k8s.Cluster, namespace, applicationName string) error {
	err := cluster.Clientset.AppsV1().StatefulSets(namespace).Delete(ctx, applicationName, metav1.DeleteOptions{})
	if apierrors.IsNotFound(err) {
		return nil
	}
	return err
}

func (s *Server) migrateNamespace(c *gin.Context) {
	ctx := c.Request.Context()
	namespace, name := c.Param("namespace"), c.Param("name")
	var request struct {
		TargetNamespace string `json:"targetNamespace"`
	}
	if err := c.ShouldBindJSON(&request); err != nil || strings.TrimSpace(request.TargetNamespace) == "" {
		c.JSON(http.StatusOK, fail("Target namespace is required"))
		return
	}
	target := strings.TrimSpace(request.TargetNamespace)
	if target == namespace {
		c.JSON(http.StatusOK, fail("Target namespace must be different from the current namespace"))
		return
	}
	application, err := s.store.FindApplication(ctx, namespace, name)
	if err != nil {
		c.JSON(http.StatusOK, fail("Application not found"))
		return
	}
	if !s.ensureOwnerOrAdmin(ctx, application, principalFrom(c).UserID) {
		c.JSON(http.StatusOK, fail("Permission denied"))
		return
	}
	if exists, _ := s.store.NamespaceRecordExists(ctx, target); !exists {
		c.JSON(http.StatusOK, fail("Target namespace not found: "+target))
		return
	}
	if _, err := s.store.FindApplication(ctx, target, name); err == nil {
		c.JSON(http.StatusOK, fail("An application named "+name+" already exists in namespace "+target))
		return
	}
	if active, _ := s.store.HasActivePipeline(ctx, namespace, name); active {
		c.JSON(http.StatusOK, fail("Application is being deployed"))
		return
	}

	// Phase 1: snapshot live workloads (image + config) per bound environment.
	type migrationPlan struct {
		EnvironmentName string
		Environment     *store.EnvironmentFull
		Cluster         *k8s.Cluster
		CurrentImage    string
		ConfigMaps      []k8s.ConfigMapItem
	}
	bindings, _ := s.store.ListEnvironmentBindings(ctx, namespace, name)
	migratedEnvironments, failedEnvironments := []string{}, []string{}
	plans := []migrationPlan{}
	for _, binding := range bindings {
		environment, err := s.store.FindEnvironmentFullByName(ctx, binding.EnvironmentName)
		if err != nil {
			continue
		}
		cluster, err := s.clusterForEnvironment(environment)
		if err != nil {
			failedEnvironments = append(failedEnvironments, binding.EnvironmentName)
			continue
		}
		image, err := k8s.FindCurrentImage(ctx, cluster, namespace, name)
		if err != nil {
			failedEnvironments = append(failedEnvironments, binding.EnvironmentName)
			continue
		}
		if image == nil || *image == "" {
			continue // bound but not running here; nothing to recreate
		}
		configMaps, err := k8s.GetConfigMaps(ctx, cluster, namespace, name)
		if err != nil {
			failedEnvironments = append(failedEnvironments, binding.EnvironmentName)
			continue
		}
		plans = append(plans, migrationPlan{
			EnvironmentName: binding.EnvironmentName,
			Environment:     environment,
			Cluster:         cluster,
			CurrentImage:    *image,
			ConfigMaps:      configMaps,
		})
	}

	// Phase 2: move the database rows.
	if err := s.store.MigrateApplicationNamespace(ctx, namespace, target, name); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}

	// Phase 3: recreate each running workload in the target, then delete the old.
	for _, plan := range plans {
		err := s.redeployToTarget(ctx, &plan.ConfigMaps, plan.Cluster, plan.EnvironmentName, target, name, plan.CurrentImage)
		if err != nil {
			log.Printf("failed to migrate workload %s/%s env %s: %v", namespace, name, plan.EnvironmentName, err)
			failedEnvironments = append(failedEnvironments, plan.EnvironmentName)
			continue
		}
		if err := deleteWorkload(ctx, plan.Cluster, namespace, name); err != nil {
			log.Printf("failed to delete old workload %s/%s env %s: %v", namespace, name, plan.EnvironmentName, err)
		}
		migratedEnvironments = append(migratedEnvironments, plan.EnvironmentName)
	}

	c.JSON(http.StatusOK, ok(gin.H{
		"migratedEnvironments": migratedEnvironments,
		"failedEnvironments":   failedEnvironments,
	}))
}

func configItemsToCommands(items []k8s.ConfigMapItem) []k8s.UpdateConfigMapCommand {
	commands := make([]k8s.UpdateConfigMapCommand, 0, len(items))
	for _, item := range items {
		commands = append(commands, k8s.UpdateConfigMapCommand{
			Key: item.Key, Value: item.Value, Secret: item.Secret,
			MountPath: item.MountPath, Group: item.Group, Comment: item.Comment,
		})
	}
	return commands
}

func (s *Server) redeployToTarget(ctx context.Context, configMaps *[]k8s.ConfigMapItem, cluster *k8s.Cluster,
	environmentName, target, name, currentImage string) error {

	// Config first so new pods start with their environment present.
	if len(*configMaps) > 0 {
		if err := k8s.UpdateConfigMaps(ctx, cluster, target, name, configItemsToCommands(*configMaps)); err != nil {
			return err
		}
	}
	if err := s.engine.DeployImageTo(ctx, target, name, environmentName, currentImage); err != nil {
		return err
	}
	// Re-apply so the config inherits the StatefulSet owner reference.
	if len(*configMaps) > 0 {
		if err := k8s.UpdateConfigMaps(ctx, cluster, target, name, configItemsToCommands(*configMaps)); err != nil {
			return err
		}
	}
	return nil
}
