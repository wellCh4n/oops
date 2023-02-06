/* eslint-disable @typescript-eslint/no-unused-vars */
import { uuidv4 } from '@antv/xflow';
import { XFlowNodeCommands } from '@antv/xflow';
import { DND_RENDER_ID } from './constant';
import type { NsNodeCmd } from '@antv/xflow';
import type { NsNodeCollapsePanel } from '@antv/xflow';
import { Card } from 'antd';
import React from 'react';
import request from 'umi-request';
import { PipeStuct } from './data';

export const onNodeDrop: NsNodeCollapsePanel.IOnNodeDrop = async (node, commands, modelService) => {
  const args: NsNodeCmd.AddNode.IArgs = {
    nodeConfig: { ...node, id: uuidv4() },
  }
  commands.executeCommand(XFlowNodeCommands.ADD_NODE.id, args)
}

const pipeStructsUrl = '/oops/api/application/pipeStructs';

const NodeDescription = (props: { name: string }) => {
  return (
    <Card size="small" title="Pipe介绍" style={{ width: '200px' }} bordered={false}>
      {props.name}
    </Card>
  )
}

export const nodeDataService: NsNodeCollapsePanel.INodeDataService = async (meta, modelService) => {
  // console.log(meta, modelService)
  let response = await request.get(pipeStructsUrl, {});
  let pipeStructs = response.data as PipeStuct[];
  return [
    {
      id: 'Pipe',
      header: 'Pipe',
      children: pipeStructs.map((pipeStruct) => {
        return {
          id: pipeStruct.title,
          label: pipeStruct.title,
          renderKey: DND_RENDER_ID,
          popoverContent: <NodeDescription name={pipeStruct.title} />,
          data: pipeStruct
        }
      })
    }
  ]
}

export const searchService: NsNodeCollapsePanel.ISearchService = async (
  nodes: NsNodeCollapsePanel.IPanelNode[] = [],
  keyword: string,
) => {
  const list = nodes.filter(node => node.label?.includes(keyword))
  console.log(list, keyword, nodes)
  return list
}
