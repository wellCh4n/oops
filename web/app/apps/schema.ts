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
  repository: z.string().min(1, "Repository is required"),
  dockerFile: z.string().optional(),
  buildImage: z.string().optional(),
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
  repository: z.string().min(1, "Repository is required"),
  dockerFile: z.string().optional(),
  buildImage: z.string().optional(),
  environmentConfigs: z.array(z.object({
    environmentName: z.string(),
    buildCommand: z.string().optional(),
  })),
})

export const applicationPerformanceEnvSchema = z.object({
  environmentConfigs: z.array(z.object({
    environmentName: z.string(),
    replicas: z.number().int().min(0).optional(),
    cpuRequest: z.string().optional(),
    cpuLimit: z.string().optional(),
    memoryRequest: z.string().optional(),
    memoryLimit: z.string().optional(),
    autoscaling: z.object({
      enabled: z.boolean().optional(),
      minReplicas: z.number().int().min(1).optional(),
      maxReplicas: z.number().int().min(1).optional(),
      targetCpuUtilization: z.number().int().min(1).max(100).optional(),
      targetMemoryUtilization: z.number().int().min(1).max(100).optional(),
    }).optional(),
  })),
})

export const applicationConfigSchema = z.object({
  configMaps: z.array(z.object({
    key: z.string().min(1, "Key is required"),
    value: z.string().min(1, "Value is required"),
  })),
})

export type ApplicationBasicFormValues = z.infer<typeof applicationBasicSchema>
export type ApplicationBuildConfigFormValues = z.infer<typeof applicationBuildConfigSchema>
export type CreateApplicationFormValues = z.infer<typeof createApplicationSchema>
export type ApplicationBuildFormValues = z.infer<typeof applicationBuildSchema>
export type ApplicationPerformanceEnvFormValues = z.infer<typeof applicationPerformanceEnvSchema>
export type ApplicationConfigFormValues = z.infer<typeof applicationConfigSchema>
