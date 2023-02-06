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
    let pipeStruct = targetData.data as PipeStuct
    let response = await request.get(pipeStructUrl, {
      params: {
        pipeClass: pipeStruct.clazzName
      }
    })
    let data = response.data as PipeStuct;

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
              // [
              //   {
              //     name: 'Tab3-1',
              //     label: '算法配置1',
              //     shape: 'Input',
              //     disabled: false,
              //     required: true,
              //     tooltip: '算法配置1',
              //     placeholder: 'please write something',
              //     value: '',
              //     defaultValue: '', // 可以认为是默认值
              //     hidden: false,
              //     options: [{ title: '', value: '' }],
              //     originData: {}, // 原始数据
              //   },
              //   {
              //     name: 'Tab3-2',
              //     label: '算法配置2',
              //     shape: 'Input',
              //     disabled: false,
              //     required: true,
              //     tooltip: '算法配置2',
              //     placeholder: 'please write something',
              //     value: '',
              //     defaultValue: '', // 可以认为是默认值
              //     hidden: false,
              //     options: [{ title: '', value: '' }],
              //     originData: {}, // 原始数据
              //   },
              // ],
            },
          ],
        },
      ],
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
