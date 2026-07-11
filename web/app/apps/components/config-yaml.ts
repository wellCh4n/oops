import yaml from "js-yaml"

// A single config item as edited in the form. `order` is implicit in array position; secrets are never
// serialized to the OOPS YAML format (mirroring the dotenv export, which also omits secrets).
export interface ConfigYamlItem {
  key: string
  value: string
  secret?: boolean
  mounted?: boolean
  mountPath?: string | null
  group?: string | null
  comment?: string | null
}

// Version of the OOPS YAML config format. Bump when the shape changes incompatibly.
const OOPS_CONFIG_YAML_VERSION = 1

interface SerializedItem {
  key: string
  value: string
  mountPath?: string
  group?: string
  comment?: string
}

interface ExportMeta {
  application?: string
  environment?: string
}

// Serializes non-secret config items into the OOPS YAML format. List order carries the manual ordering that
// a ConfigMap `data` map (unordered) cannot express; empty group/comment/mountPath are omitted. `mounted` is
// not written — it is inferred on import from the presence of `mountPath`.
export function serializeYamlConfig(items: ConfigYamlItem[], meta: ExportMeta = {}): string {
  const serializedItems: SerializedItem[] = items
    .filter((item) => !item.secret)
    .map((item) => {
      const serialized: SerializedItem = {
        key: item.key,
        value: item.value ?? "",
      }
      const mountPath = item.mountPath?.trim()
      if (item.mounted && mountPath) {
        serialized.mountPath = mountPath
      }
      const group = item.group?.trim()
      if (group) {
        serialized.group = group
      }
      const comment = item.comment?.trim()
      if (comment) {
        serialized.comment = comment
      }
      return serialized
    })

  const document: Record<string, unknown> = { version: OOPS_CONFIG_YAML_VERSION }
  if (meta.application) {
    document.application = meta.application
  }
  if (meta.environment) {
    document.environment = meta.environment
  }
  document.items = serializedItems

  return yaml.dump(document, { indent: 2, lineWidth: -1, noRefs: true })
}

// Parses the OOPS YAML format back into config items. Never yields secret items (import cannot introduce
// secrets); `mounted` is derived from a non-blank `mountPath`. Throws on malformed YAML or a missing/invalid
// `items` array so the caller can surface a parse error.
export function parseYamlConfig(content: string): ConfigYamlItem[] {
  const parsed = yaml.load(content)
  if (!parsed || typeof parsed !== "object") {
    throw new Error("Invalid config YAML: expected a mapping at the top level")
  }
  const rawItems = (parsed as { items?: unknown }).items
  if (!Array.isArray(rawItems)) {
    throw new Error("Invalid config YAML: missing 'items' array")
  }

  const items: ConfigYamlItem[] = []
  for (const raw of rawItems) {
    if (!raw || typeof raw !== "object") {
      continue
    }
    const entry = raw as Record<string, unknown>
    const key = typeof entry.key === "string" ? entry.key.trim() : ""
    if (!key) {
      continue
    }
    const mountPath = typeof entry.mountPath === "string" ? entry.mountPath.trim() : ""
    items.push({
      key,
      value: entry.value == null ? "" : String(entry.value),
      secret: false,
      mounted: mountPath.length > 0,
      mountPath: mountPath || null,
      group: typeof entry.group === "string" ? entry.group.trim() : null,
      comment: typeof entry.comment === "string" ? entry.comment.trim() : null,
    })
  }
  return items
}
