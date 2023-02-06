export type ApplicationPipe = {
  id: number,
  appId: number,
  pipeClass: string,
  pipeName: string,
  order: number,
  params: any
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