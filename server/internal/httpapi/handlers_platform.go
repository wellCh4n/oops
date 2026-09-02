package httpapi

import (
	"net/http"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/service"
)

// ---------------------------------------------------------------------------
// environments

func (s *Server) listEnvironments(w http.ResponseWriter, r *http.Request) {
	environments, err := s.services.Environments.List(r.Context())
	if err != nil {
		Error(w, r, err)
		return
	}
	views := make([]service.EnvironmentView, 0, len(environments))
	for _, environment := range environments {
		views = append(views, service.EnvironmentViewOf(environment))
	}
	OK(w, views)
}

func (s *Server) getEnvironment(w http.ResponseWriter, r *http.Request) {
	environment, err := s.services.Environments.FindByID(r.Context(), Param(r, "id"))
	if err != nil {
		Error(w, r, err)
		return
	}
	if environment == nil {
		OK(w, nil)
		return
	}
	OK(w, service.EnvironmentViewOf(*environment))
}

// environmentRequest is the create/update body. It mirrors the entity shape the
// UI edit form round-trips.
type environmentRequest struct {
	Name                string                      `json:"name"`
	KubernetesApiServer *domain.KubernetesApiServer `json:"kubernetesApiServer"`
	WorkNamespace       *string                     `json:"workNamespace"`
	BuildStorageClass   *string                     `json:"buildStorageClass"`
	ImageRepository     *domain.ImageRepository     `json:"imageRepository"`
	GitCredential       *domain.GitCredential       `json:"gitCredential"`
}

func (e environmentRequest) toDomain() *domain.Environment {
	return &domain.Environment{
		Name: e.Name, KubernetesApiServer: e.KubernetesApiServer,
		WorkNamespace: e.WorkNamespace, BuildStorageClass: e.BuildStorageClass,
		ImageRepository: e.ImageRepository, GitCredential: e.GitCredential,
	}
}

func (s *Server) createEnvironment(w http.ResponseWriter, r *http.Request) {
	var request environmentRequest
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	environment, err := s.services.Environments.Create(r.Context(), request.toDomain())
	if err != nil {
		Error(w, r, err)
		return
	}
	OK(w, service.EnvironmentViewOf(*environment))
}

func (s *Server) updateEnvironmentCluster(w http.ResponseWriter, r *http.Request) {
	var request environmentRequest
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	Respond(w, r, true, s.services.Environments.UpdateCluster(r.Context(), Param(r, "id"), request.toDomain()))
}

func (s *Server) updateEnvironmentCredentials(w http.ResponseWriter, r *http.Request) {
	var request environmentRequest
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	Respond(w, r, true, s.services.Environments.UpdateCredentials(r.Context(), Param(r, "id"), request.toDomain()))
}

func (s *Server) deleteEnvironment(w http.ResponseWriter, r *http.Request) {
	Respond(w, r, true, s.services.Environments.Delete(r.Context(), Param(r, "id")))
}

// kubernetesValidationRequest probes a cluster before it is registered.
type kubernetesValidationRequest struct {
	KubernetesApiServer *domain.KubernetesApiServer `json:"kubernetesApiServer"`
	WorkNamespace       string                      `json:"workNamespace"`
}

func (s *Server) validateKubernetes(w http.ResponseWriter, r *http.Request) {
	var request kubernetesValidationRequest
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	OK(w, s.services.Environments.ValidateKubernetes(r.Context(), request.KubernetesApiServer, request.WorkNamespace))
}

func (s *Server) createKubernetesNamespace(w http.ResponseWriter, r *http.Request) {
	var request kubernetesValidationRequest
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	err := s.services.Environments.CreateKubernetesNamespace(r.Context(), request.KubernetesApiServer, request.WorkNamespace)
	Respond(w, r, true, err)
}

func (s *Server) validateImageRepository(w http.ResponseWriter, r *http.Request) {
	var request domain.ImageRepository
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	OK(w, s.services.Environments.ValidateImageRepository(r.Context(), &request))
}

// ---------------------------------------------------------------------------
// domains

func (s *Server) listDomains(w http.ResponseWriter, r *http.Request) {
	domains, err := s.services.Domains.List(r.Context())
	if err != nil {
		Error(w, r, err)
		return
	}
	views := make([]service.DomainView, 0, len(domains))
	for _, record := range domains {
		views = append(views, service.DomainViewOf(record))
	}
	OK(w, views)
}

func (s *Server) getDomain(w http.ResponseWriter, r *http.Request) {
	record, err := s.services.Domains.Get(r.Context(), Param(r, "id"))
	if err != nil {
		Error(w, r, err)
		return
	}
	OK(w, service.DomainViewOf(*record))
}

func (s *Server) createDomain(w http.ResponseWriter, r *http.Request) {
	var request service.UpsertDomain
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	record, err := s.services.Domains.Create(r.Context(), request)
	if err != nil {
		Error(w, r, err)
		return
	}
	OK(w, service.DomainViewOf(*record))
}

func (s *Server) updateDomain(w http.ResponseWriter, r *http.Request) {
	var request service.UpsertDomain
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	record, err := s.services.Domains.Update(r.Context(), Param(r, "id"), request)
	if err != nil {
		Error(w, r, err)
		return
	}
	OK(w, service.DomainViewOf(*record))
}

func (s *Server) deleteDomain(w http.ResponseWriter, r *http.Request) {
	Respond(w, r, true, s.services.Domains.Delete(r.Context(), Param(r, "id")))
}

// ---------------------------------------------------------------------------
// cluster

func (s *Server) listNodes(w http.ResponseWriter, r *http.Request) {
	nodes, err := s.services.Cluster.ListNodes(r.Context(), environmentOf(r))
	Respond(w, r, EmptyIfNil(nodes), err)
}

// setNodeSchedulable cordons or uncordons. Both parameters are in the query
// string; the request carries no body.
func (s *Server) setNodeSchedulable(w http.ResponseWriter, r *http.Request) {
	err := s.services.Cluster.SetNodeSchedulable(r.Context(), environmentOf(r),
		Param(r, "name"), Query(r, "schedulable") == "true")
	Respond(w, r, true, err)
}

func (s *Server) listServiceAccounts(w http.ResponseWriter, r *http.Request) {
	accounts, err := s.services.Cluster.ListServiceAccounts(r.Context(), environmentOf(r), Param(r, "namespace"))
	Respond(w, r, EmptyIfNil(accounts), err)
}

// ---------------------------------------------------------------------------
// index and search

func (s *Server) queryPipelines(w http.ResponseWriter, r *http.Request) {
	var query service.PipelineQuery
	if err := DecodeJSON(r, &query); err != nil {
		Error(w, r, err)
		return
	}
	pipelines, err := s.services.QueryPipelines(r.Context(), query)
	Respond(w, r, EmptyIfNil(pipelines), err)
}

func (s *Server) queryApplications(w http.ResponseWriter, r *http.Request) {
	var query service.ApplicationQuery
	if err := DecodeJSON(r, &query); err != nil {
		Error(w, r, err)
		return
	}
	applications, err := s.services.QueryApplications(r.Context(), query)
	Respond(w, r, EmptyIfNil(applications), err)
}

func (s *Server) searchApplications(w http.ResponseWriter, r *http.Request) {
	applications, err := s.services.Applications.Search(r.Context(), Query(r, "keyword"), intQuery(r, "size", 5))
	Respond(w, r, EmptyIfNil(applications), err)
}

// ---------------------------------------------------------------------------
// static assets

func (s *Server) listAssets(w http.ResponseWriter, r *http.Request) {
	entries, err := s.services.Assets.List(r.Context(), Query(r, "path"))
	Respond(w, r, EmptyIfNil(entries), err)
}

func (s *Server) createAssetUploadURL(w http.ResponseWriter, r *http.Request) {
	var request service.AssetUploadCommand
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	result, err := s.services.Assets.CreateUploadURL(r.Context(), request)
	Respond(w, r, result, err)
}

func (s *Server) deleteAsset(w http.ResponseWriter, r *http.Request) {
	Respond(w, r, true, s.services.Assets.Delete(r.Context(), Query(r, "key")))
}
