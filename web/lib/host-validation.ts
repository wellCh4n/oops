// Mirrors backend DomainPolicy.HOST_PATTERN: lowercase RFC 1123 host names only,
// since hosts are embedded into Kubernetes resource names.
const HOST_PATTERN = /^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/

export function isValidHost(host: string): boolean {
  return HOST_PATTERN.test(host)
}
