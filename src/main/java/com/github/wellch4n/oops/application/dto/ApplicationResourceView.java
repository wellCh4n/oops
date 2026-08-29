package com.github.wellch4n.oops.application.dto;

/**
 * A single live Kubernetes resource owned by an application, rendered as a manifest for the
 * read-only "expert mode" resource viewer.
 */
public record ApplicationResourceView(String kind, String name, String data) {
}
