package httpapi

import (
	"net/http"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/service"
)

// namespaceAndName reads the two path parameters every application route carries.
func namespaceAndName(r *http.Request) (string, string) {
	return Param(r, "namespace"), Param(r, "name")
}

// ---------------------------------------------------------------------------
// profile

func (s *Server) getApplication(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	view, err := s.services.Applications.Get(r.Context(), namespace, name)
	Respond(w, r, view, err)
}

func (s *Server) listApplications(w http.ResponseWriter, r *http.Request) {
	page, err := s.services.Applications.List(r.Context(), Param(r, "namespace"), Query(r, "keyword"),
		intQuery(r, "page", 1), intQuery(r, "size", 10), callerOf(r), boolQuery(r, "ownerOnly"))
	Respond(w, r, Page[service.ApplicationView]{
		Total: page.Total, Data: EmptyIfNil(page.Data), Size: page.Size, TotalPages: page.TotalPages,
	}, err)
}

func (s *Server) createApplication(w http.ResponseWriter, r *http.Request) {
	var request service.ProfileRequest
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	id, err := s.services.Applications.Create(r.Context(), Param(r, "namespace"), request, callerOf(r))
	Respond(w, r, id, err)
}

func (s *Server) updateApplication(w http.ResponseWriter, r *http.Request) {
	var request service.ProfileRequest
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	namespace, name := namespaceAndName(r)
	Respond(w, r, true, s.services.Applications.Update(r.Context(), namespace, name, request))
}

func (s *Server) deleteApplication(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	Respond(w, r, true, s.services.Applications.Delete(r.Context(), namespace, name, callerOf(r)))
}

func (s *Server) migrateNamespace(w http.ResponseWriter, r *http.Request) {
	var request struct {
		TargetNamespace string `json:"targetNamespace"`
	}
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	namespace, name := namespaceAndName(r)
	result, err := s.services.Applications.MigrateNamespace(r.Context(), namespace, name, request.TargetNamespace, callerOf(r))
	Respond(w, r, result, err)
}

// ---------------------------------------------------------------------------
// build config

func (s *Server) getBuildConfig(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	view, err := s.services.Applications.GetBuildConfig(r.Context(), namespace, name)
	Respond(w, r, view, err)
}

func (s *Server) updateBuildConfig(w http.ResponseWriter, r *http.Request) {
	var request service.BuildConfigView
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	namespace, name := namespaceAndName(r)
	Respond(w, r, true, s.services.Applications.UpdateBuildConfig(r.Context(), namespace, name, request))
}

func (s *Server) getBuildEnvironmentConfigs(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	configs, err := s.services.Applications.GetBuildEnvironmentConfigs(r.Context(), namespace, name)
	Respond(w, r, EmptyIfNil(configs), err)
}

func (s *Server) updateBuildEnvironmentConfigs(w http.ResponseWriter, r *http.Request) {
	var request []domain.BuildEnvironmentConfig
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	namespace, name := namespaceAndName(r)
	Respond(w, r, true, s.services.Applications.UpdateBuildEnvironmentConfigs(r.Context(), namespace, name, request))
}

func (s *Server) listBranches(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	branches, err := s.services.Applications.ListBranches(r.Context(), namespace, name, environmentOf(r))
	Respond(w, r, EmptyIfNil(branches), err)
}

// ---------------------------------------------------------------------------
// runtime spec

func (s *Server) getRuntimeSpec(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	view, err := s.services.Applications.GetRuntimeSpec(r.Context(), namespace, name)
	Respond(w, r, view, err)
}

func (s *Server) updateRuntimeSpec(w http.ResponseWriter, r *http.Request) {
	var request service.RuntimeSpecView
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	namespace, name := namespaceAndName(r)
	Respond(w, r, true, s.services.Applications.UpdateRuntimeSpec(r.Context(), namespace, name, request))
}

func (s *Server) getRuntimeEnvironmentConfigs(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	configs, err := s.services.Applications.GetRuntimeEnvironmentConfigs(r.Context(), namespace, name)
	Respond(w, r, EmptyIfNil(configs), err)
}

func (s *Server) updateRuntimeEnvironmentConfigs(w http.ResponseWriter, r *http.Request) {
	var request []domain.RuntimeEnvironmentConfig
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	namespace, name := namespaceAndName(r)
	Respond(w, r, true, s.services.Applications.UpdateRuntimeEnvironmentConfigs(r.Context(), namespace, name, request))
}

// ---------------------------------------------------------------------------
// service config

func (s *Server) getServiceConfig(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	view, err := s.services.Applications.GetServiceConfig(r.Context(), namespace, name)
	Respond(w, r, view, err)
}

func (s *Server) updateServiceConfig(w http.ResponseWriter, r *http.Request) {
	var request service.ServiceConfigView
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	namespace, name := namespaceAndName(r)
	Respond(w, r, true, s.services.Applications.UpdateServiceConfig(r.Context(), namespace, name, request))
}

func (s *Server) checkServiceHost(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	conflict, err := s.services.Applications.FindHostConflict(r.Context(), namespace, name, Query(r, "host"))
	Respond(w, r, conflict, err)
}

func (s *Server) getClusterDomain(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	view, err := s.services.Applications.GetClusterDomain(r.Context(), namespace, name, environmentOf(r))
	Respond(w, r, view, err)
}

// ---------------------------------------------------------------------------
// expert config and environment bindings

func (s *Server) getExpertConfig(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	view, err := s.services.Applications.GetExpertConfig(r.Context(), namespace, name)
	Respond(w, r, view, err)
}

func (s *Server) updateExpertConfig(w http.ResponseWriter, r *http.Request) {
	var request service.ExpertConfigView
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	namespace, name := namespaceAndName(r)
	Respond(w, r, true, s.services.Applications.UpdateExpertConfig(r.Context(), namespace, name, request))
}

func (s *Server) getApplicationEnvironments(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	bindings, err := s.services.Applications.GetEnvironments(r.Context(), namespace, name)
	Respond(w, r, EmptyIfNil(bindings), err)
}

func (s *Server) updateApplicationEnvironments(w http.ResponseWriter, r *http.Request) {
	var request []service.EnvironmentBindingView
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	namespace, name := namespaceAndName(r)
	Respond(w, r, true, s.services.Applications.UpdateEnvironments(r.Context(), namespace, name, request))
}

// ---------------------------------------------------------------------------
// live views

func (s *Server) getApplicationStatus(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	statuses, err := s.services.Applications.GetStatus(r.Context(), namespace, name, environmentOf(r))
	Respond(w, r, EmptyIfNil(statuses), err)
}

func (s *Server) getApplicationEvents(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	var since *time.Time
	if raw := Query(r, "since"); raw != "" {
		if parsed, err := time.Parse(time.RFC3339, raw); err == nil {
			since = &parsed
		}
	}
	events, err := s.services.Applications.GetEvents(r.Context(), namespace, name, environmentOf(r), since, intQuery(r, "limit", 0))
	Respond(w, r, EmptyIfNil(events), err)
}

func (s *Server) getApplicationResources(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	resources, err := s.services.Applications.GetResources(r.Context(), namespace, name, environmentOf(r))
	Respond(w, r, EmptyIfNil(resources), err)
}

func (s *Server) getApplicationMetrics(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	metrics, err := s.services.Applications.GetMetrics(r.Context(), namespace, name, environmentOf(r))
	Respond(w, r, EmptyIfNil(metrics), err)
}

func (s *Server) getApplicationMetricsHistory(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	rangeText := Query(r, "range")
	if rangeText == "" {
		rangeText = "1h"
	}
	history, err := s.services.Applications.MetricsHistory(r.Context(), namespace, name, environmentOf(r), rangeText, Query(r, "agg"))
	Respond(w, r, history, err)
}

func (s *Server) getCurrentImage(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	image, err := s.services.Applications.GetCurrentImage(r.Context(), namespace, name, environmentOf(r))
	Respond(w, r, image, err)
}

func (s *Server) restartPod(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	err := s.services.Applications.RestartPod(r.Context(), namespace, name, Param(r, "pod"), environmentOf(r))
	Respond(w, r, true, err)
}

// ---------------------------------------------------------------------------
// configmaps

func (s *Server) getConfigMaps(w http.ResponseWriter, r *http.Request) {
	items, err := s.services.ConfigMaps.List(r.Context(), environmentOf(r),
		Param(r, "namespace"), Param(r, "applicationName"))
	Respond(w, r, EmptyIfNil(items), err)
}

func (s *Server) updateConfigMaps(w http.ResponseWriter, r *http.Request) {
	var request []k8s.ConfigMapCommand
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	err := s.services.ConfigMaps.Update(r.Context(), environmentOf(r),
		Param(r, "namespace"), Param(r, "applicationName"), request)
	Respond(w, r, true, err)
}
