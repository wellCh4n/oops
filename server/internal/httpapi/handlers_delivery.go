package httpapi

import (
	"net/http"

	"github.com/wellch4n/oops/server/internal/k8s/ide"
	"github.com/wellch4n/oops/server/internal/service"
)

// ---------------------------------------------------------------------------
// pipelines

func (s *Server) listPipelines(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	page, err := s.services.Pipelines.List(r.Context(), namespace, name, environmentOf(r),
		intQuery(r, "page", 1), intQuery(r, "size", 20))
	Respond(w, r, Page[service.PipelineView]{
		Total: page.Total, Data: EmptyIfNil(page.Data), Size: page.Size, TotalPages: page.TotalPages,
	}, err)
}

func (s *Server) getPipeline(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	view, err := s.services.Pipelines.Get(r.Context(), namespace, name, Param(r, "id"))
	Respond(w, r, view, err)
}

func (s *Server) deployPipeline(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	err := s.services.Pipelines.Deploy(r.Context(), namespace, name, Param(r, "id"), callerOf(r))
	Respond(w, r, true, err)
}

func (s *Server) rollbackPipeline(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	id, err := s.services.Pipelines.Rollback(r.Context(), namespace, name, Param(r, "id"), callerOf(r))
	Respond(w, r, id, err)
}

func (s *Server) stopPipeline(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	err := s.services.Pipelines.Stop(r.Context(), namespace, name, Param(r, "id"), callerOf(r))
	Respond(w, r, true, err)
}

func (s *Server) activeDeployments(w http.ResponseWriter, r *http.Request) {
	deployments, err := s.services.Pipelines.ActiveDeployments(r.Context(), Param(r, "namespace"))
	Respond(w, r, EmptyIfNil(deployments), err)
}

func (s *Server) lastSuccessfulPipeline(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	view, err := s.services.Pipelines.LastSuccessful(r.Context(), namespace, name)
	Respond(w, r, view, err)
}

// ---------------------------------------------------------------------------
// deployments

func (s *Server) deploy(w http.ResponseWriter, r *http.Request) {
	var request service.DeployCommand
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	namespace, name := namespaceAndName(r)
	id, err := s.services.Deployments.Deploy(r.Context(), namespace, name, request, callerOf(r))
	Respond(w, r, id, err)
}

func (s *Server) createSourceUpload(w http.ResponseWriter, r *http.Request) {
	var request service.SourceUploadCommand
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	namespace, name := namespaceAndName(r)
	result, err := s.services.Deployments.CreateSourceUpload(r.Context(), namespace, name, request)
	Respond(w, r, result, err)
}

// ---------------------------------------------------------------------------
// IDEs

func (s *Server) listIDEs(w http.ResponseWriter, r *http.Request) {
	instances, err := s.services.IDEs.List(r.Context(), environmentOf(r), Param(r, "application"))
	Respond(w, r, EmptyIfNil(instances), err)
}

func (s *Server) defaultIDEConfig(w http.ResponseWriter, r *http.Request) {
	config, err := s.services.IDEs.DefaultConfig(r.Context(), environmentOf(r))
	Respond(w, r, config, err)
}

func (s *Server) createIDE(w http.ResponseWriter, r *http.Request) {
	var request ide.CreateRequest
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	name, err := s.services.IDEs.Create(r.Context(), environmentOf(r), Param(r, "namespace"), Param(r, "application"), request)
	Respond(w, r, name, err)
}

func (s *Server) deleteIDE(w http.ResponseWriter, r *http.Request) {
	Respond(w, r, true, s.services.IDEs.Delete(r.Context(), environmentOf(r), Param(r, "name")))
}
