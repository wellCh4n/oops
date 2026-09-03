package httpapi

import "net/http"

// Routes is the whole HTTP surface, in one table.
//
// The table exists rather than a scattering of router calls because three
// separate things are derived from it: the /api mounting, the /openapi mounting
// with its different authentication, and the route inventory the integration
// suite's coverage report checks. Keeping them in step by hand across a hundred
// endpoints is exactly the sort of thing that silently rots.
func (s *Server) Routes() []Route {
	const (
		apps = "/namespaces/{namespace}/applications"
		app  = apps + "/{name}"
		pods = app + "/pods/{pod}"
		// The config endpoints spell the application parameter differently; it is
		// part of the published path, so it stays as it is.
		configs = "/namespaces/{namespace}/applications/{applicationName}/configmaps"
		ides    = "/namespaces/{namespace}/applications/{application}/ides"
		sandbox = "/sandbox"
	)
	get, post, put, del := http.MethodGet, http.MethodPost, http.MethodPut, http.MethodDelete

	return []Route{
		// -- public --------------------------------------------------------
		{post, "/auth/login", s.login, "AuthController", false, false, false, true},
		{get, "/health", s.health, "HealthController", false, false, false, true},
		{get, "/features", s.features, "FeaturesController", false, false, false, true},
		{get, "/auth/external/providers", s.externalProviders, "ExternalAccountController", false, false, false, true},
		// Both halves of the OAuth flow are public: the caller has no session
		// yet — acquiring one is the point.
		{get, "/auth/external/{provider}/redirect", s.externalLoginURL, "ExternalAccountController", false, false, false, true},
		{post, "/auth/external/{provider}/callback", s.externalCallback, "ExternalAccountController", false, false, false, true},

		// -- users ---------------------------------------------------------
		{get, "/users", s.listUsers, "UserController", false, false, false, false},
		{get, "/users/page", s.listUsersPage, "UserController", false, false, false, false},
		{get, "/users/me", s.currentUser, "UserController", false, false, false, false},
		{put, "/users/me", s.updateMyProfile, "UserController", false, false, false, false},
		{put, "/users/me/password", s.changeMyPassword, "UserController", false, false, false, false},
		{post, "/users/me/access-token/reset", s.resetMyAccessToken, "UserController", false, false, false, false},
		{post, "/users", s.createUser, "UserController", true, false, false, false},
		{put, "/users/{id}", s.updateUser, "UserController", true, false, false, false},
		{del, "/users/{id}", s.deleteUser, "UserController", true, false, false, false},

		// -- namespaces ----------------------------------------------------
		{get, "/namespaces", s.listNamespaces, "NamespaceController", false, false, false, false},
		{post, "/namespaces", s.createNamespace, "NamespaceController", true, false, false, false},
		{put, "/namespaces", s.updateNamespace, "NamespaceController", true, false, false, false},

		// -- environments --------------------------------------------------
		{get, "/environments", s.listEnvironments, "EnvironmentController", false, false, false, false},
		{get, "/environments/{id}", s.getEnvironment, "EnvironmentController", false, false, false, false},
		{post, "/environments", s.createEnvironment, "EnvironmentController", true, false, false, false},
		{put, "/environments/{id}/cluster", s.updateEnvironmentCluster, "EnvironmentController", true, false, false, false},
		{put, "/environments/{id}/credentials", s.updateEnvironmentCredentials, "EnvironmentController", true, false, false, false},
		{del, "/environments/{id}", s.deleteEnvironment, "EnvironmentController", true, false, false, false},

		// -- domains -------------------------------------------------------
		{get, "/domains", s.listDomains, "DomainController", false, false, false, false},
		{get, "/domains/{id}", s.getDomain, "DomainController", false, false, false, false},
		{post, "/domains", s.createDomain, "DomainController", true, false, false, false},
		{put, "/domains/{id}", s.updateDomain, "DomainController", true, false, false, false},
		{del, "/domains/{id}", s.deleteDomain, "DomainController", true, false, false, false},

		// -- cluster -------------------------------------------------------
		{get, "/nodes", s.listNodes, "NodeController", true, false, false, false},
		{post, "/nodes/{name}/schedulable", s.setNodeSchedulable, "NodeController", true, false, false, false},
		{get, "/namespaces/{namespace}/service-accounts", s.listServiceAccounts, "ServiceAccountController", false, false, false, false},
		{post, "/kubernetes/validations", s.validateKubernetes, "KubernetesController", true, false, false, false},
		{post, "/kubernetes/namespaces", s.createKubernetesNamespace, "KubernetesController", true, false, false, false},
		{post, "/image-repositories/validations", s.validateImageRepository, "ImageRepositoryController", true, false, false, false},

		// -- search and index ----------------------------------------------
		{get, "/search/applications", s.searchApplications, "SearchController", false, false, false, false},
		{post, "/index/pipelines", s.queryPipelines, "IndexController", false, false, false, false},
		{post, "/index/applications", s.queryApplications, "IndexController", false, false, false, false},
		{get, "/cron/next", s.nextCronRuns, "CronController", false, false, false, false},

		// -- assets --------------------------------------------------------
		{get, "/assets", s.listAssets, "StaticAssetController", false, false, false, false},
		{post, "/assets/upload-url", s.createAssetUploadURL, "StaticAssetController", false, false, false, false},
		{del, "/assets", s.deleteAsset, "StaticAssetController", true, false, false, false},

		// -- applications --------------------------------------------------
		{get, apps, s.listApplications, "ApplicationController", false, true, false, false},
		{post, apps, s.createApplication, "ApplicationController", false, true, false, false},
		{get, apps + "/active-deployments", s.activeDeployments, "ApplicationController", false, true, false, false},
		{get, app, s.getApplication, "ApplicationController", false, true, false, false},
		{put, app, s.updateApplication, "ApplicationController", false, true, false, false},
		// Deletion is hidden from the machine surface: it cascades into every
		// cluster the application is bound to, and a token has no confirmation
		// step behind it.
		{del, app, s.deleteApplication, "ApplicationController", false, true, true, false},
		{post, app + "/namespace-migration", s.migrateNamespace, "ApplicationController", false, true, true, false},

		{get, app + "/build/config", s.getBuildConfig, "ApplicationController", false, true, false, false},
		{put, app + "/build/config", s.updateBuildConfig, "ApplicationController", false, true, false, false},
		{get, app + "/branches", s.listBranches, "ApplicationController", false, true, false, false},
		{get, app + "/environments/build/configs", s.getBuildEnvironmentConfigs, "ApplicationController", false, true, false, false},
		{put, app + "/environments/build/configs", s.updateBuildEnvironmentConfigs, "ApplicationController", false, true, false, false},

		{get, app + "/runtime-spec", s.getRuntimeSpec, "ApplicationController", false, true, false, false},
		{put, app + "/runtime-spec", s.updateRuntimeSpec, "ApplicationController", false, true, false, false},
		{get, app + "/environments/runtime-specs", s.getRuntimeEnvironmentConfigs, "ApplicationController", false, true, false, false},
		{put, app + "/environments/runtime-specs", s.updateRuntimeEnvironmentConfigs, "ApplicationController", false, true, false, false},

		{get, app + "/service", s.getServiceConfig, "ApplicationController", false, true, false, false},
		{put, app + "/service", s.updateServiceConfig, "ApplicationController", false, true, false, false},
		{get, app + "/service/host-check", s.checkServiceHost, "ApplicationController", false, true, false, false},
		{get, app + "/service/cluster-domain", s.getClusterDomain, "ApplicationController", false, true, false, false},

		{get, app + "/expert-config", s.getExpertConfig, "ApplicationController", false, true, false, false},
		{put, app + "/expert-config", s.updateExpertConfig, "ApplicationController", false, true, false, false},
		{get, app + "/environments", s.getApplicationEnvironments, "ApplicationController", false, true, false, false},
		{put, app + "/environments", s.updateApplicationEnvironments, "ApplicationController", false, true, false, false},

		{get, app + "/status", s.getApplicationStatus, "ApplicationController", false, true, false, false},
		{get, app + "/status/watch", s.watchApplicationStatus, "ApplicationController", false, true, false, false},
		{get, app + "/events", s.getApplicationEvents, "ApplicationController", false, true, false, false},
		{get, app + "/resources", s.getApplicationResources, "ApplicationController", false, true, false, false},
		{get, app + "/metrics", s.getApplicationMetrics, "ApplicationController", false, true, false, false},
		{get, app + "/metrics/history", s.getApplicationMetricsHistory, "ApplicationController", false, true, false, false},
		{get, app + "/current-image", s.getCurrentImage, "ApplicationController", false, true, false, false},
		{get, app + "/last-successful-pipeline", s.lastSuccessfulPipeline, "ApplicationController", false, true, false, false},
		{put, pods + "/restart", s.restartPod, "ApplicationController", false, true, false, false},

		// -- deployments and pipelines --------------------------------------
		{post, app + "/deployments", s.deploy, "DeploymentController", false, true, false, false},
		{post, app + "/deployments/source-upload", s.createSourceUpload, "DeploymentController", false, true, false, false},
		{get, app + "/pipelines", s.listPipelines, "PipelineController", false, true, false, false},
		{get, app + "/pipelines/{id}", s.getPipeline, "PipelineController", false, true, false, false},
		{put, app + "/pipelines/{id}/deploy", s.deployPipeline, "PipelineController", false, true, false, false},
		{put, app + "/pipelines/{id}/stop", s.stopPipeline, "PipelineController", false, true, false, false},
		{post, app + "/pipelines/{id}/rollback", s.rollbackPipeline, "PipelineController", false, true, false, false},

		// -- configmaps ------------------------------------------------------
		{get, configs, s.getConfigMaps, "ConfigMapController", false, true, false, false},
		{put, configs, s.updateConfigMaps, "ConfigMapController", false, true, false, false},

		// -- pod filesystem --------------------------------------------------
		{get, pods + "/files", s.listPodFiles, "PodFileSystemController", false, false, false, false},
		{get, pods + "/files/content", s.readPodFile, "PodFileSystemController", false, false, false, false},
		{put, pods + "/files/content", s.writePodFile, "PodFileSystemController", false, false, false, false},
		{get, pods + "/files/download", s.downloadPodFile, "PodFileSystemController", false, false, false, false},
		{post, pods + "/files/upload", s.uploadPodFile, "PodFileSystemController", false, false, false, false},
		{post, pods + "/files/directory", s.createPodDirectory, "PodFileSystemController", false, false, false, false},
		{post, pods + "/files/rename", s.renamePodFile, "PodFileSystemController", false, false, false, false},
		{del, pods + "/files", s.deletePodFile, "PodFileSystemController", false, false, false, false},

		// -- IDEs -------------------------------------------------------------
		{get, ides, s.listIDEs, "IdeController", false, false, false, false},
		{post, ides, s.createIDE, "IdeController", false, false, false, false},
		{get, ides + "/config/default", s.defaultIDEConfig, "IdeController", false, false, false, false},
		{del, ides + "/{name}", s.deleteIDE, "IdeController", false, false, false, false},

		// -- sandbox ----------------------------------------------------------
		{get, sandbox + "/images", s.sandboxImages, "SandboxController", false, true, false, false},
		{post, sandbox + "/executions", s.sandboxExecute, "SandboxController", false, true, false, false},
		{get, sandbox + "/instances", s.listSandboxInstances, "SandboxController", false, true, false, false},
		{post, sandbox + "/instances", s.createSandboxInstance, "SandboxController", false, true, false, false},
		{get, sandbox + "/instances/{id}", s.getSandboxInstance, "SandboxController", false, true, false, false},
		// Not hidden, unlike application deletion: tearing a sandbox down is part
		// of its lifecycle, and a CLI that creates one has to be able to remove it.
		{del, sandbox + "/instances/{id}", s.deleteSandboxInstance, "SandboxController", false, true, false, false},
		{post, sandbox + "/instances/{id}/exec", s.execSandboxInstance, "SandboxController", false, true, false, false},
		{get, sandbox + "/instances/{id}/files", s.listSandboxFiles, "SandboxController", false, true, false, false},
		{get, sandbox + "/instances/{id}/files/content", s.readSandboxFile, "SandboxController", false, true, false, false},
		{put, sandbox + "/instances/{id}/files/content", s.writeSandboxFile, "SandboxController", false, true, false, false},
		{get, sandbox + "/instances/{id}/files/download", s.downloadSandboxFile, "SandboxController", false, true, false, false},
		{post, sandbox + "/instances/{id}/files/upload", s.uploadSandboxFile, "SandboxController", false, true, false, false},
		{post, sandbox + "/instances/{id}/files/directory", s.createSandboxDirectory, "SandboxController", false, true, false, false},
		{post, sandbox + "/instances/{id}/files/rename", s.renameSandboxFile, "SandboxController", false, true, false, false},
		{del, sandbox + "/instances/{id}/files", s.deleteSandboxFile, "SandboxController", false, true, false, false},
	}
}
