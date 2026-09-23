package com.github.wellch4n.oops.domain.shared;

/**
 * One build-time variable: an environment variable of every build step (fetch, compile, publish)
 * and a {@code --build-arg} of the image build. Not a secret store — a build arg stays readable in
 * the image history.
 */
public record BuildVariable(String name, String value) {
}
