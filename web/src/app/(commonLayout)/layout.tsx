'use client';

import type { ReactNode } from 'react'
import { theme } from 'antd';

const CommonLayout = ({ children }: { children: ReactNode }) => {

	const { token: { colorBgContainer, borderRadiusLG } } = theme.useToken();

	return (
		<div
			className="h-full p-2"
			style={{
				background: colorBgContainer,
				borderRadius: borderRadiusLG,
			}}
		>
			{children}
		</div>
	)
}

export default CommonLayout
