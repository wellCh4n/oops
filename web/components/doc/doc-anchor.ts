/**
 * Anchor ids are derived from the translation key, not the rendered title, so a
 * link like `#deployments-trigger` keeps pointing at the same heading in every
 * locale. `doc.deployments.trigger.title` -> `deployments-trigger`.
 */
export function anchorFromKey(key: string): string {
  return key
    .replace(/^doc\./, "")
    .replace(/\.(title|heading)$/, "")
    .replace(/\./g, "-")
}
