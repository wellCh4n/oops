'use client';

import type { ReactNode } from 'react'
import { theme } from 'antd';

const CommonLayout = ({ children }: { children: ReactNode }) => {

	const { token: { colorBgContainer, borderRadiusLG } } = theme.useToken();

	return (
		<div
			style={{
				padding: 24,
				minHeight: 360,
				background: colorBgContainer,
				borderRadius: borderRadiusLG,
			}}
		>
			{children}
		</div>
	)
}

export default CommonLayout
