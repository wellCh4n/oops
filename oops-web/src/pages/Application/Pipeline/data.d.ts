export type ApplicationPipeRelation = {
  appId: number,
  vertex: ApplicationPipeVertex[],
  edges: ApplicationPipeEdge[]
}

export type ApplicationPipeVertex = {
  id: string,
  appId: number,
  pipeClass: string,
  params: any,
  pipeName: string
}

export type ApplicationPipeEdge = {
  id: number,
  startVertex: string,
  endVertex: string,
  appId: number
}

export type PipeStuct = {
  title: string,
  inputs: PipeInput[],
  clazzName: string
}

export type PipeInput = {
  clazz: string,
  description: string,
  name: string
}