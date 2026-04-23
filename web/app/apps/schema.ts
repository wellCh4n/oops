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
})

export const applicationBasicSchema = getApplicationBasicSchema()

export const applicationBuildConfigSchema = z.object({
  sourceType: z.enum(["GIT", "ZIP"]),
  repository: z.string().optional(),
  dockerFile: z.string().optional(),
  buildImage: z.string().optional(),
}).superRefine((value, ctx) => {
  if (value.sourceType === "GIT" && !value.repository?.trim()) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["repository"], message: "Repository is required" })
  }
})

export const getCreateApplicationSchema = (t?: (key: string) => string) => z.object({
  name: z.string()
    .min(1, t?.("validation.required") || "Name is required")
    .max(NAME_MAX_LENGTH, t?.("validation.nameMaxLength") || `Name must be at most ${NAME_MAX_LENGTH} characters`)
    .regex(NAME_REGEX, t?.("validation.nameInvalid") || "Name can only contain lowercase letters, numbers, and hyphens, and must start and end with a letter or number"),
  namespace: z.string().min(1, t?.("validation.required") || "Namespace is required"),
  description: z.string().optional(),
})

export const createApplicationSchema = getCreateApplicationSchema()

export const applicationBuildSchema = z.object({
  sourceType: z.enum(["GIT", "ZIP"]),
  repository: z.string().optional(),
  dockerFile: z.string().optional(),
  buildImage: z.string().optional(),
  environmentConfigs: z.array(z.object({
    environmentName: z.string(),
    buildCommand: z.string().optional(),
  })),
}).superRefine((value, ctx) => {
  if (value.sourceType === "GIT" && !value.repository?.trim()) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["repository"], message: "Repository is required" })
  }
})

export const applicationPerformanceEnvSchema = z.object({
  environmentConfigs: z.array(z.object({
    environmentName: z.string(),
    replicas: z.number().int().min(0).optional(),
    cpuRequest: z.string().optional(),
    cpuLimit: z.string().optional(),
    memoryRequest: z.string().optional(),
    memoryLimit: z.string().optional(),
  })),
})

export const applicationConfigSchema = z.object({
  configMaps: z.array(z.object({
    key: z.string().min(1, "Key is required"),
    value: z.string().min(1, "Value is required"),
  })),
})

export const applicationServiceSchema = z.object({
  port: z.string().optional(),
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

export type ApplicationBasicFormValues = z.infer<typeof applicationBasicSchema>
export type ApplicationBuildConfigFormValues = z.infer<typeof applicationBuildConfigSchema>
export type CreateApplicationFormValues = z.infer<typeof createApplicationSchema>
export type ApplicationBuildFormValues = z.infer<typeof applicationBuildSchema>
export type ApplicationPerformanceEnvFormValues = z.infer<typeof applicationPerformanceEnvSchema>
export type ApplicationConfigFormValues = z.infer<typeof applicationConfigSchema>
export type ApplicationServiceFormValues = z.infer<typeof applicationServiceSchema>
