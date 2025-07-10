export type ApplicationItem = {
  id: string;
  name: string;
  namespace: string;
  repository: string;
  buildImage?: string;
  buildCommand?: string;
  dockerFile?: string;
  replicas?: number;
}

export type ApplicationPodItem = {
  name: string;
  namspace: string;
  status: string;
}