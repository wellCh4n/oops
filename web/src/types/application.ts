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

export type ApplicationDetailItem = ApplicationItem & {
  buildStorages: BuildStorageItem[];
}

export type BuildStorageItem = {
  id: string;
  path: string;
  volume: string;
}

export type ApplicationPodItem = {
  name: string;
  namspace: string;
  status: string;
  image: string[];
  podIP: string;
}

export type ApplicationPodFileDirectory = {
  pwd: string;
  items: ApplicationPodFileItem[];
}

export type ApplicationPodFileItem = {
  absolutePath: string;
  permissions: string;
  links: number;
  owner: string;
  group: string;
  size: string;
  date: string;
  name: string;
  directory: boolean;
}
