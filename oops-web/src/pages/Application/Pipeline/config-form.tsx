import type { NsJsonSchemaForm } from '@antv/xflow'
import { controlMapService, ControlShapeEnum } from './form-controls'
import { MODELS } from '@antv/xflow'
import { PipeStuct } from './data'
import request from 'umi-request';

export function delay(ms: number) {
  return new Promise(resolve => setTimeout(() => resolve(true), ms))
}

let i = 0

const pipeStructUrl = '/oops/api/application/pipeStruct';

export const formSchemaService: NsJsonSchemaForm.IFormSchemaService = async args => {
  const { targetData, modelService, targetType } = args
  /** 可以使用获取 graphMeta */
  const graphMeta = await MODELS.GRAPH_META.useValue(modelService)
  console.log('formSchemaService', graphMeta, args)

  if (targetType === 'canvas') {
    return {
      tabs: [],
    }
  }

  if (targetData) {
    let response = await request.get(pipeStructUrl, {
      params: {
        pipeClass: targetData.data.pipeClass
      }
    })
    let data = response.data as PipeStuct;

    console.log(targetData.data.pipeParams);
    if (targetData.data.pipeParams) {
      return {
        tabs: [
          {
            name: data.title,
            groups: [
              {
                name: '',
                controls: data.inputs.map((input) => {
                  return {
                    name: input.name,
                    label: input.description,
                    shape: 'Input',
                    required: true,
                    tooltip: input.description,
                    value: targetData.data.pipeParams[input.name]
                  }
                })
              }
            ]
          }
        ]
      }
    } else {
      return {
        tabs: [
          {
            name: data.title,
            groups: [
              {
                name: '',
                controls: data.inputs.map((input) => {
                  return {
                    name: input.name,
                    label: input.description,
                    shape: 'Input',
                    required: true,
                    tooltip: input.description
                  }
                })
              },
            ],
          },
        ],
      }
    }
  }

  // if (targetData) {
  //   return nodeSchema
  // }

  return {
    tabs: [],
  }
}

export const formValueUpdateService: NsJsonSchemaForm.IFormValueUpdateService = async args => {
  console.log('formValueUpdateService', args)
}

export { controlMapService }
