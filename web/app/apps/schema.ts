import { z } from "zod"

export const applicationSchema = z.object({
  id: z.string().optional(),
  name: z.string().min(1, "Name is required"),
  description: z.string().optional(),
  repository: z.string().min(1, "Repository is required"),
  dockerFile: z.string().optional(),
  buildImage: z.string().optional(),
  environmentConfigs: z.array(z.object({
    environmentId: z.string(),
    buildCommand: z.string().optional(),
    replicas: z.number().int().min(0).optional(),
    cpuRequest: z.string().optional(),
    cpuLimit: z.string().optional(),
    memoryRequest: z.string().optional(),
    memoryLimit: z.string().optional(),
  })),
})

export type ApplicationFormValues = z.infer<typeof applicationSchema>
