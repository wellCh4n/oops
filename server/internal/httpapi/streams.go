package httpapi

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/gorilla/websocket"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s/stream"
)

// watchApplicationStatus streams pod status changes for the status page. It is
// SSE rather than a WebSocket because nothing flows back up.
func (s *Server) watchApplicationStatus(w http.ResponseWriter, r *http.Request) {
	namespace, name := namespaceAndName(r)
	updates, failures, err := s.services.Applications.WatchStatus(r.Context(), namespace, name, environmentOf(r))
	if err != nil {
		Error(w, r, err)
		return
	}
	sse, ok := newSSE(w)
	if !ok {
		Fail(w, "Streaming is not supported by this connection")
		return
	}
	for {
		select {
		case <-r.Context().Done():
			return
		case statuses, open := <-updates:
			if !open {
				return
			}
			if sse.EventJSON("status", EmptyIfNil(statuses)) != nil {
				return
			}
		case failure, open := <-failures:
			if !open {
				return
			}
			if failure != nil {
				_ = sse.Event("error", failure.Error())
			}
			return
		}
	}
}

// mountWebSockets registers the four streaming endpoints.
//
// They sit outside the route table because their authentication differs from
// it: a browser cannot set headers on an upgrade request, so the UI ones accept
// the JWT as a query parameter, while the sandbox terminal is CLI-only and
// takes the access token in the header.
func (s *Server) mountWebSockets(router chi.Router, authenticator *Authenticator) {
	const podPrefix = "/api/namespaces/{namespace}/applications/{name}/pods/{pod}"
	router.Group(func(ws chi.Router) {
		ws.Use(authenticator.RequireJWT)
		ws.Get(podPrefix+"/terminal", s.podTerminal)
		ws.Get(podPrefix+"/log", s.podLog)
		ws.Get("/api/namespaces/{namespace}/applications/{name}/pipelines/{pipelineId}/log", s.pipelineLog)
		ws.Get("/api/sandbox/instances/{id}/terminal", s.sandboxTerminal)
	})
	router.Group(func(ws chi.Router) {
		ws.Use(authenticator.RequireAccessToken)
		ws.Get("/openapi/sandbox/instances/{id}/terminal", s.sandboxTerminal)
	})
}

// Every socket below upgrades first and reports trouble on the socket itself.
//
// The handshake is a transport concern: the route exists and the caller is
// authenticated, so it succeeds. An unknown environment or an unreachable pod is
// an application-level failure, and a client that has already been told the
// handshake failed has nowhere to read the reason from — the browser surfaces it
// as an opaque connection error.

// podTerminal attaches an interactive shell to a pod.
func (s *Server) podTerminal(w http.ResponseWriter, r *http.Request) {
	conn, ok := upgrade(w, r)
	if !ok {
		return
	}
	sink := newWSSink(conn)
	environment, err := s.services.Environments.FindByName(r.Context(), environmentOf(r))
	if err != nil || environment == nil {
		_ = sink.ClosePolicy("Environment not found")
		return
	}
	s.openTerminal(r, conn, sink, environment.KubernetesApiServer, Param(r, "namespace"), Param(r, "pod"), Query(r, "container"))
}

// sandboxTerminal attaches a shell to a long-lived sandbox's pod.
func (s *Server) sandboxTerminal(w http.ResponseWriter, r *http.Request) {
	conn, ok := upgrade(w, r)
	if !ok {
		return
	}
	sink := newWSSink(conn)
	environmentName, namespace, pod, container, err := s.services.Sandboxes.InstanceTarget(r.Context(), Param(r, "id"))
	if err != nil {
		_ = sink.ClosePolicy(err.Error())
		return
	}
	environment, err := s.services.Environments.FindByName(r.Context(), environmentName)
	if err != nil || environment == nil {
		_ = sink.ClosePolicy("Environment not found")
		return
	}
	s.openTerminal(r, conn, sink, environment.KubernetesApiServer, namespace, pod, container)
}

// upgrade turns the request into a socket, reporting whether it worked. A failed
// upgrade has already written its own response.
func upgrade(w http.ResponseWriter, r *http.Request) (*websocket.Conn, bool) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return nil, false
	}
	return conn, true
}

func (s *Server) openTerminal(r *http.Request, conn *websocket.Conn, sink *wsSink, apiServer *domain.KubernetesApiServer, namespace, pod, container string) {
	session, err := stream.OpenTerminal(r.Context(), apiServer, namespace, pod, container, sink)
	if err != nil {
		_ = sink.ClosePolicy(err.Error())
		return
	}
	defer session.Close()

	// Whatever the browser types goes to the remote TTY unchanged. A terminal
	// never uses a text "ping" heartbeat, because the payload would be typed
	// into the shell — the native control frames below do that job.
	stop := make(chan struct{})
	defer close(stop)
	go sink.heartbeat(stop)
	for {
		messageType, payload, err := conn.ReadMessage()
		if err != nil {
			return
		}
		switch messageType {
		case websocket.TextMessage, websocket.BinaryMessage:
			if session.Write(payload) != nil {
				return
			}
		}
	}
}

// podLog tails a pod's log.
func (s *Server) podLog(w http.ResponseWriter, r *http.Request) {
	conn, ok := upgrade(w, r)
	if !ok {
		return
	}
	sink := newWSSink(conn)
	environment, err := s.services.Environments.FindByName(r.Context(), environmentOf(r))
	if err != nil || environment == nil {
		_ = sink.ClosePolicy("Environment not found")
		return
	}
	closer, err := stream.StreamPodLog(r.Context(), environment.KubernetesApiServer,
		Param(r, "namespace"), Param(r, "pod"), sink)
	if err != nil {
		_ = sink.ClosePolicy(err.Error())
		return
	}
	defer closer.Close()
	s.pumpUntilClosed(conn, sink)
}

// pipelineLog streams a build's log, step by step.
func (s *Server) pipelineLog(w http.ResponseWriter, r *http.Request) {
	conn, ok := upgrade(w, r)
	if !ok {
		return
	}
	sink := newWSSink(conn)
	namespace, name := namespaceAndName(r)
	pipeline, err := s.services.Pipelines.FindPipeline(r.Context(), namespace, name, Param(r, "pipelineId"))
	if err != nil || pipeline == nil {
		_ = sink.ClosePolicy("Pipeline not found")
		return
	}
	environment, err := s.services.Environments.FindByName(r.Context(), pipeline.Environment)
	if err != nil || environment == nil {
		_ = sink.ClosePolicy("Environment not found")
		return
	}
	closer, err := stream.StreamPipelineLog(r.Context(), environment.KubernetesApiServer,
		domain.Deref(environment.WorkNamespace), pipeline.Name(), pipeline.Finished(), sink)
	if err != nil {
		_ = sink.ClosePolicy(err.Error())
		return
	}
	defer closer.Close()
	s.pumpUntilClosed(conn, sink)
}

// pumpUntilClosed keeps a read-only stream open, answering the text "ping" the
// browser sends and running the native ping heartbeat alongside it.
func (s *Server) pumpUntilClosed(conn *websocket.Conn, sink *wsSink) {
	stop := make(chan struct{})
	defer close(stop)
	go sink.heartbeat(stop)
	for {
		_, payload, err := conn.ReadMessage()
		if err != nil {
			return
		}
		if string(payload) == "ping" {
			if sink.SendText("pong") != nil {
				return
			}
		}
	}
}
