import { z } from "zod"
import { NAME_MAX_LENGTH, NAME_REGEX } from "@/lib/utils"

export const getApplicationBasicSchema = (t?: (key: string) => string) => z.object({
  id: z.string().optional(),
  name: z.string()
    .min(1, t?.("validation.required") || "Name is required")
    .max(NAME_MAX_LENGTH, t?.("validation.nameMaxLength") || `Name must be at most ${NAME_MAX_LENGTH} characters`)
    .regex(NAME_REGEX, t?.("validation.nameInvalid") || "Name can only contain lowercase letters, numbers, and hyphens, and must start and end with a letter or number"),
  namespace: z.string().min(1, t?.("validation.required") || "Namespace is required"),
  description: z.string().optional(),
  owner: z.string().optional(),
  collaborators: z.array(z.string()).optional(),
})

export const getCreateApplicationSchema = (t?: (key: string) => string) => z.object({
  name: z.string()
    .min(1, t?.("validation.required") || "Name is required")
    .max(NAME_MAX_LENGTH, t?.("validation.nameMaxLength") || `Name must be at most ${NAME_MAX_LENGTH} characters`)
    .regex(NAME_REGEX, t?.("validation.nameInvalid") || "Name can only contain lowercase letters, numbers, and hyphens, and must start and end with a letter or number"),
  namespace: z.string().min(1, t?.("validation.required") || "Namespace is required"),
  description: z.string().optional(),
})

export const applicationBuildSchema = z.object({
  sourceType: z.enum(["GIT", "ZIP"]),
  repository: z.string().nullish(),
  dockerFileConfig: z.object({
    type: z.enum(["BUILTIN", "USER"]),
    path: z.string().nullish(),
    content: z.string().nullish(),
  }).nullish(),
  buildImage: z.string().nullish(),
  environmentConfigs: z.array(z.object({
    environmentName: z.string(),
    buildCommand: z.string().nullish(),
  })),
}).superRefine((value, ctx) => {
  if (value.sourceType === "GIT" && !value.repository?.trim()) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["repository"], message: "Repository is required" })
  }
  if (value.dockerFileConfig?.type === "USER" && !value.dockerFileConfig?.content?.trim()) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["dockerFileConfig", "content"], message: "Dockerfile content is required" })
  }
})

export const applicationRuntimeSpecSchema = z.object({
  environmentConfigs: z.array(z.object({
    environmentName: z.string(),
    replicas: z.number().int().min(0).optional(),
    cpuRequest: z.string().optional(),
    cpuLimit: z.string().optional(),
    memoryRequest: z.string().optional(),
    memoryLimit: z.string().optional(),
  })),
  healthCheck: z.object({
    liveness: z.object({
      enabled: z.boolean().optional(),
      path: z.string().optional(),
      initialDelaySeconds: z.number().int().min(0).optional(),
      periodSeconds: z.number().int().min(1).optional(),
      timeoutSeconds: z.number().int().min(1).optional(),
      failureThreshold: z.number().int().min(1).optional(),
    }).optional(),
    readiness: z.object({
      enabled: z.boolean().optional(),
      path: z.string().optional(),
      initialDelaySeconds: z.number().int().min(0).optional(),
      periodSeconds: z.number().int().min(1).optional(),
      timeoutSeconds: z.number().int().min(1).optional(),
      failureThreshold: z.number().int().min(1).optional(),
    }).optional(),
  }).optional(),
}).superRefine((value, ctx) => {
  for (const probeName of ["liveness", "readiness"] as const) {
    const probe = value.healthCheck?.[probeName]
    if (probe?.enabled && !probe.path?.trim()) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["healthCheck", probeName, "path"], message: "Path is required" })
    }
    if (probe?.path && !probe.path.startsWith("/")) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["healthCheck", probeName, "path"], message: "Path must start with /" })
    }
  }
})

export const applicationConfigSchema = z.object({
  configMaps: z.array(z.object({
    key: z.string().min(1, "Key is required"),
    value: z.string().min(1, "Value is required"),
    secret: z.boolean().optional(),
    // Form-only flag: the backend derives "mounted" from a non-blank mountPath, but the UI needs an explicit
    // toggle so a freshly-checked item with an empty path stays distinguishable from an env-only item.
    mounted: z.boolean().optional(),
    // The API returns null for env items, so accept null/undefined as well as string.
    mountPath: z.string().nullish(),
    // Optional UI metadata: display group and free-text note. The API returns null when unset.
    group: z.string().nullish(),
    comment: z.string().nullish(),
  }).superRefine((item, ctx) => {
    if (item.mounted) {
      if (!item.mountPath || !item.mountPath.trim()) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["mountPath"], message: "Mount path is required" })
      } else if (!item.mountPath.startsWith("/")) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["mountPath"], message: "Mount path must start with /" })
      }
    }
  })),
})

export const applicationExpertConfigSchema = z.object({
  environmentConfigs: z.array(z.object({
    environmentName: z.string(),
    serviceAccountName: z.string().optional(),
    priority: z.string().optional(),
    scheduledRestartEnabled: z.boolean().optional(),
    scheduledRestartCron: z.string().optional(),
  })),
})

export const applicationServiceSchema = z.object({
  port: z.string(),
  internalPorts: z.array(z.string()),
  environmentConfigs: z.array(z.object({
    environmentName: z.string(),
    hosts: z.array(z.object({
      host: z.string(),
      https: z.boolean(),
      editing: z.boolean(),
      prefix: z.string(),
      suffix: z.string(),
    })),
  })),
})

export type ApplicationBasicFormValues = z.infer<ReturnType<typeof getApplicationBasicSchema>>
export type CreateApplicationFormValues = z.infer<ReturnType<typeof getCreateApplicationSchema>>
export type ApplicationBuildFormValues = z.infer<typeof applicationBuildSchema>
export type ApplicationRuntimeSpecFormValues = z.infer<typeof applicationRuntimeSpecSchema>
export type ApplicationConfigFormValues = z.infer<typeof applicationConfigSchema>
export type ApplicationExpertConfigFormValues = z.infer<typeof applicationExpertConfigSchema>
export type ApplicationServiceFormValues = z.infer<typeof applicationServiceSchema>
