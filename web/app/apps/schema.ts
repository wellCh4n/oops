import { z } from "zod"

export const applicationBasicSchema = z.object({
  id: z.string().optional(),
  name: z.string().min(1, "Name is required"),
  namespace: z.string().min(1, "Namespace is required"),
  description: z.string().optional(),
})

export const applicationBuildConfigSchema = z.object({
  repository: z.string().min(1, "Repository is required"),
  dockerFile: z.string().optional(),
  buildImage: z.string().optional(),
})

export const createApplicationSchema = z.object({
  name: z.string().min(1, "Name is required"),
  namespace: z.string().min(1, "Namespace is required"),
  description: z.string().optional(),
})

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
  })),
})

export const applicationConfigSchema = z.object({
  configMaps: z.array(z.object({
    key: z.string().min(1, "Key is required"),
    value: z.string().min(1, "Value is required"),
    mountPath: z.string(),
  })),
})

export type ApplicationBasicFormValues = z.infer<typeof applicationBasicSchema>
export type ApplicationBuildConfigFormValues = z.infer<typeof applicationBuildConfigSchema>
export type CreateApplicationFormValues = z.infer<typeof createApplicationSchema>
export type ApplicationBuildFormValues = z.infer<typeof applicationBuildSchema>
export type ApplicationPerformanceEnvFormValues = z.infer<typeof applicationPerformanceEnvSchema>
export type ApplicationConfigFormValues = z.infer<typeof applicationConfigSchema>
